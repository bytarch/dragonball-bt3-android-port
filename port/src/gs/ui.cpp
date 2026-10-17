// The settings window of the PC build: Dear ImGui over the finished picture. F1 opens and closes it, or START + SELECT
// of a controller, or the key and button of its Controls list (gs_input.c: PortInput_Hotkey).
// Everything it shows lives elsewhere (ui.h): the renderer's settings in gs_gpu.c, the bindings in gs_input.c.
// It runs on the render thread, which owns the window and its events.
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include "imgui.h"
#include "imgui_internal.h" // (ClearActiveID: a finger drag over a button scrolls the window)
#include "imgui_impl_sdl3.h"
#include "imgui_impl_sdlgpu3.h"
#include "imgui_impl_opengl3.h"
#include "ui.h"
#include "namefont.h"
#ifdef __ANDROID__
#include <jni.h>
#include <unistd.h>
#endif

static int sMeter = -1; // the meter (frame rate, connection): -1 not read yet
static bool meter_on(void);
static int sGL; // 1: the OpenGL back end draws the window (ImGui's OpenGL3 backend, no SDL GPU device)

extern "C" {
extern volatile int gPortNetMenuRequest;
void Port_NetOptions(int rollback, int delay); // gs/net.c
void Port_NetRulesSet(int type, int dp, int time); // gs/net.c: the host's battle type, DP limit row, time limit
void Port_NetNameSet(const char *name);         // gs/net.c: this player's name for the match
const char *Port_NetName(int player);          // the name of the player of side 0 or 1 ("" outside a match)
const char *Port_NetNameOwn(void);             // gs/net.c: this player's own name, made up and saved if there is none
void Port_NetNameOwnSave(const char *name);    // gs/net.c: stores it in bt3_settings.txt
int Port_NetRecord(int *wins, int *losses);    // gs/net_match.c: the record, -1 while it is not known yet
const char *Port_NetId(void);                  // gs/net.c: this copy's recovery code (32 hex characters)
int Port_NetIdSet(const char *code);           // gs/net.c: takes another machine's code, 1 when it was one
int Port_NetShare(int set);                    // gs/net.c: whether opponents may see this record (-1 asks)
const char *Port_NetPeerName(void);            // gs/net.c: the other player in this session, as the launcher found them
const char *Port_NetPeerId(void);              // gs/net.c: their public id (records are looked up by it)
int Port_NetPeerRecord(int *wins, int *losses);
int Port_LobbyPeerRecord(int *wins, int *losses); // the other player's record, -1 when they keep it private
void Match_AskRecord(const char *name);      // gs/net_match.c: asks the service what this copy's record is
const char *Port_LobbyPeerPlatform(void);      // "PC", "Mobile", or "" when the service has not said
int Port_NetMe(void);                          // gs/net.c: which side this copy plays (0 or 1)


/* What kind of machine this is, beside the name in the lobby: a phone or a computer. Not the system itself - which
   phone or which Windows is of no use to the other player, and the game's own release is the same either way. */
static const char *net_platform_name(void) {
#ifdef __ANDROID__
    return "Mobile";
#else
    return "PC";
#endif
}
int Port_HudSwap(void);                        // the two sides of the HUD are in each other's places on this copy
int Port_NetActive(void);
int Port_NetEmote(int id);                     // gs/net.c: says one of the quick things to the other player
int Port_NetEmoteCount(void);
const char *Port_NetEmoteName(int id);
void *BtlChar_FindByObjId(int objId);          // the game: the fighter of a side, NULL outside a fight
int Port_NetStats(int *out);                   // gs/net.c: the meter's numbers of an online match
int Port_LobbyOther(void);
extern unsigned gPortLiveBlanks;               // plat_stub.c: vertical blanks the game has gone through
int Port_RollCan(void);                        // gs/state.c
const char *Port_FileRoot(void);               // plat_file.c: the game's data folder
unsigned GsGl_StripTexture(const void *rgba, int w, int h); // gs_gl.c
void GsGl_StripTextureFree(unsigned id);
const char *PortStages_Dir(void);                          // plat_stages.c
const char *PortStages_Name(int index);
int PortStages_Count(void);
const char *PortSongs_Dir(void);                           // plat_songs.c
const char *PortSongs_Name(int index);
int PortSongs_Count(void);
int Port_Setting(const char *name, int def);   // plat_settings.c
void Port_SettingSave(const char *name, int value);
int Port_ClashSquare(void);                    // gs_input.c: Square counts as a direction in clashes
void Port_ClashSquareSet(int on);
int Port_ClashSquareX(void);                   // what a press is worth, in quarters of a point (4, 6, 7, 8)
void Port_ClashSquareXSet(int quarters);
extern int gsInterp;                           // gs_draw.c: in-between pictures on (1) / off (0); -1 before the first frame
void Port_SettingsWrite(void);
int Port_LobbyStart(int host, const char *address, int port); // gs/net.c
int Port_LobbyStartCode(int host, const char *code);         // the same with a room code (the matchmaking service)
int Port_LobbyStartQueue(void);                             // quick match: the service pairs this player with one waiting
void Port_LobbyWaiting(int *ahead, int *total);              // quick match while waiting: players ahead, players waiting
const char *Port_LobbyCode(void);                            // the host's room code, "" until it is known
const char *Port_LobbyError(void);                           // why the matchmaking failed (state -3)
const char *Port_LobbyRelayWhy(void);                        // why the service gave no relay address, "" when it gave one
int Port_LobbyRelayed(void);                                 // the other player was reached through the relay
int Port_LobbyPoll(void);
void Port_LobbyCancel(void);
void Port_LobbyLaunch(void);
}
static bool sReady, sOpen;
static bool sNet;                              // the open window is the online screen (Dragon Net Battle), not the settings
static int sForceTab = -1;                     // BT3_UI_OPEN=<tab>: open at start on that tab (testing)
static int sCapKind, sCapPlayer, sCapAction;   // waiting for a key (1) or a controller button (2) to bind
static SDL_GPUDevice *sDevice;

/* Name strips (gamedata/stages/names.rgba and gamedata/songs/names.rgba): the names of the stages and tracks added
   from outside the disc, drawn by the port over the menu in the game's own lettering. Raw RGBA, a 12-byte header
   (w, h, count) and then `count` images of w x h stacked. */
static SDL_GPUTexture *sNameTex, *sSongTex;
static uint32_t sNameW, sNameH, sNameCount, sSongW, sSongH, sSongCount;

static uint8_t *read_strip(const char *path, size_t *bytes, uint32_t *w, uint32_t *h, uint32_t *count) {
    FILE *fp = fopen(path, "rb");
    uint32_t hdr[3];
    uint8_t *pix;

    if (fp == NULL) {
        return NULL;
    }
    if (fread(hdr, sizeof(hdr), 1, fp) != 1 || hdr[0] == 0 || hdr[1] == 0 || hdr[2] == 0) {
        fclose(fp);
        return NULL;
    }
    *w = hdr[0];
    *h = hdr[1];
    *count = hdr[2];
    *bytes = (size_t)hdr[0] * hdr[1] * hdr[2] * 4;
    pix = (uint8_t *)malloc(*bytes);
    if (pix == NULL || fread(pix, 1, *bytes, fp) != *bytes) {
        free(pix);
        fclose(fp);
        return NULL;
    }
    fclose(fp);
    return pix;
}

static SDL_GPUTexture *make_strip(uint8_t *pix, size_t bytes, uint32_t w, uint32_t h, uint32_t count) {
    SDL_GPUTextureCreateInfo ci;
    SDL_GPUTransferBufferCreateInfo tbi;
    SDL_GPUTransferBuffer *tb;
    SDL_GPUCommandBuffer *cmd;
    SDL_GPUCopyPass *cp;
    SDL_GPUTextureTransferInfo src;
    SDL_GPUTextureRegion dst;
    SDL_GPUTexture *tex;
    void *map;

    if (sGL) { // the OpenGL back end: a GL texture, its name carried in the pointer (ImGui's OpenGL3 backend takes it so)
        unsigned id = GsGl_StripTexture(pix, (int)w, (int)(h * count));
        free(pix);
        return (SDL_GPUTexture *)(intptr_t)id;
    }
    SDL_zero(ci);
    ci.type = SDL_GPU_TEXTURETYPE_2D;
    ci.format = SDL_GPU_TEXTUREFORMAT_R8G8B8A8_UNORM;
    ci.width = w;
    ci.height = h * count;
    ci.layer_count_or_depth = 1;
    ci.num_levels = 1;
    ci.usage = SDL_GPU_TEXTUREUSAGE_SAMPLER;
    tex = SDL_CreateGPUTexture(sDevice, &ci);
    if (tex == NULL) {
        free(pix);
        return NULL;
    }
    SDL_zero(tbi);
    tbi.usage = SDL_GPU_TRANSFERBUFFERUSAGE_UPLOAD;
    tbi.size = (Uint32)bytes;
    tb = SDL_CreateGPUTransferBuffer(sDevice, &tbi);
    map = SDL_MapGPUTransferBuffer(sDevice, tb, false);
    memcpy(map, pix, bytes);
    SDL_UnmapGPUTransferBuffer(sDevice, tb);
    free(pix);
    cmd = SDL_AcquireGPUCommandBuffer(sDevice);
    cp = SDL_BeginGPUCopyPass(cmd);
    SDL_zero(src);
    src.transfer_buffer = tb;
    src.pixels_per_row = w;
    src.rows_per_layer = h * count;
    SDL_zero(dst);
    dst.texture = tex;
    dst.w = w;
    dst.h = h * count;
    dst.d = 1;
    SDL_UploadToGPUTexture(cp, &src, &dst, false);
    SDL_EndGPUCopyPass(cp);
    SDL_SubmitGPUCommandBuffer(cmd);
    SDL_ReleaseGPUTransferBuffer(sDevice, tb);
    return tex;
}

static SDL_GPUTexture *load_strip(const char *env, const char *rel, uint32_t *w, uint32_t *h, uint32_t *count,
                                  const char *what) {
    const char *paths[3];
    uint8_t *pix = NULL;
    size_t bytes = 0;
    SDL_GPUTexture *tex;
    uint32_t i;

    char inData[600];
    // in the stages / songs folder (plat_extras.c); the environment variable names another file
    snprintf(inData, sizeof(inData), "%s", rel);
    paths[0] = getenv(env);
    paths[1] = inData;
    paths[2] = NULL;
    for (i = 0; i < 3 && pix == NULL; i++) {
        if (paths[i] != NULL) {
            pix = read_strip(paths[i], &bytes, w, h, count);
        }
    }
    if (pix == NULL) {
        return NULL;
    }
    tex = make_strip(pix, bytes, *w, *h, *count);
    if (tex != NULL) {
        fprintf(stderr, "bt3: %s overlay: %u names, %ux%u each\n", what, *count, *w, *h);
    }
    return tex;
}

