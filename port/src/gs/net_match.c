/*
 * Matchmaking with room codes: the client side of port/matchmaking/worker.js.
 *
 * The lobby (net.c) opens the UDP socket the match will use and hands it over. A thread then:
 *   1. asks a STUN server, from that socket, which address and port the rest of the internet sees it under;
 *   2. tells the Worker (host: "make a room", and then asks every two seconds whether someone has joined; the one who
 *      joins: "join room <code>");
 *   3. ends with the other player's addresses, which the lobby sends its greetings to (both sides at once: that is
 *      what lets the two routers pass each other's packets).
 * The Worker is spoken to over HTTPS with what the system has: WinHTTP on Windows, the curl library on Linux, and
 * failing those the `curl` program (BT3_MATCH_CURL=1 asks for the program). No game data goes that way.
 *
 * The service is the one compiled in (MATCH_URL_DEFAULT); BT3_MATCH_URL=<address> names another one, for a desktop
 * build or a test against a local service.
 */
#include <SDL3/SDL.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifdef _WIN32
#include <winsock2.h>
#include <ws2tcpip.h>
#else
#include <arpa/inet.h>
#include <netdb.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <unistd.h>
#endif

/* This build's own matchmaking service (port/matchmaking/server.py on our VPS behind Caddy, see
   port/matchmaking/VPS.md). BT3_MATCH_URL, or match_url.txt in the game folder on Android, names another one. */
#define MATCH_URL_DEFAULT "https://dbt3-server.bytarch.com"
#include "net_match.h"
#include "net_relay.h"

static struct {
    SDL_Mutex *lock;
    SDL_Thread *thread;
    SDL_AtomicInt cancel, gen; /* gen: which search is the current one (a thread of an earlier one writes nothing) */
    MatchStatus st;
    int role, sock, localPort, version;
    char code[8], name[20];
} sM;

static SDL_TLSID sMine; /* the thread's own search number */
static int http_post(const char *what, const char *json, char *out, size_t size); /* below, with the other POSTs */
#define STOPPED() (SDL_GetAtomicInt(&sM.cancel) || (int)(intptr_t)SDL_GetTLS(&sMine) != SDL_GetAtomicInt(&sM.gen))

/* The match the lobby found, kept after the search thread is gone: the session that follows has no room code or
   key of its own, and the record is said against the room the match was found in (Match_Report). */
static char sRoomCode[8], sRoomKey[40];
static int sRecordWins, sRecordLosses, sRecordKnown = -1;
static SDL_AtomicInt sRecordAskBusy;
static uint64_t sRecordAskedAt;
extern const char *Port_NetId(void); /* gs/net.c: this copy's id, made up and kept the first time it is asked for */
extern int Port_NetShare(int set);   /* gs/net.c: whether opponents may see this record (-1 asks) */
extern const char *Port_NetPlatform(void); /* gs/net.c: "pc" or "mobile" */
static void tell_service_who_we_are(const char *name, char *out, size_t size); /* below, in the search thread */

/* Windows asks about the firewall by itself when a program visibly listens for connections. This game does not, and
   so the question was never asked: on a Public network the firewall dropped everything the other game sent, the pair
   could not be completed, and the window said only that the two games could not reach each other - with nothing in
   the log to say why. So the rule is asked for the way a game's installer asks, in one UAC prompt the first time
   online play is used. Asked once and remembered: a player who says no is not asked again at every match. */
#if defined(_WIN32)
#include <windows.h>
#include <shellapi.h>

extern const char *Port_SettingText(const char *name);        /* plat_settings.c */
extern void Port_SettingTextSave(const char *name, const char *value);

static void ask_firewall(void) {
    char exe[MAX_PATH], cmd[1024];
    HINSTANCE r;

    const char *asked = Port_SettingText("fw_rule");
    if (asked != NULL && asked[0] != '\0') {
        return;
    }
    Port_SettingTextSave("fw_rule", "asked");
    if (GetModuleFileNameA(NULL, exe, sizeof(exe)) == 0) {
        return;
    }
    wsprintfA(cmd, "advfirewall firewall add rule name=\"DBT3 online play\" dir=in action=allow "
                   "program=\"%s\" protocol=UDP enable=yes profile=any", exe);
    r = ShellExecuteA(NULL, "runas", "netsh.exe", cmd, NULL, SW_HIDE); /* (runas: this is the prompt) */
    if ((INT_PTR)r <= 32) {
        fprintf(stderr, "firewall: the rule was not added (%ld), and is not asked again\n", (long)(INT_PTR)r);
    }
}
#else
static void ask_firewall(void) {}
#endif

/* What the service knows of this copy's record, for the window. 0 when it has answered, -1 while it has
   not: an unknown record is not a player who has won nothing. */
int Port_NetRecord(int *wins, int *losses) {
    *wins = sRecordWins;
    *losses = sRecordLosses;
    return sRecordKnown;
}

/* The room this match was found in, kept after the search thread is gone: the session that follows has neither the
   code nor the key of its own, and the record is said against the room the match came from (Match_Report). Both
   are set in one place, because one without the other is no use and only a missing key is silent about it. */
static void remember_room(const char *key) {
    SDL_LockMutex(sM.lock);
    SDL_strlcpy(sRoomCode, sM.code, sizeof(sRoomCode));
    SDL_strlcpy(sRoomKey, key, sizeof(sRoomKey));
    SDL_UnlockMutex(sM.lock);
}

/* The room a match came from, when this copy did not find it: the Android launcher does the matchmaking itself and
   hands the room over in the environment (BT3_NET_ROOM, BT3_NET_KEY), because the result is said against that room.
   Both words or neither: one without the other is no use to the service. */
void Match_Room(const char *code, const char *key) {
    if (code == NULL || key == NULL || code[0] == '\0' || key[0] == '\0') {
        return;
    }
    SDL_LockMutex(sM.lock);
    SDL_strlcpy(sRoomCode, code, sizeof(sRoomCode));
    SDL_strlcpy(sRoomKey, key, sizeof(sRoomKey));
    SDL_UnlockMutex(sM.lock);
}

