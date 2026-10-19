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
    private static final String LATITUDE = "24.7136", LONGITUDE = "46.6753";
    private static final long PRAYER_CACHE_AGE = 48 * 3600000L, WEATHER_CACHE_AGE = 3 * 3600000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final SharedPreferences cache;
    private final WebView view;
    private boolean closed, started;
    private int tick;
    private String lastDate = "";
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (closed) return;
            String today = date(0);
            if (!today.equals(lastDate) || tick % 60 == 0) { lastDate = today; fetchPrayer("prayers", today); fetchPrayer("tomorrow", date(1)); }
            if (tick % 15 == 0) fetchWeather();
            tick++;
            main.postDelayed(this, 60000);
        }
    };

    Dashboard(Context context) {
        cache = context.getSharedPreferences("cache", Context.MODE_PRIVATE);
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
                replay("tomorrow", PRAYER_CACHE_AGE);
                replay("weather", WEATHER_CACHE_AGE);
                main.post(refresh);
            }
        });
        view.loadUrl("file:///android_asset/index.html?lat=" + LATITUDE + "&lon=" + LONGITUDE);
    }
    WebView view() { return view; }
    void close() { closed = true; main.removeCallbacks(refresh); worker.shutdownNow(); view.destroy(); }
    private String date(int dayOffset) {
        SimpleDateFormat format = new SimpleDateFormat("dd-MM-yyyy", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("Asia/Riyadh"));
        Calendar calendar = Calendar.getInstance(TimeZone.getTimeZone("Asia/Riyadh"));
        calendar.add(Calendar.DAY_OF_MONTH, dayOffset);
        return format.format(calendar.getTime());
    }
    private void fetchPrayer(String kind, String date) {
        request(kind, "https://api.aladhan.com/v1/timings/" + date + "?latitude=" + LATITUDE + "&longitude=" + LONGITUDE + "&method=4&timezonestring=Asia%2FRiyadh");
    }
    private void fetchWeather() {
        request("weather", "https://api.open-meteo.com/v1/forecast?latitude=" + LATITUDE + "&longitude=" + LONGITUDE + "&current=temperature_2m,weather_code&timezone=Asia%2FRiyadh");
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
        worker.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(address).openConnection();
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(12000);
                if (connection.getResponseCode() != 200) throw new java.io.IOException("HTTP " + connection.getResponseCode());
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (InputStream in = connection.getInputStream()) {
                    byte[] buffer = new byte[4096]; int count;
                    while ((count = in.read(buffer)) != -1) { bytes.write(buffer, 0, count); if (bytes.size() > 250000) throw new java.io.IOException("Response too large"); }
                }
                final String payload = new JSONObject(bytes.toString("UTF-8")).toString();
                cache.edit().putString(kind, payload).putLong(kind + "_at", System.currentTimeMillis()).apply();
                main.post(() -> { if (!closed) deliver(kind, payload); });
            } catch (Exception ignored) {
                main.post(() -> { if (!closed) view.evaluateJavascript("window.setError(" + JSONObject.quote(kind) + ")", null); });
            } finally { if (connection != null) connection.disconnect(); }
        });
    }
}
