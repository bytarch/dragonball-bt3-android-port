/*
 * The host's open files behind the game's file handles (plat_file.c).
 *
 * A handle the game holds says only which file it is and where its read position is: things that can be saved and
 * restored with the game's state. The operating system's file objects are kept here, outside that state, found by
 * the file's relative path and opened when first needed; a read always says where in the file it reads. So a frame
 * that is run again after a restore (online play rewinds and replays frames) opens and reads the same files and
 * gets the same data, and nothing is opened or closed twice. A few dozen files stay open; the one unused longest
 * is closed when another is needed.
 * (Its variables must NOT be part of the saved state: port/tools/make_state.py leaves this file out.)
 */
#include <ctype.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
extern void Port_StateTouch(void *p, unsigned long n); /* gs/state.c */

extern const char *Port_FileRoot(void); /* plat_file.c: BT3_DATA or "gamedata" */

#ifdef __EMSCRIPTEN__
/* The web host: the data lives inside the disc image the page names, over HTTP (port/tools/webdata.js,
 * a --js-library). Same shape as the PC host, without a file system: a handle opens a path of the image,
 * its reads give where in the file they read. So a span keeps its base and restore-and-replay still works. */
extern int WebData_Open(const char *path); /* a handle into the image (-1: the image or this file is not there) */
extern long WebData_Size(int h);
extern size_t WebData_Read(int h, long off, void *buf, size_t bytes);
extern void WebData_Close(int h);

#define PORT_HOST_WEB 1
#endif

enum { SLOTS = 32 };
/* base: where the file starts in the host file (0, or its place inside an AFS archive) */
#ifdef PORT_HOST_WEB
static struct { char rel[128]; int web; long base, size; unsigned long used; } sSlot[SLOTS];
#else
static struct { char rel[128]; FILE *fp; long base, size; unsigned long used; } sSlot[SLOTS];
#endif
static unsigned long sClock;
static volatile int sLock;

/* The archives themselves. An install may keep DATA/<NAME>.AFS whole under disc/ instead of a folder of loose files
   (the Android app does: writing ~20 000 small files is what made its install slow). A file "<name>/<index>.bin" that
   is not there as a loose file is then read from the archive, through its table: offset and size per entry. */
static struct { char part[64]; char path[512]; uint32_t count; uint32_t *toc; int tried; } sAfs[8];