/* One POST of how the match went, on its own thread: the game is back in the menu by the time this is called and
   must not wait for the service, and the match is over anyway. */
static int SDLCALL report_thread(void *arg) {
    char code[8], key[40], body[192], answer[512];
    int *w = (int *)arg;

    SDL_LockMutex(sM.lock);
    SDL_strlcpy(code, sRoomCode, sizeof(code));
    SDL_strlcpy(key, sRoomKey, sizeof(key));
    SDL_UnlockMutex(sM.lock);
    if (code[0] == '\0' || key[0] == '\0') { /* said out loud: a match that is not reported leaves no trace */
        fprintf(stderr, "bt3: net: the result of the match was not said: no room (code \"%s\", key \"%s\")\n", code, key);
        SDL_free(w);
        return 0;
    }
    SDL_snprintf(body, sizeof(body), "{\"code\":\"%s\",\"key\":\"%s\",\"id\":\"%s\",\"won\":%s,\"rated\":true}",
                 code, key, Port_NetId(), w[0] != 0 ? "true" : "false");
    http_post("result", body, answer, sizeof(answer));
    fprintf(stderr, "bt3: net: said how the match went (room %s, %s won)\n", code, w[0] != 0 ? "this side" : "the other side");
    /* The record the window shows is this copy's, so it is asked for again rather than waited for: the pause
       menu and the next lobby draw the new numbers without anything being reopened.

       Twice, and the second one is the one that matters: a match is only counted once BOTH sides have said how
       it went, so asking the moment this side's report is sent reads a record the opponent has not been added to
       yet - one match behind for as long as it takes the other game to finish saying. The other side's report
       lands within a second or two, so ask again after a moment rather than leave a stale number on screen. */
    tell_service_who_we_are(sM.name, answer, sizeof(answer));
    SDL_Delay(2500);
    tell_service_who_we_are(sM.name, answer, sizeof(answer));
    SDL_free(w);
    return 0;
}

void Match_Report(int won) {
    int *w = (int *)SDL_malloc(sizeof(int));

    if (w == NULL) {
        return;
    }
    w[0] = won;
    SDL_CreateThread(report_thread, "bt3record", w);
}

/* The record shown by Profile: the service is asked on its own thread, and the answer is kept between requests so the
   window never waits on HTTPS. Match_AskRecord decides how often (see below: the service's own limit on questions is
   what sets the pace, not how fast the window is drawn). */
static int SDLCALL ask_record_thread(void *arg) {
    char answer[512];

    tell_service_who_we_are((const char *)arg, answer, sizeof(answer));
    if (sRecordKnown != 0) {
        sRecordAskedAt = SDL_GetTicks(); /* an answer: the next question is a while away */
    }
    SDL_SetAtomicInt(&sRecordAskBusy, 0);
    SDL_free(arg);
    return 0;
}

void Match_AskRecord(const char *name) {
    char *copy;
    uint64_t now = SDL_GetTicks();

    /* How often the Profile tab may ask. The service allows a player 200 name look-ups a day, counted per address,
       so a tab that asked on every frame spent a day's allowance in seventeen minutes and the service then refused
       every later question with "busy names" (429) - the record stopped updating and nothing said why. So: keep
       asking while there is no answer at all (a network that is down must not leave the tab stuck for ever), and
       once there is one, go back to a few an hour. A finished match asks separately, which is what moves the numbers
       straight after a win or a loss. */
    if (name == NULL || name[0] == '\0' || SDL_GetAtomicInt(&sRecordAskBusy) != 0) {
        return;
    }
    if (sRecordKnown != 0) {
        if (sRecordAskedAt != 0 && now - sRecordAskedAt < 300000) {
            return;
        }
    } else if (sRecordAskedAt != 0 && now - sRecordAskedAt < 5000) {
        return; /* no answer yet: look again in a moment, not on the next frame */
    }
    sRecordAskedAt = now;
    copy = (char *)SDL_malloc(SDL_strlen(name) + 1);
    if (copy == NULL) {
        return;
    }
    SDL_strlcpy(copy, name, SDL_strlen(name) + 1);
    SDL_SetAtomicInt(&sRecordAskBusy, 1);
    if (SDL_CreateThread(ask_record_thread, "bt3record?", copy) == NULL) {
        SDL_SetAtomicInt(&sRecordAskBusy, 0);
        SDL_free(copy);
    }
}

/* A pasted recovery code is a different player, even when the display name is unchanged. Do not leave the previous
   player's numbers on screen while Profile asks the service for the new id. */
void Match_RecordIdentityChanged(void) {
    sRecordWins = sRecordLosses = 0;
    sRecordKnown = -1;
    sRecordAskedAt = 0;
}

/* This build talks to one service, the one it is compiled with. There is no file in the game folder and nothing
   on the phone that can point it elsewhere: an APK handed to someone should reach the service that goes with it.
   BT3_MATCH_URL still names another one, for a desktop build or a test against a local service. */
static const char *match_url(void) {
    const char *e = SDL_getenv("BT3_MATCH_URL");
#ifdef __ANDROID__
    static int said;
    if (!said) {
        said = 1;
        fprintf(stderr, "net: matchmaking service %s\n", e != NULL && e[0] != '\0' ? e : MATCH_URL_DEFAULT);
    }
#endif
    return e != NULL && e[0] != '\0' ? e : MATCH_URL_DEFAULT;
}

static void set_state(int state, const char *error) {
    if ((int)(intptr_t)SDL_GetTLS(&sMine) != SDL_GetAtomicInt(&sM.gen)) {
        return;
    }
    if (error != NULL && state < 0) {
        fprintf(stderr, "net: %s\n", error); /* (in the log, for a report about online play) */
    }
    SDL_LockMutex(sM.lock);
    sM.st.state = state;
    if (error != NULL) {
        SDL_strlcpy(sM.st.error, error, sizeof(sM.st.error));
    }
    SDL_UnlockMutex(sM.lock);
}