static void load_strips(void) {
    char path[600];
    // The names of added stages and songs are drawn by this overlay, over the menu: from a strip of pre-rendered
    // names if the install script made one (names.rgba in the folder), else as text (frame_build). Either way the
    // game leaves the name to the overlay, so the flags are set whenever the overlay exists.
    snprintf(path, sizeof(path), "%s/names.rgba", PortStages_Dir());
    sNameTex = PortStages_Dir()[0] != '\0' ? load_strip("BT3_STAGE_NAMES", path, &sNameW, &sNameH, &sNameCount, "stage-name") : NULL;
    snprintf(path, sizeof(path), "%s/names.rgba", PortSongs_Dir());
    sSongTex = PortSongs_Dir()[0] != '\0' ? load_strip("BT3_SONG_NAMES", path, &sSongW, &sSongH, &sSongCount, "song-name") : NULL;
    if (sNameTex == NULL) { sNameCount = 0; }
    if (sSongTex == NULL) { sSongCount = 0; }
    gUiNameReady = 1;
    gUiSongReady = 1;
}

static void style() {
    ImGuiStyle &st = ImGui::GetStyle();
    ImGui::StyleColorsDark();
    st.WindowRounding = 10.0f;
    st.FrameRounding = 6.0f;
    st.GrabRounding = 6.0f;
    st.TabRounding = 6.0f;
    st.PopupRounding = 6.0f;
    st.WindowPadding = ImVec2(18.0f, 16.0f);
    st.FramePadding = ImVec2(10.0f, 6.0f);
    st.ItemSpacing = ImVec2(10.0f, 9.0f);
    st.WindowBorderSize = 0.0f;
    st.WindowTitleAlign = ImVec2(0.5f, 0.5f);
    ImVec4 *c = st.Colors;
    const ImVec4 accent(0.96f, 0.55f, 0.13f, 1.0f), accentDim(0.96f, 0.55f, 0.13f, 0.55f);
    c[ImGuiCol_WindowBg] = ImVec4(0.07f, 0.08f, 0.11f, 0.96f);
    c[ImGuiCol_TitleBg] = c[ImGuiCol_TitleBgActive] = ImVec4(0.10f, 0.12f, 0.17f, 1.0f);
    c[ImGuiCol_FrameBg] = ImVec4(0.15f, 0.17f, 0.23f, 1.0f);
    c[ImGuiCol_FrameBgHovered] = ImVec4(0.21f, 0.24f, 0.32f, 1.0f);
    c[ImGuiCol_FrameBgActive] = ImVec4(0.26f, 0.29f, 0.38f, 1.0f);
    c[ImGuiCol_Button] = ImVec4(0.17f, 0.20f, 0.27f, 1.0f);
    c[ImGuiCol_ButtonHovered] = accentDim;
    c[ImGuiCol_ButtonActive] = accent;
    c[ImGuiCol_CheckMark] = c[ImGuiCol_SliderGrab] = c[ImGuiCol_SliderGrabActive] = accent;
    c[ImGuiCol_Header] = ImVec4(0.96f, 0.55f, 0.13f, 0.30f);
    c[ImGuiCol_HeaderHovered] = accentDim;
    c[ImGuiCol_HeaderActive] = accent;
    c[ImGuiCol_Tab] = ImVec4(0.13f, 0.15f, 0.20f, 1.0f);
    c[ImGuiCol_TabHovered] = accentDim;
    c[ImGuiCol_TabSelected] = ImVec4(0.96f, 0.55f, 0.13f, 0.80f);
    c[ImGuiCol_PopupBg] = ImVec4(0.09f, 0.10f, 0.14f, 0.98f);
    c[ImGuiCol_TableRowBgAlt] = ImVec4(1.0f, 1.0f, 1.0f, 0.03f);
}

int Ui_Init(SDL_Window *window, SDL_GPUDevice *device, void *gl_context) {
    static const char *fonts[] = { // a proportional system font if there is one; else the library's built-in font
        "/usr/share/fonts/TTF/DejaVuSans.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/noto/NotoSans-Regular.ttf", "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf",
        "/usr/share/fonts/liberation/LiberationSans-Regular.ttf", "C:\\Windows\\Fonts\\segoeui.ttf",
        "/system/fonts/Roboto-Regular.ttf", "/system/fonts/NotoSans-Regular.ttf", // Android
    };
    ImGui_ImplSDLGPU3_InitInfo info;

    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGuiIO &io = ImGui::GetIO();
    io.IniFilename = NULL; // no window-layout file next to the game
    io.ConfigFlags |= ImGuiConfigFlags_NavEnableKeyboard | ImGuiConfigFlags_NavEnableGamepad; // (a controller opens it too: START + SELECT)
    style();
#ifdef __ANDROID__
    // a phone's or a handheld's dense screen, touched or driven with the controller: everything larger, and the
    // controller moves through the window (the back button opens and closes it, gs_gpu.c)
    io.ConfigFlags |= ImGuiConfigFlags_NavEnableGamepad;
    io.BackendFlags |= ImGuiBackendFlags_HasGamepad;
    float uiScale = SDL_GetWindowDisplayScale(window);
    if (uiScale < 1.5f) {
        uiScale = 1.5f;
    }
    ImGui::GetStyle().ScaleAllSizes(uiScale);
#else
    float uiScale = 1.0f;
#endif
    for (size_t i = 0; i < sizeof(fonts) / sizeof(fonts[0]); i++) {
        FILE *fp = fopen(fonts[i], "rb");
        if (fp != NULL) {
            fclose(fp);
            io.Fonts->AddFontFromFileTTF(fonts[i], 18.0f * uiScale);
            break;
        }
    }
    if (device == NULL) { // the OpenGL back end (gs_gl.c), with its context current
        sGL = 1;
        if (!ImGui_ImplSDL3_InitForOpenGL(window, (SDL_GLContext)gl_context) || !ImGui_ImplOpenGL3_Init(
#ifdef __ANDROID__
                                                                                     "#version 300 es"
#else
                                                                                     "#version 330 core"
#endif
                                                                                     )) {
            return 0;
        }
    } else {
        if (!ImGui_ImplSDL3_InitForSDLGPU(window)) {
            return 0;
        }
        info.Device = device;
        info.ColorTargetFormat = SDL_GetGPUSwapchainTextureFormat(device, window);
        info.MSAASamples = SDL_GPU_SAMPLECOUNT_1;
        if (!ImGui_ImplSDLGPU3_Init(&info)) {
            return 0;
        }
    }
    sDevice = device;
    sReady = true;
    load_strips(); /* the stage- and song-name overlays; the ready flags stay 0 if there is no strip */
    if (getenv("BT3_UI_OPEN") != NULL) {
        sForceTab = atoi(getenv("BT3_UI_OPEN"));
        if (sForceTab == 9) { // testing: the online screen
            sForceTab = -1;
            gPortNetMenuRequest = 1;
        }
        sOpen = true;
        gPortOverlayOpen = 1;
    }
    return 1;
}

static void set_open(bool open) {
    sOpen = open;
    sNet = false;
    sCapKind = 0;
    gPortInputCapture = 0;
    gPortOverlayOpen = open;
}

// Dragon Net Battle: the screen behind the main menu's entry. For now only its shape: what a player fills in to
// host or join. Nothing is sent anywhere yet.
extern "C" {
extern volatile int gPortNetMenuRequest;
extern volatile int gPortNetWindowClose; // gs/state.c
}

static void net_open(void) {
    set_open(true);
    sNet = true;
    gPortInputCapture = 1; // the game takes no input at all while this is open (it sits on the main menu behind it)
}

