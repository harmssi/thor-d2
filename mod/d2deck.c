// Thor D2: data for the deck on the second screen (character values, all carried items with names and places).
// Read only: nothing here changes the game. Included by d2mod.c after d2quest.c.

#define PKT_DECK_STATS 40
#define PKT_DECK_ITEMS 41
#define DECK_PERIOD_MS 500
#define DECK_REPEAT_MS 3000
#define DECK_NAME_MAX  48
#define DECK_ITEM_MAX  3900

enum { DECK_INV, DECK_BELT, DECK_BODY, DECK_STASH, DECK_CUBE };

static const WORD deckStatIds[] = {
    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 12, 13, 14, 15, 31, 39, 40, 41, 42, 43, 44, 45, 46, 80, 105, 93, 99, 127
};

static int deckPage(void* data, int* page) {
    BYTE node = RD8(data, IDATA_NODEPAGE), loc = RD8(data, IDATA_LOCATION);
    if (node == 2) { *page = DECK_BELT; return 1; }
    if (node == 3) { *page = DECK_BODY; return 1; }
    if (node != 1) return 0;
    if (loc == 0) *page = DECK_INV;
    else if (loc == 3) *page = DECK_CUBE;
    else if (loc == 4) *page = DECK_STASH;
    else return 0;
    return 1;
}

static int deckGear(void* item) {
    int tier, cat = itemCategory(item, itemCode(item), &tier);
    return cat == CAT_WEAPON || cat == CAT_ARMOR;
}

// ---- waypoints for the deck: which are active, and the waypoint the player stands at (1.13d client values) -------
// Checked at runtime instead of by code sites: the menu is open, a waypoint object is near, and the waypoint of the
// level the player stands in is active in the table. Anything else: the deck gets no waypoints.
#define RVA_WaypointTable   0x1088FD
#define DECK_WP_COUNT       39
static const BYTE DECK_WP_LEVELS[DECK_WP_COUNT] = {1, 3, 4, 5, 6, 27, 29, 32, 35, 40, 48, 42, 57, 43, 44, 52, 74, 46,
    75, 76, 77, 78, 79, 80, 81, 83, 101, 103, 106, 107, 109, 111, 112, 113, 115, 123, 117, 118, 129};

// The waypoint object next to the player (objects.txt rows of the waypoints of all acts).
static void* deckNearWaypoint(void* player) {
    static const WORD rows[] = {119, 145, 156, 157, 237, 238, 288, 323, 324, 398, 402, 429, 494, 496, 511, 539};
    void* act = RDP(player, U_ACT);
    void* best = NULL;
    double bestD = 30;
    int units = 0;
    for (void* room = act ? RDP(act, ACT_ROOM1) : NULL; room && units < 4096; room = RDP(room, ROOM1_NEXT)) {
        for (void* unit = RDP(room, ROOM1_UNITS); unit && units < 4096; unit = RDP(unit, U_LISTNEXT), units++) {
            if (RD32(unit, U_TYPE) != UNIT_OBJECT) continue;
            DWORD row = RD32(unit, U_TXTNO);
            int wp = 0;
            for (unsigned i = 0; i < sizeof(rows) / sizeof(rows[0]); i++) wp |= rows[i] == row;
            double d = wp ? unitDistance(player, unit) : 1e9;
            if (d < bestD) {
                bestD = d;
                best = unit;
            }
        }
    }
    return best;
}

static int deckWaypoints(void* player, BYTE bits[5], DWORD* wpId) {
    static int logged;
    memset(bits, 0, 5);
    if (!player || !getUiVar(0x14)) { logged = 0; return 0; }
    const BYTE* table = readable((void*) (client + RVA_WaypointTable), 4) ? *(const BYTE* const*) (client + RVA_WaypointTable) : NULL;
    void* unit = deckNearWaypoint(player);
    DWORD id = unit ? RD32(unit, U_ID) : 0;
    int ok = readable(table, 8) && table[0] == 2 && table[1] == 1 && unit, here = -1;
    DWORD level = unitLevel(player);
    for (int i = 0; i < DECK_WP_COUNT; i++) if (DECK_WP_LEVELS[i] == level) here = i;
    if (ok) memcpy(bits, table + 2, 5);
    ok = ok && here >= 0 && (bits[here >> 3] >> (here & 7) & 1);
    if (!logged) {
        logged = 1;
        logLine("deck waypoints: table %p (%02X %02X %02X %02X %02X %02X %02X %02X), object %lu %s, level %lu index %d: %s",
            table, readable(table, 8) ? table[0] : 0, readable(table, 8) ? table[1] : 0, bits[0], bits[1], bits[2], bits[3],
            bits[4], readable(table, 8) ? table[7] : 0, (unsigned long) id, unit ? "found" : "not found",
            (unsigned long) level, here, ok ? "ok" : "not as expected");
    }
    if (!ok) memset(bits, 0, 5);
    *wpId = id;
    return ok;
}

