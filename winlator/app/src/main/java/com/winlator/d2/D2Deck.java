package com.winlator.d2;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Thor D2: the deck on the lower screen (1240x1080 on the AYN Thor). Plain dark panels drawn on a canvas, live from
 * the mod: values, skills, effects, area and quests, inventory, belt, stash, cube and mercenary, and a sort button.
 * Skill icons come at runtime from the player's own game files (D2Hud) when they are loaded; nothing from the game is
 * part of the app.
 */
@SuppressLint("ViewConstructor")
public final class D2Deck extends View {
    public interface Host {
        D2ModLink link();
        D2Hud hud();
    }

    private static final float W = 1240, H = 1080;
    private static final int BG = 0xFF0B0B0D, PANEL = 0xFF17161A, PANEL_EDGE = 0xFF2A2830, TEXT = 0xFFE6E1D6,
        DIM = 0xFF8C877D, ACCENT = 0xFFC8A86A, LIFE = 0xFFB0302A, MANA = 0xFF2F55C0, XP = 0xFFB89A3A, MERC = 0xFF4E9A45;
    private static final int TAB_INV = 0, TAB_STASH = 1, TAB_CUBE = 2, TAB_MERC = 3, TAB_QUESTS = 4;

    private final Host host;
    private final boolean german;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG),
        text = new Paint(Paint.ANTI_ALIAS_FLAG), icon = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF r = new RectF();
    private final D2Effects effects = new D2Effects();
    private D2Hud effectsHud;
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
        text.setTypeface(Typeface.create("sans-serif-condensed", Typeface.NORMAL));
        setBackgroundColor(BG);
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

    // ---- drawing ------------------------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        hits.clear();
        float k = Math.min(getWidth() / W, getHeight() / H);
        c.save();
        c.scale(k, k);
        D2ModLink link = host.link();
        D2ModState s = link != null ? link.state() : null;
        D2DeckData.Stats st = link != null ? link.deck.stats(3000) : null;
        if (s == null || st == null) {
            drawWaiting(c, link);
            c.restore();
            return;
        }
        D2Hud hud = host.hud();
        drawHeader(c, link, s, st);
        drawBars(c, link, s, st);
        drawValues(c, st);
        drawSkills(c, s, hud);
        drawEffects(c, link, hud);
        drawTabs(c);
        RectF content = new RectF(616, 182, 1224, 900);
        switch (tab) {
            case TAB_INV: drawInventory(c, link, content); break;
            case TAB_STASH: drawGridPage(c, link.deck.items(D2DeckData.OWNER_HERO), D2DeckData.PAGE_STASH, 6, 8, content,
                t("Stash is empty or not loaded yet", "Truhe leer oder noch nicht geladen")); break;
            case TAB_CUBE: drawGridPage(c, link.deck.items(D2DeckData.OWNER_HERO), D2DeckData.PAGE_CUBE, 3, 4, content,
                t("Cube is empty", "Würfel ist leer")); break;
            case TAB_MERC: drawMerc(c, link, st, content); break;
            default: drawQuests(c, link, s, content);
        }
        drawActions(c, link, s, st);
        c.restore();
    }

    private void drawWaiting(Canvas c, D2ModLink link) {
        panel(c, 16, 16, 1224, 1064);
        text.setColor(ACCENT);
        text.setTextSize(54);
        text.setTextAlign(Paint.Align.CENTER);
        c.drawText("Thor D2", W / 2, 470, text);
        text.setColor(DIM);
        text.setTextSize(30);
        c.drawText(link == null || !link.isConnected() ? t("Waiting for the game …", "Warte auf das Spiel …")
            : t("Waiting for a character in the game …", "Warte auf einen Charakter im Spiel …"), W / 2, 530, text);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawHeader(Canvas c, D2ModLink link, D2ModState s, D2DeckData.Stats st) {
        panel(c, 16, 16, 1224, 96);
        String name = link.characterName();
        String[] classes = german
            ? new String[]{"Amazone", "Zauberin", "Totenbeschwörer", "Paladin", "Barbar", "Druide", "Assassine"}
            : new String[]{"Amazon", "Sorceress", "Necromancer", "Paladin", "Barbarian", "Druid", "Assassin"};
        String cls = s.characterClass >= 0 && s.characterClass < classes.length ? classes[s.characterClass] : "";
        String[] diffs = german ? new String[]{"Normal", "Albtraum", "Hölle"} : new String[]{"Normal", "Nightmare", "Hell"};
        label(c, (name != null ? name : "?"), 36, 62, 38, TEXT, true);
        label(c, cls + " · " + t("Level ", "Stufe ") + st.get(12)
            + (st.difficulty < 3 ? " · " + diffs[st.difficulty] : ""), 36, 88, 22, DIM, false);
        D2LevelNames names = D2LevelNames.current();
        String area = names != null ? names.name(s.levelNo) : null;
        int act = actOf(s.levelNo);
        text.setTextAlign(Paint.Align.RIGHT);
        label(c, area != null ? area : t("Area ", "Gebiet ") + s.levelNo, 1204, 62, 32, ACCENT, true);
        label(c, (act > 0 ? t("Act ", "Akt ") + act : "") + (s.inTown ? t(" · town", " · Stadt") : "")
            + " · " + t("Gold ", "Gold ") + fmt(st.get(14)) + t(" · stash ", " · Truhe ") + fmt(st.get(15)), 1204, 88, 22, DIM, false);
        text.setTextAlign(Paint.Align.LEFT);
    }

    private void drawBars(Canvas c, D2ModLink link, D2ModState s, D2DeckData.Stats st) {
        panel(c, 16, 108, 600, 400);
        bar(c, 36, 128, t("Life", "Leben"), st.get(6) >> 8, st.get(7) >> 8, LIFE);
        bar(c, 36, 196, "Mana", st.get(8) >> 8, st.get(9) >> 8, MANA);
        long[] xp = link.experience();
        if (xp != null && xp[2] > xp[1]) {
            long have = xp[0] - xp[1], need = xp[2] - xp[1];
            bar(c, 36, 264, t("Experience", "Erfahrung"), have, need, XP);
        }
        else bar(c, 36, 264, t("Experience", "Erfahrung"), st.get(13), 0, XP);
        if (st.merc) bar(c, 36, 332, t("Mercenary", "Söldner") + " (" + t("level ", "Stufe ") + st.mercLevel + ")",
            st.mercLife >> 8, st.mercMaxLife >> 8, MERC);
        else label(c, t("No mercenary nearby", "Kein Söldner in der Nähe"), 36, 372, 22, DIM, false);
    }

    private void bar(Canvas c, float x, float y, String what, long value, long max, int color) {
        label(c, what, x, y + 22, 22, DIM, false);
        text.setTextAlign(Paint.Align.RIGHT);
        label(c, max > 0 ? fmt(value) + " / " + fmt(max) : fmt(value), 580, y + 22, 22, TEXT, false);
        text.setTextAlign(Paint.Align.LEFT);
        r.set(x, y + 32, 580, y + 56);
        fill.setColor(0xFF0E0D10);
        c.drawRoundRect(r, 6, 6, fill);
        if (max > 0) {
            r.right = x + (580 - x) * Math.max(0, Math.min(1, value / (float) max));
            fill.setColor(color);
            c.drawRoundRect(r, 6, 6, fill);
        }
    }

    private void drawValues(Canvas c, D2DeckData.Stats st) {
        panel(c, 16, 412, 600, 610);
        int[][] res = {{39, 40, 0xFFD8603A}, {43, 44, 0xFF6FA8DC}, {41, 42, 0xFFE8D45A}, {45, 46, 0xFF6DBF5A}};
        String[] resNames = german ? new String[]{"Feuer", "Kälte", "Blitz", "Gift"} : new String[]{"Fire", "Cold", "Light.", "Poison"};
        for (int i = 0; i < 4; i++) {
            float x = 36 + i * 140;
            r.set(x, 428, x + 128, 506);
            fill.setColor(0xFF0E0D10);
            c.drawRoundRect(r, 8, 8, fill);
            stroke.setColor(res[i][2]);
            stroke.setStrokeWidth(2);
            c.drawRoundRect(r, 8, 8, stroke);
            int v = st.resistance(res[i][0], res[i][1]);
            text.setTextAlign(Paint.Align.CENTER);
            label(c, resNames[i], x + 64, 454, 20, res[i][2], false);
            label(c, v + "%", x + 64, 494, 34, v < 0 ? 0xFFE05050 : TEXT, true);
            text.setTextAlign(Paint.Align.LEFT);
        }
        String[] attr = german ? new String[]{"Stärke", "Geschick", "Vitalität", "Energie"} : new String[]{"Strength", "Dexterity", "Vitality", "Energy"};
        long[] av = {st.get(0), st.get(2), st.get(3), st.get(1)};
        for (int i = 0; i < 4; i++) {
            float x = 36 + i * 140;
            label(c, attr[i], x, 540, 20, DIM, false);
            label(c, String.valueOf(av[i]), x, 570, 28, TEXT, true);
        }
        String extra = t("Defense ", "Verteidigung ") + st.get(31) + " · MF " + st.get(80) + "%";
        if (st.get(4) > 0) extra += t(" · stat points ", " · Attributpunkte ") + st.get(4);
        if (st.get(5) > 0) extra += t(" · skill points ", " · Fertigkeitspunkte ") + st.get(5);
        label(c, extra, 36, 600, 20, st.get(4) > 0 || st.get(5) > 0 ? ACCENT : DIM, false);
    }

    private void drawSkills(Canvas c, D2ModState s, D2Hud hud) {
        panel(c, 16, 622, 600, 820);
        skill(c, hud, s.leftSkill, t("Left", "Links"), 36, 640);
        skill(c, hud, s.rightSkill, t("Right", "Rechts"), 316, 640);
        for (int i = 0; i < 8; i++) {
            int id = s.hotkeySkill[i];
            float x = 36 + (i % 4) * 140, y = 742 + (i / 4) * 34;
            label(c, "F" + (i + 1) + " " + (id >= 0 ? ellipsize(skillName(hud, id), 110, 18) : "–"), x, y, 18,
                id >= 0 ? TEXT : DIM, false);
        }
    }

    private void skill(Canvas c, D2Hud hud, int id, String side, float x, float y) {
        Bitmap b = hud != null && id >= 0 ? hud.skillIcon(id) : null;
        r.set(x, y, x + 64, y + 64);
        fill.setColor(0xFF0E0D10);
        c.drawRoundRect(r, 6, 6, fill);
        if (b != null) c.drawBitmap(b, null, r, icon);
        label(c, side, x + 76, y + 24, 18, DIM, false);
        label(c, id >= 0 ? ellipsize(skillName(hud, id), 190, 24) : "–", x + 76, y + 54, 24, TEXT, true);
    }

    private String skillName(D2Hud hud, int id) {
        String n = hud != null ? hud.skillName(id) : null;
        return n != null ? n : "#" + id;
    }

    private void drawEffects(Canvas c, D2ModLink link, D2Hud hud) {
        panel(c, 16, 832, 600, 1064);
        label(c, t("Effects and auras", "Effekte und Auren"), 36, 862, 22, ACCENT, true);
        if (hud != effectsHud) {
            effects.setTexts(getContext(), hud);
            effectsHud = hud;
        }
        D2Effects.Effect[] list = link.effects();
        if (list.length == 0) {
            label(c, t("none", "keine"), 36, 898, 20, DIM, false);
            return;
        }
        float y = 898;
        for (D2Effects.Effect e : list) {
            if (y > 1050) break;
            String d;
            try {
                d = effects.describe(e);
            }
            catch (RuntimeException ex) {
                d = "#" + e.state;
            }
            label(c, ellipsize(d, 548, 20), 36, y, 20, TEXT, false);
            y += 30;
        }
    }

    private void drawTabs(Canvas c) {
        String[] names = german ? new String[]{"Inventar", "Truhe", "Würfel", "Söldner", "Quests"}
            : new String[]{"Inventory", "Stash", "Cube", "Mercenary", "Quests"};
        float w = (1224 - 616) / (float) names.length;
        for (int i = 0; i < names.length; i++) {
            final int which = i;
            RectF a = new RectF(616 + i * w + (i > 0 ? 4 : 0), 108, 616 + (i + 1) * w, 170);
            fill.setColor(i == tab ? 0xFF2C2622 : PANEL);
            c.drawRoundRect(a, 10, 10, fill);
            stroke.setColor(i == tab ? ACCENT : PANEL_EDGE);
            stroke.setStrokeWidth(2);
            c.drawRoundRect(a, 10, 10, stroke);
            text.setTextAlign(Paint.Align.CENTER);
            label(c, names[i], a.centerX(), a.centerY() + 9, 24, i == tab ? ACCENT : TEXT, i == tab);
            text.setTextAlign(Paint.Align.LEFT);
            hits.add(new Hit(a, () -> { tab = which; selectedId = -1; }));
        }
    }

    private void drawInventory(Canvas c, D2ModLink link, RectF area) {
        panel(c, area.left, area.top, area.right, area.bottom);
        List<D2DeckData.Item> items = link.deck.items(D2DeckData.OWNER_HERO);
        float top = area.top + 16;
        float bottom = grid(c, items, D2DeckData.PAGE_INV, 10, 4, area.left + 16, top, area.width() - 32, 58);
        int rows = 1;
        for (D2DeckData.Item it : items) if (it.page == D2DeckData.PAGE_BELT) rows = Math.max(rows, it.x / 4 + 1);
        label(c, t("Belt", "Gürtel"), area.left + 16, bottom + 34, 22, ACCENT, true);
        float beltBottom = beltGrid(c, items, rows, area.left + 16, bottom + 46, 52);
        label(c, t("Equipped", "Angelegt"), area.left + 268, bottom + 34, 22, ACCENT, true);
        String[] slots = german
            ? new String[]{"", "Kopf", "Amulett", "Rüstung", "Rechte Hand", "Linke Hand", "Ring rechts", "Ring links", "Gürtel", "Stiefel", "Handschuhe", "Waffe 2 rechts", "Waffe 2 links"}
            : new String[]{"", "Head", "Amulet", "Armor", "Right hand", "Left hand", "Right ring", "Left ring", "Belt", "Boots", "Gloves", "Swap right", "Swap left"};
        float y = bottom + 66;
        for (D2DeckData.Item it : items) {
            if (it.page != D2DeckData.PAGE_BODY || y > area.bottom - 60) continue;
            String slot = it.x < slots.length ? slots[it.x] : "?";
            RectF a = new RectF(area.left + 268, y - 22, area.right - 16, y + 6);
            if (it.id == selectedId) {
                fill.setColor(0xFF2C2622);
                c.drawRect(a, fill);
            }
            label(c, ellipsize(slot + ": " + it.oneLine(), area.right - area.left - 300, 19), area.left + 268, y, 19, qualityColor(it), false);
            hits.add(new Hit(a, () -> selectedId = it.id));
            y += 28;
        }
        drawSelected(c, items, area, Math.max(beltBottom, y));
    }

    private void drawGridPage(Canvas c, List<D2DeckData.Item> items, int page, int minW, int minH, RectF area, String empty) {
        panel(c, area.left, area.top, area.right, area.bottom);
        boolean any = false;
        for (D2DeckData.Item it : items) any |= it.page == page;
        if (!any) {
            label(c, empty, area.left + 24, area.top + 50, 24, DIM, false);
            return;
        }
        float bottom = grid(c, items, page, minW, minH, area.left + 16, area.top + 16, area.width() - 32, area.height() - 140);
        drawSelected(c, items, area, bottom);
    }

    private void drawMerc(Canvas c, D2ModLink link, D2DeckData.Stats st, RectF area) {
        panel(c, area.left, area.top, area.right, area.bottom);
        if (!st.merc) {
            label(c, t("No mercenary nearby (or none hired)", "Kein Söldner in der Nähe (oder keiner angeheuert)"), area.left + 24, area.top + 50, 24, DIM, false);
            return;
        }
        label(c, t("Mercenary, level ", "Söldner, Stufe ") + st.mercLevel + " · " + t("life ", "Leben ") + (st.mercLife >> 8) + " / " + (st.mercMaxLife >> 8),
            area.left + 24, area.top + 50, 26, TEXT, true);
        float y = area.top + 100;
        List<D2DeckData.Item> items = link.deck.items(D2DeckData.OWNER_MERC);
        if (items.isEmpty()) label(c, t("No equipment seen", "Keine Ausrüstung gesehen"), area.left + 24, y, 22, DIM, false);
        for (D2DeckData.Item it : items) {
            label(c, ellipsize(it.oneLine(), area.width() - 48, 22), area.left + 24, y, 22, qualityColor(it), false);
            y += 34;
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

    private void drawQuests(Canvas c, D2ModLink link, D2ModState s, RectF area) {
        panel(c, area.left, area.top, area.right, area.bottom);
        int[] words = link.questWorte();
        int act = Math.max(1, actOf(s.levelNo));
        float y = area.top + 46;
        String hint = null;
        for (int a = 0; a < 5; a++) {
            boolean current = a + 1 == act;
            label(c, t("Act ", "Akt ") + (a + 1), area.left + 24, y, current ? 26 : 22, current ? ACCENT : DIM, true);
            y += current ? 8 : 0;
            for (int q = 0; q < QUESTS_EN[a].length; q++) {
                int w = QUEST_FIRST_WORD[a] + q;
                int v = words != null && w < words.length ? words[w] : -1;
                String state = v < 0 ? "?" : (v & 1) != 0 ? "✓" : (v & 2) != 0 ? t("reward", "Belohnung") : (v & 4) != 0 ? t("started", "begonnen") : "·";
                if (current) {
                    y += 30;
                    label(c, QUESTS_EN[a][q], area.left + 48, y, 22, (v & 1) != 0 && v >= 0 ? DIM : TEXT, false);
                    text.setTextAlign(Paint.Align.RIGHT);
                    label(c, state, area.right - 24, y, 22, (v & 1) != 0 && v >= 0 ? MERC : ACCENT, false);
                    text.setTextAlign(Paint.Align.LEFT);
                }
                if (hint == null && v >= 0 && (v & 1) == 0 && a + 1 >= act - 0) hint = QUESTS_EN[a][q] + (state.equals("·") ? "" : " (" + state + ")");
            }
            if (!current) {
                int done = 0;
                for (int q = 0; q < QUESTS_EN[a].length; q++) {
                    int w = QUEST_FIRST_WORD[a] + q;
                    if (words != null && w < words.length && (words[w] & 1) != 0) done++;
                }
                text.setTextAlign(Paint.Align.RIGHT);
                label(c, words != null ? done + " / " + QUESTS_EN[a].length : "?", area.right - 24, y, 22, DIM, false);
                text.setTextAlign(Paint.Align.LEFT);
            }
            y += 40;
        }
        label(c, words == null ? t("Quest state not known yet", "Queststand noch unbekannt")
            : hint != null ? t("Next: ", "Als Nächstes: ") + hint : t("All quests of this act are done", "Alle Quests dieses Akts erledigt"),
            area.left + 24, area.bottom - 24, 24, ACCENT, true);
    }

    private void drawActions(Canvas c, D2ModLink link, D2ModState s, D2DeckData.Stats st) {
        panel(c, 616, 912, 1224, 1064);
        boolean single = st.singlePlayer && link.singlePlayer();
        D2Points pts = link.points(3000);
        boolean sorting = pts != null && pts.sortState == D2Points.SORT_RUNNING && SystemClock.uptimeMillis() - sortSentAt < 25000;
        button(c, new RectF(632, 928, 900, 1000), sorting ? t("Sorting …", "Sortiere …") : t("Sort inventory", "Inventar sortieren"),
            single && !sorting, () -> {
                sortSentAt = SystemClock.uptimeMillis();
                link.sortInventory(false);
                note(t("Sorting: the game moves the items one by one", "Sortieren: Das Spiel legt die Gegenstände einzeln um"));
            });
        button(c, new RectF(912, 928, 1060, 1000), t("Portal", "Portal"), single, link::portal);
        button(c, new RectF(1072, 928, 1208, 1000), t("Potion", "Trank"), single, () -> link.drink(D2ModLink.DRINK_HEAL, false));
        String status;
        if (!single) status = t("Multiplayer / Battle.net: deck actions are off", "Mehrspieler/Battle.net: Deck-Aktionen gesperrt");
        else if (note != null && SystemClock.uptimeMillis() < noteUntil) status = note;
        else if (pts != null && pts.sortCounter > 0) status = sortText(pts.sortState, pts.sortMoves);
        else status = t("Tap an item for its full name", "Gegenstand antippen zeigt den vollen Namen");
        label(c, ellipsize(status, 570, 20), 632, 1040, 20, DIM, false);
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

    private void button(Canvas c, RectF a, String label, boolean enabled, Runnable action) {
        fill.setColor(enabled ? 0xFF2C2622 : 0xFF141316);
        c.drawRoundRect(a, 12, 12, fill);
        stroke.setColor(enabled ? ACCENT : PANEL_EDGE);
        stroke.setStrokeWidth(2);
        c.drawRoundRect(a, 12, 12, stroke);
        text.setTextAlign(Paint.Align.CENTER);
        label(c, label, a.centerX(), a.centerY() + 9, 25, enabled ? TEXT : DIM, true);
        text.setTextAlign(Paint.Align.LEFT);
        if (enabled) hits.add(new Hit(a, action));
    }

    /** Draws the items of one page as a grid; returns the bottom edge. */
    private float grid(Canvas c, List<D2DeckData.Item> items, int page, int minW, int minH, float x0, float y0, float maxW, float maxCell) {
        int cols = minW, rows = minH;
        for (D2DeckData.Item it : items) {
            if (it.page != page) continue;
            cols = Math.max(cols, it.x + it.w);
            rows = Math.max(rows, it.y + it.h);
        }
        float cell = Math.min(maxCell, Math.min(maxW / cols, 640f / rows));
        fill.setColor(0xFF0E0D10);
        stroke.setColor(0xFF201F24);
        stroke.setStrokeWidth(1);
        for (int y = 0; y < rows; y++) for (int x = 0; x < cols; x++) {
            r.set(x0 + x * cell + 1, y0 + y * cell + 1, x0 + (x + 1) * cell - 1, y0 + (y + 1) * cell - 1);
            c.drawRect(r, fill);
            c.drawRect(r, stroke);
        }
        for (D2DeckData.Item it : items) {
            if (it.page != page) continue;
            RectF a = new RectF(x0 + it.x * cell + 2, y0 + it.y * cell + 2, x0 + (it.x + it.w) * cell - 2, y0 + (it.y + it.h) * cell - 2);
            tile(c, it, a, cell);
        }
        return y0 + rows * cell;
    }

    private float beltGrid(Canvas c, List<D2DeckData.Item> items, int rows, float x0, float y0, float cell) {
        fill.setColor(0xFF0E0D10);
        for (int y = 0; y < rows; y++) for (int x = 0; x < 4; x++) {
            r.set(x0 + x * cell + 1, y0 + y * cell + 1, x0 + (x + 1) * cell - 1, y0 + (y + 1) * cell - 1);
            c.drawRect(r, fill);
        }
        for (D2DeckData.Item it : items) {
            if (it.page != D2DeckData.PAGE_BELT) continue;
            int col = it.x % 4, row = it.x / 4;
            // Row 0 is the bottom row of the belt, as in the game.
            float top = y0 + (rows - 1 - row) * cell;
            tile(c, it, new RectF(x0 + col * cell + 2, top + 2, x0 + (col + 1) * cell - 2, top + cell - 2), cell);
        }
        return y0 + rows * cell;
    }

    private void tile(Canvas c, D2DeckData.Item it, RectF a, float cell) {
        int color = qualityColor(it);
        fill.setColor((color & 0x00FFFFFF) | (it.id == selectedId ? 0x70000000 : 0x30000000));
        c.drawRoundRect(a, 5, 5, fill);
        stroke.setColor(color);
        stroke.setStrokeWidth(it.id == selectedId ? 3 : 1.5f);
        c.drawRoundRect(a, 5, 5, stroke);
        float size = Math.max(12, Math.min(17, cell * 0.3f));
        String name = it.oneLine();
        if (it.w == 1 && it.h == 1) name = shortName(it);
        wrap(c, name, a, size, color);
        if (it.quantity > 1 && it.w * it.h <= 2) {
            text.setTextAlign(Paint.Align.RIGHT);
            label(c, String.valueOf(it.quantity), a.right - 4, a.bottom - 4, 14, TEXT, true);
            text.setTextAlign(Paint.Align.LEFT);
        }
        hits.add(new Hit(a, () -> selectedId = it.id));
    }

    private void drawSelected(Canvas c, List<D2DeckData.Item> items, RectF area, float below) {
        for (D2DeckData.Item it : items) {
            if (it.id != selectedId) continue;
            String line = it.oneLine() + (it.ethereal() ? t(" · ethereal", " · ätherisch") : "")
                + (!it.identified() && it.quality >= D2ModItems.QUALITY_MAGIC ? t(" · unidentified", " · nicht identifiziert") : "");
            label(c, ellipsize(line, area.width() - 32, 22), area.left + 16, Math.min(area.bottom - 16, below + 34), 22, qualityColor(it), true);
            return;
        }
    }

    /** One- and two-word short names for 1x1 cells: potions, gems, runes, scrolls, charms. */
    private static String shortName(D2DeckData.Item it) {
        String n = it.oneLine();
        String[] words = n.split(" ");
        if (words.length <= 2) return n;
        return words[words.length - 2] + " " + words[words.length - 1];
    }

    private static int qualityColor(D2DeckData.Item it) {
        if (it.runeword()) return 0xFFC7B377;
        switch (it.quality) {
            case D2ModItems.QUALITY_MAGIC: return 0xFF7878FF;
            case D2ModItems.QUALITY_SET: return 0xFF2BD02B;
            case D2ModItems.QUALITY_RARE: return 0xFFFFFF64;
            case D2ModItems.QUALITY_UNIQUE: return 0xFFC7B377;
            case D2ModItems.QUALITY_CRAFTED: return 0xFFFFA800;
            case D2ModItems.QUALITY_LOW: return 0xFF9A9A9A;
            default: return it.ethereal() || (it.flags & 2) != 0 ? 0xFFB0B0B0 : 0xFFE0E0E0;
        }
    }

    private void wrap(Canvas c, String s, RectF a, float size, int color) {
        text.setTextSize(size);
        text.setColor(color);
        text.setFakeBoldText(false);
        float maxW = a.width() - 6;
        int maxLines = Math.max(1, (int) ((a.height() - 4) / (size * 1.1f)));
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split(" ")) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (text.measureText(next) <= maxW || line.length() == 0) line = new StringBuilder(next);
            else {
                lines.add(line.toString());
                line = new StringBuilder(word);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        int n = Math.min(maxLines, lines.size());
        float y = a.centerY() - (n - 1) * size * 0.55f + size * 0.35f;
        text.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < n; i++) {
            String l = lines.get(i);
            if (i == n - 1 && n < lines.size()) l = l + "…";
            c.drawText(ellipsizeRaw(l, maxW), a.centerX(), y + i * size * 1.1f, text);
        }
        text.setTextAlign(Paint.Align.LEFT);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private void panel(Canvas c, float l, float t, float rr, float b) {
        r.set(l, t, rr, b);
        fill.setColor(PANEL);
        c.drawRoundRect(r, 14, 14, fill);
        stroke.setColor(PANEL_EDGE);
        stroke.setStrokeWidth(2);
        c.drawRoundRect(r, 14, 14, stroke);
    }

    private void label(Canvas c, String s, float x, float y, float size, int color, boolean bold) {
        text.setTextSize(size);
        text.setColor(color);
        text.setFakeBoldText(bold);
        c.drawText(s, x, y, text);
        text.setFakeBoldText(false);
    }

    private String ellipsize(String s, float maxW, float size) {
        text.setTextSize(size);
        return ellipsizeRaw(s, maxW);
    }

    private String ellipsizeRaw(String s, float maxW) {
        if (text.measureText(s) <= maxW) return s;
        int n = s.length();
        while (n > 1 && text.measureText(s, 0, n) + text.measureText("…") > maxW) n--;
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