static void build_net(void) {
    static char name[17] = "";
    static int tab, roll = -1, delay = 1, battle = -1, timeLimit = 3;
    static char roomCode[8] = "";     // the code to join with
    static int lastState = 0;         // what the lobby said last (for its messages after a failure)
    static Uint32 sQueueSince;        // when the last quick match was asked for (the window counts the seconds)
    static int mode = Port_Setting("net_mode", 0); /* 0 a room code (host or join), 1 quick match */
    ImGuiIO &io = ImGui::GetIO();
    bool open = true;

    ImGui::SetNextWindowPos(ImVec2(io.DisplaySize.x * 0.5f, io.DisplaySize.y * 0.5f), ImGuiCond_Appearing, ImVec2(0.5f, 0.5f));
    ImGui::SetNextWindowSize(ImVec2(560.0f, 0.0f), ImGuiCond_Appearing);
    if (ImGui::Begin("Dragon Net Battle", &open, ImGuiWindowFlags_NoCollapse | ImGuiWindowFlags_AlwaysAutoResize)) {
        ImGui::TextWrapped("Fight a player on another device. The two games find each other through the matchmaking service, "
                          "then talk to each other directly.");
        ImGui::Spacing();
        ImGui::SetNextItemWidth(260.0f);
        if (name[0] == '\0') { /* (the saved name, or a made-up one the first time: it is never "Player" twice) */
            const char *own = Port_NetNameOwn();
            SDL_snprintf(name, sizeof(name), "%s", own != NULL ? own : "Player");
        }
        if (ImGui::InputText("Player name", name, sizeof(name))) {
            /* The other player is shown this name, so it is saved as it is typed rather than when a match starts. */
            if (name[0] != '\0') {
                Port_NetNameOwnSave(name);
            }
        }
        ImGui::SameLine();
        ImGui::TextDisabled("%s", net_platform_name());
        ImGui::Spacing();
        {   /* which way a match is found: with a code you tell each other, or with quick match the service pairs
               whoever is waiting. Both end in the same room and the same session; only the beginning differs. */
            static const char *const kMode[] = {"Room code", "Quick match"};
            ImGui::SetNextItemWidth(260.0f);
            if (ImGui::Combo("Find a match", &mode, kMode, 2)) {
                Port_SettingSave("net_mode", mode);
                Port_SettingsWrite();
            }
            if (ImGui::IsItemHovered()) {
                ImGui::SetTooltip("Room code: one of you hosts and tells the other a short code, which the other types in.\n"
                                  "Quick match: press one button and the service pairs you with whoever is waiting.\n"
                                  "Both need the same release of the game.");
            }
        }
        ImGui::Spacing();
        if (mode == 0 && ImGui::BeginTabBar("net")) {
            if (ImGui::BeginTabItem("Host")) {
                static const char *const kRoll[] = {"Off (wait for each other)", "Up to 2 frames", "Up to 4 frames", "Up to 6 frames", "Up to 8 frames"};
                tab = 0;
                if (roll < 0) { // the choices of last time (new names: the first ones' defaults were 4 frames and delay 1)
                    roll = Port_RollCan() ? Port_Setting("net_rollback2", 8) / 2 : 0;
                    delay = Port_Setting("net_delay2", -1) + 1; // 0 = automatic, else the delay + 1
                    if (roll < 0 || roll > 4) { roll = 4; }
                    if (delay < 0 || delay > 7) { delay = 0; }
                }
                ImGui::BeginDisabled(!Port_RollCan());
                ImGui::SetNextItemWidth(260.0f);
                ImGui::Combo("Rollback", &roll, kRoll, 5);
                ImGui::EndDisabled();
                if (ImGui::IsItemHovered(ImGuiHoveredFlags_AllowWhenDisabled)) {
                    ImGui::SetTooltip("With rollback your own moves come out at once and the game corrects itself when the\n"
                                      "other player's buttons arrive. More frames cope with a slower connection and cost\n"
                                      "more processor time. Off: both games wait for each other every frame.");
                }
                ImGui::SetNextItemWidth(260.0f);
                {
                    static const char *const kDelay[] = {"Automatic (from the connection)", "0 frames", "1 frame", "2 frames", "3 frames",
                                                         "4 frames", "5 frames", "6 frames"};
                    ImGui::Combo("Input delay", &delay, kDelay, 8);
                }
                if (ImGui::IsItemHovered()) {
                    ImGui::SetTooltip("How long after a press your own move comes out. A slow connection needs more, or the game\n"
                                      "has to guess too far ahead and stutters. Automatic measures the connection when the match\n"
                                      "starts: 1 frame on a good one, more on a slow one.");
                }
                {
                    // The match's rules: the game's own versus menu is not shown online, so they are chosen here.
                    static const char *const kBattle[] = {"Single battle", "Team battle", "DP battle (10 DP)", "DP battle (15 DP)", "DP battle (20 DP)"};
                    static const char *const kTime[] = {"60 seconds", "90 seconds", "180 seconds", "240 seconds", "No limit"};
                    if (battle < 0) {
                        battle = Port_Setting("net_battle", 0);
                        timeLimit = Port_Setting("net_time", 3);
                        if (battle < 0 || battle > 4) { battle = 0; }
                        if (timeLimit < 0 || timeLimit > 4) { timeLimit = 3; }
                    }
                    ImGui::Spacing();
                    ImGui::SetNextItemWidth(260.0f);
                    ImGui::Combo("Battle", &battle, kBattle, 5);
                    ImGui::SetNextItemWidth(260.0f);
                    ImGui::Combo("Time limit", &timeLimit, kTime, 5);
                }
                ImGui::TextDisabled("The host's choices apply to both players.");
                ImGui::EndTabItem();
            }
            if (ImGui::BeginTabItem("Join")) {
                tab = 1;
                ImGui::SetNextItemWidth(120.0f);
                ImGui::InputText("Room code", roomCode, sizeof(roomCode), ImGuiInputTextFlags_CharsUppercase | ImGuiInputTextFlags_CharsNoBlank);
                ImGui::EndTabItem();
            }
            ImGui::EndTabBar();
        }
        ImGui::Spacing();
        {
            int state = Port_LobbyPoll();
            if (state == 2) {
                Port_LobbyLaunch(); // the other player is there: the game turns into the session at its next blank
                set_open(false);
                sNet = false;
            }
            bool byCode = tab == 0 || roomCode[0] != '\0'; /* (both modes go through the service: there is no other way) */
            if (state != 0) {
                lastState = state;
            }
            if (mode == 1) { /* quick match: the service is looking for another player of this release */
                if (state == 3 || state == 1) {
                    int ahead = 0, total = 0, secs;
                    Port_LobbyWaiting(&ahead, &total);
                    secs = sQueueSince != 0 ? (int)((SDL_GetTicks() - sQueueSince) / 1000u) : 0;
                    /* The lobby is at 3 while the service still has this player - in the queue, or taking a relay
                       address - and at 1 once the other player is known and the two are finding each other. */
                    if (state == 1) {
                        ImGui::TextUnformatted("Paired. Connecting to the other player...");
                    } else {
                        ImGui::TextUnformatted("You are in the quick match queue.");
                        if (total > 1) {
                            ImGui::Text("Waiting for another player: %d in the queue, you are number %d.", total, ahead + 1);
                        } else {
                            ImGui::TextUnformatted("Nobody else is waiting yet.");
                        }
                        ImGui::TextDisabled("Looking for another player of this release, %d second%s so far.",
                                            secs, secs == 1 ? "" : "s");
                        if (total < 2) {
                            ImGui::TextWrapped("Two phones on one wifi are paired with each other too: press Find "
                                               "opponent on both, and nothing else to do.");
                        }
                    }
                    if (ImGui::Button("Cancel", ImVec2(120.0f, 0.0f))) {
                        Port_LobbyCancel();
                        lastState = 0;
                    }
                } else {
                    if (lastState == -3) {
                        ImGui::TextWrapped("%s", Port_LobbyError());
                    } else if (lastState == -4) {
                        ImGui::TextWrapped("The two games could not reach each other. Try again, or host a match with a room code.");
                    }
                    if (ImGui::Button("Find opponent", ImVec2(200.0f, 0.0f))) {
                        if (roll >= 0) {
                            Port_NetOptions(roll * 2, delay == 0 ? -2 : delay - 1);
                            if (battle >= 0) {
                                Port_NetRulesSet(battle == 0 ? 0 : battle == 1 ? 1 : 2, battle >= 2 ? battle - 2 : 0, timeLimit);
                            }
                        } else {
                            Port_NetOptions(-1, -1); /* (the pair's host sends its own; this is only a fallback) */
                        }
                        Port_NetNameSet(name);
                        lastState = 0;
                        sQueueSince = SDL_GetTicks();
                        Port_LobbyStartQueue();
                    }
                    ImGui::SameLine();
                    if (ImGui::Button("Back", ImVec2(120.0f, 0.0f))) {
                        open = false;
                    }
                    ImGui::TextDisabled("Paired with whoever is waiting for a match on this release.");
                }
            } else if (state == 3 || (state == 1 && byCode)) { // a room code: asking the service, then reaching the other player
                if (tab == 0 && Port_LobbyCode()[0] != '\0') {
                    ImGui::TextUnformatted("Room code:");
                    ImGui::SameLine();
                    ImGui::SetWindowFontScale(1.8f);
                    ImGui::TextColored(ImVec4(1.0f, 0.85f, 0.3f, 1.0f), "%s", Port_LobbyCode());
                    ImGui::SetWindowFontScale(1.0f);
                    ImGui::TextUnformatted(state == 3 ? "Tell the other player this code. Waiting for them to join..." : "The other player has joined. Connecting...");
                    if (Port_LobbyRelayWhy()[0] != '\0' && tab == 0) {
                        ImGui::TextDisabled("No relay for this address: %s", Port_LobbyRelayWhy());
                        ImGui::TextDisabled("A player whose router will not let the other in directly cannot be reached.");
                    }
                } else {
                    ImGui::TextUnformatted(state == 1 ? "Found the room. Connecting to the host..." : tab == 0 ? "Getting a room code..." : "Looking for the room...");
                }
                if (ImGui::Button("Cancel", ImVec2(120.0f, 0.0f))) {
                    Port_LobbyCancel();
                    lastState = 0;
                }
            } else if (state == 1) {
                if (Port_LobbyOther()) {
                    ImGui::TextWrapped("A player with a different version of the game is trying to join. Both need the same release.");
                } else {
                    ImGui::TextUnformatted("Waiting for the other player...");
                }
                if (ImGui::Button("Cancel", ImVec2(120.0f, 0.0f))) {
                    Port_LobbyCancel();
                }
            } else {
                if (lastState == -3) {
                    ImGui::TextWrapped("%s", Port_LobbyError());
                } else if (lastState == -4) {
                    ImGui::TextWrapped("The two games could not reach each other, directly or through the relay. "
                                       "Try again, or use quick match.");
                }
                bool can = tab == 0 || strlen(roomCode) == 6;
                ImGui::BeginDisabled(!can);
                if (ImGui::Button(tab == 0 ? "Host a match" : "Join the match", ImVec2(200.0f, 0.0f))) {
                    if (tab == 0 && roll >= 0) {
                        Port_NetOptions(roll * 2, delay == 0 ? -2 : delay - 1);
                        if (battle >= 0) {
                            Port_NetRulesSet(battle == 0 ? 0 : battle == 1 ? 1 : 2, battle >= 2 ? battle - 2 : 0, timeLimit);
                            Port_SettingSave("net_battle", battle);
                            Port_SettingSave("net_time", timeLimit);
                        }
                        Port_SettingSave("net_rollback2", roll * 2);
                        Port_SettingSave("net_delay2", delay - 1);
                        Port_SettingsWrite();
                    } else {
                        Port_NetOptions(-1, -1); // joining: the host's choices arrive with its answer
                    }
                    Port_NetNameSet(name);
                    lastState = 0;
                    Port_LobbyStartCode(tab == 0, roomCode); /* always through the service: no port, no address */
                }
                ImGui::EndDisabled();
                ImGui::SameLine();
                if (ImGui::Button("Back", ImVec2(120.0f, 0.0f))) {
                    open = false;
                }
                if (state == -2) {
                    ImGui::TextWrapped("The host's game is a different version. Both players need the same release.");
                }
            }
        }
        ImGui::Spacing();
        ImGui::TextDisabled("When you are connected the game switches to the match: both players get the same roster,");
        ImGui::TextDisabled("with everything unlocked, and your own save is left alone. Leaving the match brings you");
        ImGui::TextDisabled("back to where you were. The host's controls also work the menus.");
    }
    ImGui::End();
    if (!open) {
        Port_LobbyCancel();
        set_open(false);
    }
}

void Ui_Toggle(void) {
    if (sReady) {
        set_open(!sOpen);
    }
}

// The game waits while the settings window is open (plat_stub.c), except behind the online screen and in a match.
int Ui_Pauses(void) {
    return sReady && sOpen && !sNet;
}

// Any of the windows is open (Android: the on-screen controller steps aside, so touches reach the window).
int Ui_IsOpen(void) {
    return sReady && sOpen;
}

static void bind(int value) {
    if (sCapAction == PORT_ACTIONS) { // the row "Open this window"
        if (sCapKind == 1) {
            *PortInput_OverlayKey() = value > 0 ? value : SDL_SCANCODE_F1; // (cleared = F1 again: a keyboard always has a way in)
        } else if (value < PORT_PAD_LT) { // (a button, not a trigger)
            *PortInput_OverlayPad() = value;
        }
    } else if (sCapKind == 1) {
        PortInput_Keys(sCapPlayer)[sCapAction] = value;
    } else {
        PortInput_PadButtons(sCapPlayer)[sCapAction] = value;
    }
    PortInput_Save();
}

