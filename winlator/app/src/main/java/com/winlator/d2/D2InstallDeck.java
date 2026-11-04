package com.winlator.d2;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.View;

/**
 * Thor D2: the lower screen during the installation – the steps with the current one and its progress, the overall
 * progress, the status line of the page above and hints. Shows nothing typed: the CD keys stay on the page above.
 */
@SuppressLint("ViewConstructor")
final class D2InstallDeck extends View {
    interface Host {
        D2InstallSteps steps();
        String stepName(D2InstallSteps.Step step);
        CharSequence status();
    }

    private static final float W = 1240, H = 1080;
    private static final int GOLD = 0xFFC7B377, TEXT = 0xFFE8E2D2, DIM = 0xFF7F7865, EDGE = 0xFF5A4A30, FILL = 0xE0100C09;
    private final Host host;
    private final boolean german;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), bg = new Paint();
    private final RectF r = new RectF();
    private boolean running;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            invalidate();
            postDelayed(this, 500);
        }
    };

    D2InstallDeck(Context context, Host host) {
        super(context);
        this.host = host;
        german = context.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("de");
        paint.setTypeface(Typeface.create(Typeface.SERIF, Typeface.NORMAL));
        stroke.setStyle(Paint.Style.STROKE);
        bg.setShader(new LinearGradient(0, 0, 0, H, 0xFF15110D, 0xFF070605, Shader.TileMode.CLAMP));
    }

    static D2DeckScreen.Page page(Host host) {
        return new D2DeckScreen.Page() {
            @Override public View create(Context context) { return new D2InstallDeck(context, host); }
            @Override public void start(View view) { ((D2InstallDeck) view).start(); }
            @Override public void stop(View view) { ((D2InstallDeck) view).stop(); }
        };
    }

    void start() {
        if (running) return;
        running = true;
        post(tick);
    }

    void stop() {
        running = false;
        removeCallbacks(tick);
    }

    private String t(String en, String de) {
        return german ? de : en;
    }

    @Override
    protected void onDraw(Canvas c) {
        float k = Math.min(getWidth() / W, getHeight() / H);
        c.save();
        c.scale(k, k);
        c.drawRect(0, 0, W, H, bg);
        panel(c, 16, 16, 760, 1064);
        text(c, t("Installing Diablo II", "Diablo II wird installiert"), 48, 84, 40, GOLD, true);
        D2InstallSteps steps = host.steps();
        float y = 160;
        for (D2InstallSteps.Step step : D2InstallSteps.Step.values()) {
            int state = steps.state(step);
            String mark = state == D2InstallSteps.DONE ? "✓" : state == D2InstallSteps.NOW ? "▸" : "·";
            int color = state == D2InstallSteps.DONE ? TEXT : state == D2InstallSteps.NOW ? GOLD : DIM;
            text(c, mark, 52, y, 32, color, true);
            text(c, host.stepName(step), 100, y, 30, color, state == D2InstallSteps.NOW);
            if (state == D2InstallSteps.NOW && steps.percent() >= 0) {
                paint.setTextAlign(Paint.Align.RIGHT);
                text(c, steps.percent() + " %", 730, y, 30, GOLD, true);
                paint.setTextAlign(Paint.Align.LEFT);
            }
            y += 70;
        }
        int total = steps.overall();
        text(c, t("Overall ", "Gesamt ") + total + " %", 48, y + 30, 28, TEXT, true);
        r.set(48, y + 50, 730, y + 80);
        paint.setColor(0xFF0A0807);
        c.drawRect(r, paint);
        r.right = 48 + 682 * total / 100f;
        paint.setColor(0xFFB0302A);
        c.drawRect(r, paint);
        r.right = 730;
        stroke.setColor(EDGE);
        stroke.setStrokeWidth(2);
        c.drawRect(r, stroke);
        CharSequence status = host.status();
        if (status != null) wrap(c, status.toString(), 48, y + 130, 682, 24, DIM, 3);

        panel(c, 776, 16, 1224, 1064);
        text(c, t("Good to know", "Gut zu wissen"), 804, 84, 32, GOLD, true);
        String[] hints = german ? new String[]{
            "Die CD-Keys tippst du oben ein. Sie gehen direkt an Blizzards Installer und werden nie gespeichert.",
            "WLAN nutzen: etwa 2 GB, 10 bis 40 Minuten.",
            "Blizzards eigene Dateien: Downloader, Diablo II, Lord of Destruction, dann Patch 1.13d.",
            "Steuerkreuz wählt, A bestätigt, B geht zurück.",
            "Der Thor darf dabei am Strom bleiben; das Display bleibt an."
        } : new String[]{
            "Type your CD keys on the upper screen. They go straight to Blizzard's installer and are never stored.",
            "Use Wi-Fi: about 2 GB, 10 to 40 minutes.",
            "Blizzard's own files: downloader, Diablo II, Lord of Destruction, then patch 1.13d.",
            "D-pad chooses, A confirms, B goes back.",
            "Keep the Thor plugged in; the screen stays on."
        };
        float hy = 150;
        for (String h : hints) hy = wrap(c, h, 804, hy, 392, 24, TEXT, 5) + 34;
        c.restore();
    }

    private float wrap(Canvas c, String s, float x, float y, float maxW, float size, int color, int maxLines) {
        paint.setTextSize(size);
        StringBuilder line = new StringBuilder();
        int lines = 0;
        for (String word : s.split(" ")) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (paint.measureText(next) > maxW && line.length() > 0) {
                text(c, line.toString(), x, y, size, color, false);
                y += size * 1.35f;
                line = new StringBuilder(word);
                if (++lines >= maxLines) return y;
            }
            else line = new StringBuilder(next);
        }
        if (line.length() > 0) {
            text(c, line.toString(), x, y, size, color, false);
            y += size * 1.35f;
        }
        return y;
    }

    private void panel(Canvas c, float l, float t, float rr, float b) {
        r.set(l, t, rr, b);
        paint.setColor(FILL);
        c.drawRect(r, paint);
        stroke.setColor(EDGE);
        stroke.setStrokeWidth(3);
        c.drawRect(r, stroke);
        stroke.setColor(0xFF2A2116);
        stroke.setStrokeWidth(1);
        c.drawRect(l + 6, t + 6, rr - 6, b - 6, stroke);
    }

    private void text(Canvas c, String s, float x, float y, float size, int color, boolean bold) {
        paint.setTextSize(size);
        paint.setColor(color);
        paint.setFakeBoldText(bold);
        c.drawText(s, x, y, paint);
        paint.setFakeBoldText(false);
    }
}
