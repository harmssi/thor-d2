package com.winlator.d2;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Thor D2: the deck on the lower screen (1240x1080 on the AYN Thor), live from the mod: values, life and mana orbs,
 * skills, effects, area and quests, inventory, belt, stash, cube and mercenary, and a sort button.
 * It looks like the game when the player's game files are there: the frames, orbs, skill icons, fonts and item
 * pictures are read at runtime from the player's own MPQs (D2Hud, D2DeckArt). Without them it draws its own dark
 * look. No graphics of the game are part of the app.
 */
@SuppressLint("ViewConstructor")
public final class D2Deck extends View {
    public interface Host {
        D2ModLink link();
        D2Hud hud();
        File gameDir();
    }

    private static final float W = 1240, H = 1080;
    // Colours of the game: gold headings, white/blue/yellow/gold/green/orange/grey items, resistance colours.
    private static final int GOLD = 0xFFC7B377, TEXT = 0xFFE8E2D2, DIM = 0xFF8F8775, RED = 0xFFD04040,
        PANEL_FILL = 0xE0100C09, EDGE = 0xFF5A4A30, XP = 0xFFB89A3A, MERC = 0xFF3FA040;
    private static final int TAB_INV = 0, TAB_STASH = 1, TAB_CUBE = 2, TAB_MERC = 3, TAB_QUESTS = 4;