int Ui_Event(const SDL_Event *ev) {
    if (!sReady || !sOpen) {
        return 0;
    }
    if (sCapKind != 0) { // the next key or button is the binding; Esc leaves it as it was, Delete clears it
        bool done = false;
        if (ev->type == SDL_EVENT_KEY_DOWN) {
            if (ev->key.scancode == SDL_SCANCODE_DELETE || ev->key.scancode == SDL_SCANCODE_BACKSPACE) {
                bind(sCapKind == 1 ? 0 : -1);
            } else if (ev->key.scancode != SDL_SCANCODE_ESCAPE && sCapKind == 1) {
                bind((int)ev->key.scancode);
            }
            done = ev->key.scancode == SDL_SCANCODE_ESCAPE || ev->key.scancode == SDL_SCANCODE_DELETE ||
                   ev->key.scancode == SDL_SCANCODE_BACKSPACE || sCapKind == 1;
        } else if (sCapKind == 2 && ev->type == SDL_EVENT_GAMEPAD_BUTTON_DOWN) {
            bind((int)ev->gbutton.button);
            done = true;
        } else if (sCapKind == 2 && ev->type == SDL_EVENT_GAMEPAD_AXIS_MOTION && ev->gaxis.value > 20000 &&
                   (ev->gaxis.axis == SDL_GAMEPAD_AXIS_LEFT_TRIGGER || ev->gaxis.axis == SDL_GAMEPAD_AXIS_RIGHT_TRIGGER)) {
            bind(ev->gaxis.axis == SDL_GAMEPAD_AXIS_LEFT_TRIGGER ? PORT_PAD_LT : PORT_PAD_RT);
            done = true;
        }
        if (done) {
            sCapKind = 0;
            gPortInputCapture = 0;
        }
        if (ev->type == SDL_EVENT_KEY_DOWN || ev->type == SDL_EVENT_KEY_UP) {
            return 1;
        }
    } else if (ev->type == SDL_EVENT_KEY_DOWN && ev->key.scancode == SDL_SCANCODE_ESCAPE) {
        set_open(false);
        return 1;
    }
    if (ev->type == SDL_EVENT_FINGER_DOWN || ev->type == SDL_EVENT_FINGER_MOTION || ev->type == SDL_EVENT_FINGER_UP ||
        ev->type == SDL_EVENT_FINGER_CANCELED) {
        // A finger is the mouse, from the touch itself (the mouse events SDL makes from touches are not used): one
        // finger at a time; its position in the window's units, which are the window's (ImGui's display size).
        static SDL_FingerID finger;
        ImGuiIO &io = ImGui::GetIO();
        bool down = ev->type == SDL_EVENT_FINGER_DOWN, up = ev->type == SDL_EVENT_FINGER_UP || ev->type == SDL_EVENT_FINGER_CANCELED;
        if (down && finger == 0) {
            finger = ev->tfinger.fingerID;
        }
        if (finger == 0 || ev->tfinger.fingerID != finger) {
            return 1;
        }
        io.AddMouseSourceEvent(ImGuiMouseSource_TouchScreen);
        io.AddMousePosEvent(ev->tfinger.x * io.DisplaySize.x, ev->tfinger.y * io.DisplaySize.y);
        if (down) {
            io.AddMouseButtonEvent(0, true);
        }
        if (up) {
            io.AddMouseButtonEvent(0, false);
            finger = 0;
        }
        return 1;
    }
    if ((ev->type == SDL_EVENT_MOUSE_MOTION && ev->motion.which == SDL_TOUCH_MOUSEID) ||
        ((ev->type == SDL_EVENT_MOUSE_BUTTON_DOWN || ev->type == SDL_EVENT_MOUSE_BUTTON_UP) && ev->button.which == SDL_TOUCH_MOUSEID)) {
        return 1; // (the same touch again, as a mouse)
    }
    ImGui_ImplSDL3_ProcessEvent(ev);
    return ev->type == SDL_EVENT_KEY_DOWN || ev->type == SDL_EVENT_KEY_UP || ev->type == SDL_EVENT_TEXT_INPUT ||
           ev->type == SDL_EVENT_MOUSE_BUTTON_DOWN || ev->type == SDL_EVENT_MOUSE_BUTTON_UP || ev->type == SDL_EVENT_MOUSE_WHEEL;
}

static const char *pad_source_name(int src) {
    static const char *names[] = {"A / Cross", "B / Circle", "X / Square", "Y / Triangle", "Back / Select", "Guide", "Start",
                                  "Left stick click", "Right stick click", "Left shoulder", "Right shoulder", "D-pad up",
                                  "D-pad down", "D-pad left", "D-pad right", "Misc 1", "Right paddle 1", "Left paddle 1",
                                  "Right paddle 2", "Left paddle 2", "Touchpad"};
    static char other[24];
    if (src == PORT_PAD_LT) { return "Left trigger"; }
    if (src == PORT_PAD_RT) { return "Right trigger"; }
    if (src < 0) { return "-"; }
    if (src < (int)(sizeof(names) / sizeof(names[0]))) { return names[src]; }
    snprintf(other, sizeof(other), "Button %d", src);
    return other;
}

static bool tab(const char *label, int index) {
    ImGuiTabItemFlags flags = sForceTab == index ? ImGuiTabItemFlags_SetSelected : 0;
    return ImGui::BeginTabItem(label, NULL, flags);
}

/* Who this copy is playing as: the name the other player is shown in a match, and whether it is a phone or a
   computer. The same two the lobby window has, and the same one line of bt3_settings.txt, so a name set in either
   place is the name in both. On Android the launcher sets it before the game starts; here it is the only place on
   a PC, which is why it has a tab of its own. */
static void profile_tab(void) {
    static char name[17] = "";

    if (name[0] == '\0') {
        const char *own = Port_NetNameOwn();
        SDL_snprintf(name, sizeof(name), "%s", own != NULL ? own : "Player");
    }
    ImGui::SetNextItemWidth(260.0f);
    if (ImGui::InputText("Player name", name, sizeof(name))) {
        if (name[0] != '\0') {
            Port_NetNameOwnSave(name);
        }
    }
    ImGui::SetItemTooltip("The other player sees this in a match, beside whether you are on a phone or a computer.\n"
                          "Up to 16 characters. Kept between runs; made up for you the first time there is none.");
    ImGui::SameLine();
    ImGui::TextDisabled("%s", net_platform_name());
    ImGui::Spacing();
    {   /* The record: refresh it while this tab is visible, and show the last answer meanwhile. A known old answer is
           still better than flashing "not known" while the new POST is on its worker thread. */
        int wins = 0, losses = 0;
        Match_AskRecord(name);
        if (Port_NetRecord(&wins, &losses) == 0) {
            ImGui::Text("Record: %d won, %d lost", wins, losses);
        } else {
            ImGui::TextDisabled("Record: asking the service...");
        }
    }
    ImGui::Spacing();
    {   /* The recovery code: what carries the record to another machine. Shown, and another one can be typed in -
           which takes this copy's record over to the one that code already has, so do that first on the machine
           that still has the record. It is a secret like any password: whoever has it has the record. */
        static char code[40] = "";
        static int seeded;

        if (!seeded) { /* once: doing it every frame would wipe what is being typed */
            seeded = 1;
            SDL_strlcpy(code, Port_NetId(), sizeof(code));
        }

        ImGui::TextDisabled("Recovery code");
        ImGui::SetNextItemWidth(300.0f);
        if (ImGui::Button("Copy it to the clipboard")) {
            SDL_SetClipboardText(Port_NetId());
        }
        ImGui::SetItemTooltip("Paste this into the Profile tab (or the launcher) on your other device, and both\n"
                              "show the same record from then on. Keep it to yourself: it is that record's key.");
        ImGui::SameLine();
        ImGui::TextDisabled("%s", Port_NetId());
        ImGui::SetNextItemWidth(300.0f);
        if (ImGui::InputText("##code", code, sizeof(code), ImGuiInputTextFlags_CharsHexadecimal)) {
            if (ImGui::IsItemDeactivatedAfterEdit()) {
                if (Port_NetIdSet(code)) {
                    ImGui::TextColored(ImVec4(0.6f, 0.9f, 0.6f, 1.0f), "Taken. This device now shows that record.");
                } else {
                    ImGui::TextColored(ImVec4(0.9f, 0.6f, 0.6f, 1.0f), "Not a recovery code (32 letters a-f and digits).");
                }
            }
        }
        ImGui::SetItemTooltip("The code from your other device. This copy's own record stays on the service, so\n"
                              "nothing is lost: the old code still works there.");
    }
    ImGui::Spacing();
    {   /* The other player in this match, as the service knows them. Records are public, so there is no switch to hide
           this one (the launcher passes the other side's in: BT3_NET_PEER_*), and the public id is what anybody can
           look that record up by. */
        int wins = 0, losses = 0;
        const char *who = Port_NetPeerName();

        if (who != NULL && who[0] != '\0') {
            ImGui::Text("Opponent in this match");
            if (Port_NetPeerRecord(&wins, &losses)) {
                ImGui::TextDisabled("%s: %d won, %d lost", who, wins, losses);
            } else {
                ImGui::TextDisabled("%s: no record yet", who);
            }
            if (Port_NetPeerId()[0] != '\0') {
                ImGui::TextDisabled("Public ID: %s", Port_NetPeerId());
            }
        }
    }
    ImGui::Spacing();
    ImGui::TextWrapped("One name and one record for this machine. Changing either changes nothing else: not your "
                       "saves, not your settings, and not any match you have already played.");
    ImGui::Spacing();
#ifndef __ANDROID__
    /* The keys are otherwise undiscoverable - the touch HUD has buttons for the same thing, so this is the only place
       they can be written down. A match only; outside one there is nobody to say them to. */
    {
        ImGui::TextDisabled("In a match, say one to the other player:");
        for (int i = 0, n = Port_NetEmoteCount(); i < n; i++) {
            ImGui::SameLine();
            ImGui::Text("%d %s", i + 1, Port_NetEmoteName(i));
        }
    }
#endif
}

