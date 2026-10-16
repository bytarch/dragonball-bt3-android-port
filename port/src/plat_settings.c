/*
 * The player's settings, kept between runs in a small text file of `name=value` lines:
 *
 *     bt3_settings.txt in the current directory, or the path in $BT3_SETTINGS (an empty $BT3_SETTINGS: no file)
 *
 * The settings overlay (port/src/gs/gs_gpu.c) writes it whenever a setting changes. At start an environment
 * variable (BT3_SCALE, BT3_ASPECT, ...) still wins over the file, so a single run can be started differently.
 * Names: scale, aspect_milli, fullscreen, fx_off, glow, music, effects, display; the bindings of port/src/gs/gs_input.c
 * (key_p1_cross = an SDL scancode, pad_p1_cross = a controller button, padslot_p1 = which controller).
 * A value that is not a number is text (net_name, the player's name). The file is shared with the launcher, which
 * writes net_name from its own screen, so a line it does not know must survive being written back.
 */
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define MAX_SETTINGS 200
#define MAX_TEXTS 16
#define TEXT_LEN 64
static struct { char name[24]; int value; } sSet[MAX_SETTINGS];
static struct { char name[24]; char value[TEXT_LEN]; } sText[MAX_TEXTS];
static int sDirty;
static int sCount;
static int sTextCount;
static pthread_once_t sOnce = PTHREAD_ONCE_INIT;
static pthread_mutex_t sLock = PTHREAD_MUTEX_INITIALIZER;

static const char *path(void) {
    const char *p = getenv("BT3_SETTINGS");
    return p == NULL ? "bt3_settings.txt" : p[0] == '\0' ? NULL : p;
}

static void load(void) {
    char line[128], name[24], text[TEXT_LEN];
    int value;
    FILE *fp = path() != NULL ? fopen(path(), "r") : NULL;

    while (fp != NULL && fgets(line, sizeof(line), fp) != NULL) {
        if (sscanf(line, " %23[a-z0-9_]=%d", name, &value) == 2) { /* a number: a setting */
            int i;
            for (i = 0; i < sCount && strcmp(sSet[i].name, name) != 0; i++) {
            }
            if (i == sCount && sCount < MAX_SETTINGS) {
                strcpy(sSet[sCount].name, name);
                sCount++;
            }
            if (i < sCount) {
                sSet[i].value = value; /* last line wins, without keeping duplicate entries in memory */
            }
        } else if (sscanf(line, " %23[a-z0-9_]=%63[^\n]", name, text) == 2) { /* anything else is text, and is kept */
            char *e = text + strlen(text);
            while (e > text && (e[-1] == '\r' || e[-1] == ' ')) {
                *--e = '\0';
            }
            {
                int i;
                for (i = 0; i < sTextCount && strcmp(sText[i].name, name) != 0; i++) {
                }
                if (i == sTextCount && sTextCount < MAX_TEXTS) {
                    strcpy(sText[sTextCount].name, name);
                    sTextCount++;
                }
                if (i < sTextCount) {
                    strcpy(sText[i].value, text); /* last line wins, without keeping duplicate entries in memory */
                }
            }
        }
    }
    if (fp != NULL) {
        fclose(fp);
    }
}

/* The saved value of a setting, or `def` when the file does not have it. */
int Port_Setting(const char *name, int def) {
    int i, v = def;

    pthread_once(&sOnce, load);
    pthread_mutex_lock(&sLock);
    for (i = 0; i < sCount; i++) {
        if (strcmp(sSet[i].name, name) == 0) {
            v = sSet[i].value;
        }
    }
    pthread_mutex_unlock(&sLock);
    return v;
}

/* Stores a setting; Port_SettingsWrite puts the changes into the file. */
void Port_SettingSave(const char *name, int value) {
    int i;

    pthread_once(&sOnce, load);
    pthread_mutex_lock(&sLock);
    for (i = 0; i < sCount && strcmp(sSet[i].name, name) != 0; i++) {
    }
    if (i < MAX_SETTINGS) {
        if (i == sCount) {
            snprintf(sSet[sCount++].name, sizeof(sSet[0].name), "%s", name);
            sDirty = 1;
        }
        if (sSet[i].value != value) {
            sSet[i].value = value;
            sDirty = 1;
        }
    }
    pthread_mutex_unlock(&sLock);
}

void Port_SettingsWrite(void) {
    FILE *fp;
    int i;

    pthread_mutex_lock(&sLock);
    fp = sDirty && path() != NULL ? fopen(path(), "w") : NULL;
    if (fp != NULL) {
        for (i = 0; i < sCount; i++) {
            /* A name that is also a text setting is not written as a number as well: a line left behind by an
               older build (net_id=515) would otherwise be written out again for ever, and the text value read
               back is the one that counts. Writing the file this way heals such a line. */
            int j, also = 0;
            for (j = 0; j < sTextCount && !also; j++) {
                also = strcmp(sText[j].name, sSet[i].name) == 0;
            }
            if (!also) {
                fprintf(fp, "%s=%d\n", sSet[i].name, sSet[i].value);
            }
        }
        for (i = 0; i < sTextCount; i++) {
            fprintf(fp, "%s=%s\n", sText[i].name, sText[i].value);
        }
        fclose(fp);
    }
    sDirty = 0;
    pthread_mutex_unlock(&sLock);
}

/* ---- the settings that are text, not a number ---- */

/* The saved text of a setting, or NULL when the file has none (net_name, the player's name). The string belongs to
   the settings and stays valid until the next Port_SettingTextSave; copy it if it must outlive that. */
const char *Port_SettingText(const char *name) {
    const char *v = NULL;
    int i;

    pthread_once(&sOnce, load);
    pthread_mutex_lock(&sLock);
    for (i = 0; i < sTextCount; i++) {
        if (strcmp(sText[i].name, name) == 0) {
            v = sText[i].value;
        }
    }
    pthread_mutex_unlock(&sLock);
    return v;
}

void Port_SettingTextSave(const char *name, const char *value) {
    int i;

    pthread_once(&sOnce, load);
    pthread_mutex_lock(&sLock);
    for (i = 0; i < sTextCount && strcmp(sText[i].name, name) != 0; i++) {
    }
    if (i == MAX_TEXTS) { /* (the table is full: the old value stands) */
        pthread_mutex_unlock(&sLock);
        return;
    }
    if (i == sTextCount) {
        strncpy(sText[i].name, name, sizeof(sText[0].name) - 1);
        sTextCount++;
    }
    if (strcmp(sText[i].value, value) != 0) {
        snprintf(sText[i].value, sizeof(sText[0].value), "%s", value);
        sDirty = 1;
    }
    pthread_mutex_unlock(&sLock);
}
