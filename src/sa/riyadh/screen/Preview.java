package sa.riyadh.screen;
import android.app.Activity;
import android.media.AudioManager;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Toast;
import org.json.JSONObject;

public final class Preview extends Activity {
    private Dashboard dashboard;
    private boolean okPressed;
    // The settings menu is drawn by the page (window.showMenu) but driven from here,
    // since the page can't call Java. level is null while the menu is closed.
    private String level;
    private int focus;
    // The system's own navigation and click sounds, as native lists play them; they
    // follow the TV's "sound effects" setting.
    private AudioManager audio;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        dashboard = new Dashboard(this);
        setContentView(dashboard.view());
    }
    // Keys are handled here, before the WebView sees them. OK acts only on a full
    // press (down and up both seen), so a press that closed the menu can't reopen it.
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode(), action = event.getAction();
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER;
        if (level == null && !ok) return super.dispatchKeyEvent(event);
        if (ok) {
            if (action == KeyEvent.ACTION_DOWN) okPressed = true;
            else if (action == KeyEvent.ACTION_UP && okPressed) {
                okPressed = false;
                if (level == null) open("root", 0); else select();
            }
        } else if (action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_UP) move(-1);
        else if (action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_DOWN) move(1);
        else if (action == KeyEvent.ACTION_UP && code == KeyEvent.KEYCODE_BACK) back();
        return true;
    }
    private String title() {
        return "root".equals(level) ? "الإعدادات" : "city".equals(level) ? "المدينة" : "شاشة التوقف";
    }
    private String[] items() {
        if ("root".equals(level)) {
            return new String[] {
                "المدينة: " + dashboard.city().name + (dashboard.isAuto() ? " (تلقائي)" : ""),
                "شاشة التوقف: " + (ScreenSaver.active(this) ? "مواقيت" : "Google"),
            };
        }
        if ("city".equals(level)) {
            String[] items = new String[City.ALL.length + 1];
            items[0] = "تلقائي — الآن: " + dashboard.city().name;
            for (int i = 0; i < City.ALL.length; i++) items[i + 1] = City.ALL[i].name;
            return items;
        }
        return new String[] {"مواقيت", "شاشة Google الأصلية"};
    }
    /** The row marked as current, or -1 when the level has no choice (the root). */
    private int checked() {
        if ("city".equals(level)) return dashboard.isAuto() ? 0 : dashboard.city().index() + 1;
        if ("saver".equals(level)) return ScreenSaver.active(this) ? 0 : 1;
        return -1;
    }
    private void open(String next, int at) { level = next; focus = at; render(); }
    private void move(int step) {
        int next = Math.max(0, Math.min(items().length - 1, focus + step));
        if (next == focus) return;   // at the end of the list: no move, no sound
        focus = next;
        audio.playSoundEffect(step < 0 ? AudioManager.FX_FOCUS_NAVIGATION_UP : AudioManager.FX_FOCUS_NAVIGATION_DOWN);
        render();
    }
    private void back() { if ("root".equals(level)) close(); else open("root", "city".equals(level) ? 0 : 1); }
    private void close() { level = null; dashboard.menu(null); }
    private void select() {
        audio.playSoundEffect(AudioManager.FX_KEY_CLICK);
        if ("root".equals(level)) {
            level = focus == 0 ? "city" : "saver";
            focus = Math.max(0, checked());
            render();
        } else if ("city".equals(level)) {
            City chosen = focus == 0 ? null : City.ALL[focus - 1];
            close();
            dashboard.chooseCity(chosen);
        } else {
            boolean ours = focus == 0;
            close();
            boolean done = ScreenSaver.use(this, ours);
            Toast.makeText(this, done ? "شاشة التوقف: " + (ours ? "مواقيت" : "شاشة Google الأصلية") : "يلزم منح الصلاحية مرة واحدة عبر adb", Toast.LENGTH_LONG).show();
        }
    }
    private void render() {
        String[] items = items();
        StringBuilder json = new StringBuilder("{\"title\":").append(JSONObject.quote(title()))
            .append(",\"focus\":").append(focus).append(",\"checked\":").append(checked()).append(",\"items\":[");
        for (int i = 0; i < items.length; i++) json.append(i > 0 ? "," : "").append(JSONObject.quote(items[i]));
        dashboard.menu(json.append("]}").toString());
    }
    @Override public void onDestroy() {
        if (dashboard != null) dashboard.close();
        super.onDestroy();
    }
}