static void video_tab(PortVideo &v) {
    static const struct { const char *name; int milli; } aspects[] = {
#ifdef __ANDROID__
        {"Screen (the device's own shape)", 0},
#endif
        {"4:3 (original)", 1333}, {"16:10", 1600}, {"16:9", 1778}, {"20:9", 2222}, {"21:9", 2389}, {"32:9", 3556}};
    char label[64];
    int best = 0, count = 0;

    /* in quarters: the steps between 1x and 2x for weaker GPUs (1.5x is 672 lines, 1.75x 784) */
    static const int steps[] = {4, 5, 6, 7, 8, 10, 12, 16, 20, 24, 32};
    snprintf(label, sizeof(label), "%gx  (%d x %d)", v.scale4 / 4.0, 128 * v.scale4, 112 * v.scale4);
    if (ImGui::BeginCombo("Internal resolution", label)) {
        for (int q : steps) {
            snprintf(label, sizeof(label), "%gx  (%d x %d)", q / 4.0, 128 * q, 112 * q);
            if (ImGui::Selectable(label, q == v.scale4)) { v.scale4 = q; }
        }
        ImGui::EndCombo();
    }
    ImGui::SetItemTooltip("How finely the game is drawn. 1x is the PlayStation 2's own resolution.\nHigher is sharper and uses more video memory.");
    {
        static const char *filters[] = {"Bilinear", "Sharp bilinear", "FXAA (smooths jagged edges)", "AMD FSR 1", "Snapdragon GSR"};
        int f = v.filter < 0 || v.filter > 4 ? 0 : v.filter;
        if (ImGui::BeginCombo("Screen filter", filters[f])) {
            for (int n = 0; n < 5; n++) {
                if (ImGui::Selectable(filters[n], n == f)) { v.filter = n; }
            }
            ImGui::EndCombo();
        }
        ImGui::SetItemTooltip("How the game's picture is scaled to the screen.\nFXAA smooths jagged edges; FSR and Snapdragon GSR upscale with sharper edges,\nbest with an internal resolution below the screen's (1x to 2x).");
    }
    {   // the entry that is the picture's shape: the exact one (0 = the screen's, Android), else the nearest
        int bestd = 1 << 30;
        for (int i = 0; i < (int)(sizeof(aspects) / sizeof(aspects[0])); i++) {
            int d = aspects[i].milli == 0 ? (v.aspectMilli == 0 ? 0 : 1 << 29) : abs(aspects[i].milli - v.aspectMilli);
            if (d < bestd) { bestd = d; best = i; }
        }
    }
    if (ImGui::BeginCombo("Aspect ratio", aspects[best].name)) {
        for (int i = 0; i < (int)(sizeof(aspects) / sizeof(aspects[0])); i++) {
            if (ImGui::Selectable(aspects[i].name, i == best)) { v.aspectMilli = aspects[i].milli; }
        }
        ImGui::EndCombo();
    }
#ifdef __ANDROID__
    ImGui::SetItemTooltip("Screen: the picture takes the shape of this device's screen, so it is not squeezed.\nWider than 4:3 shows more of the fight to the sides. Menus are stretched.");
#else
    ImGui::SetItemTooltip("Wider than 4:3 shows more of the fight to the sides. Menus are stretched.");
#endif
    if (ImGui::BeginCombo("2D filtering", v.smooth2d ? "Smooth" : "Sharp (console)")) {
        if (ImGui::Selectable("Sharp (console)", !v.smooth2d)) { v.smooth2d = 0; }
        if (ImGui::Selectable("Smooth", v.smooth2d != 0)) { v.smooth2d = 1; }
        ImGui::EndCombo();
    }
    ImGui::SetItemTooltip("How the display of a fight, the menus and text are drawn above 1x internal resolution.\n"
                          "Sharp: each of the PlayStation 2's pixels is a block. Smooth: as an emulator shows them.\n"
                          "No difference at 1x.");
    bool full = v.fullscreen != 0;
    if (ImGui::Checkbox("Full screen", &full)) { v.fullscreen = full; }
    ImGui::SameLine();
    ImGui::TextDisabled("(F11)");
    SDL_DisplayID *displays = SDL_GetDisplays(&count);
    if (v.display > count) { v.display = 0; }
    snprintf(label, sizeof(label), "%s", v.display == 0 ? "Chosen by the desktop" : SDL_GetDisplayName(displays[v.display - 1]));
    if (ImGui::BeginCombo("Display", label)) {
        if (ImGui::Selectable("Chosen by the desktop", v.display == 0)) { v.display = 0; }
        for (int i = 0; i < count; i++) {
            snprintf(label, sizeof(label), "%d: %s", i + 1, SDL_GetDisplayName(displays[i]));
            if (ImGui::Selectable(label, v.display == i + 1)) { v.display = i + 1; }
        }
        ImGui::EndCombo();
    }
    ImGui::SetItemTooltip("Takes effect the next time the game starts.");
    SDL_free(displays);
    {   // which renderer draws the game: Vulkan (the default) or OpenGL 3.3, for machines without a working Vulkan
        // (Android: OpenGL ES 3.2; the launcher picks it by itself for Mali GPUs unless chosen here or in the app)
#ifdef __ANDROID__
        static const char *const kApi[] = {"Vulkan", "OpenGL ES"};
#else
        static const char *const kApi[] = {"Vulkan", "OpenGL"};
#endif
        static int api = -1;
        if (api < 0) {
            api = Port_Setting("gpu_api", 0) == 1 ? 1 : 0;
        }
        if (ImGui::Combo("Renderer", &api, kApi, 2)) {
            Port_SettingSave("gpu_api", api);
#ifdef __ANDROID__
            Port_SettingSave("dr_renderer", api + 1); /* (the player's choice: the launcher no longer picks by GPU) */
#endif
            Port_SettingsWrite();
        }
#ifdef __ANDROID__
        ImGui::SetItemTooltip("Takes effect the next time the game starts. If the chosen one cannot start, the game tries the other.\n"
                              "OpenGL ES: chosen by itself for Mali GPUs. No depth clamping (what comes very near the camera\n"
                              "can be cut), no screen filters (FXAA, FSR, GSR).");
#else
        ImGui::SetItemTooltip("Takes effect the next time the game starts. Both draw the same picture; Vulkan is faster.\n"
                              "If the chosen one cannot start, the game tries the other.");
#endif
        if (api != (sGL ? 1 : 0)) {
            ImGui::TextDisabled("Running now: %s. %s from the next start.", kApi[sGL ? 1 : 0], kApi[api]);
        }
    }
    {
        // In-between pictures (gs_draw.c): the fight is computed 30 times a second, as on the console; with this
        // on one more picture is drawn between each two. The game itself, its replays and online play are the same.
        static const char *kSmooth[] = { "Off (30, as the console)", "60 pictures a second", "120 pictures a second", "240 pictures a second" };
        int smooth = gsInterp < 0 ? 0 : gsInterp > 3 ? 3 : gsInterp;
        ImGui::SetNextItemWidth(416.0f);
        if (ImGui::Combo("Smooth motion", &smooth, kSmooth, 4)) {
            gsInterp = smooth;
            Port_SettingSave("interp", gsInterp);
            Port_SettingsWrite();
        }
        ImGui::SetItemTooltip("Experimental. The game computes a fight 30 times a second. With this, more pictures are\n"
                              "drawn between each two. The fight itself does not change, and neither does online play.\n"
                              "The newest picture reaches the screen later: 17 ms at 60, 25 ms at 120, 29 ms at 240.\n"
                              "Choose what your screen can show. F9 switches it off and on.");
    }
#ifdef __ANDROID__
    {   // how finished pictures reach the screen (gs_gpu.c); applied between two frames, kept for the next start
        static const char *kPresent[] = { "Automatic (Mailbox where the device has it)", "FIFO (VSync)", "Mailbox", "Immediate" };
        int pm = GsGpu_PresentGet();
        pm = pm < 0 || pm > 3 ? 0 : pm;
        ImGui::SetNextItemWidth(416.0f);
        if (ImGui::Combo("Present mode", &pm, kPresent, 4)) {
            GsGpu_PresentSet(pm);
            Port_SettingsWrite();
        }
        ImGui::SetItemTooltip("How the finished pictures reach the screen.\n"
                              "FIFO (VSync): no tearing, a little more latency, steadier pace on some devices.\n"
                              "Mailbox: no tearing, no waiting. Immediate: least latency, may tear.\n"
                              "A mode the device does not offer falls back to Automatic.");
    }
#endif
    {
        bool on = meter_on();
        if (ImGui::Checkbox("Show frame rate, and the connection in an online match", &on)) {
            sMeter = on;
            Port_SettingSave("meter", sMeter);
            Port_SettingsWrite();
        }
    }
    ImGui::Spacing();
    if (v.texPackCount > 0) {
        bool on = v.texPack != 0;
        snprintf(label, sizeof(label), "Texture pack (%d textures)", v.texPackCount);
        if (ImGui::Checkbox(label, &on)) { v.texPack = on; }
        ImGui::SetItemTooltip("Replacement textures from the folder \"textures\" next to the game.");
    } else {
        ImGui::BeginDisabled();
        bool off = false;
        ImGui::Checkbox("Texture pack (none found)", &off);
        ImGui::EndDisabled();
        ImGui::SetItemTooltip("Put a pack's files (PCSX2 naming, .dds or .png) into a folder \"textures\" next to the game.");
    }
}

static void effects_tab(PortVideo &v) {
    static const struct { const char *name, *tip; } fx[6] = {
        {"Outline", "The black line around the fighters."},
        {"See-through tint", "The tint that shows a fighter behind scenery."},
        {"Depth tint", "The haze that colours distant things."},
        {"Glare and glow", "The bloom around bright things and the sky's glare."},
        {"Distance blur", "The soft focus on distant scenery."},
        {"Movement blur", "The blur of the scenery while the view turns or moves fast."},
    };
    for (int i = 0; i < 6; i++) {
        bool on = !((v.fxOff >> i) & 1);
        if (ImGui::Checkbox(fx[i].name, &on)) { v.fxOff = (v.fxOff & ~(1 << i)) | (on ? 0 : 1 << i); }
        ImGui::SetItemTooltip("%s", fx[i].tip);
    }
    ImGui::Spacing();
    ImGui::SliderInt("Glow strength", &v.glow, 0, 200, "%d%%", ImGuiSliderFlags_AlwaysClamp);
    ImGui::SetItemTooltip("100%% is what the PlayStation 2 draws; the default here is 60%%.");
}

static void audio_tab(PortVideo &v) {
    ImGui::SliderInt("Music", &v.music, 0, 200, "%d%%", ImGuiSliderFlags_AlwaysClamp);
    ImGui::SliderInt("Sound effects and voices", &v.effects, 0, 200, "%d%%", ImGuiSliderFlags_AlwaysClamp);
    ImGui::SetItemTooltip("Takes effect with the next sound that starts.");
}

// Cheats. "Unlock everything" is the game's own leftover debug function (Save_UnlockAll, which nothing in the game
// calls): the request is set here and carried out on the game's side at the next vertical blank (headless.c).
extern "C" {
extern volatile int gPortUnlockAll, gPortUnlockDone;
}