// One pass over a unit's items: hash only (out == NULL) or write them, starting new packets as they fill.
static DWORD deckItems(void* unit, int owner, int write, BYTE* chunk) {
    static Packet p;
    DWORD hash = 2166136261u ^ (DWORD) owner;
    int count = 0, countAt = 0;
    if (write) {
        putHeader(&p, PKT_DECK_ITEMS);
        putU8(&p, (BYTE) owner);
        putU8(&p, (*chunk)++);
        countAt = p.len;
        putU8(&p, 0);
    }
    void* inventory = unit ? RDP(unit, U_INVENTORY) : NULL;
    int guard = 0;
    for (void* item = readable(inventory, 0x10) ? RDP(inventory, INV_FIRST) : NULL; item && guard < 512; guard++) {
        void* data = RDP(item, U_DATA);
        if (!readable(data, 0x70)) break;
        void* next = RDP(data, IDATA_NEXTINV);
        void* path = RDP(item, U_PATH);
        int page, w = 1, h = 1;
        // equipped items have no position (path) of their own
        if (!deckPage(data, &page) || (page != DECK_BODY && !readable(path, 0x14))) { item = next; continue; }
        equipSize(item, &w, &h);
        DWORD x = page == DECK_BODY ? RD8(data, IDATA_BODYLOC) : RD32(path, IPATH_X);
        DWORD y = page == DECK_BODY ? 0 : RD32(path, IPATH_Y);
        DWORD flags = RD32(data, IDATA_FLAGS);
        DWORD id = RD32(item, U_ID);
        hash = (hash ^ id) * 16777619u;
        hash = (hash ^ (page | x << 4 | y << 12)) * 16777619u;
        hash = (hash ^ flags) * 16777619u;
        if (write) {
            char name[120];
            int len = itemNameUtf8(item, name, sizeof(name));
            if (len < 0) len = 0;
            if (len > DECK_NAME_MAX) len = DECK_NAME_MAX;
            if (p.len + 20 + len > DECK_ITEM_MAX || count == 255) {
                p.data[countAt] = (BYTE) count;
                sendPacket(&p);
                putHeader(&p, PKT_DECK_ITEMS);
                putU8(&p, (BYTE) owner);
                putU8(&p, (*chunk)++);
                countAt = p.len;
                putU8(&p, 0);
                count = 0;
            }
            const char* code = itemCode(item);
            putU32(&p, id);
            for (int k = 0; k < 4; k++) putU8(&p, code[k] && code[k] != ' ' ? (BYTE) code[k] : ' ');
            putU8(&p, (BYTE) page);
            putU8(&p, (BYTE) x);
            putU8(&p, (BYTE) y);
            putU8(&p, (BYTE) ((w & 15) << 4 | (h & 15)));
            putU8(&p, (BYTE) RD32(data, IDATA_QUALITY));
            putU8(&p, ((flags & IFLAG_ETHEREAL) ? 1 : 0) | ((flags & IFLAG_SOCKETED) ? 2 : 0)
                | ((flags & IFLAG_IDENTIFIED) ? 4 : 0) | ((flags & IFLAG_RUNEWORD) ? 8 : 0) | (deckGear(item) ? 16 : 0));
            DWORD quantity = fGetUnitStat(item, STAT_QUANTITY, 0);
            putU16(&p, quantity > 0xFFFF ? 0xFFFF : quantity);
            putU8(&p, (BYTE) len);
            for (int k = 0; k < len; k++) putU8(&p, (BYTE) name[k]);
            count++;
        }
        item = next;
    }
    if (write) {
        p.data[countAt] = (BYTE) count;
        sendPacket(&p);
    }
    return hash;
}

