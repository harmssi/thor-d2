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
                | ((flags & IFLAG_IDENTIFIED) ? 4 : 0) | ((flags & IFLAG_RUNEWORD) ? 8 : 0));
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
} di;

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
static int diCellsFree(void* player, int kind, DWORD self, int x, int y, int iw, int ih) {
    int w, h;
    if (!diGrid(player, kind, &w, &h) || x < 0 || y < 0 || x + iw > w || y + ih > h) return 0;
    void* inventory = RDP(player, U_INVENTORY);
    int guard = 0;
    for (void* item = readable(inventory, 0x10) ? RDP(inventory, INV_FIRST) : NULL; item && guard < 512; guard++) {
        void* data = RDP(item, U_DATA);
        if (!readable(data, 0x70)) return 0;
        if (RD8(data, IDATA_LOCATION) == kind && RD8(data, IDATA_NODEPAGE) == 1 && RD32(item, U_ID) != self) {
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
            else if (!diCellsFree(player, to, id, tx, ty, w, h)) why = "no room there";
            else if (to == DI_TO_CUBE && strncmp(itemCode(item), "box", 3) == 0) why = "the cube into itself";
        }
        else if (to == 11) {
            if (tx < 1 || tx > 10) why = "no such body slot";
            else if (equipFind(player, 0, 1, (BYTE) tx)) why = "the slot is taken (take that item off first)";
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
            diPlace(di.toKind, di.id, di.toX, di.toY);
            di.step = DIS_PLACED;
            di.since = now;
        }
        else if (waited > DI_STEP_MS) {
            logLine("deck item %lu: not taken to the cursor", (unsigned long) di.id);
            di.step = DIS_NONE;
        }
        break;
    case DIS_PLACED:
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