static void cheats_tab(void) {
    ImGui::TextWrapped("Unlock everything: all characters, stages, music and items, and the largest amount of Zenni. "
                       "This is a debug function the game's developers left in.");
    ImGui::Spacing();
    ImGui::TextWrapped("It also empties your records list, and it becomes permanent the next time the game saves. "
                       "Use it after your save has been loaded (from the main menu on).");
    ImGui::Spacing();
    if (ImGui::Button("Unlock everything...")) {
        ImGui::OpenPopup("Unlock everything?");
    }
    if (gPortUnlockDone) {
        ImGui::SameLine();
        ImGui::TextUnformatted("Done.");
    }
    if (ImGui::BeginPopupModal("Unlock everything?", NULL, ImGuiWindowFlags_AlwaysAutoResize)) {
        ImGui::TextUnformatted("Your records list will be emptied. This cannot be undone once the game saves.");
        ImGui::Spacing();
        if (ImGui::Button("Unlock", ImVec2(140.0f, 0.0f))) {
            gPortUnlockAll = 1;
            ImGui::CloseCurrentPopup();
        }
        ImGui::SameLine();
        if (ImGui::Button("Cancel", ImVec2(140.0f, 0.0f))) {
            ImGui::CloseCurrentPopup();
        }
        ImGui::EndPopup();
    }
}

static void controls_tab(void) {
    static int player;
    char label[96];
    int count = 0;

    ImGui::RadioButton("Player 1", &player, 0);
    ImGui::SameLine();
    ImGui::RadioButton("Player 2", &player, 1);
    int *slot = PortInput_PadSlot(player), *keys = PortInput_Keys(player), *pads = PortInput_PadButtons(player);
    SDL_JoystickID *ids = SDL_GetGamepads(&count);
    if (*slot < 0) {
        snprintf(label, sizeof(label), "None");
    } else if (*slot < count) {
        snprintf(label, sizeof(label), "%d: %s", *slot + 1, SDL_GetGamepadNameForID(ids[*slot]));
    } else {
        snprintf(label, sizeof(label), "Controller %d (not connected)", *slot + 1);
    }
    ImGui::SetNextItemWidth(300.0f);
    if (ImGui::BeginCombo("Controller", label)) {
        if (ImGui::Selectable("None", *slot < 0)) { *slot = -1; PortInput_Save(); }
        for (int i = 0; i < (count > 4 ? count : 4); i++) {
            if (i < count) {
                snprintf(label, sizeof(label), "%d: %s", i + 1, SDL_GetGamepadNameForID(ids[i]));
            } else {
                snprintf(label, sizeof(label), "Controller %d (not connected)", i + 1);
            }
            if (ImGui::Selectable(label, *slot == i)) { *slot = i; PortInput_Save(); }
        }
        ImGui::EndCombo();
    }
    SDL_free(ids);
    ImGui::SameLine();
    if (ImGui::Button("Reset to defaults")) {
        PortInput_ResetDefaults(player);
        PortInput_Save();
    }
    ImGui::TextDisabled("Click a binding, then press the new key or button. Esc keeps it, Delete clears it.");
    if (ImGui::BeginTable("bindings", 3, ImGuiTableFlags_RowBg | ImGuiTableFlags_ScrollY | ImGuiTableFlags_BordersInnerV,
                          ImVec2(0.0f, ImGui::GetContentRegionAvail().y))) {
        ImGui::TableSetupScrollFreeze(0, 1);
        ImGui::TableSetupColumn("Action");
        ImGui::TableSetupColumn("Keyboard");
        ImGui::TableSetupColumn("Controller");
        ImGui::TableHeadersRow();
        {   // what opens and closes this window (not a player's: one for the program)
            const int a = PORT_ACTIONS;
            bool waitKey = sCapKind == 1 && sCapAction == a, waitPad = sCapKind == 2 && sCapAction == a;
            int key = *PortInput_OverlayKey(), pad = *PortInput_OverlayPad();
            ImGui::TableNextRow();
            ImGui::TableNextColumn();
            ImGui::AlignTextToFramePadding();
            ImGui::TextUnformatted("Open this window");
            ImGui::TableNextColumn();
            snprintf(label, sizeof(label), "%s##k%d", waitKey ? "press a key..." : key > 0 ? SDL_GetScancodeName((SDL_Scancode)key) : "-", a);
            if (ImGui::Button(label, ImVec2(-FLT_MIN, 0.0f))) {
                sCapKind = 1; sCapPlayer = player; sCapAction = a; gPortInputCapture = 1;
            }
            ImGui::TableNextColumn();
            snprintf(label, sizeof(label), "%s##p%d", waitPad ? "press a button..." : pad >= 0 ? pad_source_name(pad) : "START + SELECT", a);
            if (ImGui::Button(label, ImVec2(-FLT_MIN, 0.0f))) {
                sCapKind = 2; sCapPlayer = player; sCapAction = a; gPortInputCapture = 1;
            }
        }
        for (int a = 0; a < PORT_ACTIONS; a++) {
            bool waitKey = sCapKind == 1 && sCapPlayer == player && sCapAction == a;
            bool waitPad = sCapKind == 2 && sCapPlayer == player && sCapAction == a;
            ImGui::TableNextRow();
            ImGui::TableNextColumn();
            ImGui::AlignTextToFramePadding();
            ImGui::TextUnformatted(PortInput_ActionLabel(a));
            ImGui::TableNextColumn();
            snprintf(label, sizeof(label), "%s##k%d", waitKey ? "press a key..." : keys[a] > 0 ? SDL_GetScancodeName((SDL_Scancode)keys[a]) : "-", a);
            if (ImGui::Button(label, ImVec2(-FLT_MIN, 0.0f))) {
                sCapKind = 1; sCapPlayer = player; sCapAction = a; gPortInputCapture = 1;
            }
            ImGui::TableNextColumn();
            if (a < PORT_PAD_BUTTONS) {
                snprintf(label, sizeof(label), "%s##p%d", waitPad ? "press a button..." : pad_source_name(pads[a]), a);
                if (ImGui::Button(label, ImVec2(-FLT_MIN, 0.0f))) {
                    sCapKind = 2; sCapPlayer = player; sCapAction = a; gPortInputCapture = 1;
                }
            } else {
                ImGui::AlignTextToFramePadding();
                ImGui::TextDisabled(a < 20 ? "Left stick" : "Right stick");
            }
        }
        ImGui::EndTable();
    }
}

// A finger dragged up or down scrolls the window it is on, also when it started on a button (the button is let go:
// a vertical drag is never meant for one; sliders are moved sideways).
static void touch_scroll(void) {
    ImGuiIO &io = ImGui::GetIO();
    ImGuiContext &g = *GImGui;
    static bool scrolling;
    if (io.MouseSource != ImGuiMouseSource_TouchScreen || !io.MouseDown[0]) {
        scrolling = false;
        return;
    }
    ImVec2 drag = ImGui::GetMouseDragDelta(0, 0.0f);
    if (!scrolling && fabsf(drag.y) > 14.0f && fabsf(drag.y) > fabsf(drag.x) * 1.5f) {
        scrolling = true;
        if (g.ActiveId != 0) {
            ImGui::ClearActiveID();
        }
    }
    if (scrolling && g.HoveredWindow != NULL) {
        ImGuiWindow *w = g.HoveredWindow;
        while (w->ScrollMax.y <= 0.0f && w->ParentWindow != NULL) {
            w = w->ParentWindow; // the nearest one that can scroll
        }
        ImGui::SetScrollY(w, w->Scroll.y - io.MouseDelta.y);
    }
}

#ifdef __ANDROID__
// "Quit to the launcher": the activity (GameActivity.quitToApp) brings the launcher forward and ends the game's own
// process, which cannot be started again in place (the engine needs the fixed addresses a fresh process has).
static void quit_to_app(void) {
    JNIEnv *env = (JNIEnv *)SDL_GetAndroidJNIEnv();
    jobject act = env != NULL ? (jobject)SDL_GetAndroidActivity() : NULL;
    jclass cls = act != NULL ? env->GetObjectClass(act) : NULL;
    jmethodID m = cls != NULL ? env->GetMethodID(cls, "quitToApp", "()V") : NULL;
    if (m == NULL) {
        if (env != NULL) { env->ExceptionClear(); }
        fflush(NULL);
        _exit(0); /* (no activity to ask: at least the game ends) */
    }
    env->CallVoidMethod(act, m);
    if (env->ExceptionCheck()) { env->ExceptionClear(); }
}
#endif