static void sendDeck(void* player) {
    static DWORD lastAt, lastSent, lastHash;
    DWORD now = GetTickCount();
    if (!player || now - lastAt < DECK_PERIOD_MS) return;
    lastAt = now;

    static Packet p;
    putHeader(&p, PKT_DECK_STATS);
    putU8(&p, questSite > 0 ? RD8(client, RVA_QuestDifficulty) : 0xFF);
    putU8(&p, (BYTE) (sizeof(deckStatIds) / sizeof(deckStatIds[0])));
    for (unsigned i = 0; i < sizeof(deckStatIds) / sizeof(deckStatIds[0]); i++) {
        putU16(&p, deckStatIds[i]);
        putU32(&p, fGetUnitStat(player, deckStatIds[i], 0));
    }
    void* merc = findMercenary(player);
    putU8(&p, merc ? 1 : 0);
    putU32(&p, merc ? fGetUnitStat(merc, 6, 0) : 0);
    putU32(&p, merc ? fGetUnitStat(merc, 7, 0) : 0);
    putU8(&p, merc ? (BYTE) fGetUnitStat(merc, 12, 0) : 0);
    putU8(&p, (BYTE) singlePlayerGame());
    BYTE wp[5];
    DWORD wpId;
    putU8(&p, (BYTE) deckWaypoints(player, wp, &wpId));
    for (int i = 0; i < 5; i++) putU8(&p, wp[i]);
    sendPacket(&p);

    DWORD hash = deckItems(player, 0, 0, NULL) ^ (merc ? deckItems(merc, 1, 0, NULL) * 31 : 0);
    if (hash == lastHash && now - lastSent < DECK_REPEAT_MS) return;
    lastHash = hash;
    lastSent = now;
    BYTE chunk = 0;
    deckItems(player, 0, 1, &chunk);
    chunk = 0;
    deckItems(merc, 1, 1, &chunk);
}

// ---- Thor D2: moving and using items from the deck ---------------------------------------------------------------
// The deck says which item goes where; the mod does what the game's own clicks send: take the item to the cursor
// (0x19 from a grid, 0x24 from the belt, 0x1C from the body), then put it down (0x18 into a grid, 0x23 into the belt,
// 0x17 on the ground). Using an item is 0x20 (inventory) or 0x26 (belt). Single player only; the target is checked
// before anything is sent, and an item left on the cursor goes back where it came from.

#define CMD_DECK_ITEM   38
#define DI_MOVE         1
#define DI_USE          2
#define DI_IDENTIFY     3
#define DI_BELT_AUTO    4
#define DI_SELL         5
#define DI_GOLD         6
#define DI_WAYPOINT     7
#define DI_TO_INV       0
#define DI_TO_CUBE      3
#define DI_TO_STASH     4
#define DI_TO_BELT      10
#define DI_TO_GROUND    12
#define DI_STEP_MS      1000

enum { DIS_NONE, DIS_TO_CURSOR, DIS_PLACED, DIS_BACK };
static struct {
    int step;
    DWORD id, since;
    int fromKind, fromX, fromY, toKind, toX, toY;  // kinds: 0/3/4 grids, 10 belt, 11 body, 12 ground
    DWORD swapId;                                  // the item in the way: it is swapped (0x1F, body 0x1D) and goes home
    int homeKind, homeX, homeY;
} di;
static struct { DWORD id; int kind, x, y; } diSwap;

static void* diFind(void* player, DWORD id, int* kind, int* x, int* y) {
    void* inventory = RDP(player, U_INVENTORY);
    int guard = 0;
    for (void* item = readable(inventory, 0x10) ? RDP(inventory, INV_FIRST) : NULL; item && guard < 512; guard++) {
        void* data = RDP(item, U_DATA);
        if (!readable(data, 0x70)) break;
        if (RD32(item, U_ID) == id) {
            void* path = RDP(item, U_PATH);
            BYTE node = RD8(data, IDATA_NODEPAGE);
            if (node == 3) { *kind = 11; *x = RD8(data, IDATA_BODYLOC); *y = 0; return item; }
            if (!readable(path, 0x14)) return NULL;
            *x = (int) RD32(path, IPATH_X);
            *y = (int) RD32(path, IPATH_Y);
            if (node == 2) { *kind = DI_TO_BELT; return item; }
            if (node != 1) return NULL;
            *kind = RD8(data, IDATA_LOCATION);
            return *kind == 0 || *kind == 3 || *kind == 4 ? item : NULL;
        }
        item = RDP(data, IDATA_NEXTINV);
    }
    return NULL;
}