int Port_AfsSpan(const char *rel, char *path, unsigned n, long *base, long *size) {
    char part[64];
    const char *slash = strchr(rel, '/');
    int index, i, k;
    size_t pl;

    if (slash == NULL || (pl = (size_t)(slash - rel)) == 0 || pl >= sizeof(part) || strchr(slash + 1, '/') != NULL ||
        sscanf(slash + 1, "%d.bin", &index) != 1 || index < 0) {
        return 0;
    }
    memcpy(part, rel, pl);
    part[pl] = '\0';
    for (i = 0; i < 8 && sAfs[i].tried && strcmp(sAfs[i].part, part) != 0; i++) {
    }
    if (i == 8) {
        return 0;
    }
    if (!sAfs[i].tried) {
#ifdef PORT_HOST_WEB
        /* the whole AFS archive, as a file of the disc image: its header and table in two reads */
        int h, k;
        uint8_t head[8];
        uint32_t count;
        uint8_t *raw;

        sAfs[i].tried = 1;
        snprintf(sAfs[i].part, sizeof(sAfs[i].part), "%s", part);
        k = snprintf(sAfs[i].path, sizeof(sAfs[i].path), "disc/DATA/");
        for (pl = 0; part[pl] != '\0' && k < (int)sizeof(sAfs[i].path) - 5; pl++) {
            sAfs[i].path[k++] = (char)toupper((unsigned char)part[pl]);
        }
        snprintf(sAfs[i].path + k, sizeof(sAfs[i].path) - (size_t)k, ".AFS");
        h = WebData_Open(sAfs[i].path);
        if (h >= 0 && WebData_Size(h) > 20) {
            count = 0;
            if (WebData_Read(h, 0, head, 8) == 8 && memcmp(head, "AFS", 4) == 0) {
                count = (uint32_t)head[4] | (uint32_t)head[5] << 8 | (uint32_t)head[6] << 16 | (uint32_t)head[7] << 24;
                raw = count < 0x100000 ? malloc((size_t)count * 8) : NULL;
                if (raw != NULL && WebData_Read(h, 8, raw, (size_t)count * 8) == count * 8) {
                    sAfs[i].toc = malloc((size_t)count * 8);
                    for (k = 0; sAfs[i].toc != NULL && k < (int)count * 2; k++) {
                        sAfs[i].toc[k] = (uint32_t)raw[k * 4] | (uint32_t)raw[k * 4 + 1] << 8 | (uint32_t)raw[k * 4 + 2] << 16 |
                                         (uint32_t)raw[k * 4 + 3] << 24;
                    }
                    sAfs[i].count = sAfs[i].toc != NULL ? count : 0;
                    fprintf(stderr, "bt3: archive %s: %u files\n", sAfs[i].path, sAfs[i].count);
                }
                free(raw);
            } else {
                fprintf(stderr, "bt3: %s: not an AFS archive\n", sAfs[i].path);
            }
            WebData_Close(h);
        }
#else
        FILE *fp;
        uint8_t head[8];
        sAfs[i].tried = 1;
        snprintf(sAfs[i].part, sizeof(sAfs[i].part), "%s", part);
        k = snprintf(sAfs[i].path, sizeof(sAfs[i].path), "%s/disc/DATA/", Port_FileRoot());
        for (pl = 0; part[pl] != '\0' && k < (int)sizeof(sAfs[i].path) - 5; pl++) {
            sAfs[i].path[k++] = (char)toupper((unsigned char)part[pl]);
        }
        snprintf(sAfs[i].path + k, sizeof(sAfs[i].path) - (size_t)k, ".AFS");
        fp = fopen(sAfs[i].path, "rb");
        if (fp != NULL) {
            if (fread(head, 1, 8, fp) == 8 && memcmp(head, "AFS", 4) == 0) {
                uint32_t count = (uint32_t)head[4] | (uint32_t)head[5] << 8 | (uint32_t)head[6] << 16 | (uint32_t)head[7] << 24;
                uint8_t *raw = count < 0x100000 ? malloc((size_t)count * 8) : NULL;
                if (raw != NULL && fread(raw, 8, count, fp) == count) {
                    sAfs[i].toc = malloc((size_t)count * 8);
                    for (k = 0; sAfs[i].toc != NULL && k < (int)count * 2; k++) {
                        sAfs[i].toc[k] = (uint32_t)raw[k * 4] | (uint32_t)raw[k * 4 + 1] << 8 | (uint32_t)raw[k * 4 + 2] << 16 |
                                         (uint32_t)raw[k * 4 + 3] << 24;
                    }
                    sAfs[i].count = sAfs[i].toc != NULL ? count : 0;
                    fprintf(stderr, "bt3: archive %s: %u files\n", sAfs[i].path, sAfs[i].count);
                }
                free(raw);
            }
            fclose(fp);
        }
#endif
    }
    if (sAfs[i].toc == NULL || (uint32_t)index >= sAfs[i].count) {
        return 0;
    }
    snprintf(path, n, "%s", sAfs[i].path);
    *base = (long)sAfs[i].toc[index * 2];
    *size = (long)sAfs[i].toc[index * 2 + 1];
    return 1;
}

