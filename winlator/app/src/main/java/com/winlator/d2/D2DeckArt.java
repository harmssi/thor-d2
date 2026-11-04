package com.winlator.d2;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Thor D2: graphics for the deck, read at runtime from the player's own game files (MPQ) – the game's fonts and the
 * inventory pictures of items. Nothing of it is part of the app. Without game files the deck uses its own look.
 */
final class D2DeckArt {
    private static final String TAG = "ThorD2";
    private static final int ITEM_RECORD = 424, I_INVFILE = 0x20, I_UNIQUE_INVFILE = 0x40, I_SET_INVFILE = 0x60, I_CODE = 0x80;

    /** A bitmap font of the game (DC6 glyphs + TBL widths), tinted per colour. */
    static final class Font {
        private final Bitmap[] glyphs;
        private final int[] widths;
        private final int height;
        private final Map<Integer, Paint> paints = new HashMap<>();
        private final RectF dst = new RectF();

        Font(Bitmap[] glyphs, int[] widths) {
            this.glyphs = glyphs;
            this.widths = widths;
            // size = height of a capital letter (the frames of the glyphs carry a lot of empty space)
            this.height = Math.max(1, ink(glyphs['H']));
        }

        private static int ink(Bitmap b) {
            if (b == null) return 1;
            int top = -1, bottom = -1;
            for (int y = 0; y < b.getHeight(); y++) for (int x = 0; x < b.getWidth(); x++) {
                if ((b.getPixel(x, y) >>> 24) == 0) continue;
                if (top < 0) top = y;
                bottom = y;
                break;
            }
            return top < 0 ? b.getHeight() : bottom - top + 1;
        }

        float width(String text, float size) {
            String t = D2FontText.map(text);
            float k = size / height, w = 0;
            for (int i = 0; i < t.length(); i++) w += widths[t.charAt(i) & 0xFF] * k;
            return w;
        }

        /** Draws text with its baseline area ending at y (bottom of the glyph cell), size = cell height in pixels. */
        void draw(Canvas c, String text, float x, float y, float size, int rgb, Paint.Align align) {
            String t = D2FontText.map(text);
            float k = size / height;
            if (align == Paint.Align.CENTER) x -= width(text, size) / 2;
            else if (align == Paint.Align.RIGHT) x -= width(text, size);
            Paint p = paint(rgb);
            for (int i = 0; i < t.length(); i++) {
                int ch = t.charAt(i) & 0xFF;
                Bitmap g = glyphs[ch];
                if (g != null) {
                    dst.set(x, y - g.getHeight() * k, x + g.getWidth() * k, y);
                    c.drawBitmap(g, null, dst, p);
                }
                x += widths[ch] * k;
            }
        }

        private Paint paint(int rgb) {
            Paint p = paints.get(rgb);
            if (p == null) {
                p = new Paint(Paint.FILTER_BITMAP_FLAG);
                p.setColorFilter(new ColorMatrixColorFilter(new ColorMatrix(new float[]{
                    ((rgb >> 16) & 255) / 255f, 0, 0, 0, 0,
                    0, ((rgb >> 8) & 255) / 255f, 0, 0, 0,
                    0, 0, (rgb & 255) / 255f, 0, 0,
                    0, 0, 0, 1, 0})));
                paints.put(rgb, p);
            }
            return p;
        }
    }

    final Font title, body;
    private final D2Sprites sprites;
    private final Map<String, String[]> invFiles = new HashMap<>();
    private final Map<String, Bitmap> pictures = new HashMap<>();
    private final ExecutorService loader = Executors.newSingleThreadExecutor();

    private D2DeckArt(D2Sprites s) throws IOException {
        sprites = s;
        title = font(s, "font30");
        body = font(s, "font16");
        for (String table : new String[]{"weapons", "armor", "misc"}) {
            byte[] d = s.read("data\\global\\excel\\" + table + ".bin");
            if (d == null || d.length < 4) continue;
            int n = (d[0] & 0xFF) | (d[1] & 0xFF) << 8 | (d[2] & 0xFF) << 16 | (d[3] & 0xFF) << 24;
            if (n <= 0 || 4 + (long) n * ITEM_RECORD > d.length) continue;
            for (int i = 0; i < n; i++) {
                int at = 4 + i * ITEM_RECORD;
                String code = new String(d, at + I_CODE, 4, StandardCharsets.ISO_8859_1).replace('\0', ' ');
                invFiles.put(code, new String[]{str(d, at + I_INVFILE), str(d, at + I_UNIQUE_INVFILE), str(d, at + I_SET_INVFILE)});
            }
        }
    }

    static D2DeckArt load(File gameDir) {
        try {
            return new D2DeckArt(new D2Sprites(gameDir));
        }
        catch (IOException | RuntimeException e) {
            Log.w(TAG, "deck: no game graphics, own look", e);
            return null;
        }
    }

    /** The inventory picture of an item, or null while it loads (then onLoaded runs on the loader thread). */
    synchronized Bitmap picture(D2DeckData.Item it, Runnable onLoaded) {
        String[] files = invFiles.get(it.code);
        if (files == null) return null;
        String file = it.quality == D2ModItems.QUALITY_UNIQUE && !files[1].isEmpty() ? files[1]
            : it.quality == D2ModItems.QUALITY_SET && !files[2].isEmpty() ? files[2] : files[0];
        if (file.isEmpty()) return null;
        if (pictures.containsKey(file)) return pictures.get(file);
        pictures.put(file, null);
        loader.execute(() -> {
            Bitmap b = null;
            try {
                D2Sprites.Frame[] f = sprites.dc6("data\\global\\items\\" + file + ".dc6");
                if (f != null && f.length > 0) b = Bitmap.createBitmap(f[0].argb, f[0].width, f[0].height, Bitmap.Config.ARGB_8888);
            }
            catch (IOException | RuntimeException e) {
                Log.w(TAG, "deck: no picture " + file, e);
            }
            synchronized (D2DeckArt.this) {
                pictures.put(file, b);
            }
            if (b != null) onLoaded.run();
        });
        return null;
    }

    private static Font font(D2Sprites s, String name) throws IOException {
        D2Sprites.Frame[] f = s.dc6("data\\local\\font\\latin\\" + name + ".dc6");
        byte[] tbl = s.read("data\\local\\font\\latin\\" + name + ".tbl");
        if (f == null || f.length < 256 || tbl == null || tbl.length < 12 + 14 * 256) throw new IOException("no font " + name);
        Bitmap[] g = new Bitmap[256];
        int[] w = new int[256];
        for (int i = 0; i < 256; i++) {
            if (f[i].width > 0 && f[i].height > 0) g[i] = Bitmap.createBitmap(f[i].argb, f[i].width, f[i].height, Bitmap.Config.ARGB_8888);
            w[i] = tbl[12 + i * 14 + 3] & 0xFF;
        }
        return new Font(g, w);
    }

    private static String str(byte[] d, int at) {
        int n = 0;
        while (n < 32 && d[at + n] != 0) n++;
        String s = new String(d, at, n, StandardCharsets.ISO_8859_1);
        return s.matches("[A-Za-z0-9_]*") ? s : "";
    }
}