static int diGrid(void* player, int kind, int* w, int* h) {
    if (kind == DI_TO_CUBE) { *w = 3; *h = 4; return 1; }
    return moveGrid(player, kind, w, h);
}

// Whether the cells x..x+iw, y..y+ih of a grid are inside it and free (the item itself does not count).
static int diCellsFree(void* player, int kind, DWORD self, DWORD self2, int x, int y, int iw, int ih) {
    int w, h;
    if (!diGrid(player, kind, &w, &h) || x < 0 || y < 0 || x + iw > w || y + ih > h) return 0;
    void* inventory = RDP(player, U_INVENTORY);
    int guard = 0;
    for (void* item = readable(inventory, 0x10) ? RDP(inventory, INV_FIRST) : NULL; item && guard < 512; guard++) {
        void* data = RDP(item, U_DATA);
        if (!readable(data, 0x70)) return 0;
        if (RD8(data, IDATA_LOCATION) == kind && RD8(data, IDATA_NODEPAGE) == 1 && RD32(item, U_ID) != self && RD32(item, U_ID) != self2) {
            void* path = RDP(item, U_PATH);
            int ow, oh;
            if (!readable(path, 0x14) || !equipSize(item, &ow, &oh)) return 0;
            int ox = (int) RD32(path, IPATH_X), oy = (int) RD32(path, IPATH_Y);
            if (ox < x + iw && x < ox + ow && oy < y + ih && y < oy + oh) return 0;
        }
        item = RDP(data, IDATA_NEXTINV);
    }
    return 1;
}

// Where the item in the way goes: to the place the moved item leaves if it fits there, else to a free place in the
// inventory. (tx, ty, w, h) is where the moved item lands in grid `to` (to == 11: a body slot, nothing to avoid).
static const char* diSwapHome(void* player, void* other, DWORD id, int kind, int x, int y, int to, int tx, int ty, int w, int h) {
    int bw, bh, fx, fy;
    DWORD oid = RD32(other, U_ID);
    if (!equipSize(other, &bw, &bh)) return "size of the other item unknown";
    int grid = kind == 0 || kind == DI_TO_CUBE || kind == DI_TO_STASH;
    if (grid && diCellsFree(player, kind, id, oid, x, y, bw, bh)
        && !(kind == to && x < tx + w && tx < x + bw && y < ty + h && ty < y + bh)
        && !(kind == DI_TO_CUBE && strncmp(itemCode(other), "box", 3) == 0)) {
        diSwap.kind = kind;
        diSwap.x = x;
        diSwap.y = y;
    }
    else if (moveFreePlace(player, 0, bw, bh, &fx, &fy) && !(to == 0 && fx < tx + w && tx < fx + bw && fy < ty + h && ty < fy + bh)) {
        diSwap.kind = 0;
        diSwap.x = fx;
        diSwap.y = fy;
    }
    else return "no room for the item in the way";
    diSwap.id = oid;
    return NULL;
}

// A grid target that is not free: a swap when exactly one other item is in the way.
static const char* diSwapPlan(void* player, DWORD id, int kind, int x, int y, int to, int tx, int ty, int w, int h) {
    int gw, gh, n = 0, guard = 0;
    if (!diGrid(player, to, &gw, &gh) || tx < 0 || ty < 0 || tx + w > gw || ty + h > gh) return "no room there";
    void* other = NULL;
    void* inventory = RDP(player, U_INVENTORY);
    for (void* item = readable(inventory, 0x10) ? RDP(inventory, INV_FIRST) : NULL; item && guard < 512; guard++) {
        void* data = RDP(item, U_DATA);
        if (!readable(data, 0x70)) return "no room there";
        if (RD8(data, IDATA_LOCATION) == to && RD8(data, IDATA_NODEPAGE) == 1 && RD32(item, U_ID) != id) {
            void* path = RDP(item, U_PATH);
            int ow, oh;
            if (!readable(path, 0x14) || !equipSize(item, &ow, &oh)) return "no room there";
            int ox = (int) RD32(path, IPATH_X), oy = (int) RD32(path, IPATH_Y);
            if (ox < tx + w && tx < ox + ow && oy < ty + h && ty < oy + oh) {
                other = item;
                n++;
            }
        }
        item = RDP(data, IDATA_NEXTINV);
    }
    if (n != 1) return "no room there";
    return diSwapHome(player, other, id, kind, x, y, to, tx, ty, w, h);
}

