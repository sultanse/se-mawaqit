package sa.riyadh.screen;
import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

/**
 * Switches the system screensaver between Dream and whatever was set before.
 * Google TV doesn't list third-party screensavers in Settings, so the app writes
 * the setting itself. That needs WRITE_SECURE_SETTINGS, granted once over adb:
 * adb shell pm grant sa.riyadh.screen android.permission.WRITE_SECURE_SETTINGS
 */
final class ScreenSaver {
    private static final String COMPONENTS = "screensaver_components", OURS = "sa.riyadh.screen/.Dream";
    private ScreenSaver() {}
    static boolean active(Context context) {
        return OURS.equals(Settings.Secure.getString(context.getContentResolver(), COMPONENTS));
    }
    /** Returns false if the permission hasn't been granted or there's nothing to restore. */
    static boolean use(Context context, boolean ours) {
        ContentResolver resolver = context.getContentResolver();
        SharedPreferences prefs = context.getSharedPreferences("screensaver", Context.MODE_PRIVATE);
        String current = Settings.Secure.getString(resolver, COMPONENTS), target;
        if (ours) {
            // Remember the original (e.g. Google's Backdrop) so it can be restored.
            if (current != null && !current.isEmpty() && !current.equals(OURS)) prefs.edit().putString("previous", current).apply();
            target = OURS;
        } else {
            target = prefs.getString("previous", Settings.Secure.getString(resolver, "screensaver_default_component"));
        }
        if (target == null || target.isEmpty()) return false;
        try {
            if (ours) Settings.Secure.putInt(resolver, "screensaver_enabled", 1);
            return Settings.Secure.putString(resolver, COMPONENTS, target);
        } catch (SecurityException e) {
            return false;
        }
    }
}