/* Why there is no relay address, for the window: the service's own reason, which is the only thing that tells the
   player whether a player behind a strict router will be able to reach them. Not a failure: a room works without one. */
static void set_relay_why(const char *why) {
    if ((int)(intptr_t)SDL_GetTLS(&sMine) != SDL_GetAtomicInt(&sM.gen) || why == NULL) {
        return;
    }
    SDL_LockMutex(sM.lock);
    SDL_strlcpy(sM.st.relayWhy, why, sizeof(sM.st.relayWhy));
    SDL_UnlockMutex(sM.lock);
}

/* ---- the Worker, through curl ---- */

#ifdef _WIN32
/* Windows: its own HTTPS (WinHTTP), so nothing has to be installed and no other program is started. */
#include <winhttp.h>

static int http_post_native(const char *url, const char *json, char *out, size_t size) {
    wchar_t wurl[512], host[256], path[256];
    URL_COMPONENTS uc;
    HINTERNET ses = NULL, con = NULL, req = NULL;
    DWORD got = 0, used = 0, status = 0, len = sizeof(status);
    int ok = 0;

    if (MultiByteToWideChar(CP_UTF8, 0, url, -1, wurl, 512) == 0) {
        return 0;
    }
    memset(&uc, 0, sizeof(uc));
    uc.dwStructSize = sizeof(uc);
    uc.lpszHostName = host;
    uc.dwHostNameLength = 256;
    uc.lpszUrlPath = path;
    uc.dwUrlPathLength = 256;
    if (!WinHttpCrackUrl(wurl, 0, 0, &uc)) {
        return 0;
    }
    ses = WinHttpOpen(L"DBT3", WINHTTP_ACCESS_TYPE_DEFAULT_PROXY, WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (ses != NULL) {
        WinHttpSetTimeouts(ses, 8000, 8000, 8000, 12000);
        con = WinHttpConnect(ses, host, uc.nPort, 0);
    }
    if (con != NULL) {
        req = WinHttpOpenRequest(con, L"POST", path, NULL, WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES, uc.nScheme == INTERNET_SCHEME_HTTPS ? WINHTTP_FLAG_SECURE : 0);
    }
    if (req != NULL && WinHttpSendRequest(req, L"Content-Type: application/json\r\n", (DWORD)-1, (LPVOID)json, (DWORD)strlen(json), (DWORD)strlen(json), 0) &&
        WinHttpReceiveResponse(req, NULL)) {
        WinHttpQueryHeaders(req, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER, WINHTTP_HEADER_NAME_BY_INDEX, &status, &len, WINHTTP_NO_HEADER_INDEX);
        while (used + 1 < size && WinHttpReadData(req, out + used, (DWORD)(size - 1 - used), &got) && got != 0) {
            used += got;
        }
        out[used] = '\0';
        ok = used > 0;
    }
    if (req != NULL) { WinHttpCloseHandle(req); }
    if (con != NULL) { WinHttpCloseHandle(con); }
    if (ses != NULL) { WinHttpCloseHandle(ses); }
    return ok;
}
#elif defined(__ANDROID__)
/* Android: no curl. The app's Java side does the request (GameActivity.netPost, HttpsURLConnection), called here
   through JNI on this thread (SDL attaches it). */
#include <jni.h>

static int http_post_native(const char *url, const char *json, char *out, size_t size) {
    JNIEnv *env = (JNIEnv *)SDL_GetAndroidJNIEnv();
    jobject act;
    jclass cls;
    jmethodID m;
    jstring ju, jb, jr;
    int ok = 0;

    if (env == NULL || (act = (jobject)SDL_GetAndroidActivity()) == NULL) {
        return 0;
    }
    cls = (*env)->GetObjectClass(env, act);
    m = cls != NULL ? (*env)->GetMethodID(env, cls, "netPost", "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;") : NULL;
    if (m == NULL) {
        (*env)->ExceptionClear(env);
    } else {
        ju = (*env)->NewStringUTF(env, url);
        jb = (*env)->NewStringUTF(env, json);
        jr = (jstring)(*env)->CallObjectMethod(env, act, m, ju, jb);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            jr = NULL;
        }
        if (jr != NULL) {
            const char *r = (*env)->GetStringUTFChars(env, jr, NULL);
            if (r != NULL) {
                SDL_strlcpy(out, r, size);
                ok = out[0] != '\0';
                (*env)->ReleaseStringUTFChars(env, jr, r);
            }
            (*env)->DeleteLocalRef(env, jr);
        }
        (*env)->DeleteLocalRef(env, ju);
        (*env)->DeleteLocalRef(env, jb);
    }
    if (cls != NULL) {
        (*env)->DeleteLocalRef(env, cls);
    }
    (*env)->DeleteLocalRef(env, act);
    return ok;
}
#else
/* Linux: the system's curl library, if it is there (it nearly always is), loaded when first needed. */
static int http_post_native(const char *url, const char *json, char *out, size_t size) {
    typedef void *(*init_fn)(void);
    typedef int (*setopt_fn)(void *, int, ...);
    typedef int (*perform_fn)(void *);
    typedef void (*cleanup_fn)(void *);
    static SDL_SharedObject *lib;
    static int tried;
    static init_fn init;
    static setopt_fn setopt;
    static perform_fn perform;
    static cleanup_fn cleanup;
    void *h;
    SDL_IOStream *mem;
    FILE *fp;
    int rc;
    long n;

    if (!tried) {
        static const char *const names[] = {"libcurl.so.4", "libcurl-gnutls.so.4", "libcurl.so"};
        size_t i;
        tried = 1;
        for (i = 0; lib == NULL && i < sizeof(names) / sizeof(names[0]); i++) {
            lib = SDL_LoadObject(names[i]);
        }
        if (lib != NULL) {
            init = (init_fn)SDL_LoadFunction(lib, "curl_easy_init");
            setopt = (setopt_fn)SDL_LoadFunction(lib, "curl_easy_setopt");
            perform = (perform_fn)SDL_LoadFunction(lib, "curl_easy_perform");
            cleanup = (cleanup_fn)SDL_LoadFunction(lib, "curl_easy_cleanup");
        }
    }
    (void)mem;
    if (init == NULL || setopt == NULL || perform == NULL || cleanup == NULL || (h = init()) == NULL) {
        return 0;
    }
    fp = tmpfile(); /* (the library writes the answer with fwrite by default: into a file that is never on disk by name) */
    if (fp == NULL) {
        cleanup(h);
        return 0;
    }
    setopt(h, 10002 /* CURLOPT_URL */, url);
    setopt(h, 10015 /* CURLOPT_POSTFIELDS */, json);
    setopt(h, 10001 /* CURLOPT_WRITEDATA */, fp);
    setopt(h, 13 /* CURLOPT_TIMEOUT */, 12L);
    setopt(h, 99 /* CURLOPT_NOSIGNAL */, 1L);
    rc = perform(h);
    cleanup(h);
    n = 0;
    if (rc == 0) {
        rewind(fp);
        n = (long)fread(out, 1, size - 1, fp);
        out[n > 0 ? n : 0] = '\0';
    }
    fclose(fp);
    return rc == 0 && n > 0;
}
#endif

