package sa.riyadh.screen;
import android.app.Activity;
import android.os.Bundle;
import android.view.View;

public final class Preview extends Activity {
    private Dashboard dashboard;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        dashboard = new Dashboard(this);
        setContentView(dashboard.view());
    }
    @Override public void onDestroy() {
        if (dashboard != null) dashboard.close();
        super.onDestroy();
    }
}
