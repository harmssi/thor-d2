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
        if (!deckPage(data, &page) || !readable(path, 0x14)) { item = next; continue; }
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