static HWND goldWindow(void);  // d2gold.c

// Gold into the stash or out of it (0x4F, buttons 0x14 and 0x13), and travelling by the open waypoint (0x49).
static void deckExtraCommand(void* player, int op, int arg) {
    const char* why = !equipAvailable ? "the senders are not as expected" : !singlePlayerGame() ? "not a single player game"
        : worldPaused() ? "paused" : NULL;
    if (!why && op == DI_GOLD) {
        DWORD level = fGetUnitStat(player, 12, 0), gold = fGetUnitStat(player, 14, 0), bank = fGetUnitStat(player, 15, 0);
        DWORD bankCap = (level < 31 ? level / 10 + 1 : level / 2 + 1) * 50000, purseCap = level * 10000, amount;
        if (bankCap > 2500000) bankCap = 2500000;
        if (arg == 0) amount = bankCap > bank ? (gold < bankCap - bank ? gold : bankCap - bank) : 0;
        else amount = purseCap > gold ? (bank < purseCap - gold ? bank : purseCap - gold) : 0;
        if (!getUiVar(0x19)) why = "the stash is not open";
        else if (!amount) why = "nothing to move";
        else {
            BYTE p[7] = {0x4F, (BYTE) (arg == 0 ? 0x14 : 0x13), 0};
            *(WORD*) (p + 3) = (WORD) (amount >> 16);
            *(WORD*) (p + 5) = (WORD) amount;
            fSendPacket(7, 1, p);
            logLine("deck gold: %s %lu (purse %lu, stash %lu)", arg == 0 ? "deposit" : "withdraw", (unsigned long) amount,
                (unsigned long) gold, (unsigned long) bank);
        }
    }
    else if (!why) {
        BYTE bits[5];
        DWORD wpId = 0;
        int index = -1;
        for (int i = 0; i < DECK_WP_COUNT; i++) if (DECK_WP_LEVELS[i] == arg) index = i;
        if (!deckWaypoints(player, bits, &wpId)) why = "no waypoint open";
        else if (index < 0 || !(bits[index >> 3] >> (index & 7) & 1)) why = "that waypoint is not active";
        else if ((DWORD) arg == unitLevel(player)) why = "already there";
        else {
            BYTE p[9] = {0x49};
            *(DWORD*) (p + 1) = wpId;
            *(DWORD*) (p + 5) = (DWORD) arg;
            fSendPacket(9, 1, p);
            HWND window = goldWindow();  // the game closes its menu on its own click only: close it as Escape does
            if (window) {
                PostMessageA(window, WM_KEYDOWN, VK_ESCAPE, 0x00010001);
                PostMessageA(window, WM_KEYUP, VK_ESCAPE, 0xC0010001);
            }
            logLine("deck waypoint: to level %d by object %lu", arg, (unsigned long) wpId);
        }
    }
    if (why) logLine("deck extra %d %d: refused, %s", op, arg, why);
}

static int diBeltable(void* item) {
    int tier, cat = itemCategory(item, itemCode(item), &tier);
    return cat == CAT_HEAL || cat == CAT_MANA || cat == CAT_REJUV || cat == CAT_POTION_OTHER || cat == CAT_TP_SCROLL
        || cat == CAT_ID_SCROLL;
}

static void diSend(int n, BYTE* p) {
    fSendPacket(n, 1, p);
}

static void diPick(int kind, DWORD id, int x) {
    BYTE p[5];
    if (kind == 11) {
        BYTE q[3] = {0x1C, (BYTE) x, 0};
        diSend(3, q);
        return;
    }
    p[0] = kind == DI_TO_BELT ? 0x24 : 0x19;
    *(DWORD*) (p + 1) = id;
    diSend(5, p);
}

