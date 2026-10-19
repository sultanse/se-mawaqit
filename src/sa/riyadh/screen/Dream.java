package sa.riyadh.screen;
import android.service.dreams.DreamService;

/** The dashboard as the TV screensaver. Chosen from the app (OK button), see ScreenSaver. */
public final class Dream extends DreamService {
    private Dashboard dashboard;
    @Override public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        dashboard = new Dashboard(this);
        setContentView(dashboard.view());
    }
    @Override public void onDetachedFromWindow() {
        if (dashboard != null) dashboard.close();
        dashboard = null;
        super.onDetachedFromWindow();
    }
}