/* POSTs `json` to <url>/v1/<what>; the answer's text in out. 0 = no answer. The system's own HTTPS first (above); if
   that is not to be had, the `curl` program. */
static int http_post(const char *what, const char *json, char *out, size_t size) {
    char url[256], body[768];
    const char *args[12];
    SDL_Process *p;
    size_t got = 0;
    int code = 0, n = 0;
    void *data;

    /* Every request carries this player's recovery code, so the service knows whose room this is and can answer
       "what may the other player be told" without either game telling the other anything. The code goes to the
       service and nowhere else, and the service stores only its hash. */
    SDL_snprintf(body, sizeof(body), "%.*s,\"id\":\"%s\"}", (int)SDL_strlen(json) - 1, json, Port_NetId());
    json = body;
    SDL_snprintf(url, sizeof(url), "%s/v1/%s", match_url(), what);
    if (SDL_getenv("BT3_MATCH_CURL") == NULL && http_post_native(url, json, out, size)) {
        return 1;
    }
#ifdef __ANDROID__
    (void)args; (void)p; (void)got; (void)code; (void)n; (void)data;
    return 0; /* (no curl program to fall back on) */
#endif
#ifdef _WIN32
    args[n++] = "curl.exe";
#else
    args[n++] = "curl";
#endif
    args[n++] = "-s";
    args[n++] = "-m";
    args[n++] = "12";
    args[n++] = "-X";
    args[n++] = "POST";
    args[n++] = url;
    args[n++] = "-d";
    args[n++] = json;
    args[n] = NULL;
    p = SDL_CreateProcess(args, true);
    if (p == NULL) {
        return 0;
    }
    data = SDL_ReadProcess(p, &got, &code);
    SDL_DestroyProcess(p);
    if (data == NULL) {
        return 0;
    }
    SDL_strlcpy(out, (const char *)data, size);
    SDL_free(data);
    return got > 0;
}

/* The text of "key":"..." in a flat JSON answer. 0 = not there. */
/* In an answer, at the value that follows a ":". A service that writes a space after the colon - json.dumps does by
   default, JSON.stringify does not - is read the same as one that does not; the port's own service writes it compact,
   as worker.js does, and this is what keeps a change there from going unnoticed until a phone cannot host at all. */
static const char *after_colon(const char *p) {
    while (*p == ' ') {
        p++;
    }
    return p;
}

static int json_str(const char *json, const char *key, char *out, size_t size) {
    char pat[40];
    const char *p, *e;

    SDL_snprintf(pat, sizeof(pat), "\"%s\":", key);
    p = SDL_strstr(json, pat);
    if (p == NULL) {
        return 0;
    }
    p = after_colon(p + SDL_strlen(pat));
    if (*p != '"') {
        return 0;
    }
    p++;
    e = SDL_strchr(p, '"');
    if (e == NULL || (size_t)(e - p) >= size) {
        return 0;
    }
    SDL_memcpy(out, p, (size_t)(e - p));
    out[e - p] = '\0';
    return 1;
}

/* The number after "key": in a flat answer, 0 when there is none (the queue's "matched" and "waiting"). */
static int json_int(const char *json, const char *key) {
    char pat[40];
    const char *p;
    int n = 0;

    SDL_snprintf(pat, sizeof(pat), "\"%s\":", key);
    p = SDL_strstr(json, pat);
    if (p == NULL) {
        return 0;
    }
    for (p += SDL_strlen(pat); *p == ' '; p++) {
    }
    for (; *p >= '0' && *p <= '9'; p++) {
        n = n * 10 + (*p - '0');
    }
    return n;
}