static void diPlace(int kind, DWORD id, int x, int y) {
    if (kind == DI_TO_GROUND) {
        BYTE p[5] = {0x17};
        *(DWORD*) (p + 1) = id;
        diSend(5, p);
    }
    else if (kind == DI_TO_BELT) {
        BYTE p[9] = {0x23};
        *(DWORD*) (p + 1) = id;
        *(DWORD*) (p + 5) = (DWORD) x;
        diSend(9, p);
    }
    else if (kind == 11) {
        BYTE p[9] = {0x1A};
        *(DWORD*) (p + 1) = id;
        *(DWORD*) (p + 5) = (DWORD) x;
        diSend(9, p);
    }
    else moveSendPlace(id, x, y, kind);
}

static int diWindowOpen(int kind) {
    return kind == DI_TO_STASH ? getUiVar(0x19) != 0 : kind == DI_TO_CUBE ? getUiVar(0x1A) != 0 : 1;
}

static void deckItemCommand(void* player, const BYTE* buf, int n) {
    if (n < 18) return;
    int op = buf[8];
    DWORD id = *(const DWORD*) (buf + 9), other = n >= 20 ? *(const DWORD*) (buf + 16) : 0;
    int to = buf[13], tx = buf[14], ty = buf[15];
    int kind, x, y, tier;
    void* item = NULL;
    const char* why = NULL;
    if (op == DI_GOLD || op == DI_WAYPOINT) {
        deckExtraCommand(player, op, to);
        return;
    }
    memset(&diSwap, 0, sizeof(diSwap));
    if (!equipAvailable) why = "the senders are not as expected";
    else if (!singlePlayerGame()) why = "not a single player game";
    else if (worldPaused()) why = "paused";
    else if (di.step != DIS_NONE || mv.step != MV_NONE || equip.step != EQ_NONE || sort.active || cubeBusy()) why = "another move runs";
    else if (equipCursorId(player)) why = "an item is on the cursor";
    else if (!(item = diFind(player, id, &kind, &x, &y))) why = "no such item";
    else if (!diWindowOpen(kind)) why = "its window is not open";
    else if (itemCategory(item, itemCode(item), &tier) == CAT_QUEST && op == DI_MOVE && to == DI_TO_GROUND) why = "a quest item";
    if (!why && op == DI_MOVE) {
        int w, h;
        if (!equipSize(item, &w, &h)) why = "size unknown";
        else if (to == DI_TO_GROUND) {}
        else if (to == DI_TO_BELT) {
            if (!diBeltable(item) || w != 1 || h != 1) why = "does not go into the belt";
            else if (tx >= 16) why = "no such belt slot";
            else {
                void* inventory = RDP(player, U_INVENTORY);
                int guard = 0;
                for (void* it = readable(inventory, 0x10) ? RDP(inventory, INV_FIRST) : NULL; it && guard < 512 && !why; guard++) {
                    void* data = RDP(it, U_DATA);
                    if (!readable(data, 0x70)) break;
                    void* path = RDP(it, U_PATH);
                    if (RD8(data, IDATA_NODEPAGE) == 2 && it != item && readable(path, 0x14) && (int) RD32(path, IPATH_X) == tx)
                        why = "the belt slot is taken";
                    it = RDP(data, IDATA_NEXTINV);
                }
            }
        }
        else if (to == 0 || to == DI_TO_CUBE || to == DI_TO_STASH) {
            if (!diWindowOpen(to)) why = "the target window is not open";
            else if (to == DI_TO_CUBE && strncmp(itemCode(item), "box", 3) == 0) why = "the cube into itself";
            else if (!diCellsFree(player, to, id, 0, tx, ty, w, h)) why = diSwapPlan(player, id, kind, x, y, to, tx, ty, w, h);
        }
        else if (to == 11) {
            if (tx < 1 || tx > 10) why = "no such body slot";
            else {
                void* worn = equipFind(player, 0, 1, (BYTE) tx);
                if (worn && worn != item) why = diSwapHome(player, worn, id, kind, x, y, 11, 0, 0, 0, 0);
            }
        }
        else why = "no such target";
    }
    else if (!why && op == DI_USE) {
        int cat = itemCategory(item, itemCode(item), &tier);
        if (cat != CAT_HEAL && cat != CAT_MANA && cat != CAT_REJUV && cat != CAT_POTION_OTHER && cat != CAT_TP_SCROLL
            && cat != CAT_TP_TOME) why = "not a usable item";
        else if (kind != 0 && kind != DI_TO_BELT) why = "only from the inventory or the belt";
    }
    else if (!why && op == DI_IDENTIFY) {
        int sk, sx, sy;
        void* scroll = diFind(player, other, &sk, &sx, &sy);
        int cat = scroll ? itemCategory(scroll, itemCode(scroll), &tier) : -1;
        if (cat != CAT_ID_SCROLL && cat != CAT_ID_TOME) why = "no identify scroll or tome";
        else if (sk != 0 && sk != DI_TO_BELT) why = "the scroll is not at hand";
        else if (RD32(RDP(item, U_DATA), IDATA_FLAGS) & IFLAG_IDENTIFIED) why = "already identified";
    }
    else if (!why && op == DI_BELT_AUTO) {
        if (kind != 0 || !diBeltable(item)) why = "does not go into the belt";
    }
    else if (!why && op == DI_SELL) {
        if (!sellAvailable) why = "selling is not available";
        else if (sell.pending) why = "a sale still waits for the server";
        else if (!shopNpc()) why = "no shop open";
        else if (kind != 0) why = "only from the inventory";
        else if (itemCategory(item, itemCode(item), &tier) == CAT_QUEST) why = "a quest item";
    }
    else if (!why) why = "no such operation";
    if (why) {
        logLine("deck item %lu op %d to %d %d,%d: refused, %s", (unsigned long) id, op, to, tx, ty, why);
        return;
    }
    if (op == DI_USE) {
        BYTE p[13] = {0};
        void* path = RDP(player, U_PATH);
        p[0] = kind == DI_TO_BELT ? 0x26 : 0x20;
        *(DWORD*) (p + 1) = id;
        if (kind != DI_TO_BELT) {
            *(WORD*) (p + 5) = path ? (WORD) (RD32(path, PATH_X) >> 16) : 0;
            *(WORD*) (p + 9) = path ? (WORD) (RD32(path, PATH_Y) >> 16) : 0;
        }
        diSend(13, p);
        playUseSound(item);
        logLine("deck item %lu: used (0x%02X)", (unsigned long) id, p[0]);
        return;
    }
    if (op == DI_SELL) {
        // As the game's own sale (0x33), as d2sell.c sends it, without needing the pointer on the item.
        void* npc = shopNpc();
        int price = fGetItemPrice(player, item, RD8((void*) client, RVA_ShopDifficulty), *(void**) (client + RVA_ShopQuestFlags),
            RD32(npc, U_TXTNO), 1);
        BYTE p[17] = {0x33};
        *(DWORD*) (p + 1) = RD32(npc, U_ID);
        *(DWORD*) (p + 5) = id;
        *(DWORD*) (p + 9) = RD32(item, U_MODE);
        *(DWORD*) (p + 13) = (DWORD) price;
        diSend(17, p);
        sell.pending = 1;
        sell.request = 0;
        sell.item = id;
        sell.npc = RD32(npc, U_ID);
        sell.price = price;
        sell.goldBefore = sellGold(player);
        sell.sentAt = GetTickCount();
        memcpy(sell.code, itemCode(item), 3);
        sell.code[3] = 0;
        logLine("deck item %lu: sold to npc %lu for %d", (unsigned long) id, (unsigned long) sell.npc, price);
        return;
    }
    if (op == DI_IDENTIFY) {
        // As the game does it: use the scroll or tome first (the server then waits for a target), then the target.
        int sk2, sx2, sy2;
        void* scroll = diFind(player, other, &sk2, &sx2, &sy2);
        BYTE u[13] = {0};
        void* path = RDP(player, U_PATH);
        u[0] = sk2 == DI_TO_BELT ? 0x26 : 0x20;
        *(DWORD*) (u + 1) = other;
        if (sk2 != DI_TO_BELT) {
            *(WORD*) (u + 5) = path ? (WORD) (RD32(path, PATH_X) >> 16) : 0;
            *(WORD*) (u + 9) = path ? (WORD) (RD32(path, PATH_Y) >> 16) : 0;
        }
        if (scroll) diSend(13, u);
        BYTE p[9] = {0x27};
        *(DWORD*) (p + 1) = id;
        *(DWORD*) (p + 5) = other;
        diSend(9, p);
        logLine("deck item %lu: identify with %lu", (unsigned long) id, (unsigned long) other);
        return;
    }
    if (op == DI_BELT_AUTO) {
        BYTE p[5] = {0x63};
        *(DWORD*) (p + 1) = id;
        diSend(5, p);
        logLine("deck item %lu: to the belt (0x63)", (unsigned long) id);
        return;
    }
    memset(&di, 0, sizeof(di));
    di.id = id;
    di.fromKind = kind;
    di.fromX = x;
    di.fromY = y;
    di.toKind = to;
    di.toX = tx;
    di.toY = ty;
    di.swapId = diSwap.id;
    di.homeKind = diSwap.kind;
    di.homeX = diSwap.x;
    di.homeY = diSwap.y;
    di.since = GetTickCount();
    di.step = DIS_TO_CURSOR;
    diPick(kind, id, x);
    logLine("deck item %lu (%.3s): from %d %d,%d to %d %d,%d", (unsigned long) id, itemCode(item), kind, x, y, to, tx, ty);
}

