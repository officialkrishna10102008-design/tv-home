package local.tv.home;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.KeyEvent;

public class HomeActivity extends Activity {
    private HomeView view;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        view = new HomeView(this);
        setContentView(view);
        getWindow().getDecorView().setBackgroundColor(Color.BLACK);
        view.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.refresh();
        view.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        view.stop();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already on the launcher: go back to the dock.
        view.resetToDock();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (view.onKey(keyCode)) return true;
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        // Nothing to go back to on the home screen.
    }
}
