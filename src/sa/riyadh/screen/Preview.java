package sa.riyadh.screen;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Toast;

public final class Preview extends Activity {
    private Dashboard dashboard;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        dashboard = new Dashboard(this);
        setContentView(dashboard.view());
    }
    // OK on the remote opens the screensaver choice. Handled here, before the WebView sees the key.
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_DPAD_CENTER && code != KeyEvent.KEYCODE_ENTER) return super.dispatchKeyEvent(event);
        if (event.getAction() == KeyEvent.ACTION_UP) chooseScreenSaver();
        return true;
    }
    private void chooseScreenSaver() {
        final String[] items = {"مواقيت", "شاشة Google الأصلية"};
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("شاشة التوقف")
            .setSingleChoiceItems(items, ScreenSaver.active(this) ? 0 : 1, (choice, which) -> {
                choice.dismiss();
                boolean done = ScreenSaver.use(this, which == 0);
                Toast.makeText(this, done ? "شاشة التوقف: " + items[which] : "يلزم منح الصلاحية مرة واحدة عبر adb", Toast.LENGTH_LONG).show();
            }).create();
        // RTL even when the TV's system language is English; must be set before show().
        dialog.getWindow().getDecorView().setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        dialog.show();
    }
    @Override public void onDestroy() {
        if (dashboard != null) dashboard.close();
        super.onDestroy();
    }
}