static void build(void) {
    PortVideo v, was;
    ImGuiIO &io = ImGui::GetIO();
    bool open = true;

    GsGpu_GetSettings(&v);
    was = v;
    /* The quick things said to the other player, on the number keys. In a match and nowhere else: outside one there
       is nobody to say them to. On the edge of a press, not while held, or a held key would say it sixty times a
       second. */
    if (Port_NetActive()) {
        static const SDL_Scancode kEmoteKeys[] = {SDL_SCANCODE_1, SDL_SCANCODE_2, SDL_SCANCODE_3,
                                                 SDL_SCANCODE_4, SDL_SCANCODE_5, SDL_SCANCODE_6};
        static unsigned held;
        unsigned now = 0, pressed;
        int n = Port_NetEmoteCount();
        int i;
        const bool *ks = SDL_GetKeyboardState(NULL);

        for (i = 0; i < n && i < (int)(sizeof(kEmoteKeys) / sizeof(kEmoteKeys[0])); i++) {
            now |= ks[kEmoteKeys[i]] ? 1u << i : 0u;
        }
        pressed = now & ~held; /* the edge of the press: a held key says it once, not sixty times a second */
        for (i = 0; i < n; i++) {
            if (pressed & (1u << i)) {
                Port_NetEmote(i);
            }
        }
        held = now;
    }
    ImGui::SetNextWindowPos(ImVec2(io.DisplaySize.x * 0.5f, io.DisplaySize.y * 0.5f), ImGuiCond_Appearing, ImVec2(0.5f, 0.5f));
#ifdef __ANDROID__
    // most of the screen, fixed: fingers move through it by dragging its content, not its frame
    ImGui::SetNextWindowSize(ImVec2(io.DisplaySize.x * 0.92f, io.DisplaySize.y * 0.9f), ImGuiCond_Always);
    ImGui::SetNextWindowPos(ImVec2(io.DisplaySize.x * 0.5f, io.DisplaySize.y * 0.5f), ImGuiCond_Always, ImVec2(0.5f, 0.5f));
    const ImGuiWindowFlags flags = ImGuiWindowFlags_NoCollapse | ImGuiWindowFlags_NoMove | ImGuiWindowFlags_NoResize;
#else
    ImGui::SetNextWindowSize(ImVec2(640.0f, 560.0f), ImGuiCond_Appearing);
    const ImGuiWindowFlags flags = ImGuiWindowFlags_NoCollapse;
#endif
    if (ImGui::Begin("DBT3  -  paused###Settings", &open, flags)) {
#ifdef __ANDROID__
        if (ImGui::Button("  Resume  ")) {
            open = false;
        }
        ImGui::SameLine();
        if (ImGui::Button("  Quit to launcher  ")) {
            ImGui::OpenPopup("Quit to launcher?");
        }
        ImGui::SameLine();
        ImGui::TextDisabled("Back button or START + SELECT: resume");
        if (ImGui::BeginPopupModal("Quit to launcher?", NULL, ImGuiWindowFlags_AlwaysAutoResize)) {
            ImGui::TextUnformatted("The game closes and you go back to the app's menu.");
            ImGui::TextUnformatted("Progress that has not been saved in the game is lost.");
            ImGui::Spacing();
            if (ImGui::Button("Quit", ImVec2(160.0f, 0.0f))) {
                quit_to_app();
            }
            ImGui::SameLine();
            if (ImGui::Button("Stay", ImVec2(160.0f, 0.0f))) {
                ImGui::CloseCurrentPopup();
            }
            ImGui::EndPopup();
        }
        ImGui::Separator();
#endif
        if (ImGui::BeginTabBar("tabs")) {
            if (tab("Video", 0)) { video_tab(v); ImGui::EndTabItem(); }
            if (tab("Effects", 1)) { effects_tab(v); ImGui::EndTabItem(); }
            if (tab("Audio", 2)) { audio_tab(v); ImGui::EndTabItem(); }
            if (tab("Controls", 3)) {
                bool cs = Port_ClashSquare() != 0;
                if (ImGui::Checkbox("Clashes: Square counts as a direction", &cs)) {
                    Port_ClashSquareSet(cs);
                }
                ImGui::SetItemTooltip("In a clash of two beams or two rushes the game counts newly pressed directions:\n"
                                      "turning the stick, or tapping the d-pad. With this on, each press of Square\n"
                                      "counts as one of them while the clash lasts, as a tap of the d-pad does.\n"
                                      "It only changes your own pad, so it can be used in online play.");
                if (cs) {
                    static const int kQuarters[] = {4, 6, 7, 8};
                    static const char *const kWorth[] = {"1", "1.5", "1.75", "2"};
                    int at = 2;
                    for (int i = 0; i < 4; i++) {
                        if (kQuarters[i] == Port_ClashSquareX()) {
                            at = i;
                        }
                    }
                    ImGui::SameLine();
                    ImGui::SetNextItemWidth(80.0f);
                    if (ImGui::Combo("per press", &at, kWorth, 4)) {
                        Port_ClashSquareXSet(kQuarters[at]);
                    }
                    ImGui::SetItemTooltip("What one press of Square is worth. The d-pad has four buttons to tap in turn and the\n"
                                          "stick four directions a turn; Square is one button. 1 is a single d-pad tap.\n"
                                          "Never more than one a frame, which is the most the game counts from any pad.");
                }
                ImGui::Separator();
                controls_tab();
                ImGui::EndTabItem();
            }
            if (tab("Cheats", 4)) { cheats_tab(); ImGui::EndTabItem(); }
            if (tab("Profile", 5)) { profile_tab(); ImGui::EndTabItem(); }
            ImGui::EndTabBar();
        }
        if (ImGui::GetFrameCount() > 3) {
            sForceTab = -1;
        }
    }
    touch_scroll();
    ImGui::End();
    if (memcmp(&v, &was, sizeof(v)) != 0) {
        GsGpu_SetSettings(&v);
    }
    if (!open) {
        set_open(false);
    }
}

static bool sBuilt; // this frame has something to draw: the window, or the line over the picture

void Ui_DrawAgain(SDL_GPUCommandBuffer *cmd, SDL_GPUTexture *target) {
    ImDrawData *dd = sReady && sBuilt ? ImGui::GetDrawData() : NULL; // (sBuilt: Ui_Draw made a picture this frame)
    SDL_GPUColorTargetInfo ti;
    SDL_GPURenderPass *pass;

    if (dd == NULL || dd->CmdListsCount == 0 || dd->DisplaySize.x <= 0.0f || dd->DisplaySize.y <= 0.0f) {
        return;
    }
    ImGui_ImplSDLGPU3_PrepareDrawData(dd, cmd);
    SDL_zero(ti);
    ti.texture = target;
    ti.load_op = SDL_GPU_LOADOP_LOAD;
    ti.store_op = SDL_GPU_STOREOP_STORE;
    pass = SDL_BeginGPURenderPass(cmd, &ti, 1, NULL);
    ImGui_ImplSDLGPU3_RenderDrawData(dd, cmd, pass);
    SDL_EndGPURenderPass(pass);
}

static char sNotice[160];
static Uint64 sNoticeUntil;
static volatile int sNoticeNew;

extern "C" void Port_UiNotice(const char *text) {
    SDL_strlcpy(sNotice, text != NULL ? text : "", sizeof(sNotice));
    sNoticeNew = 1;
}

// The meter: frames shown and the game's speed, and in an online match the connection. A setting (Video tab).
static bool meter_on(void) {
    if (sMeter < 0) {
        sMeter = getenv("BT3_METER") != NULL ? atoi(getenv("BT3_METER")) != 0 : Port_Setting("meter", 0) != 0;
    }
    return sMeter != 0;
}
static float sFps, sSpeed; // frames shown per second; the game's vertical blanks per second as a share of 60

static void meter_count(void) { // once per frame shown
    static Uint64 t0;
    static unsigned frames, blanks0;
    Uint64 now = SDL_GetTicks();
    frames++;
    if (t0 == 0) {
        t0 = now;
        blanks0 = gPortLiveBlanks;
    } else if (now - t0 >= 500) {
        sFps = (float)frames * 1000.0f / (float)(now - t0);
        sSpeed = (float)(gPortLiveBlanks - blanks0) * 1000.0f / (float)(now - t0) / 60.0f * 100.0f;
        frames = 0;
        blanks0 = gPortLiveBlanks;
        t0 = now;
    }
}

static void meter_draw(void) {
    int n[8];
    ImGui::SetNextWindowPos(ImVec2(8.0f, 8.0f), ImGuiCond_Always);
    ImGui::SetNextWindowBgAlpha(0.6f);
    if (ImGui::Begin("##meter", NULL, ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_AlwaysAutoResize | ImGuiWindowFlags_NoInputs |
                                          ImGuiWindowFlags_NoFocusOnAppearing | ImGuiWindowFlags_NoNav | ImGuiWindowFlags_NoSavedSettings)) {
        // a fight shows 30 frames a second and the menus 60, as on the console; the speed is what should stay at 100%
        ImGui::Text("%.0f fps   speed %.0f%%", sFps, sSpeed);
        if (Port_NetStats(n)) {
            if (n[0] >= 0) {
                ImGui::Text("ping %d ms%s", n[0], Port_LobbyRelayed() ? " (relay)" : "");
            } else {
                ImGui::TextUnformatted("ping ...");
            }
            if (n[5] > 0) {
                ImGui::Text("rollbacks %d/s, %.1f frames", n[1], (float)n[2] / 10.0f);
            } else {
                ImGui::TextUnformatted("rollback off");
            }
            ImGui::Text("waited %d ms/s (%d times)", n[4], n[3]);
            ImGui::Text("delay %d%s, rollback up to %d", n[6], n[7] ? " (auto)" : "", n[5]);
        }
    }
    ImGui::End();
}

// The name of an added stage or song as text, in the rectangle the menu gives (the strip images' place): cream
// letters with a dark outline, as tall as the rectangle allows, centred for a stage and from the left for a song.
static void name_text(ImDrawList *dl, const char *text, float x, float y, float w, float h, bool centre) {
    ImFont *font = ImGui::GetFont();
    float size = h * (centre ? 0.42f : 0.72f);
    ImVec2 ext = font->CalcTextSizeA(size, 10000.0f, 0.0f, text);
    if (ext.x > w * 0.96f && ext.x > 0.0f) { // a long name: smaller, to fit
        size *= w * 0.96f / ext.x;
        ext = font->CalcTextSizeA(size, 10000.0f, 0.0f, text);
    }
    float tx = centre ? x + (w - ext.x) * 0.5f : x + h * 0.15f, ty = y + (h - ext.y) * 0.5f, o = size * 0.07f + 1.0f;
    for (int k = 0; k < 8; k++) {
        static const float dx[8] = {-1, 0, 1, -1, 1, -1, 0, 1}, dy[8] = {-1, -1, -1, 0, 0, 1, 1, 1};
        dl->AddText(font, size, ImVec2(tx + dx[k] * o, ty + dy[k] * o), IM_COL32(40, 20, 0, 255), text);
    }
    dl->AddText(font, size, ImVec2(tx, ty), IM_COL32(255, 236, 170, 255), text);
}

/* Names in the game's own lettering (namefont.c): a name with no picture in a strip is put together from the letters
   of the disc's name pictures, once, and kept as a texture while it is the one shown (a few are kept, for going up
   and down a list). */
struct FontName {
    SDL_GPUTexture *tex; // NULL: this name cannot be drawn that way (a character the disc's names do not have)
    int style, w, h;
    char text[64];
    bool used;
};
static FontName sFontNames[12];
static unsigned sFontNext;

static const FontName *font_name(int style, const char *text) {
    if (text == NULL || text[0] == '\0' || !NameFont_Ready()) { // (not ready: asked again next frame)
        return NULL;
    }
    for (FontName &n : sFontNames) {
        if (n.used && n.style == style && strncmp(n.text, text, sizeof(n.text) - 1) == 0) {
            return n.tex != NULL ? &n : NULL;
        }
    }
    FontName &n = sFontNames[sFontNext++ % (sizeof(sFontNames) / sizeof(sFontNames[0]))];
    if (n.used && n.tex != NULL) {
        if (sGL) {
            GsGl_StripTextureFree((unsigned)(intptr_t)n.tex);
        } else {
            SDL_ReleaseGPUTexture(sDevice, n.tex);
        }
    }
    n.tex = NULL;
    n.used = true;
    n.style = style;
    SDL_strlcpy(n.text, text, sizeof(n.text));
    unsigned char *pix = NameFont_Compose(style, n.text, &n.w, &n.h);
    if (pix != NULL) {
        n.tex = make_strip(pix, (size_t)n.w * n.h * 4, (uint32_t)n.w, (uint32_t)n.h, 1); // (frees pix)
    }
    return n.tex != NULL ? &n : NULL;
}

/* Draws such a name over the place of the disc's name picture: (x, y) is the corner of that picture's row in the
   window, kx and ky are window pixels per pixel of the row (512 wide). A name longer than the row is made smaller
   to fit, about the row's middle line; a stage name stays in the middle, a song name keeps its left edge. */