/* The peer of an answer: "peer":{"name":"..","addrs":["..",".."]}. 0 = none ("peer":null, or no such part). */
static int json_peer(const char *json, MatchStatus *st) {
    const char *p = SDL_strstr(json, "\"peer\":"), *a;
    int n = 0;

    if (p == NULL) {
        return 0;
    }
    p = after_colon(p + 7);
    if (*p != '{') {
        return 0; /* "peer":null: nobody has joined yet */
    }
    json_str(p, "name", st->peerName, sizeof(st->peerName));
    a = SDL_strstr(p, "\"addrs\":");
    if (a == NULL || *after_colon(a + 8) != '[') {
        return 0;
    }
    a = after_colon(a + 8) + 1;
    while (*a == '"' && n < MATCH_ADDRS) {
        const char *e = SDL_strchr(a + 1, '"');
        if (e == NULL || (size_t)(e - a - 1) >= sizeof(st->peerAddr[0])) {
            break;
        }
        SDL_memcpy(st->peerAddr[n], a + 1, (size_t)(e - a - 1));
        st->peerAddr[n][e - a - 1] = '\0';
        n++;
        a = e + 1;
        if (*a == ',') {
            a = after_colon(a + 1); /* and past a space, if the service writes one after the comma */
        }
    }
    st->peerCount = n;
    /* The peer's record, when they have asked the service to show it: "record":{"wins":3,"losses":1}. Left at
       -1 otherwise, which is not the same as zero: zero is a player who has won nothing. */
    st->wins = st->losses = -1;
    if (SDL_strstr(p, "\"record\":") != NULL) {
        st->wins = json_int(p, "wins");
        st->losses = json_int(p, "losses");
    }
    json_str(p, "platform", st->peerPlatform, sizeof(st->peerPlatform));
    return n > 0;
}

/* ---- STUN (RFC 5389): one question, "which address do you see me under?" ---- */

static int stun_ask(int sock, const char *host, int port, char *out, size_t size) {
    struct addrinfo hints, *res = NULL;
    uint8_t req[20], pkt[512];
    char portText[8];
    int tries, i;

    SDL_memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_DGRAM;
    SDL_snprintf(portText, sizeof(portText), "%d", port);
    if (getaddrinfo(host, portText, &hints, &res) != 0 || res == NULL) {
        return 0;
    }
    SDL_memset(req, 0, sizeof(req));
    req[1] = 0x01; /* binding request, no attributes */
    req[4] = 0x21; req[5] = 0x12; req[6] = 0xA4; req[7] = 0x42; /* the magic cookie */
    for (i = 8; i < 20; i++) {
        req[i] = (uint8_t)SDL_rand(256);
    }
    for (tries = 0; tries < 3 && !STOPPED(); tries++) {
        int waited;
        sendto(sock, (const char *)req, sizeof(req), 0, res->ai_addr, (int)res->ai_addrlen);
        for (waited = 0; waited < 500; waited += 10) {
            struct sockaddr_in from;
            socklen_t fromLen = sizeof(from);
            int n = (int)recvfrom(sock, (char *)pkt, sizeof(pkt), 0, (struct sockaddr *)&from, &fromLen), at;
            if (n < 20 || pkt[0] != 0x01 || pkt[1] != 0x01 || SDL_memcmp(pkt + 8, req + 8, 12) != 0) {
                SDL_Delay(10);
                continue;
            }
            for (at = 20; at + 4 <= n;) {
                int type = pkt[at] << 8 | pkt[at + 1], len = pkt[at + 2] << 8 | pkt[at + 3];
                if (at + 4 + len > n) {
                    break;
                }
                if ((type == 0x0020 || type == 0x0001) && len >= 8 && pkt[at + 5] == 0x01) {
                    const uint8_t *v = &pkt[at + 4];
                    unsigned p = (unsigned)(v[2] << 8 | v[3]);
                    uint8_t ip[4] = {v[4], v[5], v[6], v[7]};
                    if (type == 0x0020) { /* XOR-MAPPED-ADDRESS: with the cookie */
                        p ^= 0x2112;
                        ip[0] ^= 0x21; ip[1] ^= 0x12; ip[2] ^= 0xA4; ip[3] ^= 0x42;
                    }
                    SDL_snprintf(out, size, "%u.%u.%u.%u:%u", ip[0], ip[1], ip[2], ip[3], p);
                    freeaddrinfo(res);
                    return 1;
                }
                at += 4 + ((len + 3) & ~3);
            }
        }
    }
    freeaddrinfo(res);
    return 0;
}

/* This machine's address on its own network (for two players behind the same router): the address a socket would
   send from, found without sending anything. */
static int lan_address(int localPort, char *out, size_t size) {
    struct sockaddr_in to, me;
    socklen_t len = sizeof(me);
    int s = (int)socket(AF_INET, SOCK_DGRAM, 0), ok = 0;
    char text[32];

    if (s < 0) {
        return 0;
    }
    SDL_memset(&to, 0, sizeof(to));
    to.sin_family = AF_INET;
    to.sin_port = htons(9);
    inet_pton(AF_INET, "192.0.2.1", &to.sin_addr); /* (a documentation address: nothing is sent) */
    if (connect(s, (struct sockaddr *)&to, sizeof(to)) == 0 && getsockname(s, (struct sockaddr *)&me, &len) == 0 &&
        inet_ntop(AF_INET, &me.sin_addr, text, sizeof(text)) != NULL && SDL_strcmp(text, "0.0.0.0") != 0) {
        SDL_snprintf(out, size, "%s:%d", text, localPort);
        ok = 1;
    }
#ifdef _WIN32
    closesocket(s);
#else
    close(s);
#endif
    return ok;
}

/* ---- the thread ---- */

/* Who this copy is, so the service has the record before any match is reported against it, and this player's own
   choice about who may see it (the Profile tab's switch, Port_NetShare). The answer is the record as the service
   has it, which is what the window shows. Called when the lobby starts, so a change to the switch reaches the
   service with the next match rather than at once. */
static void tell_service_who_we_are(const char *name, char *out, size_t size) {
    char body[256];
    const char *p;

    SDL_snprintf(body, sizeof(body), "{\"name\":\"%s\",\"share\":%s,\"platform\":\"%s\"}", name,
                 Port_NetShare(-1) != 0 ? "true" : "false", Port_NetPlatform());
    if (http_post("player", body, out, size) && (p = SDL_strstr(out, "\"wins\":")) != NULL) {
        sRecordWins = json_int(out, "wins");
        sRecordLosses = json_int(out, "losses");
        sRecordKnown = 1;
    }
}