static void deckItemTick(void* player, DWORD now) {
    if (di.step == DIS_NONE) return;
    if (!player) {
        di.step = DIS_NONE;
        return;
    }
    if (worldPaused()) {
        di.since = now;
        return;
    }
    long waited = (long) (now - di.since);
    DWORD cursor = equipCursorId(player);
    int kind, x, y;
    switch (di.step) {
    case DIS_TO_CURSOR:
        if (cursor == di.id) {
            if (di.swapId && di.toKind == 11) {
                BYTE p[9] = {0x1D};
                *(DWORD*) (p + 1) = di.id;
                *(DWORD*) (p + 5) = (DWORD) di.toX;
                diSend(9, p);
            }
            else if (di.swapId) {
                BYTE p[17] = {0x1F};
                *(DWORD*) (p + 1) = di.id;
                *(DWORD*) (p + 5) = di.swapId;
                *(DWORD*) (p + 9) = (DWORD) di.toX;
                *(DWORD*) (p + 13) = (DWORD) di.toY;
                diSend(17, p);
            }
            else diPlace(di.toKind, di.id, di.toX, di.toY);
            di.step = DIS_PLACED;
            di.since = now;
        }
        else if (waited > DI_STEP_MS) {
            logLine("deck item %lu: not taken to the cursor", (unsigned long) di.id);
            di.step = DIS_NONE;
        }
        break;
    case DIS_PLACED:
        if (cursor != di.id && di.swapId) {
            // the item that was in the way comes to the cursor: it goes home, as a plain move of its own
            if (cursor != di.swapId && waited < 500) break;
            if (cursor == di.swapId) {
                logLine("deck item %lu: swapped with %lu, that one to %d %d,%d", (unsigned long) di.id, (unsigned long) di.swapId,
                    di.homeKind, di.homeX, di.homeY);
                diPlace(di.homeKind, di.swapId, di.homeX, di.homeY);
                di.id = di.swapId;
                di.fromKind = di.toKind = di.homeKind;
                di.fromX = di.toX = di.homeX;
                di.fromY = di.toY = di.homeY;
                di.since = now;
            }
            di.swapId = 0;
            break;
        }
        if (cursor != di.id) {
            logLine("deck item %lu: placed after %ld ms (%s)", (unsigned long) di.id, waited,
                di.toKind == DI_TO_GROUND || diFind(player, di.id, &kind, &x, &y) ? "ok" : "gone");
            di.step = DIS_NONE;
        }
        else if (waited > DI_STEP_MS) {
            diPlace(di.fromKind, di.id, di.fromX, di.fromY);
            di.step = DIS_BACK;
            di.since = now;
            logLine("deck item %lu: not placed, back", (unsigned long) di.id);
        }
        break;
    case DIS_BACK:
        if (cursor != di.id || waited > DI_STEP_MS) di.step = DIS_NONE;
        break;
    }
}
