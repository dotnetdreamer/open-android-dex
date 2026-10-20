package com.ccrstech.openandroiddex.launcher;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.KeyEvent;
import android.widget.FrameLayout;

/**
 * One Linux app's window on the desktop: a task of its own, so it has the
 * desktop's title bar, a taskbar button and a place among the Android
 * windows, like any app's (doc/linux-windows.md).
 *
 * <p>It shows one X window — the one its intent names — through a
 * {@link LinuxWinView}, and owns nothing else: what the window is, where its
 * pixels come from and where its input goes all live in {@link LinuxWindows}.
 * The X window decides when it goes: an app that closes its window finishes
 * this activity. The other way round, the caption's ✕ (and a task removed
 * from outside) asks the app to close, as any window manager's close does —
 * the app may well ask about unsaved work first.
 *
 * <p>Every configuration change is handled here rather than rebuilding the
 * activity: a rebuild would take the surface away from a window mid-resize,
 * and the app inside cannot be rebuilt with it.
 */
public class LinuxWindowActivity extends Activity {

    /** The X window this task shows. */
    static final String EXTRA_XID = "xid";

    private LinuxWindows windows;
    private LinuxWindows.Entry entry;
    private LinuxWinView view;
    private boolean listening;

    /** The caption's ✕ for this task: the app is asked, not the task removed. */
    private final BroadcastReceiver closeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            if (!LinuxWindowActivity.class.getName().equals(intent.getStringExtra("activity"))) return;
            if (intent.getIntExtra("task", -1) != getTaskId()) return;
            if (entry != null) windows.close(entry);
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(DexLocale.wrap(base));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        windows = LinuxWindows.get(this);
        int xid = getIntent() != null ? getIntent().getIntExtra(EXTRA_XID, 0) : 0;
        entry = windows.attach(xid, this);
        if (entry == null) {
            // A task Android brought back after the window it showed was gone
            // — the process restarted, say. There is nothing for it to show.
            finishAndRemoveTask();
            return;
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xff000000);
        // The caption strip is the desktop's: the content starts below it.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets i = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                v.setPadding(i.left, i.top, i.right, i.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });
        view = new LinuxWinView(this, windows, entry.wm, entry.win, false);
        root.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        root.requestApplyInsets();
        view.requestFocus();
        IntentFilter f = new IntentFilter(LauncherActivity.ACTION_CLOSE_WINDOW);
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            registerReceiver(closeReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(closeReceiver, f);
        }
        listening = true;
        describe();
    }

    /** The task's own title and icon, which the system's task lists show. */
    @SuppressWarnings("deprecation")
    void describe() {
        if (entry == null) return;
        String title = entry.title();
        Bitmap icon = entry.icon;
        try {
            setTaskDescription(new ActivityManager.TaskDescription(title, icon));
        } catch (RuntimeException ignored) {
            // an icon the system will not take is not worth a window
        }
        setTitle(title);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (entry != null) windows.focused(entry, hasFocus, this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (entry != null) windows.shown(entry, true);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (entry != null) windows.shown(entry, false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (listening) {
            listening = false;
            try {
                unregisterReceiver(closeReceiver);
            } catch (RuntimeException ignored) {
            }
        }
        if (entry != null) windows.detach(entry, this, isFinishing());
    }

    /** Every key is the app's, but the volume keys: the activity decides nothing. */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (view != null && view.key(event)) return true;
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onBackPressed() {
        // Nothing to go back from: a desktop window closes from its ✕.
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (view != null) view.place();
    }

    /** The app closed its window, or the session ended: this task goes with it. */
    void gone() {
        entry = null;
        finishAndRemoveTask();
    }
}
