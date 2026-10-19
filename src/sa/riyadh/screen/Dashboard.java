package sa.riyadh.screen;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Dashboard {
    // Prayers cover today + 7 days and weather carries a 3-day hourly forecast, so the
    // cache keeps the screen full through an outage; JS drops whatever has gone stale.
    private static final int PRAYER_DAYS = 7;
    private static final long PRAYER_CACHE_AGE = PRAYER_DAYS * 24 * 3600000L, WEATHER_CACHE_AGE = 48 * 3600000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SharedPreferences cache, place;
    private final WebView view;
    // Read on the worker to drop responses that belong to a city we've since left.
    private volatile City city;
    private boolean closed, started;
    private int tick;
    private String lastDate = "";
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (closed) return;
            String today = date(0);
            if (tick % 1440 == 0 && isAuto()) detectCity();
            if (!today.equals(lastDate) || tick % 60 == 0) { lastDate = today; fetchPrayers(today, date(PRAYER_DAYS)); }
            if (tick % 15 == 0) fetchWeather();
            tick++;
            main.postDelayed(this, 60000);
        }
    };

    Dashboard(Context context) {
        cache = context.getSharedPreferences("cache", Context.MODE_PRIVATE);
        place = context.getSharedPreferences("place", Context.MODE_PRIVATE);
        city = City.named(place.getString("city", null));
        view = new WebView(context);
        WebSettings settings = view.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(false);
        view.setBackgroundColor(0xff10232d);
        view.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView page, String url) {
                if (closed || started) return;
                started = true;
                replay("prayers", PRAYER_CACHE_AGE);
                replay("weather", WEATHER_CACHE_AGE);
                main.post(refresh);
            }
        });
        view.loadUrl("file:///android_asset/index.html?" + city.query());
    }
    WebView view() { return view; }
    void close() { closed = true; main.removeCallbacks(refresh); worker.shutdownNow(); view.destroy(); }
    City city() { return city; }
    /** Shows the page's settings menu ({title, items, focus, checked}), or hides it for null. */
    void menu(String json) { view.evaluateJavascript("window.showMenu(" + (json == null ? "null" : json) + ")", null); }
    boolean isAuto() { return !place.getBoolean("manual", false); }
    /** null switches back to automatic (by IP); a city pins it until switched back. */
    void chooseCity(City chosen) {
        place.edit().putBoolean("manual", chosen != null).apply();
        if (chosen == null) detectCity(); else moveTo(chosen);
    }
    // IP geolocation, snapped to the nearest listed city. Outside Saudi Arabia or on
    // failure the current city stays. ipinfo.io is the fallback if ipwho.is is down.
    private void detectCity() {
        worker.execute(() -> {
            City found = null;
            try {
                JSONObject ip = new JSONObject(fetch("https://ipwho.is/?fields=success,country_code,latitude,longitude"));
                if (ip.optBoolean("success") && "SA".equals(ip.optString("country_code"))) found = City.nearest(ip.getDouble("latitude"), ip.getDouble("longitude"));
            } catch (Exception ignored) {}
            if (found == null) try {
                JSONObject ip = new JSONObject(fetch("https://ipinfo.io/json"));
                String[] loc = ip.optString("loc").split(",");
                if ("SA".equals(ip.optString("country")) && loc.length == 2) found = City.nearest(Double.parseDouble(loc[0]), Double.parseDouble(loc[1]));
            } catch (Exception ignored) {}
            final City result = found;
            if (result != null) main.post(() -> { if (!closed && isAuto()) moveTo(result); });
        });
    }
    private void moveTo(City next) {
        if (next == city) return;
        city = next;
        place.edit().putString("city", next.name).apply();
        cache.edit().clear().apply();   // cached times and weather belong to the old city
        deliver("place", next.json());
        lastDate = date(0);
        fetchPrayers(lastDate, date(PRAYER_DAYS));
        fetchWeather();
    }
    private String date(int dayOffset) {
        SimpleDateFormat format = new SimpleDateFormat("dd-MM-yyyy", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Riyadh"));
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Riyadh"));
        calendar.add(Calendar.DAY_OF_MONTH, dayOffset);
        return format.format(calendar.getTime());
    }
    private void fetchPrayers(String from, String to) {
        request("prayers", "https://api.aladhan.com/v1/calendar/from/" + from + "/to/" + to + "?latitude=" + city.lat + "&longitude=" + city.lon + "&method=4&timezonestring=Asia%2FRiyadh");
    }
    private void fetchWeather() {
        request("weather", "https://api.open-meteo.com/v1/forecast?latitude=" + city.lat + "&longitude=" + city.lon + "&current=temperature_2m,weather_code&hourly=temperature_2m,weather_code&forecast_days=3&timezone=Asia%2FRiyadh");
    }
    private void replay(String kind, long maxAge) {
        String payload = cache.getString(kind, null);
        long age = System.currentTimeMillis() - cache.getLong(kind + "_at", 0);
        if (payload != null && age >= 0 && age < maxAge) deliver(kind, payload);
    }
    private void deliver(String kind, String payload) {
        view.evaluateJavascript("window.setData(" + JSONObject.quote(kind) + "," + JSONObject.quote(payload) + ")", null);
    }
    private void request(final String kind, final String address) {
        final City at = city;
        worker.execute(() -> {
            try {
                final String payload = new JSONObject(fetch(address)).toString();
                if (at != city) return;
                cache.edit().putString(kind, payload).putLong(kind + "_at", System.currentTimeMillis()).apply();
                main.post(() -> { if (!closed && at == city) deliver(kind, payload); });
            } catch (Exception ignored) {
                main.post(() -> { if (!closed && at == city) view.evaluateJavascript("window.setError(" + JSONObject.quote(kind) + ")", null); });
            }
        });
    }
    /** GET over HTTPS with a 12 s timeout and a 250 KB cap; runs on the worker. */
    private static String fetch(String address) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        try {
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(12000);
            if (connection.getResponseCode() != 200) throw new IOException("HTTP " + connection.getResponseCode());
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = in.read(buffer)) != -1) { bytes.write(buffer, 0, count); if (bytes.size() > 250000) throw new IOException("Response too large"); }
            }
            return bytes.toString("UTF-8");
        } finally { connection.disconnect(); }
    }
}