static int SDLCALL match_thread(void *arg) {
    char pub[64] = "", lan[64] = "", addrs[160], body[512], answer[1024], key[40] = "", you[64] = "", name[20];
    /* The other player, wherever this search got them. One variable for the whole function, and it has to be this one:
       quick match fills it and then jumps out of its own block with `goto joined`, and `joined` is written where a
       second `st` used to be declared - an uninitialised one. So the pairing's role, name and addresses were read out
       of whatever the stack held, which is why a copy sometimes knocked when the service had made it the joiner, and
       sometimes greeted an address that was never anybody's. */
    MatchStatus st;
    int i, n = 0, relayHave = 0; /* (a relay address already taken: quick match takes one before it is paired) */

    SDL_SetTLS(&sMine, arg, NULL);
    for (i = 0; sM.name[i] != '\0' && n < (int)sizeof(name) - 1; i++) { /* (a name that needs no escaping) */
        char c = sM.name[i];
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == ' ' || c == '_' || c == '-' || c == '.') {
            name[n++] = c;
        }
    }
    name[n] = '\0';
    tell_service_who_we_are(name, answer, sizeof(answer));
    if (!stun_ask(sM.sock, "stun.cloudflare.com", 3478, pub, sizeof(pub))) {
        stun_ask(sM.sock, "stun.l.google.com", 19302, pub, sizeof(pub));
    }
    lan_address(sM.localPort, lan, sizeof(lan));
    if (STOPPED()) {
        return 0;
    }
    /* (no public address found: the Worker's view of this machine's address with the local port is tried in its
       place, below, once the Worker has said what it sees) */
    SDL_snprintf(addrs, sizeof(addrs), "%s%s%s%s%s%s%s", pub[0] ? "\"" : "", pub, pub[0] ? "\"" : "", pub[0] && lan[0] ? "," : "", lan[0] ? "\"" : "", lan,
                 lan[0] ? "\"" : "");
    if (addrs[0] == '\0') {
        set_state(-1, "This computer's address could not be found (no network?).");
        return 0;
    }
    if (sM.role == 3) {
        /* Quick match: the service pairs this player with whoever is waiting for the same build. The waiting one
           hosts (it has had time to take a relay address, which is why the service pairs it that way), and this
           player joins: the answer that pairs it carries the host's addresses, so it connects at once and needs
           nothing but a poll while it waits. From the pair it is the room-code path below, unchanged. */
        char relay[64] = "", qkey[40] = "", qbody[512], tserver[128], tuser[160], tpass[160];
        int publish = 0;

        SDL_memset(&st, 0, sizeof(st));
        SDL_snprintf(body, sizeof(body), "{\"v\":%d,\"name\":\"%s\",\"addrs\":[%s]}", sM.version, name, addrs);
        if (!http_post("queue", body, answer, sizeof(answer))) {
            set_state(-1, "The matchmaking service could not be reached (is the network up?).");
            return 0;
        }
        if (!json_str(answer, "key", qkey, sizeof(qkey))) {
            /* What the service actually said, said as such: every answer without a key reads the same way here, so a
               refusal (429, 503, a version the service will not pair) was all of them reported as an empty queue. */
            char why[96] = "";
            json_str(answer, "error", why, sizeof(why));
            fprintf(stderr, "net: the service answered: %.200s\n", answer);
            set_state(-1, SDL_strcmp(why, "busy matches") == 0 ? "This address has made too many quick matches this hour." :
                      SDL_strcmp(why, "bad request") == 0 ? "The matchmaking service would not accept this build's request." :
                      SDL_strcmp(why, "no room to give") == 0 ? "The matchmaking service had no room to give right now." :
                      why[0] != '\0' ? "The matchmaking service refused: it said \"error\"." :
                      "The matchmaking service gave no place in the queue.");
            return 0;
        }
        /* A relay address, if the service gives access to one: published while waiting, so that a joiner paired
           with this player is told about it (a host that only got its relay after the pairing cannot say so).
           What it did with the answer is written to the log: whether a pair has a relay is the difference between
           working and not, and the answer alone does not say what happened to it. */
        {
            char why[128] = "";
            json_str(answer, "noRelay", why, sizeof(why));
            set_relay_why(why); /* (this player may end up hosting the pair, and it is the host that needs one) */
            if (SDL_getenv("BT3_NO_RELAY") == NULL && json_str(answer, "server", tserver, sizeof(tserver)) &&
                json_str(answer, "username", tuser, sizeof(tuser)) && json_str(answer, "credential", tpass, sizeof(tpass))) {
                if (Relay_Allocate(sM.sock, tserver, tuser, tpass, relay, sizeof(relay)) && !STOPPED()) {
                    publish = 1;
                    relayHave = 1;
                    fprintf(stderr, "relay: taken from %s, publishing %s\n", tserver, relay);
                } else {
                    fprintf(stderr, "relay: %s gave no address\n", tserver);
                }
            } else {
                fprintf(stderr, "relay: the service gave no access%s%s\n", why[0] != '\0' ? ": " : "", why);
            }
        }
        for (i = 0; i < 130 && !STOPPED(); i++) { /* the service forgets a waiting player after two minutes */
            int matched = json_int(answer, "matched");
            if (matched != 0) {
                if (!json_str(answer, "code", sM.code, sizeof(sM.code)) || !json_str(answer, "key", key, sizeof(key)) ||
                    !json_peer(answer, &st)) {
                    set_state(-1, "The matchmaking service made a pair it could not describe.");
                    return 0;
                }
                st.role = matched; /* which side of the pair this player took (1 hosts, 2 joins) */
                if (matched == 1) { /* this player waited: it hosts the pair, and the room is already there */
                    sM.role = 1;
                    goto host_room;
                }
                sM.role = 2;
                goto joined;
            }
            SDL_LockMutex(sM.lock);
            sM.st.waiting = json_int(answer, "waiting");
            sM.st.queued = json_int(answer, "queue");
            SDL_UnlockMutex(sM.lock);
            if (publish) { /* once: the relay address goes into the queue entry, for whoever is paired with it */
                SDL_snprintf(qbody, sizeof(qbody), "{\"key\":\"%s\",\"addrs\":[%s,\"relay=%s\"]}", qkey, addrs, relay);
                publish = 0;
                if (http_post("queue", qbody, answer, sizeof(answer))) {
                    continue; /* that answer is the next poll's */
                }
            }
            for (n = 0; n < 20 && !STOPPED(); n++) { /* every two seconds */
                SDL_Delay(100);
            }
            if (STOPPED()) {
                SDL_snprintf(body, sizeof(body), "{\"key\":\"%s\"}", qkey);
                http_post("qcancel", body, answer, sizeof(answer));
                return 0;
            }
            SDL_snprintf(body, sizeof(body), "{\"key\":\"%s\"}", qkey);
            if (!http_post("queue", body, answer, sizeof(answer))) {
                set_state(-1, "The matchmaking service could not be reached (is the network up?).");
                return 0;
            }
        }
        if (STOPPED()) {
            SDL_snprintf(body, sizeof(body), "{\"key\":\"%s\"}", qkey);
            http_post("qcancel", body, answer, sizeof(answer));
            return 0;
        }
        set_state(-1, "Nobody was waiting to be paired. Try again later, or host a match with a code.");
        return 0;
    }
    if (sM.role == 1) {
        SDL_snprintf(body, sizeof(body), "{\"v\":%d,\"name\":\"%s\",\"addrs\":[%s]}", sM.version, name, addrs);
        if (!http_post("host", body, answer, sizeof(answer))) {
            set_state(-1, "The matchmaking service could not be reached (is the network up?).");
            return 0;
        }
        if (STOPPED()) {
            return 0;
        }
        if (!json_str(answer, "code", sM.code, sizeof(sM.code)) || !json_str(answer, "key", key, sizeof(key))) {
            fprintf(stderr, "net: the service answered: %.200s\n", answer);
            /* The service says which limit: "busy hosts" is its hourly count of rooms, "busy rooms" is matches this
               address already has running. Asking again is never refused for the room you made a moment ago (the
               service supersedes it), so neither is worth waiting out - a minute later it will be gone anyway. */
            set_state(-1, SDL_strstr(answer, "busy hosts") != NULL ? "This address has made too many rooms this hour. Try again later." :
                      SDL_strstr(answer, "busy rooms") != NULL ? "Four matches from this address are already running. Finish one, or try again shortly." :
                      "The matchmaking service gave no room.");
            return 0;
        }
        host_room:
        /* The code is shown from here on, before the relay is taken. Taking one can take seconds - a coturn that does
           not answer costs the whole seven - and a code the player cannot read is worse than one whose relay address
           is a moment late: the host publishes the relay with /v1/addrs as soon as it has one, and the joiner is told
           of it in the poll that follows. */
        SDL_LockMutex(sM.lock);
        SDL_strlcpy(sM.st.code, sM.code, sizeof(sM.st.code)); /* (shown from here on) */
        sM.st.state = 2;
        SDL_UnlockMutex(sM.lock);
        {
            /* A relay address, if the service gives access to one: published with the others, for a player who
               cannot reach this one directly. */
            char server[128], user[160], pass[160], relay[64], why[128] = "";
            json_str(answer, "noRelay", why, sizeof(why));
            if (why[0] != '\0') {
                set_relay_why(why); /* (only an answer that carries one: the quick match lands here with the
                                       pairing answer, which has no relay field and would clear what it said) */
            }
            if (relayHave != 0) {
                fprintf(stderr, "relay: an address is held, taken before the pairing\n");
            } else if (SDL_getenv("BT3_NO_RELAY") == NULL && json_str(answer, "server", server, sizeof(server)) &&
                       json_str(answer, "username", user, sizeof(user)) && json_str(answer, "credential", pass, sizeof(pass))) {
                if (Relay_Allocate(sM.sock, server, user, pass, relay, sizeof(relay)) && !STOPPED()) {
                    relayHave = 1;
                    fprintf(stderr, "relay: taken from %s, the address is %s\n", server, relay);
                    if (sM.role != 3) { /* (quick match published it into the queue, and the room has it already) */
                        SDL_snprintf(body, sizeof(body), "{\"code\":\"%s\",\"key\":\"%s\",\"addrs\":[%s,\"relay=%s\"]}", sM.code, key, addrs, relay);
                        http_post("addrs", body, answer, sizeof(answer));
                    }
                } else {
                    fprintf(stderr, "relay: %s gave no address\n", server);
                }
            } else {
                /* No relay login in this answer - and on the quick match path that is not news: it is the answer
                   that pairs the pair, which carries no relay fields at all. Say so rather than "no access". */
                fprintf(stderr, "relay: no access%s%s%s\n", why[0] != '\0' ? ": " : "",
                        why[0] != '\0' ? why : "", why[0] != '\0' ? "" : " (none in this answer)");
            }
        }
        if (STOPPED()) {
            SDL_snprintf(body, sizeof(body), "{\"code\":\"%s\",\"key\":\"%s\"}", sM.code, key);
            http_post("leave", body, answer, sizeof(answer));
            return 0;
        }
        SDL_snprintf(body, sizeof(body), "{\"code\":\"%s\",\"key\":\"%s\"}", sM.code, key);
        for (i = 0; i < 165 && !STOPPED(); i++) { /* a room lasts ten minutes */
            int k;
            /* asked every two seconds in the first minute, every four after it (the service's daily requests) */
            for (k = 0; k < (i < 30 ? 20 : 40) && !STOPPED(); k++) {
                SDL_Delay(100);
            }
            if (STOPPED()) {
                break;
            }
            if (http_post("poll", body, answer, sizeof(answer))) {
                MatchStatus st;
                SDL_memset(&st, 0, sizeof(st));
                if (json_peer(answer, &st)) {
                    if (STOPPED()) {
                        break;
                    }
                    SDL_LockMutex(sM.lock);
                    SDL_strlcpy(sM.st.peerName, st.peerName, sizeof(sM.st.peerName));
                    SDL_memcpy(sM.st.peerAddr, st.peerAddr, sizeof(st.peerAddr));
                    sM.st.peerCount = st.peerCount;
                    sM.st.role = 1; /* (this player is the one that was waiting, so it hosts: the lobby knocks and
                                       answers the greeting, instead of both sides greeting and neither answering) */
                    sM.st.state = 3;
                    SDL_UnlockMutex(sM.lock);
                    remember_room(key); /* (the room this match was found in, for the record) */
                    http_post("leave", body, answer, sizeof(answer)); /* the room has done its work: it is given up at once */
                    return 0;
                }
                if (SDL_strstr(answer, "no room") != NULL) {
                    break;
                }
            }
        }
        if (STOPPED()) {
            http_post("leave", body, answer, sizeof(answer));
            return 0;
        }
        set_state(-1, "Nobody joined: the room code has run out. Host again for a new one.");
    } else {
        SDL_snprintf(body, sizeof(body), "{\"v\":%d,\"code\":\"%s\",\"name\":\"%s\",\"addrs\":[%s]}", sM.version, sM.code, name, addrs);
        if (!http_post("join", body, answer, sizeof(answer))) {
            set_state(-1, "The matchmaking service could not be reached (is the network up?).");
            return 0;
        }
        (void)you;
        SDL_memset(&st, 0, sizeof(st));
        if (!json_peer(answer, &st)) {
            fprintf(stderr, "net: the service answered: %.200s\n", answer);
            set_state(-1, SDL_strstr(answer, "version") != NULL ? "The host's game is a different version: both players need the same release." :
                          SDL_strstr(answer, "full") != NULL ? "Someone has already joined that room." :
                          SDL_strstr(answer, "no room") != NULL ? "No room with that code (codes last ten minutes)." :
                          SDL_strstr(answer, "busy") != NULL ? "Too many wrong codes from this address; try again in an hour." : "The matchmaking service gave no answer.");
            return 0;
        }
        if (STOPPED()) {
            return 0;
        }
joined:
        SDL_LockMutex(sM.lock);
        SDL_strlcpy(sM.st.peerName, st.peerName, sizeof(sM.st.peerName));
        SDL_memcpy(sM.st.peerAddr, st.peerAddr, sizeof(st.peerAddr));
        sM.st.peerCount = st.peerCount;
        sM.st.role = st.role; /* (0 unless quick match told it: the lobby greets as the joiner, knocks as the host) */
        sM.st.state = 3;
        SDL_UnlockMutex(sM.lock);
        remember_room(key); /* (the room this match was found in, for the record) */
    }
    return 0;
}

