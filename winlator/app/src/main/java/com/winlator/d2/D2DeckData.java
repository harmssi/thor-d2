package com.winlator.d2;

import android.os.SystemClock;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Thor D2: what mod/d2deck.c reports for the deck on the second screen (packet types 40 and 41). */
public final class D2DeckData {
    public static final int PKT_STATS = 40, PKT_ITEMS = 41;
    public static final int PAGE_INV = 0, PAGE_BELT = 1, PAGE_BODY = 2, PAGE_STASH = 3, PAGE_CUBE = 4;
    public static final int OWNER_HERO = 0, OWNER_MERC = 1;
    private static final int HEADER = 8;

    public static final class Item {
        public final long id;
        public final String code, name;
        public final int page, x, y, w, h, quality, flags, quantity;

        Item(long id, String code, int page, int x, int y, int w, int h, int quality, int flags, int quantity, String name) {
            this.id = id;
            this.code = code;
            this.page = page;
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.quality = quality;
            this.flags = flags;
            this.quantity = quantity;
            this.name = name;
        }

        public boolean ethereal() { return (flags & 1) != 0; }
        public boolean identified() { return (flags & 4) != 0; }
        public boolean runeword() { return (flags & 8) != 0; }
        public String oneLine() { return name.isEmpty() ? code.trim() : name.replace('\n', ' ').trim(); }
    }

    public static final class Stats {
        public final int difficulty;
        public final Map<Integer, Long> values;
        public final boolean merc, singlePlayer;
        public final long mercLife, mercMaxLife;
        public final int mercLevel;
        public final long at;

        Stats(int difficulty, Map<Integer, Long> values, boolean merc, long mercLife, long mercMaxLife, int mercLevel,
              boolean singlePlayer, long at) {
            this.difficulty = difficulty;
            this.values = values;
            this.merc = merc;
            this.mercLife = mercLife;
            this.mercMaxLife = mercMaxLife;
            this.mercLevel = mercLevel;
            this.singlePlayer = singlePlayer;
            this.at = at;
        }

        public long get(int stat) {
            Long v = values.get(stat);
            return v != null ? v : 0;
        }

        /** A resistance as the character screen shows it: minus the difficulty penalty, capped at 75 + max bonus. */
        public int resistance(int stat, int maxStat) {
            int penalty = difficulty == 1 ? 40 : difficulty == 2 ? 100 : 0;
            int value = (int) get(stat) - penalty;
            return Math.min(value, 75 + (int) get(maxStat));
        }
    }

    private volatile Stats stats;
    @SuppressWarnings("unchecked")
    private final List<Item>[] items = new List[]{Collections.emptyList(), Collections.emptyList()};
    private final List<Item>[] building = new List[]{new ArrayList<Item>(), new ArrayList<Item>()};
    private volatile long itemsAt;

    public Stats stats(long maxAgeMs) {
        Stats s = stats;
        return s != null && SystemClock.uptimeMillis() - s.at < maxAgeMs ? s : null;
    }

    public synchronized List<Item> items(int owner) {
        return items[owner];
    }

    public long itemsAt() {
        return itemsAt;
    }

    void accept(byte[] d, int length) {
        try {
            if (d[5] == PKT_STATS) parseStats(d, length);
            else parseItems(d, length);
        }
        catch (IndexOutOfBoundsException e) {
            D2Trace.log("deck: short packet " + d[5] + " (" + length + " bytes)");
        }
    }

    private void parseStats(byte[] d, int length) {
        int at = HEADER;
        int difficulty = d[at++] & 0xFF;
        int n = d[at++] & 0xFF;
        Map<Integer, Long> values = new HashMap<>();
        for (int i = 0; i < n; i++, at += 6) values.put(u16(d, at, length), u32(d, at + 2, length));
        boolean merc = d[at] != 0;
        long life = u32(d, at + 1, length), max = u32(d, at + 5, length);
        int level = d[at + 9] & 0xFF;
        boolean single = at + 10 < length && d[at + 10] != 0;
        stats = new Stats(difficulty, values, merc, life, max, level, single, SystemClock.uptimeMillis());
    }

    private synchronized void parseItems(byte[] d, int length) {
        int owner = d[HEADER] & 1, chunk = d[HEADER + 1] & 0xFF, n = d[HEADER + 2] & 0xFF;
        int at = HEADER + 3;
        if (chunk == 0) building[owner] = new ArrayList<>();
        List<Item> list = building[owner];
        for (int i = 0; i < n; i++) {
            long id = u32(d, at, length);
            String code = new String(d, at + 4, 4, StandardCharsets.US_ASCII);
            int page = d[at + 8] & 0xFF, x = d[at + 9] & 0xFF, y = d[at + 10] & 0xFF, size = d[at + 11] & 0xFF;
            int quality = d[at + 12] & 0xFF, flags = d[at + 13] & 0xFF, quantity = u16(d, at + 14, length);
            int len = d[at + 16] & 0xFF;
            if (at + 17 + len > length) throw new IndexOutOfBoundsException();
            String name = new String(d, at + 17, len, StandardCharsets.UTF_8);
            list.add(new Item(id, code, page, x, y, Math.max(1, size >> 4), Math.max(1, size & 15), quality, flags, quantity, name));
            at += 17 + len;
        }
        // Chunks of one report follow each other at once; showing the growing list is fine.
        items[owner] = Collections.unmodifiableList(new ArrayList<>(list));
        itemsAt = SystemClock.uptimeMillis();
    }

    private static int u16(byte[] d, int at, int length) {
        if (at + 2 > length) throw new IndexOutOfBoundsException();
        return (d[at] & 0xFF) | (d[at + 1] & 0xFF) << 8;
    }

    private static long u32(byte[] d, int at, int length) {
        if (at + 4 > length) throw new IndexOutOfBoundsException();
        return ((d[at] & 0xFFL) | (d[at + 1] & 0xFFL) << 8 | (d[at + 2] & 0xFFL) << 16 | (d[at + 3] & 0xFFL) << 24);
    }
}