    private final Host host;
    private final boolean german;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
        text = new Paint(Paint.ANTI_ALIAS_FLAG), icon = new Paint(Paint.FILTER_BITMAP_FLAG), bg = new Paint();
    private final RectF r = new RectF();
    private final D2Effects effects = new D2Effects();
    private D2Hud effectsHud;
    private volatile D2DeckArt art;
    private File artDir;
    private int tab = TAB_INV;
    private long selectedId = -1;
    private String note;
    private long noteUntil, sortSentAt;
    private final List<Hit> hits = new ArrayList<>();
    private boolean running;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            invalidate();
            postDelayed(this, 250);
        }
    };

    private static final class Hit {
        final RectF area;
        final Runnable action;

        Hit(RectF area, Runnable action) {
            this.area = area;
            this.action = action;
        }
    }

    public D2Deck(Context context, Host host) {
        super(context);
        this.host = host;
        this.german = context.getResources().getConfiguration().getLocales().get(0).getLanguage().equals("de");
        stroke.setStyle(Paint.Style.STROKE);
        text.setTypeface(Typeface.create(Typeface.SERIF, Typeface.NORMAL));
        bg.setShader(new LinearGradient(0, 0, 0, H, 0xFF15110D, 0xFF070605, Shader.TileMode.CLAMP));
    }

    public void start() {
        if (running) return;
        running = true;
        post(tick);
    }

    public void stop() {
        running = false;
        removeCallbacks(tick);
    }

    private String t(String en, String de) {
        return german ? de : en;
    }

    private void loadArt() {
        File dir = host.gameDir();
        if (dir == null || dir.equals(artDir)) return;
        artDir = dir;
        new Thread(() -> {
            D2DeckArt a = D2DeckArt.load(dir);
            if (a != null) {
                art = a;
                postInvalidate();
            }
        }, "deck-art").start();
    }

    // ---- drawing ------------------------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        hits.clear();
        float k = Math.min(getWidth() / W, getHeight() / H);
        c.save();
        c.scale(k, k);
        c.drawRect(0, 0, W, H, bg);
        loadArt();
        D2ModLink link = host.link();
        D2ModState s = link != null ? link.state() : null;
        D2DeckData.Stats st = link != null ? link.deck.stats(3000) : null;
        D2Hud hud = host.hud();
        if (s == null || st == null) {
            drawWaiting(c, link, hud);
            c.restore();
            return;
        }
        drawHeader(c, hud, link, s, st);
        drawOrbs(c, hud, link, st);
        drawValues(c, hud, st);
        drawSkills(c, hud, s);
        drawEffects(c, hud, link);
        drawTabs(c, hud);
        RectF content = new RectF(616, 182, 1224, 900);
        int[] grids = link.grids();
        switch (tab) {
            case TAB_INV: drawInventory(c, hud, link, content, grids); break;
            case TAB_STASH: drawGridPage(c, hud, link.deck.items(D2DeckData.OWNER_HERO), D2DeckData.PAGE_STASH,
                dim(grids, D2Points.GRIDS_STASH_W, 6), dim(grids, D2Points.GRIDS_STASH_H, 8), content,
                t("Stash is empty or not loaded yet", "Truhe leer oder noch nicht geladen")); break;
            case TAB_CUBE: drawGridPage(c, hud, link.deck.items(D2DeckData.OWNER_HERO), D2DeckData.PAGE_CUBE,
                dim(grids, D2Points.GRIDS_CUBE_W, 3), dim(grids, D2Points.GRIDS_CUBE_H, 4), content,
                t("Cube is empty", "Würfel ist leer")); break;
            case TAB_MERC: drawMerc(c, hud, link, st, content); break;
            default: drawQuests(c, hud, link, s, content);
        }
        drawActions(c, hud, link, st);
        c.restore();
    }

    private static int dim(int[] grids, int index, int fallback) {
        return grids != null && grids[index] > 0 ? grids[index] : fallback;
    }

    private void drawWaiting(Canvas c, D2ModLink link, D2Hud hud) {
        panel(c, hud, 16, 16, 1224, 1064);
        title(c, "Thor D2", W / 2, 500, 56, GOLD, Paint.Align.CENTER);
        body(c, link == null || !link.isConnected() ? t("Waiting for the game …", "Warte auf das Spiel …")
            : t("Waiting for a character in the game …", "Warte auf einen Charakter im Spiel …"), W / 2, 560, 28, DIM, Paint.Align.CENTER);
    }

    private void drawHeader(Canvas c, D2Hud hud, D2ModLink link, D2ModState s, D2DeckData.Stats st) {
        panel(c, hud, 16, 16, 1224, 100);
        String name = link.characterName();
        String[] classes = german
            ? new String[]{"Amazone", "Zauberin", "Totenbeschwörer", "Paladin", "Barbar", "Druide", "Assassine"}
            : new String[]{"Amazon", "Sorceress", "Necromancer", "Paladin", "Barbarian", "Druid", "Assassin"};
        String cls = s.characterClass >= 0 && s.characterClass < classes.length ? classes[s.characterClass] : "";
        String[] diffs = german ? new String[]{"Normal", "Albtraum", "Hölle"} : new String[]{"Normal", "Nightmare", "Hell"};
        title(c, name != null ? name : "?", 40, 62, 40, GOLD, Paint.Align.LEFT);
        body(c, cls + " · " + t("Level ", "Stufe ") + st.get(12) + (st.difficulty < 3 ? " · " + diffs[st.difficulty] : ""),
            40, 90, 22, DIM, Paint.Align.LEFT);
        D2LevelNames names = D2LevelNames.current();
        String area = names != null ? names.name(s.levelNo) : null;
        int act = actOf(s.levelNo);
        title(c, area != null ? area : t("Area ", "Gebiet ") + s.levelNo, 1200, 62, 34, GOLD, Paint.Align.RIGHT);
        body(c, (act > 0 ? t("Act ", "Akt ") + act : "") + (s.inTown ? t(" · town", " · Stadt") : "")
            + " · Gold " + fmt(st.get(14)) + t(" · stash ", " · Truhe ") + fmt(st.get(15)), 1200, 90, 22, DIM, Paint.Align.RIGHT);
    }

    private void drawOrbs(Canvas c, D2Hud hud, D2ModLink link, D2DeckData.Stats st) {
        panel(c, hud, 16, 108, 600, 400);
        long life = st.get(6) >> 8, maxLife = st.get(7) >> 8, mana = st.get(8) >> 8, maxMana = st.get(9) >> 8;
        int lifeP = maxLife > 0 ? (int) (life * 100 / maxLife) : 0, manaP = maxMana > 0 ? (int) (mana * 100 / maxMana) : 0;
        float size = 150;
        orb(c, hud, true, 40, 124, size, lifeP);
        orb(c, hud, false, 576 - size, 124, size, manaP);
        body(c, life + " / " + maxLife, 40 + size / 2, 304, 24, TEXT, Paint.Align.CENTER);
        body(c, mana + " / " + maxMana, 576 - size / 2, 304, 24, TEXT, Paint.Align.CENTER);
        body(c, t("Life", "Leben"), 40 + size / 2, 330, 20, RED, Paint.Align.CENTER);
        body(c, "Mana", 576 - size / 2, 330, 20, 0xFF6080E0, Paint.Align.CENTER);
        // Middle column: experience and mercenary.
        float mx = 210, mw = 196;
        body(c, t("Experience", "Erfahrung"), mx + mw / 2, 160, 20, DIM, Paint.Align.CENTER);
        long[] xp = link.experience();
        float share = 0;
        String xpText = fmt(st.get(13));
        if (xp != null && xp[2] > xp[1]) {
            share = (xp[0] - xp[1]) / (float) (xp[2] - xp[1]);
            xpText = Math.round(share * 100) + " %";
        }
        bar(c, mx, 172, mw, share, XP);
        body(c, xpText, mx + mw / 2, 222, 22, TEXT, Paint.Align.CENTER);
        if (st.merc) {
            body(c, t("Mercenary ", "Söldner ") + st.mercLevel, mx + mw / 2, 264, 20, DIM, Paint.Align.CENTER);
            bar(c, mx, 276, mw, st.mercMaxLife > 0 ? st.mercLife / (float) st.mercMaxLife : 0, MERC);
            body(c, (st.mercLife >> 8) + " / " + (st.mercMaxLife >> 8), mx + mw / 2, 326, 20, TEXT, Paint.Align.CENTER);
        }
        else body(c, t("no mercenary", "kein Söldner"), mx + mw / 2, 290, 20, DIM, Paint.Align.CENTER);
        if (st.get(4) > 0 || st.get(5) > 0)
            body(c, (st.get(4) > 0 ? t("stat points ", "Attributpunkte ") + st.get(4) : "")
                + (st.get(4) > 0 && st.get(5) > 0 ? " · " : "") + (st.get(5) > 0 ? t("skill points ", "Fertigkeitspunkte ") + st.get(5) : ""),
                308, 380, 22, GOLD, Paint.Align.CENTER);
    }

    private void orb(Canvas c, D2Hud hud, boolean life, float x, float y, float size, int percent) {
        if (hud != null) {
            float k = size / hud.orbWidth();
            hud.drawOrb(c, life, x, y, k, percent);
            return;
        }
        // Own look: a glass ball filled from below.
        float cx = x + size / 2, cy = y + size / 2, rad = size / 2 - 4;
        fill.setShader(null);
        fill.setColor(0xFF0A0808);
        c.drawCircle(cx, cy, rad, fill);
        c.save();
        c.clipRect(x, y + size - 4 - (size - 8) * Math.max(0, Math.min(100, percent)) / 100f, x + size, y + size);
        fill.setShader(new RadialGradient(cx - rad / 3, cy - rad / 3, rad * 1.3f, life ? 0xFFE04030 : 0xFF4060F0,
            life ? 0xFF500808 : 0xFF080C50, Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, rad, fill);
        fill.setShader(null);
        c.restore();
        stroke.setColor(EDGE);
        stroke.setStrokeWidth(4);
        c.drawCircle(cx, cy, rad, stroke);
    }

    private void bar(Canvas c, float x, float y, float w, float share, int color) {
        r.set(x, y, x + w, y + 18);
        fill.setColor(0xFF0A0807);
        c.drawRect(r, fill);
        r.right = x + w * Math.max(0, Math.min(1, share));
        fill.setColor(color);
        c.drawRect(r, fill);
        r.right = x + w;
        stroke.setColor(EDGE);
        stroke.setStrokeWidth(2);
        c.drawRect(r, stroke);
    }

    private void drawValues(Canvas c, D2Hud hud, D2DeckData.Stats st) {
        panel(c, hud, 16, 412, 600, 610);
        int[][] res = {{39, 40, 0xFFE05030}, {43, 44, 0xFF5090F0}, {41, 42, 0xFFF0E050}, {45, 46, 0xFF40C040}};
        String[] resNames = german ? new String[]{"Feuer", "Kälte", "Blitz", "Gift"} : new String[]{"Fire", "Cold", "Lightning", "Poison"};
        for (int i = 0; i < 4; i++) {
            float x = 36 + i * 140;
            r.set(x, 430, x + 128, 508);
            fill.setColor(0xFF0A0807);
            c.drawRect(r, fill);
            stroke.setColor(res[i][2]);
            stroke.setStrokeWidth(2);
            c.drawRect(r, stroke);
            int v = st.resistance(res[i][0], res[i][1]);
            body(c, resNames[i], x + 64, 456, 20, res[i][2], Paint.Align.CENTER);
            body(c, v + "%", x + 64, 498, 30, v < 0 ? RED : TEXT, Paint.Align.CENTER);
        }
        String[] attr = german ? new String[]{"Stärke", "Geschick", "Vitalität", "Energie"} : new String[]{"Strength", "Dexterity", "Vitality", "Energy"};
        long[] av = {st.get(0), st.get(2), st.get(3), st.get(1)};
        for (int i = 0; i < 4; i++) {
            float x = 36 + i * 140;
            body(c, attr[i], x, 540, 20, DIM, Paint.Align.LEFT);
            body(c, String.valueOf(av[i]), x, 574, 28, TEXT, Paint.Align.LEFT);
        }
        body(c, t("Defense ", "Verteidigung ") + st.get(31) + " · " + t("Magic find ", "Magiefund ") + st.get(80) + "%",
            36, 598, 20, DIM, Paint.Align.LEFT);
    }

    private void drawSkills(Canvas c, D2Hud hud, D2ModState s) {
        panel(c, hud, 16, 622, 600, 820);
        skill(c, hud, s.leftSkill, t("Left", "Links"), 36, 640);
        skill(c, hud, s.rightSkill, t("Right", "Rechts"), 316, 640);
        for (int i = 0; i < 8; i++) {
            int id = s.hotkeySkill[i];
            float x = 36 + (i % 4) * 140, y = 750 + (i / 4) * 34;
            body(c, "F" + (i + 1) + " " + (id >= 0 ? ellipsize(skillName(hud, id), 104, 18) : "–"), x, y, 18,
                id >= 0 ? TEXT : DIM, Paint.Align.LEFT);
        }
    }

    private void skill(Canvas c, D2Hud hud, int id, String side, float x, float y) {
        Bitmap b = hud != null && id >= 0 ? hud.skillIcon(id) : null;
        r.set(x, y, x + 64, y + 64);
        fill.setColor(0xFF0A0807);
        c.drawRect(r, fill);
        if (b != null) c.drawBitmap(b, null, r, icon);
        body(c, side, x + 76, y + 24, 18, DIM, Paint.Align.LEFT);
        body(c, id >= 0 ? ellipsize(skillName(hud, id), 190, 24) : "–", x + 76, y + 56, 24, GOLD, Paint.Align.LEFT);
    }

    private String skillName(D2Hud hud, int id) {
        String n = hud != null ? hud.skillName(id) : null;
        return n != null ? n : "#" + id;
    }

    private void drawEffects(Canvas c, D2Hud hud, D2ModLink link) {
        panel(c, hud, 16, 832, 600, 1064);
        body(c, t("Effects and auras", "Effekte und Auren"), 36, 870, 24, GOLD, Paint.Align.LEFT);
        if (hud != effectsHud) {
            effects.setTexts(getContext(), hud);
            effectsHud = hud;
        }
        D2Effects.Effect[] list = link.effects();
        if (list.length == 0) {
            body(c, t("none", "keine"), 36, 906, 20, DIM, Paint.Align.LEFT);
            return;
        }
        float y = 906;
        for (D2Effects.Effect e : list) {
            if (y > 1050) break;
            String d;
            try {
                d = effects.describe(e);
            }
            catch (RuntimeException ex) {
                d = "#" + e.state;
            }
            body(c, ellipsize(d, 548, 20), 36, y, 20, TEXT, Paint.Align.LEFT);
            y += 30;
        }
    }

    private void drawTabs(Canvas c, D2Hud hud) {
        String[] names = german ? new String[]{"Inventar", "Truhe", "Würfel", "Söldner", "Quests"}
            : new String[]{"Inventory", "Stash", "Cube", "Mercenary", "Quests"};
        float w = (1224 - 616) / (float) names.length;
        for (int i = 0; i < names.length; i++) {
            final int which = i;
            RectF a = new RectF(616 + i * w + (i > 0 ? 4 : 0), 108, 616 + (i + 1) * w, 172);
            panel(c, hud, a.left, a.top, a.right, a.bottom);
            if (i == tab) {
                fill.setColor(0x40C7B377);
                c.drawRect(a.left + 6, a.top + 6, a.right - 6, a.bottom - 6, fill);
            }
            body(c, names[i], a.centerX(), a.centerY() + 8, 20, i == tab ? GOLD : DIM, Paint.Align.CENTER);
            hits.add(new Hit(a, () -> { tab = which; selectedId = -1; }));
        }
    }

    private void drawInventory(Canvas c, D2Hud hud, D2ModLink link, RectF area, int[] grids) {
        panel(c, hud, area.left, area.top, area.right, area.bottom);
        List<D2DeckData.Item> items = link.deck.items(D2DeckData.OWNER_HERO);
        float bottom = grid(c, items, D2DeckData.PAGE_INV, dim(grids, D2Points.GRIDS_INV_W, 10), dim(grids, D2Points.GRIDS_INV_H, 4),
            area.left + 20, area.top + 20, area.width() - 40, 58, 420);
        int rows = 1;
        for (D2DeckData.Item it : items) if (it.page == D2DeckData.PAGE_BELT) rows = Math.max(rows, it.x / 4 + 1);
        body(c, t("Belt", "Gürtel"), area.left + 20, bottom + 40, 22, GOLD, Paint.Align.LEFT);
        float beltBottom = beltGrid(c, items, rows, area.left + 20, bottom + 52, 56);
        body(c, t("Equipped", "Angelegt"), area.left + 268, bottom + 40, 22, GOLD, Paint.Align.LEFT);
        String[] slots = german
            ? new String[]{"", "Kopf", "Amulett", "Rüstung", "Rechts", "Links", "Ring r.", "Ring l.", "Gürtel", "Stiefel", "Handsch.", "Waffe 2 r.", "Waffe 2 l."}
            : new String[]{"", "Head", "Amulet", "Armor", "Right", "Left", "Ring r.", "Ring l.", "Belt", "Boots", "Gloves", "Swap r.", "Swap l."};
        float y = bottom + 74;
        for (D2DeckData.Item it : items) {
            if (it.page != D2DeckData.PAGE_BODY || y > area.bottom - 50) continue;
            String slot = it.x < slots.length ? slots[it.x] : "?";
            RectF a = new RectF(area.left + 268, y - 22, area.right - 16, y + 6);
            if (it.id == selectedId) {
                fill.setColor(0x40C7B377);
                c.drawRect(a, fill);
            }
            body(c, slot, area.left + 268, y, 19, DIM, Paint.Align.LEFT);
            body(c, ellipsize(it.oneLine(), area.right - area.left - 400, 19), area.left + 368, y, 19, qualityColor(it), Paint.Align.LEFT);
            hits.add(new Hit(a, () -> selectedId = it.id));
            y += 28;
        }
        drawSelected(c, items, area);
    }

    private void drawGridPage(Canvas c, D2Hud hud, List<D2DeckData.Item> items, int page, int gw, int gh, RectF area, String empty) {
        panel(c, hud, area.left, area.top, area.right, area.bottom);
        boolean any = false;
        for (D2DeckData.Item it : items) any |= it.page == page;
        if (!any) {
            body(c, empty, area.left + 28, area.top + 56, 24, DIM, Paint.Align.LEFT);
            return;
        }
        grid(c, items, page, gw, gh, area.left + 20, area.top + 20, area.width() - 40, 80, area.height() - 100);
        drawSelected(c, items, area);
    }

    private void drawMerc(Canvas c, D2Hud hud, D2ModLink link, D2DeckData.Stats st, RectF area) {
        panel(c, hud, area.left, area.top, area.right, area.bottom);
        if (!st.merc) {
            body(c, t("No mercenary nearby (or none hired)", "Kein Söldner in der Nähe (oder keiner angeheuert)"),
                area.left + 28, area.top + 56, 24, DIM, Paint.Align.LEFT);
            return;
        }
        body(c, t("Mercenary, level ", "Söldner, Stufe ") + st.mercLevel, area.left + 28, area.top + 56, 28, GOLD, Paint.Align.LEFT);
        body(c, t("Life ", "Leben ") + (st.mercLife >> 8) + " / " + (st.mercMaxLife >> 8), area.left + 28, area.top + 92, 22, TEXT, Paint.Align.LEFT);
        float y = area.top + 140;
        List<D2DeckData.Item> items = link.deck.items(D2DeckData.OWNER_MERC);
        if (items.isEmpty()) body(c, t("No equipment seen", "Keine Ausrüstung gesehen"), area.left + 28, y, 22, DIM, Paint.Align.LEFT);
        for (D2DeckData.Item it : items) {
            Bitmap b = picture(it);
            if (b != null) {
                float h = 48, w = h * b.getWidth() / b.getHeight();
                r.set(area.left + 28, y - 36, area.left + 28 + w, y + 12);
                c.drawBitmap(b, null, r, icon);
            }
            body(c, ellipsize(it.oneLine(), area.width() - 140, 22), area.left + 100, y, 22, qualityColor(it), Paint.Align.LEFT);
            y += 58;
        }
    }

    private static final String[][] QUESTS_EN = {
        {"Den of Evil", "Sisters' Burial Grounds", "Tools of the Trade", "The Search for Cain", "The Forgotten Tower", "Sisters to the Slaughter"},
        {"Radament's Lair", "The Horadric Staff", "Tainted Sun", "Arcane Sanctuary", "The Summoner", "The Seven Tombs"},
        {"Lam Esen's Tome", "Khalim's Will", "Blade of the Old Religion", "The Golden Bird", "The Blackened Temple", "The Guardian"},
        {"The Fallen Angel", "Terror's End", "Hell's Forge"},
        {"Siege on Harrogath", "Rescue on Mount Arreat", "Prison of Ice", "Betrayal of Harrogath", "Rite of Passage", "Eve of Destruction"},
    };
    // Word of the first quest of each act in the quest flags (same layout as the quest block of a .d2s file).
    private static final int[] QUEST_FIRST_WORD = {1, 9, 17, 25, 35};

    private void drawQuests(Canvas c, D2Hud hud, D2ModLink link, D2ModState s, RectF area) {
        panel(c, hud, area.left, area.top, area.right, area.bottom);
        int[] words = link.questWorte();
        int act = Math.max(1, actOf(s.levelNo));
        float y = area.top + 52;
        String hint = null;
        for (int a = 0; a < 5; a++) {
            boolean current = a + 1 == act;
            body(c, t("Act ", "Akt ") + (a + 1), area.left + 28, y, current ? 28 : 22, current ? GOLD : DIM, Paint.Align.LEFT);
            int done = 0;
            for (int q = 0; q < QUESTS_EN[a].length; q++) {
                int w = QUEST_FIRST_WORD[a] + q;
                int v = words != null && w < words.length ? words[w] : -1;
                boolean finished = v >= 0 && (v & 1) != 0;
                if (finished) done++;
                String state = v < 0 ? "?" : finished ? t("done", "erledigt") : (v & 2) != 0 ? t("reward", "Belohnung") : (v & 4) != 0 ? t("started", "begonnen") : "·";
                if (current) {
                    y += 34;
                    body(c, QUESTS_EN[a][q], area.left + 52, y, 22, finished ? DIM : TEXT, Paint.Align.LEFT);
                    body(c, state, area.right - 28, y, 22, finished ? MERC : GOLD, Paint.Align.RIGHT);
                }
                if (hint == null && v >= 0 && !finished && a + 1 >= act) hint = QUESTS_EN[a][q] + (state.equals("·") ? "" : " (" + state + ")");
            }
            if (!current) body(c, words != null ? done + " / " + QUESTS_EN[a].length : "?", area.right - 28, y, 22, DIM, Paint.Align.RIGHT);
            y += 44;
        }
        body(c, words == null ? t("Quest state not known yet", "Queststand noch unbekannt")
            : hint != null ? t("Next: ", "Als Nächstes: ") + hint : t("All quests of this act are done", "Alle Quests dieses Akts erledigt"),
            area.left + 28, area.bottom - 28, 22, GOLD, Paint.Align.LEFT);
    }

    private void drawActions(Canvas c, D2Hud hud, D2ModLink link, D2DeckData.Stats st) {
        panel(c, hud, 616, 912, 1224, 1064);
        boolean single = st.singlePlayer && link.singlePlayer();
        D2Points pts = link.points(3000);
        boolean sorting = pts != null && pts.sortState == D2Points.SORT_RUNNING && SystemClock.uptimeMillis() - sortSentAt < 25000;
        button(c, hud, new RectF(636, 930, 904, 1002), sorting ? t("Sorting …", "Sortiere …") : t("Sort inventory", "Inventar sortieren"),
            single && !sorting, () -> {
                sortSentAt = SystemClock.uptimeMillis();
                link.sortInventory(false);
                note(t("Sorting: the game moves the items one by one", "Sortieren: Das Spiel legt die Gegenstände einzeln um"));
            });
        button(c, hud, new RectF(916, 930, 1060, 1002), "Portal", single, link::portal);
        button(c, hud, new RectF(1072, 930, 1204, 1002), t("Potion", "Trank"), single, () -> link.drink(D2ModLink.DRINK_HEAL, false));
        String status;
        if (!single) status = t("Multiplayer / Battle.net: deck actions are off", "Mehrspieler/Battle.net: Deck-Aktionen gesperrt");
        else if (note != null && SystemClock.uptimeMillis() < noteUntil) status = note;
        else if (pts != null && pts.sortCounter > 0) status = sortText(pts.sortState, pts.sortMoves);
        else status = t("Tap an item for its full name", "Gegenstand antippen zeigt den vollen Namen");
        body(c, ellipsize(status, 568, 20), 636, 1040, 20, DIM, Paint.Align.LEFT);
    }

    private String sortText(int state, int moves) {
        switch (state) {
            case D2Points.SORT_RUNNING: return t("Sorting …", "Sortiere …");
            case D2Points.SORT_DONE: return t("Sorted (" + moves + " moves)", "Sortiert (" + moves + " Züge)");
            case D2Points.SORT_PARTIAL: return t("Partly sorted", "Teilweise sortiert");
            case D2Points.SORT_CURSOR: return t("Sorting stopped: item on the cursor", "Abgebrochen: Gegenstand am Mauszeiger");
            case D2Points.SORT_NO_ROOM: return t("No room to sort", "Kein Platz zum Sortieren");
            default: return t("Not sorted (shop/stash open, monsters near or paused)",
                "Nicht sortiert (Händler/Truhe offen, Monster nah oder Pause)");
        }
    }

    private void note(String text) {
        note = text;
        noteUntil = SystemClock.uptimeMillis() + 4000;
    }

    private void button(Canvas c, D2Hud hud, RectF a, String label, boolean enabled, Runnable action) {
        panel(c, hud, a.left, a.top, a.right, a.bottom);
        fill.setColor(enabled ? 0x30C7B377 : 0x60000000);
        c.drawRect(a.left + 6, a.top + 6, a.right - 6, a.bottom - 6, fill);
        body(c, label, a.centerX(), a.centerY() + 9, 22, enabled ? GOLD : DIM, Paint.Align.CENTER);
        if (enabled) hits.add(new Hit(a, action));
    }

    /** Draws the items of one page as a grid; returns the bottom edge. */
    private float grid(Canvas c, List<D2DeckData.Item> items, int page, int cols, int rows, float x0, float y0, float maxW,
                       float maxCell, float maxH) {
        for (D2DeckData.Item it : items) {
            if (it.page != page) continue;
            cols = Math.max(cols, it.x + it.w);
            rows = Math.max(rows, it.y + it.h);
        }
        float cell = Math.min(maxCell, Math.min(maxW / cols, maxH / rows));
        fill.setColor(0xFF0A0807);
        stroke.setColor(0xFF2A2318);
        stroke.setStrokeWidth(1);
        for (int y = 0; y < rows; y++) for (int x = 0; x < cols; x++) {
            r.set(x0 + x * cell + 1, y0 + y * cell + 1, x0 + (x + 1) * cell - 1, y0 + (y + 1) * cell - 1);
            c.drawRect(r, fill);
            c.drawRect(r, stroke);
        }
        for (D2DeckData.Item it : items) {
            if (it.page != page) continue;
            RectF a = new RectF(x0 + it.x * cell + 1, y0 + it.y * cell + 1, x0 + (it.x + it.w) * cell - 1, y0 + (it.y + it.h) * cell - 1);
            tile(c, it, a, cell);
        }
        return y0 + rows * cell;
    }

    private float beltGrid(Canvas c, List<D2DeckData.Item> items, int rows, float x0, float y0, float cell) {
        fill.setColor(0xFF0A0807);
        stroke.setColor(0xFF2A2318);
        stroke.setStrokeWidth(1);
        for (int y = 0; y < rows; y++) for (int x = 0; x < 4; x++) {
            r.set(x0 + x * cell + 1, y0 + y * cell + 1, x0 + (x + 1) * cell - 1, y0 + (y + 1) * cell - 1);
            c.drawRect(r, fill);
            c.drawRect(r, stroke);
        }
        for (D2DeckData.Item it : items) {
            if (it.page != D2DeckData.PAGE_BELT) continue;
            int col = it.x % 4, row = it.x / 4;
            // Row 0 is the bottom row of the belt, as in the game.
            float top = y0 + (rows - 1 - row) * cell;
            tile(c, it, new RectF(x0 + col * cell + 1, top + 1, x0 + (col + 1) * cell - 1, top + cell - 1), cell);
        }
        return y0 + rows * cell;
    }

    private Bitmap picture(D2DeckData.Item it) {
        D2DeckArt a = art;
        return a != null ? a.picture(it, this::postInvalidate) : null;
    }

    private void tile(Canvas c, D2DeckData.Item it, RectF a, float cell) {
        int color = qualityColor(it);
        // Like the game: a tinted cell behind the item, blue for magic, green for set, red-ish when unidentified.
        int tint = !it.identified() && it.quality >= D2ModItems.QUALITY_MAGIC ? 0x50A02020 : (color & 0x00FFFFFF) | 0x28000000;
        fill.setColor(it.id == selectedId ? 0x70C7B377 : tint);
        c.drawRect(a, fill);
        Bitmap b = picture(it);
        if (b != null) {
            float k = Math.min(a.width() / b.getWidth(), a.height() / b.getHeight());
            float w = b.getWidth() * k, h = b.getHeight() * k;
            r.set(a.centerX() - w / 2, a.centerY() - h / 2, a.centerX() + w / 2, a.centerY() + h / 2);
            c.drawBitmap(b, null, r, icon);
        }
        else {
            stroke.setColor(color);
            stroke.setStrokeWidth(1.5f);
            c.drawRect(a, stroke);
            wrap(c, it.w == 1 && it.h == 1 ? longestWord(it.oneLine()) : it.oneLine(), a, Math.max(12, Math.min(17, cell * 0.3f)), color);
        }
        if (it.quantity > 1 && it.w * it.h <= 2) body(c, String.valueOf(it.quantity), a.right - 3, a.bottom - 3, 16, TEXT, Paint.Align.RIGHT);
        hits.add(new Hit(a, () -> selectedId = it.id));
    }

    private void drawSelected(Canvas c, List<D2DeckData.Item> items, RectF area) {
        for (D2DeckData.Item it : items) {
            if (it.id != selectedId) continue;
            String line = it.oneLine() + (it.ethereal() ? t(" · ethereal", " · ätherisch") : "")
                + (!it.identified() && it.quality >= D2ModItems.QUALITY_MAGIC ? t(" · unidentified", " · nicht identifiziert") : "");
            fill.setColor(0xE0000000);
            c.drawRect(area.left + 8, area.bottom - 50, area.right - 8, area.bottom - 8, fill);
            body(c, ellipsize(line, area.width() - 40, 22), area.left + 20, area.bottom - 20, 22, qualityColor(it), Paint.Align.LEFT);
            return;
        }
    }

    private static String longestWord(String n) {
        String best = n;
        for (String w : n.split(" ")) if (best == n || w.length() > best.length()) best = w;
        return best;
    }

    /** Item colours as in the game. */
    private static int qualityColor(D2DeckData.Item it) {
        if (it.runeword()) return 0xFFC7B377;
        switch (it.quality) {
            case D2ModItems.QUALITY_MAGIC: return 0xFF6969FF;
            case D2ModItems.QUALITY_SET: return 0xFF00C400;
            case D2ModItems.QUALITY_RARE: return 0xFFFFFF64;
            case D2ModItems.QUALITY_UNIQUE: return 0xFFC7B377;
            case D2ModItems.QUALITY_CRAFTED: return 0xFFFFA800;
            default: return it.ethereal() || (it.flags & 2) != 0 ? 0xFF8A8A8A : 0xFFE8E8E8;
        }
    }

    private void wrap(Canvas c, String s, RectF a, float size, int color) {
        float maxW = a.width() - 6;
        int maxLines = Math.max(1, (int) ((a.height() - 4) / (size * 1.1f)));
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (width(next, size, false) <= maxW || line.length() == 0) line = new StringBuilder(next);
            else {
                lines.add(line.toString());
                line = new StringBuilder(word);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        int n = Math.min(maxLines, lines.size());
        float y = a.centerY() - (n - 1) * size * 0.55f + size * 0.4f;
        for (int i = 0; i < n; i++) body(c, ellipsize(lines.get(i), maxW, size), a.centerX(), y + i * size * 1.1f, size, color, Paint.Align.CENTER);
    }

    // ---- frames and text: the game's when its files are there, else our own ---------------------------------------

    private void panel(Canvas c, D2Hud hud, float l, float t, float rr, float b) {
        r.set(l, t, rr, b);
        if (hud != null && hud.hasFrame()) {
            hud.drawFrame(c, r, 1.5f, PANEL_FILL);
            return;
        }
        fill.setColor(PANEL_FILL);
        c.drawRect(r, fill);
        stroke.setColor(EDGE);
        stroke.setStrokeWidth(3);
        c.drawRect(r, stroke);
        stroke.setColor(0xFF2A2116);
        stroke.setStrokeWidth(1);
        c.drawRect(l + 5, t + 5, rr - 5, b - 5, stroke);
    }

    private void title(Canvas c, String s, float x, float y, float size, int color, Paint.Align align) {
        D2DeckArt a = art;
        if (a != null) a.title.draw(c, s.replace('·', '-'), x, y + size * 0.1f, size * 0.85f, brighter(color), align);
        else androidText(c, s, x, y, size, color, align, true);
    }

    private void body(Canvas c, String s, float x, float y, float size, int color, Paint.Align align) {
        D2DeckArt a = art;
        if (a != null) a.body.draw(c, s.replace('·', '-'), x, y + size * 0.15f, size * 0.78f, brighter(color), align);
        else androidText(c, s, x, y, size, color, align, false);
    }

    // The game's glyphs are not white: tinting multiplies, so lift the colour to keep it as bright as meant.
    private static int brighter(int c) {
        int r = Math.min(255, ((c >> 16) & 255) * 13 / 10 + 40), g = Math.min(255, ((c >> 8) & 255) * 13 / 10 + 40),
            b = Math.min(255, (c & 255) * 13 / 10 + 40);
        return 0xFF000000 | r << 16 | g << 8 | b;
    }

    private void androidText(Canvas c, String s, float x, float y, float size, int color, Paint.Align align, boolean bold) {
        text.setTextSize(size);
        text.setColor(color);
        text.setFakeBoldText(bold);
        text.setTextAlign(align);
        c.drawText(s, x, y, text);
        text.setFakeBoldText(false);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private float width(String s, float size, boolean title) {
        D2DeckArt a = art;
        if (a != null) return (title ? a.title : a.body).width(s, size * (title ? 0.85f : 0.78f));
        text.setTextSize(size);
        return text.measureText(s);
    }

    private String ellipsize(String s, float maxW, float size) {
        if (width(s, size, false) <= maxW) return s;
        int n = s.length();
        while (n > 1 && width(s.substring(0, n) + "…", size, false) > maxW) n--;
        return s.substring(0, n) + "…";
    }

    private static String fmt(long v) {
        return String.format(Locale.ROOT, "%,d", v).replace(',', '.');
    }

    static int actOf(int level) {
        if (level <= 0) return 0;
        if (level < 40) return 1;
        if (level < 75) return 2;
        if (level < 103) return 3;
        if (level < 109) return 4;
        return 5;
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() != MotionEvent.ACTION_UP) return true;
        float k = Math.min(getWidth() / W, getHeight() / H);
        float x = e.getX() / k, y = e.getY() / k;
        for (int i = hits.size() - 1; i >= 0; i--) {
            if (hits.get(i).area.contains(x, y)) {
                hits.get(i).action.run();
                invalidate();
                return true;
            }
        }
        return true;
    }
}