/* ---- for the lobby (net.c) ---- */

/* Starts looking: role 1 makes a room, role 2 joins room `code`. `sock` is the match's own UDP socket (not blocking),
   bound to `localPort`. */
void Match_Begin(int role, int sock, int localPort, const char *code, const char *name, int version) {
    int i, n = 0;

    ask_firewall(); /* (Windows: the one prompt that lets another game reach this one - see above) */

    if (sM.lock == NULL) {
        sM.lock = SDL_CreateMutex();
    }
    if (sM.thread != NULL) {
        SDL_DetachThread(sM.thread); /* (it stops by itself: its search number is no longer the current one) */
        sM.thread = NULL;
    }
    SDL_AddAtomicInt(&sM.gen, 1);
    SDL_SetAtomicInt(&sM.cancel, 0);
    SDL_memset(&sM.st, 0, sizeof(sM.st));
    sM.st.state = 1;
    sM.role = role;
    sM.sock = sock;
    sM.localPort = localPort;
    sM.version = version;
    sM.code[0] = '\0';
    if (role == 3) {
        SDL_strlcpy(sM.name, name != NULL ? name : "Player", sizeof(sM.name)); /* (quick match has no room code) */
        sM.thread = SDL_CreateThread(match_thread, "bt3-match", (void *)(intptr_t)SDL_GetAtomicInt(&sM.gen));
        if (sM.thread == NULL) {
            set_state(-1, "The matchmaking could not be started.");
        }
        return;
    }
    for (i = 0; code != NULL && code[i] != '\0' && n < 6; i++) { /* letters and digits, capitals */
        char c = code[i];
        if (c >= 'a' && c <= 'z') {
            c = (char)(c - 'a' + 'A');
        }
        if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
            sM.code[n++] = c;
        }
    }
    sM.code[n] = '\0';
    SDL_strlcpy(sM.name, name != NULL ? name : "Player", sizeof(sM.name));
    sM.thread = SDL_CreateThread(match_thread, "bt3-match", (void *)(intptr_t)SDL_GetAtomicInt(&sM.gen));
    if (sM.thread == NULL) {
        set_state(-1, "The matchmaking could not be started.");
    }
}

/* Where it stands. */
void Match_Poll(MatchStatus *out) {
    if (sM.lock == NULL) {
        SDL_memset(out, 0, sizeof(*out));
        return;
    }
    SDL_LockMutex(sM.lock);
    *out = sM.st;
    SDL_UnlockMutex(sM.lock);
}

/* Stops looking (and, for a host, gives the room up). Does not wait for the thread: a request to the Worker that is
   under way ends by itself. */
void Match_Cancel(void) {
    if (sM.thread != NULL) {
        SDL_SetAtomicInt(&sM.cancel, 1);
        SDL_DetachThread(sM.thread);
        sM.thread = NULL;
    }
    if (sM.lock != NULL) {
        SDL_LockMutex(sM.lock);
        sM.st.state = 0;
        SDL_UnlockMutex(sM.lock);
    }
}
