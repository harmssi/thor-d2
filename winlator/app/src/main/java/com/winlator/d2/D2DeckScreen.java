package com.winlator.d2;

import android.app.Activity;
import android.app.Presentation;
import android.content.Context;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;

/**
 * Thor D2: shows {@link D2Deck} as a Presentation on the second display (Display 4 on the AYN Thor). The window is
 * FLAG_NOT_FOCUSABLE, so the gamepad stays with the game on the upper screen. Without a second display nothing
 * changes. Follows displays coming and going.
 */
final class D2DeckScreen implements DisplayManager.DisplayListener {
    private static final String TAG = "ThorD2";

    interface Listener {
        void deckShown(boolean shown);
    }

    private final Activity activity;
    private final D2Deck.Host host;
    private final Listener listener;
    private final DisplayManager displays;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Presentation presentation;
    private D2Deck deck;

    D2DeckScreen(Activity activity, D2Deck.Host host, Listener listener) {
        this.activity = activity;
        this.host = host;
        this.listener = listener;
        this.displays = (DisplayManager) activity.getSystemService(Context.DISPLAY_SERVICE);
    }

    static Display findSecondary(Activity activity, DisplayManager dm) {
        int own = Display.DEFAULT_DISPLAY;
        try {
            own = activity.getDisplay().getDisplayId();
        }
        catch (Throwable ignored) {}
        for (Display d : dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) if (d.getDisplayId() != own) return d;
        for (Display d : dm.getDisplays()) if (d.getDisplayId() != own) return d;
        return null;
    }

    void start() {
        displays.registerDisplayListener(this, handler);
        attach();
    }

    void stop() {
        displays.unregisterDisplayListener(this);
        detach();
    }

    void pause() {
        if (deck != null) deck.stop();
    }

    void resume() {
        if (deck != null) deck.start();
    }

    boolean shown() {
        return presentation != null;
    }

    private void detach() {
        if (deck != null) deck.stop();
        if (presentation != null) presentation.dismiss();
        boolean was = presentation != null;
        presentation = null;
        deck = null;
        if (was) listener.deckShown(false);
    }

    private void attach() {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        Display target = findSecondary(activity, displays);
        if (presentation != null && (target == null || presentation.getDisplay().getDisplayId() != target.getDisplayId())) detach();
        if (target == null || presentation != null) return;
        Presentation p = new Presentation(activity, target);
        // Presentation contexts do not inherit the app's language; use the activity's.
        Configuration cfg = new Configuration(p.getContext().getResources().getConfiguration());
        cfg.setLocales(activity.getResources().getConfiguration().getLocales());
        D2Deck d = new D2Deck(p.getContext().createConfigurationContext(cfg), host);
        p.setContentView(d);
        p.getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        try {
            p.show();
        }
        catch (WindowManager.InvalidDisplayException e) {
            Log.w(TAG, "deck: no presentation on display " + target.getDisplayId(), e);
            return;
        }
        presentation = p;
        deck = d;
        d.start();
        Log.i(TAG, "deck on display " + target.getDisplayId());
        listener.deckShown(true);
    }

    @Override public void onDisplayAdded(int displayId) { attach(); }
    @Override public void onDisplayRemoved(int displayId) { attach(); }
    @Override public void onDisplayChanged(int displayId) {}
}
