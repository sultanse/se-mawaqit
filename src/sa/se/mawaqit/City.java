package sa.se.mawaqit;
import android.net.Uri;
import org.json.JSONObject;

/** Saudi cities the screen can show. All share Asia/Riyadh time, so only coordinates change. */
final class City {
    static final City[] ALL = {
        new City("الرياض", 24.7136, 46.6753), new City("جدة", 21.4858, 39.1925),
        new City("مكة المكرمة", 21.4225, 39.8262), new City("المدينة المنورة", 24.5247, 39.5692),
        new City("الدمام", 26.4207, 50.0888), new City("الخبر", 26.2172, 50.1971),
        new City("الظهران", 26.2361, 50.0393), new City("الأحساء", 25.3833, 49.5864),
        new City("الجبيل", 27.0174, 49.6225),
        new City("الطائف", 21.2703, 40.4158), new City("ينبع", 24.0895, 38.0618),
        new City("تبوك", 28.3835, 36.5662), new City("بريدة", 26.3260, 43.9750),
        new City("عنيزة", 26.0844, 43.9935), new City("الرس", 25.8694, 43.4973),
        new City("حائل", 27.5114, 41.7208), new City("أبها", 18.2164, 42.5053),
        new City("خميس مشيط", 18.3000, 42.7333), new City("بيشة", 19.9928, 42.6000),
        new City("جازان", 16.8892, 42.5511), new City("نجران", 17.4917, 44.1322),
        new City("الباحة", 20.0129, 41.4677), new City("عرعر", 30.9753, 41.0381),
        new City("سكاكا", 29.9697, 40.2064), new City("القريات", 31.3317, 37.3428),
        new City("حفر الباطن", 28.4328, 45.9708), new City("الخرج", 24.1556, 47.3122),
        new City("المجمعة", 25.9039, 45.3456), new City("الدوادمي", 24.5073, 44.3923),
    };
    static final City RIYADH = ALL[0];

    final String name;
    final double lat, lon;
    private City(String name, double lat, double lon) { this.name = name; this.lat = lat; this.lon = lon; }

    static City named(String name) {
        for (City city : ALL) if (city.name.equals(name)) return city;
        return RIYADH;
    }
    /** Closest listed city; equirectangular distance is plenty at this scale. */
    static City nearest(double lat, double lon) {
        City best = RIYADH;
        double bestDistance = Double.MAX_VALUE;
        for (City city : ALL) {
            double x = (city.lon - lon) * Math.cos(Math.toRadians((city.lat + lat) / 2)), y = city.lat - lat, d = x * x + y * y;
            if (d < bestDistance) { bestDistance = d; best = city; }
        }
        return best;
    }
    int index() {
        for (int i = 0; i < ALL.length; i++) if (ALL[i] == this) return i;
        return 0;
    }
    String query() { return "lat=" + lat + "&lon=" + lon + "&city=" + Uri.encode(name); }
    String json() { return "{\"name\":" + JSONObject.quote(name) + ",\"lat\":" + lat + ",\"lon\":" + lon + "}"; }
}