static int slot_of(const char *rel) {
    char path[512];
    int i, old = 0;
    long base = 0, span = -1;
#ifdef PORT_HOST_WEB
    int h = -1;
    const char *inner;
#else
    FILE *fp;
#endif

    for (i = 0; i < SLOTS; i++) {
#ifdef PORT_HOST_WEB
        if (sSlot[i].web != 0 && strcmp(sSlot[i].rel, rel) == 0) {
#else
        if (sSlot[i].fp != NULL && strcmp(sSlot[i].rel, rel) == 0) {
#endif
            sSlot[i].used = ++sClock;
            return i;
        }
    }
#ifdef PORT_HOST_WEB
    inner = strncmp(rel, "disc/", 5) == 0 ? rel + 5 : rel;
    h = WebData_Open(inner); /* a file of the disc's tree ("disc/DATA/..."); an AFS-backed rel misses and falls through */
    if (h < 0 && Port_AfsSpan(rel, path, (unsigned)sizeof(path), &base, &span)) {
        h = WebData_Open(path); /* the whole archive: this slot reads it from base onward */
    }
    if (h < 0) {
        fprintf(stderr, "bt3: file not found: %s\n", rel);
        return -1;
    }
    if (span < 0) {
        base = 0;
        span = WebData_Size(h);
    }
    for (i = 0; i < SLOTS; i++) {
        if (sSlot[i].web == 0) {
            old = i;
            break;
        }
        if (sSlot[i].used < sSlot[old].used) {
            old = i;
        }
    }
    if (sSlot[old].web != 0) {
        WebData_Close(sSlot[old].web);
    }
    snprintf(sSlot[old].rel, sizeof(sSlot[old].rel), "%s", rel);
    sSlot[old].web = h;
    sSlot[old].base = base;
    sSlot[old].size = span;
    sSlot[old].used = ++sClock;
    return old;
#else
    {
        /* a stage added from outside the disc: the game asks for a file id past the disc's own, and the file is
           the one the stage list names (plat_stages.c), under the data folder */
        extern int PortStages_Alias(const char *rel, char *out, unsigned n);
        char target[256];
        fp = NULL;
        extern int PortSongs_Alias(const char *rel, char *out, unsigned n); /* plat_songs.c: the same for a song */
        if (PortStages_Alias(rel, target, (unsigned)sizeof(target)) || PortSongs_Alias(rel, target, (unsigned)sizeof(target))) {
            snprintf(path, sizeof(path), "%s", target); /* as it is (the stages / songs folder), or in the data */
            fp = fopen(path, "rb");
            if (fp == NULL) {
                snprintf(path, sizeof(path), "%s/%s", Port_FileRoot(), target);
                fp = fopen(path, "rb");
            }
        }
    }
    if (fp == NULL) { /* a file under mods/ wins over the original */
        snprintf(path, sizeof(path), "%s/mods/%s", Port_FileRoot(), rel);
        fp = fopen(path, "rb");
    }
    if (fp == NULL) {
        snprintf(path, sizeof(path), "%s/%s", Port_FileRoot(), rel);
        fp = fopen(path, "rb");
    }
    if (fp == NULL && Port_AfsSpan(rel, path, (unsigned)sizeof(path), &base, &span)) { /* inside the archive */
        fp = fopen(path, "rb");
    }
    if (fp == NULL) {
        fprintf(stderr, "bt3: file not found: %s\n", path);
        return -1;
    }
    for (i = 0; i < SLOTS; i++) {
        if (sSlot[i].fp == NULL) {
            old = i;
            break;
        }
        if (sSlot[i].used < sSlot[old].used) {
            old = i;
        }
    }
    if (sSlot[old].fp != NULL) {
        fclose(sSlot[old].fp);
    }
    snprintf(sSlot[old].rel, sizeof(sSlot[old].rel), "%s", rel);
    sSlot[old].fp = fp;
    sSlot[old].base = base;
    if (span >= 0) {
        sSlot[old].size = span;
    } else {
        fseek(fp, 0, SEEK_END);
        sSlot[old].size = ftell(fp);
    }
    {
        extern int Port_StageFits(const char *rel, long size); /* plat_stages.c */
        extern int Port_StageBufSize(void);
        if (!Port_StageFits(rel, sSlot[old].size)) {
            fprintf(stderr, "bt3: the stage file %s is %ld bytes; the game has room for %d. It cannot be loaded.\n", path, sSlot[old].size,
                    Port_StageBufSize());
            exit(2);
        }
    }
    sSlot[old].used = ++sClock;
    return old;
#endif
}

/* The file's size in bytes, or -1 if there is no such file. */
long Port_FileSize(const char *rel) {
    long size;
    int i;
    while (__sync_lock_test_and_set(&sLock, 1)) {
    }
    i = slot_of(rel);
    size = i < 0 ? -1 : sSlot[i].size;
    __sync_lock_release(&sLock);
    return size;
}

/* Reads `bytes` bytes at `offset`; returns how many there were. */
size_t Port_FileReadAt(const char *rel, long offset, void *buf, size_t bytes) {
    size_t got = 0;
    int i;
    while (__sync_lock_test_and_set(&sLock, 1)) {
    }
    i = slot_of(rel);
    if (i >= 0 && offset >= 0 && offset < sSlot[i].size) {
        if ((long)bytes > sSlot[i].size - offset) { /* (in an archive the next file follows) */
            bytes = (size_t)(sSlot[i].size - offset);
        }
        Port_StateTouch(buf, bytes); /* (gs/state.c: file data straight into watched memory) */
#ifdef PORT_HOST_WEB
        got = WebData_Read(sSlot[i].web, sSlot[i].base + offset, buf, bytes);
#else
        if (fseek(sSlot[i].fp, sSlot[i].base + offset, SEEK_SET) == 0) {
            got = fread(buf, 1, bytes, sSlot[i].fp);
        }
#endif
    }
    __sync_lock_release(&sLock);
    return got;
}