static void font_draw(ImDrawList *dl, const FontName *n, float x, float y, float kx, float ky, bool centre) {
    float k = n->w > 512 ? 512.0f / (float)n->w : 1.0f;
    float w = (float)n->w * kx * k, h = (float)n->h * ky * k;
    float x0 = centre ? x + (512.0f * kx - w) * 0.5f : x;
    float y0 = y + ((float)n->h * ky - h) * 0.5f;
    dl->AddImage(ImTextureRef((ImTextureID)(intptr_t)n->tex), ImVec2(x0, y0), ImVec2(x0 + w, y0 + h));
}

// Builds this frame's picture of the overlay (the settings or the online window, the line over the picture).
// false: there is nothing to draw. The same for both back ends; each then draws ImGui's data its own way.
static bool frame_build(void) {
    if (sReady && gPortNetWindowClose) { // back from an online session: the window it was started from closes
        gPortNetWindowClose = 0;
        gPortNetMenuRequest = 0;
        set_open(false);
    } else if (sReady && gPortNetMenuRequest) { // Dragon Net Battle was chosen in the game's main menu
        gPortNetMenuRequest = 0;
        net_open();
    }
    bool notice = sNotice[0] != '\0' && SDL_GetTicks() < sNoticeUntil;
    if (sNoticeNew) { // (set from the game's side: the time starts when it is first drawn)
        sNoticeNew = 0;
        sNoticeUntil = SDL_GetTicks() + 6000;
        notice = sNotice[0] != '\0';
    }
    // the name of a stage added from outside the disc, over the stage select (a strip of pre-rendered names)
    bool nameOn = gUiNameReady != 0 && gUiNameIdx >= 0 && gUiNameIdx < PortStages_Count();
    bool songOn = gUiSongReady != 0 && gUiSongIdx >= 0 && gUiSongIdx < PortSongs_Count(); // (the same for an added song)
    bool overlay = nameOn || songOn;
    bool meter = sReady && meter_on();
    // the players' names under the gauges of an online fight
    bool names = sReady && Port_NetActive() && BtlChar_FindByObjId(0) != NULL && (Port_NetName(0)[0] != '\0' || Port_NetName(1)[0] != '\0');
    meter_count();
    sBuilt = false;
    if (!sReady || (!sOpen && !notice && !overlay && !meter && !names)) {
        return false;
    }
    sBuilt = true;
    if (sGL) {
        ImGui_ImplOpenGL3_NewFrame();
    } else {
        ImGui_ImplSDLGPU3_NewFrame();
    }
    ImGui_ImplSDL3_NewFrame();
    ImGui::NewFrame();
    if (sOpen) {
        if (sNet) {
            build_net();
        } else {
            build();
        }
    }
    if (meter) {
        meter_draw();
    }
    if (names && gUiPresentW > 0 && gUiPresentH > 0) {
        // Under each side's health bar (the game's 512 x 448 page: the bars end at about y 50). The left place is
        // side 0's, or this player's own side when the HUD's sides are swapped.
        ImDrawList *dl = ImGui::GetBackgroundDrawList();
        float sx = (float)gUiPresentW / 512.0f, sy = (float)gUiPresentH / 448.0f;
        float size = 15.0f * sy;
        ImFont *font = ImGui::GetFont();
        for (int place = 0; place < 2; place++) {
            int logical = place ^ Port_HudSwap();
            bool mine = logical == Port_NetMe();
            const char *text = Port_NetName(logical);
            const char *platform = mine ? net_platform_name() : Port_LobbyPeerPlatform();
            char head[64];
            if (text[0] == '\0') {
                continue;
            }
            /* Each side gets its name and platform. The local platform is known directly; the opponent's came from
               the service when the lobby paired us. */
            if (platform[0] != '\0') {
                SDL_snprintf(head, sizeof(head), "%s   %s", text, platform);
            } else {
                SDL_snprintf(head, sizeof(head), "%s", text);
            }
            ImVec2 ts = font->CalcTextSizeA(size, FLT_MAX, 0.0f, head);
            float x = place == 0 ? (float)gUiPresentX + 62.0f * sx : (float)gUiPresentX + (512.0f - 62.0f) * sx - ts.x;
            float y = (float)gUiPresentY + 56.0f * sy;
            for (int ox = -1; ox <= 1; ox++) {
                for (int oy = -1; oy <= 1; oy++) {
                    if (ox != 0 || oy != 0) {
                        dl->AddText(font, size, ImVec2(x + (float)ox * 1.5f, y + (float)oy * 1.5f), IM_COL32(0, 0, 0, 220), head);
                    }
                }
            }
            dl->AddText(font, size, ImVec2(x, y), IM_COL32(255, 255, 255, 255), head);
            /* The local record is always available to its owner; the other player's record is under the name only
               when that player has opted in. Read every frame so a response from the service appears immediately. */
            {
                int w = 0, l = 0;
                char line[40];

                if ((mine && Port_NetRecord(&w, &l) == 0) || (!mine && Port_LobbyPeerRecord(&w, &l) == 0)) {
                    SDL_snprintf(line, sizeof(line), "%dW %dL", w, l);
                } else {
                    line[0] = '\0';
                }
                if (line[0] != '\0') {
                    ImVec2 ls = font->CalcTextSizeA(size * 0.6f, FLT_MAX, 0.0f, line);
                    float lx = place == 0 ? x : (float)gUiPresentX + (512.0f - 62.0f) * sx - ls.x;
                    dl->AddText(font, size * 0.6f, ImVec2(lx, y + (float)size * 1.05f), IM_COL32(235, 235, 235, 200), line);
                }
            }
        }
    }
    if (notice) { // a line over the picture for a few seconds (the end of an online match)
        ImGuiIO &io = ImGui::GetIO();
        ImGui::SetNextWindowPos(ImVec2(io.DisplaySize.x * 0.5f, io.DisplaySize.y * 0.5f), ImGuiCond_Always, ImVec2(0.5f, 0.5f));
        ImGui::SetNextWindowBgAlpha(0.85f);
        if (ImGui::Begin("##notice", NULL, ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_AlwaysAutoResize | ImGuiWindowFlags_NoInputs |
                                               ImGuiWindowFlags_NoFocusOnAppearing | ImGuiWindowFlags_NoNav | ImGuiWindowFlags_NoSavedSettings)) {
            ImGui::TextUnformatted(sNotice);
        }
        ImGui::End();
    }
    if (overlay && gUiPresentW > 0 && gUiPresentH > 0) {
        /* Each name's rectangle is in the game's 512x448 pixels: map it through the picture's rectangle in the
           window (the letterbox gs_gpu.c just blitted into). */
        float sx = (float)gUiPresentW / 512.0f;
        float sy = (float)gUiPresentH / 448.0f;
        ImDrawList *dl = ImGui::GetForegroundDrawList();

        if (nameOn && gUiNameIdx >= (int)sNameCount) { // no pre-rendered name for this one
            /* In the game's lettering if the disc's names have all its characters, else as plain text. The menu's
               rectangle is the strip image's (384x48, set 64 right and 14 down in the 512x64 row of the disc's own
               name picture); the lettering goes where that row is. The menu's movie draws the row a little below
               the clip's position: kNameDy, measured by drawing a disc name both ways (the same for the songs). */
            static const float kNameDy = 1.5f;
            const FontName *fn = font_name(NF_STAGE, PortStages_Name(gUiNameIdx));
            if (fn != NULL) {
                float kx = (float)gUiNameW * sx / 384.0f, ky = (float)gUiNameH * sy / 48.0f;
                font_draw(dl, fn, (float)gUiPresentX + (float)gUiNameX * sx - 64.0f * kx, (float)gUiPresentY + (float)gUiNameY * sy + (kNameDy - 14.0f) * ky, kx, ky, true);
            } else {
                name_text(dl, PortStages_Name(gUiNameIdx), (float)gUiPresentX + (float)gUiNameX * sx, (float)gUiPresentY + (float)gUiNameY * sy,
                          (float)gUiNameW * sx, (float)gUiNameH * sy, true);
            }
        } else if (nameOn) {
            float x = (float)gUiPresentX + (float)gUiNameX * sx;
            float y = (float)gUiPresentY + (float)gUiNameY * sy;
            float w = (float)gUiNameW * sx;
            float h = (float)gUiNameH * sy;
            dl->AddImage(ImTextureRef((ImTextureID)(intptr_t)sNameTex), ImVec2(x, y), ImVec2(x + w, y + h),
                         ImVec2(0.0f, (float)gUiNameIdx / (float)sNameCount),
                         ImVec2(1.0f, (float)(gUiNameIdx + 1) / (float)sNameCount));
        }
        if (songOn && gUiSongIdx >= (int)sSongCount) {
            // (the rectangle is the 512x32 row of the disc's song-name picture itself)
            const FontName *fn = font_name(gUiSongLit ? NF_SONG_LIT : NF_SONG, PortSongs_Name(gUiSongIdx));
            if (fn != NULL) {
                static const float kSongDx = -0.2f, kSongDy = 2.35f;
                float kx = (float)gUiSongW * sx / 512.0f, ky = (float)gUiSongH * sy / 32.0f;
                font_draw(dl, fn, (float)gUiPresentX + (float)gUiSongX * sx + kSongDx * kx, (float)gUiPresentY + (float)gUiSongY * sy + kSongDy * ky, kx, ky, false);
            } else {
                name_text(dl, PortSongs_Name(gUiSongIdx), (float)gUiPresentX + (float)gUiSongX * sx, (float)gUiPresentY + (float)gUiSongY * sy,
                          (float)gUiSongW * sx, (float)gUiSongH * sy, false);
            }
        } else if (songOn) {
            float x = (float)gUiPresentX + (float)gUiSongX * sx;
            float y = (float)gUiPresentY + (float)gUiSongY * sy;
            float w = (float)gUiSongW * sx;
            float h = (float)gUiSongH * sy;
            dl->AddImage(ImTextureRef((ImTextureID)(intptr_t)sSongTex), ImVec2(x, y), ImVec2(x + w, y + h),
                         ImVec2(0.0f, (float)gUiSongIdx / (float)sSongCount),
                         ImVec2(1.0f, (float)(gUiSongIdx + 1) / (float)sSongCount));
        }
    }
    ImGui::Render();
    return true;
}

void Ui_Draw(SDL_GPUCommandBuffer *cmd, SDL_GPUTexture *target) {
    if (!sGL && frame_build()) {
        Ui_DrawAgain(cmd, target);
    }
}

// The OpenGL back end's turn: over the presented picture, just before the buffers are swapped.
void Ui_DrawGL(void) {
    if (sGL && frame_build()) {
        ImGui_ImplOpenGL3_RenderDrawData(ImGui::GetDrawData());
    }
}
