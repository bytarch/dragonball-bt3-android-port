// The setup window: the launcher's own window, and the launcher's name is the one in a release (DBT3). It does
// none of the work itself: it runs `python3 install.py --machine --iso <file>` next to it and shows the events
// that script prints (one line each: @step, @note, @ok, @skip, @fail, @missing, @stopped, @done; see install.py).
//
//   build:  port/setup/build.sh   ->  ./DBT3
//   test:   BT3_SETUP_ISO=<file> starts the installation at once; BT3_SETUP_SHOT=<prefix> writes the window's
//           picture to <prefix>_<n>.ppm whenever the page or the step changes (no person needed to check it)
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <math.h>
#include <mutex>
#include <string>
#include <vector>
#include <SDL3/SDL.h>
#include "imgui.h"
#include "imgui_impl_sdl3.h"
#include "imgui_impl_sdlgpu3.h"
#include "imgui_impl_opengl3.h"
#include "imgui_impl_opengl3_loader.h" // glViewport, glClear for the OpenGL window (set up by ImGui_ImplOpenGL3_Init)
#include "native.h"
#include "net.h"

#define LAUNCH "DBT3"      /* this program: the launcher, and the one a player starts */
#define GAME "DBT3-game"   /* the program it launches: GAME, GAME.exe, GAME.dat (the engine's list is named after
                            * the program it belongs to, so these three names always travel together) */

// The project's own server: the disc image to set the game up from, and the build feed read at every start.
#define kIsoUrl "https://gcode.bytarch.com/DBT3-MOBILE.iso"
#define kFeedUrl "https://gcode.bytarch.com/version.json"
#define kBootBackdrop "boot_backdrop.jpg" /* next to this program (port/res/boot_backdrop.jpg) */

enum Page { PAGE_BOOT, PAGE_PICK, PAGE_RUN, PAGE_DONE, PAGE_FAILED };
enum State { PENDING, RUNNING, OK, SKIPPED, FAILED };
struct StepRow { std::string title, detail; State state = PENDING; Uint64 start = 0, end = 0; };
struct Missing { std::string name, why, pkg; };

// The steps of the two ways to install: from a source checkout (install.py does the work and needs build tools) and
// in a release folder (DBT3 and DBT3.dat next to this program: native.cpp unpacks the disc itself).
static const char *kSourceSteps[] = {"Requirements", "Disc image", "Game data", "Executable data", "Build", "Self-test"};
static const char *kReleaseSteps[] = {"Disc image", "Game data", "Self-test"};
static const struct { const char *title, *help; } kHelp[] = {
    {"Requirements", "Compilers and tools on this computer"}, {"Disc image", "Your disc image is read and checked"},
    {"Game data", "Models, sounds and text are unpacked"}, {"Executable data", "The data tables of the game's programs"},
    {"Build", "The port is compiled (a few minutes)"}, {"Self-test", "The game's demo fight, without a window"}};
static bool sRelease;

static const char *step_help(const std::string &title) {
    for (auto &h : kHelp) {
        if (title == h.title) {
            return h.help;
        }
    }
    return "";
}

static SDL_Window *sWindow;
static SDL_GPUDevice *sDevice;
// The window is drawn through SDL's GPU layer (Vulkan on Linux, Direct3D 12 on Windows) or, where that cannot
// start (no Vulkan driver, a graphics chip too old for it: reported for Intel Ivy Bridge), through OpenGL.
// BT3_GPU_API=gl asks for OpenGL at once, as it does for the game.
static SDL_GLContext sGl;
static bool sUseGl;
static Page sPage = PAGE_BOOT;
static char sIso[1024];
static std::string sRoot, sError, sLauncher, sLine;
static std::vector<StepRow> sRows;
static std::vector<Missing> sMissing;
static SDL_Process *sProc;
static bool sNative; // the native installer is at work (release folder)
static std::mutex sDialogLock;
static std::string sDialogResult;
static bool sDialogReady, sQuit;
static Uint64 sRunStart, sRunEnd;
static const ImVec4 kAccent(0.96f, 0.55f, 0.13f, 1.0f), kGreen(0.36f, 0.80f, 0.45f, 1.0f), kRed(0.93f, 0.36f, 0.33f, 1.0f),
    kDim(0.60f, 0.63f, 0.70f, 1.0f);

static bool file_exists(const char *path) {
    SDL_PathInfo info;
    return path[0] != '\0' && SDL_GetPathInfo(path, &info) && info.type == SDL_PATHTYPE_FILE;
}

// The folder to install into: a release folder (this program's own), or the source checkout that holds install.py.
static std::string find_root() {
    std::string dir = SDL_GetBasePath() ? SDL_GetBasePath() : "./";
    if ((file_exists((dir + GAME).c_str()) || file_exists((dir + GAME ".exe").c_str())) && file_exists((dir + GAME ".dat").c_str())) {
        sRelease = true; // a release folder: the finished program is here, only the game data is missing
        return dir;
    }
    for (int up = 0; up < 4; up++) {
        if (file_exists((dir + "install.py").c_str())) {
            return dir;
        }
        dir += "../";
    }
    return "";
}

// -------------------------------------------------------------------------------------------------- the boot

static void reset_rows(void);     // defined with the install pages below
static void step_list(const std::vector<StepRow> &rows);

// What a player sees between double-clicking this program and the page they came for: the artwork, the build, and
// each check as it lands. The same list every time is what makes it read like a game's loading step.
static const char *kBootSteps[] = {"Storage", "Game data", "Game", "Server", "Build"};
static std::vector<StepRow> sBoot;
static int sBootStep = 0;      // the step at work; sBootStep == the number of steps means it is over
static Uint64 sBootStart;
static Feed sFeed;
static bool sUpdateDownloading; // this build's own zip (winUrl) is on its way
static std::string sNotice;      // what to say on the next page (a finished download)
static bool sIsoDownloaded;     // the image on disk is ours: it is deleted once it has been unpacked
static std::string sVersion;    // this program's own version, from version.txt next to it
static ImTextureID sBackdrop;

// version.txt next to this program (written by package.py from the tag the release was built from). Without it the
// build check says so rather than claiming an update that is not there.
static void read_version() {
    FILE *fp = fopen((sRoot + "version.txt").c_str(), "rb");
    if (fp == NULL) {
        return;
    }
    char buf[64] = "";
    if (fgets(buf, sizeof(buf), fp) != NULL) {
        sVersion = buf;
        while (!sVersion.empty() && (sVersion[sVersion.size() - 1] == '\n' || sVersion[sVersion.size() - 1] == '\r' ||
                                     sVersion[sVersion.size() - 1] == ' ')) {
            sVersion.resize(sVersion.size() - 1);
        }
    }
    fclose(fp);
}

// 0.5.0 -> 500, the same number the Android build puts in its version code, so one build feed serves both.
static long long version_code(const std::string &v) {
    long long out = 0;
    size_t i = 0;
    for (int part = 0; part < 3; part++) {
        size_t start = i;
        while (i < v.size() && v[i] >= '0' && v[i] <= '9') {
            i++;
        }
        if (i == start) {
            return -1;
        }
        out = out * 100 + atoi(v.substr(start, i - start).c_str());
        if (i < v.size() && v[i] == '.') {
            i++;
        }
    }
    return out;
}

// A newer build for this computer: the feed's winUrl, which is this build's own archive (the phone app's apk is
// the Android app's business). Unknown without version.txt: nothing is claimed then.
static bool update_ready(void) {
    return sFeed.ok && !sVersion.empty() && version_code(sFeed.version) > version_code(sVersion);
}

// The finished download: Windows will not let anything replace this program while it runs, so the archive is
// unpacked by hand, over this folder, with the game closed. Opening the folder is the whole of it.
static void pump_update(void) {
    if (!sUpdateDownloading) {
        return;
    }
    long long done = 0, total = 0;
    bool running = false;
    std::string error;
    Dl_Poll(done, total, running, error);
    if (running) {
        return;
    }
    sUpdateDownloading = false;
    if (!error.empty()) {
        sError = "The new build could not be downloaded: " + error + ".";
        sPage = PAGE_FAILED;
        return;
    }
    sNotice = "Build " + sFeed.version + " is downloaded: DBT3-windows-x64.zip, next to this program. Close the game, "
              "then unpack the zip over this folder (it replaces DBT3.exe, DBT3-game.exe and their files) and start "
              "DBT3 again.";
    SDL_OpenURL(("file://" + sRoot).c_str());
    sPage = PAGE_PICK;
}

static void boot_begin() {    sBoot.assign(sizeof(kBootSteps) / sizeof(kBootSteps[0]), StepRow());
    for (size_t i = 0; i < sBoot.size(); i++) {
        sBoot[i].title = kBootSteps[i];
    }
    sBootStep = 0;
    sBootStart = SDL_GetTicks();
    sBoot[0].state = RUNNING;
}

// One step per frame: a game never hangs on one, and the window keeps drawing and closing.
static void boot_tick(void) {
    if (sPage != PAGE_BOOT || sBootStep >= (int)sBoot.size()) {
        return;
    }
    StepRow &r = sBoot[sBootStep];
    switch (sBootStep) {
    case 0: { // the folder everything is written to has to be there and have room for the game
        std::string dir = sRoot.empty() ? std::string(".") : sRoot;
        bool writable = SDL_GetPathInfo(dir.c_str(), NULL);
        r.detail = writable ? "ready" : "not writable";
        r.state = writable ? OK : FAILED;
        break;
    }
    case 1: { // the unpacked data, the same marker native.cpp leaves behind
        if (!sRoot.empty() && Native_Installed(sRoot)) {
            r.detail = "already unpacked";
            r.state = OK;
        } else {
            r.detail = "not installed yet";
            r.state = SKIPPED;
        }
        break;
    }
    case 2: // the finished program this window sets up (DBT3 and DBT3.dat)
        r.detail = sRelease ? "ready" : "source checkout";
        r.state = sRelease ? OK : SKIPPED;
        break;
    case 3: // the server, and with it the newest build: no connection is not fatal, the game is all local
        sFeed = Net_Feed(kFeedUrl);
        r.detail = sFeed.ok ? "online" : sFeed.error.empty() ? "offline" : sFeed.error;
        r.state = sFeed.ok ? OK : SKIPPED;
        break;
    default: { // is this the newest build?
        long long mine = version_code(sVersion), theirs = version_code(sFeed.version);
        if (!sFeed.ok) {
            r.detail = "not checked";
            r.state = SKIPPED;
        } else if (sVersion.empty()) {
            r.detail = "server build " + sFeed.version; // this copy carries no version.txt (see read_version)
            r.state = SKIPPED;
        } else {
            r.detail = theirs > mine ? "build " + sFeed.version + " is out" : "up to date (" + sVersion + ")";
            r.state = OK;
            // ponytail: no button to fetch it yet — one SDL_OpenURL(apk_url) on the next page when you want it
        }
        break;
    }
    }
    r.end = SDL_GetTicks();
    sBootStep++;
    if (sBootStep < (int)sBoot.size()) {
        sBoot[sBootStep].state = RUNNING;
        return;
    }
    // never a flash of a page the player cannot read (and never a frozen window while it waits: it is a gate, not a
    // sleep)
    if (SDL_GetTicks() - sBootStart < 1400) {
        return;
    }
    sPage = sRelease && !sRoot.empty() && Native_Installed(sRoot) ? PAGE_DONE : PAGE_PICK;
    if (sPage == PAGE_DONE) {
        reset_rows();
        for (auto &row : sRows) {
            row.state = SKIPPED;
            row.detail = "already done";
        }
        sLauncher = sRoot + (file_exists((sRoot + GAME ".exe").c_str()) ? GAME ".exe" : GAME);
    }
}

// The picture on the boot page, as a texture for whichever backend is drawing. Loaded once, kept until the end.
static void load_backdrop(void) {
    Image img = Img_Load(sRoot + kBootBackdrop);
    if (img.rgba == NULL) {
        return;
    }
    if (sUseGl) {
        typedef void (*PFN_TEXIMAGE2D)(unsigned, int, int, int, int, int, unsigned, unsigned, const void *);
        PFN_TEXIMAGE2D tex_image = (PFN_TEXIMAGE2D)SDL_GL_GetProcAddress("glTexImage2D");
        GLuint tex = 0;
        glGenTextures(1, &tex);
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        if (tex_image != NULL) {
            tex_image(GL_TEXTURE_2D, 0, GL_RGBA, img.width, img.height, 0, GL_RGBA, GL_UNSIGNED_BYTE, img.rgba);
        }
        sBackdrop = (ImTextureID)(intptr_t)tex;
    } else {
        SDL_GPUTextureCreateInfo ci;
        SDL_zero(ci);
        ci.type = SDL_GPU_TEXTURETYPE_2D;
        ci.format = SDL_GPU_TEXTUREFORMAT_R8G8B8A8_UNORM;
        ci.usage = SDL_GPU_TEXTUREUSAGE_SAMPLER; // SDL3 adds the copy usage itself
        ci.width = img.width;
        ci.height = img.height;
        ci.layer_count_or_depth = 1;
        ci.num_levels = 1;
        SDL_GPUTexture *tex = SDL_CreateGPUTexture(sDevice, &ci);
        if (tex != NULL) {
            SDL_GPUCommandBuffer *cmd = SDL_AcquireGPUCommandBuffer(sDevice);
            SDL_GPUTransferBufferCreateInfo ti;
            SDL_zero(ti);
            ti.usage = SDL_GPU_TRANSFERBUFFERUSAGE_UPLOAD;
            ti.size = (Uint32)((size_t)img.width * img.height * 4);
            SDL_GPUTransferBuffer *tb = SDL_CreateGPUTransferBuffer(sDevice, &ti);
            if (tb == NULL) {
                SDL_ReleaseGPUTexture(sDevice, tex);
                Img_Free(img);
                return;
            }
            void *mapped = SDL_MapGPUTransferBuffer(sDevice, tb, false);
            if (mapped != NULL) {
                memcpy(mapped, img.rgba, ti.size);
            }
            SDL_UnmapGPUTransferBuffer(sDevice, tb);
            SDL_GPUTextureTransferInfo source; // where the picture is in the transfer buffer, row by row
            SDL_zero(source);
            source.transfer_buffer = tb;
            source.offset = 0;
            source.pixels_per_row = (Uint32)img.width;
            source.rows_per_layer = (Uint32)img.height;
            SDL_GPUTextureRegion target;        // the whole texture
            SDL_zero(target);
            target.texture = tex;
            target.w = (Uint32)img.width;
            target.h = (Uint32)img.height;
            target.d = 1;
            SDL_GPUCopyPass *cp = SDL_BeginGPUCopyPass(cmd);
            SDL_UploadToGPUTexture(cp, &source, &target, false);
            SDL_EndGPUCopyPass(cp);
            SDL_SubmitGPUCommandBuffer(cmd);
            SDL_ReleaseGPUTransferBuffer(sDevice, tb);
            sBackdrop = (ImTextureID)(intptr_t)tex;
        }
    }
    Img_Free(img);
}

static void style() {
    ImGuiStyle &st = ImGui::GetStyle();
    ImGui::StyleColorsDark();
    st.WindowRounding = 0.0f;
    st.FrameRounding = 6.0f;
    st.GrabRounding = 6.0f;
    st.WindowPadding = ImVec2(28.0f, 22.0f);
    st.FramePadding = ImVec2(12.0f, 8.0f);
    st.ItemSpacing = ImVec2(10.0f, 10.0f);
    st.WindowBorderSize = 0.0f;
    ImVec4 *c = st.Colors;
    c[ImGuiCol_WindowBg] = ImVec4(0.07f, 0.08f, 0.11f, 1.0f);
    c[ImGuiCol_FrameBg] = ImVec4(0.15f, 0.17f, 0.23f, 1.0f);
    c[ImGuiCol_FrameBgHovered] = ImVec4(0.21f, 0.24f, 0.32f, 1.0f);
    c[ImGuiCol_FrameBgActive] = ImVec4(0.26f, 0.29f, 0.38f, 1.0f);
    c[ImGuiCol_Button] = ImVec4(0.17f, 0.20f, 0.27f, 1.0f);
    c[ImGuiCol_ButtonHovered] = ImVec4(0.96f, 0.55f, 0.13f, 0.55f);
    c[ImGuiCol_ButtonActive] = kAccent;
    c[ImGuiCol_PlotHistogram] = kAccent;
    c[ImGuiCol_TextSelectedBg] = ImVec4(0.96f, 0.55f, 0.13f, 0.35f);
}

// ---------------------------------------------------------------------------------------------- the engine script

static void reset_rows() {
    size_t n = sRelease ? sizeof(kReleaseSteps) / sizeof(kReleaseSteps[0]) : sizeof(kSourceSteps) / sizeof(kSourceSteps[0]);
    sRows.assign(n, StepRow());
    for (size_t i = 0; i < n; i++) {
        sRows[i].title = sRelease ? kReleaseSteps[i] : kSourceSteps[i];
    }
}

static void start_install() {
    static std::string iso;
    const char *args[8];
    SDL_PropertiesID props = SDL_CreateProperties();
    std::string script = sRoot + "install.py";
    int n = 0;

    if (sRelease) {
        SDL_DestroyProperties(props);
        reset_rows();
        sMissing.clear();
        sError.clear();
        sLauncher.clear();
        sRunStart = SDL_GetTicks();
        sNative = true;
        Native_Start(sRoot, sIso);
        sPage = PAGE_RUN;
        return;
    }
    iso = sIso;
    args[n++] = "python3";
    args[n++] = script.c_str();
    args[n++] = "--machine";
    args[n++] = "--iso";
    args[n++] = iso.c_str();
    if (getenv("BT3_SETUP_ARGS") != NULL) { // one extra argument for testing, such as --skip-test
        args[n++] = getenv("BT3_SETUP_ARGS");
    }
    args[n] = NULL;
    SDL_SetPointerProperty(props, SDL_PROP_PROCESS_CREATE_ARGS_POINTER, (void *)args);
    SDL_SetStringProperty(props, SDL_PROP_PROCESS_CREATE_WORKING_DIRECTORY_STRING, sRoot.c_str());
    SDL_SetNumberProperty(props, SDL_PROP_PROCESS_CREATE_STDIN_NUMBER, SDL_PROCESS_STDIO_NULL);
    SDL_SetNumberProperty(props, SDL_PROP_PROCESS_CREATE_STDOUT_NUMBER, SDL_PROCESS_STDIO_APP);
    SDL_SetBooleanProperty(props, SDL_PROP_PROCESS_CREATE_STDERR_TO_STDOUT_BOOLEAN, true);
    reset_rows();
    sMissing.clear();
    sError.clear();
    sLine.clear();
    sLauncher.clear();
    sProc = SDL_CreateProcessWithProperties(props);
    SDL_DestroyProperties(props);
    sRunStart = SDL_GetTicks();
    if (sProc == NULL) {
        sError = std::string("Could not start python3: ") + SDL_GetError() + "\nPython 3 has to be installed to set the game up.";
        sPage = PAGE_FAILED;
        return;
    }
    sPage = PAGE_RUN;
}

static StepRow *current() {
    for (auto &r : sRows) {
        if (r.state == RUNNING) {
            return &r;
        }
    }
    return NULL;
}

static std::string unescape(const std::string &s) {
    std::string out;
    for (size_t i = 0; i < s.size(); i++) {
        if (s[i] == '\\' && i + 1 < s.size() && s[i + 1] == 'n') {
            out += '\n';
            i++;
            while (i + 1 < s.size() && s[i + 1] == ' ') { // the terminal version indents continuation lines
                i++;
            }
        } else {
            out += s[i];
        }
    }
    return out;
}

static void event(const std::string &line) {
    if (line.empty() || line[0] != '@') {
        return;
    }
    size_t sp = line.find(' ');
    std::string kind = line.substr(1, sp == std::string::npos ? std::string::npos : sp - 1);
    std::string text = sp == std::string::npos ? "" : unescape(line.substr(sp + 1));
    StepRow *cur = current();

    if (kind == "step") {
        int i = atoi(text.c_str());
        if (i >= 1 && i <= (int)sRows.size()) {
            sRows[i - 1].state = RUNNING;
            sRows[i - 1].start = SDL_GetTicks();
            sRows[i - 1].detail.clear();
        }
    } else if (cur != NULL && kind == "note") {
        cur->detail = text;
    } else if (cur != NULL && (kind == "ok" || kind == "skip" || kind == "fail")) {
        cur->state = kind == "ok" ? OK : kind == "skip" ? SKIPPED : FAILED;
        cur->end = SDL_GetTicks();
        cur->detail = kind == "fail" ? "" : text;
    } else if (kind == "missing") {
        Missing m;
        size_t a = text.find('|'), b = text.find('|', a == std::string::npos ? 0 : a + 1);
        if (a != std::string::npos && b != std::string::npos) {
            m.name = text.substr(0, a);
            m.why = text.substr(a + 1, b - a - 1);
            m.pkg = text.substr(b + 1);
            sMissing.push_back(m);
        }
    } else if (kind == "stopped") {
        sError = text;
    } else if (kind == "done") {
        sLauncher = text;
    }
}

static void pump_install() {
    char buf[4096];
    SDL_IOStream *out;
    int code = 0;

    if (sNative) {
        std::string line;
        bool ended = !Native_Running(); // asked before the queue is emptied: nothing is left behind
        while (Native_Poll(line)) {
            event(line);
        }
        if (ended) {
            sNative = false;
            sRunEnd = SDL_GetTicks();
            for (auto &r : sRows) {
                if (r.state == RUNNING) {
                    r.state = FAILED;
                    r.end = sRunEnd;
                }
            }
            if (!sLauncher.empty()) {
                sPage = PAGE_DONE;
            } else {
                if (sError.empty()) {
                    sError = "The setup stopped unexpectedly.";
                }
                sPage = PAGE_FAILED;
            }
        }
        return;
    }
    if (sProc == NULL) {
        return;
    }
    out = SDL_GetProcessOutput(sProc);
    for (;;) {
        size_t n = out != NULL ? SDL_ReadIO(out, buf, sizeof(buf)) : 0;
        if (n == 0) {
            break;
        }
        for (size_t i = 0; i < n; i++) {
            if (buf[i] == '\n') {
                event(sLine);
                sLine.clear();
            } else if (buf[i] != '\r') {
                sLine += buf[i];
            }
        }
    }
    if (SDL_WaitProcess(sProc, false, &code)) {
        event(sLine);
        SDL_DestroyProcess(sProc);
        sProc = NULL;
        sRunEnd = SDL_GetTicks();
        for (auto &r : sRows) {
            if (r.state == RUNNING) {
                r.state = FAILED;
                r.end = sRunEnd;
            }
        }
        if (code == 0 && !sLauncher.empty()) {
            sPage = PAGE_DONE;
        } else {
            if (sError.empty()) {
                sError = "The setup script stopped unexpectedly. The details are in install.log.";
            }
            sPage = PAGE_FAILED;
        }
    }
}

// --------------------------------------------------------------------------------------------------------- pages

static void SDLCALL dialog_done(void *userdata, const char *const *files, int filter) {
    (void)userdata; (void)filter;
    std::lock_guard<std::mutex> lock(sDialogLock);
    if (files != NULL && files[0] != NULL) {
        sDialogResult = files[0];
        sDialogReady = true;
    }
}

static void heading(const char *title, const char *sub) {
    ImGui::PushFont(NULL, 30.0f);
    ImGui::TextUnformatted(title);
    ImGui::PopFont();
    ImGui::PushStyleColor(ImGuiCol_Text, kDim);
    ImGui::TextWrapped("%s", sub);
    ImGui::PopStyleColor();
    ImGui::Dummy(ImVec2(0.0f, 8.0f));
}

static bool big_button(const char *label, bool primary, bool enabled = true) {
    bool pressed;
    if (primary) {
        ImGui::PushStyleColor(ImGuiCol_Button, ImVec4(0.96f, 0.55f, 0.13f, 0.90f));
        ImGui::PushStyleColor(ImGuiCol_ButtonHovered, ImVec4(1.0f, 0.63f, 0.22f, 1.0f));
        ImGui::PushStyleColor(ImGuiCol_Text, ImVec4(0.08f, 0.06f, 0.03f, 1.0f));
    }
    ImGui::BeginDisabled(!enabled);
    pressed = ImGui::Button(label, ImVec2(150.0f, 40.0f));
    ImGui::EndDisabled();
    if (primary) {
        ImGui::PopStyleColor(3);
    }
    return pressed;
}

// Buttons of a page sit on one line at the bottom right.
static void bottom_row(int buttons) {
    ImVec2 avail = ImGui::GetContentRegionAvail();
    float w = buttons * 150.0f + (buttons - 1) * ImGui::GetStyle().ItemSpacing.x;
    ImGui::SetCursorPos(ImVec2(ImGui::GetCursorPosX() + avail.x - w, ImGui::GetCursorPosY() + avail.y - 40.0f));
}

static std::string human_bytes(long long n) {
    char buf[64];
    if (n >= 1LL << 30) {
        snprintf(buf, sizeof(buf), "%.1f GB", n / (double)(1LL << 30));
    } else if (n >= 1LL << 20) {
        snprintf(buf, sizeof(buf), "%.0f MB", n / (double)(1LL << 20));
    } else {
        snprintf(buf, sizeof(buf), "%lld kB", n / 1024);
    }
    return buf;
}

// The image from the project's own server: it runs on a thread of its own (net.cpp) and is shown as the run page's
// first step; when it is whole it is installed like an image the player picked, and then deleted.
static bool sDownloading;
static void pump_download(void) {
    if (!sDownloading) {
        return;
    }
    long long done = 0, total = 0;
    bool running = false;
    std::string error;
    Dl_Poll(done, total, running, error);
    StepRow &r = sRows[0];
    r.state = RUNNING;
    if (r.start == 0) {
        r.start = SDL_GetTicks();
    }
    r.detail = total > 0 ? human_bytes(done) + " of " + human_bytes(total) : human_bytes(done);
    if (running) {
        return;
    }
    sDownloading = false;
    r.end = SDL_GetTicks();
    if (!error.empty()) {
        r.state = FAILED;
        sError = "The download did not finish: " + error + ".";
        sPage = PAGE_FAILED;
        return;
    }
    SDL_strlcpy(sIso, (sRoot + "DBT3-MOBILE.iso").c_str(), sizeof(sIso));
    start_install(); // the rows start again: the image is unpacked into the game data now
}

static void page_boot(void) {
    ImVec2 size = ImGui::GetIO().DisplaySize;
    ImDrawList *dl = ImGui::GetBackgroundDrawList();
    if (sBackdrop != NULL) {
        dl->AddImage(sBackdrop, ImVec2(0.0f, 0.0f), size); // stretched over the window: a splash, not a picture frame
        dl->AddRectFilled(ImVec2(0.0f, size.y * 0.30f), size, ImGui::GetColorU32(ImVec4(0.04f, 0.05f, 0.08f, 0.88f)));
    }
    heading(LAUNCH, sVersion.empty() ? "Getting the game ready" : ("Getting the game ready  ·  build " + sVersion).c_str());
    step_list(sBoot);
    int done = 0;
    for (size_t i = 0; i < sBoot.size(); i++) {
        done += sBoot[i].state != PENDING && sBoot[i].state != RUNNING;
    }
    ImGui::Dummy(ImVec2(0.0f, 4.0f));
    ImGui::ProgressBar((float)done / (float)(sBoot.empty() ? 1 : sBoot.size()), ImVec2(-FLT_MIN, 8.0f), "");
    ImGui::TextColored(kDim, "Please wait...");
    if (!update_ready() && !sUpdateDownloading) {
        return;
    }
    ImGui::Dummy(ImVec2(0.0f, 10.0f));
    if (sUpdateDownloading) {
        long long got = 0, all = 0;
        bool running = false;
        std::string error;
        Dl_Poll(got, all, running, error);
        std::string label = "Build " + sFeed.version + "  " + human_bytes(got) + (all > 0 ? " of " + human_bytes(all) : "");
        ImGui::ProgressBar(all > 0 ? (float)got / (float)all : 0.0f, ImVec2(-FLT_MIN, 8.0f), label.c_str());
        bottom_row(1);
        if (big_button("Cancel", false)) {
            Dl_Cancel();
            sUpdateDownloading = false;
        }
        return;
    }
    ImGui::TextWrapped("Build %s is out. It is a zip, unpacked over this folder with the game closed.", sFeed.version.c_str());
    bottom_row(1);
    if (big_button(("Get build " + sFeed.version).c_str(), true, !Dl_Active())) {
        std::string error;
        if (Dl_Start(sFeed.win_url, sRoot + "DBT3-windows-x64.zip", error)) {
            sUpdateDownloading = true;
        } else {
            sError = error;
        }
    }
}

static void page_pick() {
    static const SDL_DialogFileFilter filters[] = {{"PS2 disc image", "iso;bin;img"}, {"All files", "*"}};
    bool ok = file_exists(sIso);

    heading(LAUNCH, "This sets the game up on your computer from your own PlayStation 2 disc.");
    if (!sNotice.empty()) {
        ImGui::PushStyleColor(ImGuiCol_Text, kGreen);
        ImGui::TextWrapped("%s", sNotice.c_str());
        ImGui::PopStyleColor();
        sNotice.clear();
    }
    ImGui::TextWrapped("Choose your disc image of Dragon Ball Z: Budokai Tenkaichi 3, USA release (SLUS-21678), as an .iso file. "
                       "The game's data is taken from it and stays on this computer. %s",
                       sRelease ? "Nothing is downloaded." : "Nothing is downloaded except one small tool.");
    ImGui::Dummy(ImVec2(0.0f, 10.0f));
    ImGui::TextUnformatted("Disc image");
    ImGui::SetNextItemWidth(ImGui::GetContentRegionAvail().x - 130.0f);
    ImGui::InputTextWithHint("##iso", "drop the file on this window, or browse", sIso, sizeof(sIso));
    ImGui::SameLine();
    if (ImGui::Button("Browse...", ImVec2(120.0f, 0.0f))) {
        SDL_ShowOpenFileDialog(dialog_done, NULL, sWindow, filters, 2, NULL, false);
    }
    if (sIso[0] != '\0' && !ok) {
        ImGui::TextColored(kRed, "That file does not exist.");
    } else if (sRoot.empty()) {
        ImGui::TextColored(kRed, "Neither the game (" GAME ", " GAME ".dat) nor install.py was found next to this program.");
    } else {
        ImGui::TextColored(kDim, sRelease ? "Needs about 4 GB of free space and takes a minute or two." : "Needs about 4 GB of free space and takes 5 to 10 minutes.");
    }
    ImGui::Dummy(ImVec2(0.0f, 10.0f));
    ImGui::TextWrapped("Or take the image from the project server instead: 2.8 GB, and it is deleted once the game "
                       "data is unpacked. Windows only (the Linux build links no HTTPS library).");
    if (big_button("Download", false, !sRoot.empty() && !Dl_Active())) {
        reset_rows();
        std::string error;
        if (Dl_Start(kIsoUrl, sRoot + "DBT3-MOBILE.iso", error)) {
            sIsoDownloaded = true;
            sDownloading = true;
            sError.clear();
            sPage = PAGE_RUN;
        } else {
            sError = error;
        }
    }
    bottom_row(2);
    if (big_button("Close", false)) {
        sQuit = true;
    }
    ImGui::SameLine();
    if (big_button("Install", true, ok && !sRoot.empty())) {
        sIsoDownloaded = false; // the image being installed is the player's own now, never ours to delete
        start_install();
    }
}

static void state_icon(State st, float t) {
    ImDrawList *dl = ImGui::GetWindowDrawList();
    ImVec2 p = ImGui::GetCursorScreenPos();
    ImVec2 c(p.x + 11.0f, p.y + 11.0f);

    if (st == OK || st == SKIPPED) {
        ImU32 col = ImGui::GetColorU32(kGreen);
        dl->AddCircle(c, 10.0f, col, 32, 1.6f);
        dl->AddLine(ImVec2(c.x - 5.0f, c.y), ImVec2(c.x - 1.5f, c.y + 4.0f), col, 2.0f);
        dl->AddLine(ImVec2(c.x - 1.5f, c.y + 4.0f), ImVec2(c.x + 5.5f, c.y - 4.0f), col, 2.0f);
    } else if (st == FAILED) {
        ImU32 col = ImGui::GetColorU32(kRed);
        dl->AddCircle(c, 10.0f, col, 32, 1.6f);
        dl->AddLine(ImVec2(c.x - 4.0f, c.y - 4.0f), ImVec2(c.x + 4.0f, c.y + 4.0f), col, 2.0f);
        dl->AddLine(ImVec2(c.x - 4.0f, c.y + 4.0f), ImVec2(c.x + 4.0f, c.y - 4.0f), col, 2.0f);
    } else if (st == RUNNING) {
        float a = t * 5.0f;
        dl->AddCircle(c, 10.0f, ImGui::GetColorU32(ImVec4(1.0f, 1.0f, 1.0f, 0.12f)), 32, 2.0f);
        dl->PathArcTo(c, 10.0f, a, a + 1.9f, 24);
        dl->PathStroke(ImGui::GetColorU32(kAccent), 0, 2.4f);
    } else {
        dl->AddCircle(c, 10.0f, ImGui::GetColorU32(ImVec4(1.0f, 1.0f, 1.0f, 0.18f)), 32, 1.4f);
    }
    ImGui::Dummy(ImVec2(22.0f, 22.0f));
}

static void step_list(const std::vector<StepRow> &rows) {
    float t = (float)SDL_GetTicks() / 1000.0f;
    for (size_t i = 0; i < rows.size(); i++) {
        const StepRow &r = rows[i];
        char clock[32] = "";
        state_icon(r.state, t);
        ImGui::SameLine(0.0f, 14.0f);
        ImGui::BeginGroup();
        ImGui::PushStyleVar(ImGuiStyleVar_ItemSpacing, ImVec2(10.0f, 1.0f));
        ImGui::TextColored(r.state == PENDING ? kDim : ImGui::GetStyleColorVec4(ImGuiCol_Text), "%s", r.title.c_str());
        ImGui::TextColored(kDim, "%s", !r.detail.empty() ? r.detail.c_str() : step_help(r.title));
        ImGui::PopStyleVar();
        ImGui::EndGroup();
        if (r.state == RUNNING) {
            snprintf(clock, sizeof(clock), "%d s", (int)((SDL_GetTicks() - r.start) / 1000));
        } else if ((r.state == OK || r.state == FAILED) && r.end - r.start >= 2000) {
            snprintf(clock, sizeof(clock), "%d s", (int)((r.end - r.start) / 1000));
        }
        if (clock[0] != '\0') {
            ImGui::SameLine(ImGui::GetWindowWidth() - ImGui::GetStyle().WindowPadding.x - ImGui::CalcTextSize(clock).x);
            ImGui::TextColored(kDim, "%s", clock);
        }
    }
}

static void page_run() {
    int done = 0;
    heading(sDownloading ? "Downloading" : "Setting up",
            sDownloading ? "You can leave this running; Cancel stops it and a part-downloaded image is deleted."
                         : "You can leave this running. Finished steps are kept if it is interrupted.");
    step_list(sRows);
    for (auto &r : sRows) {
        done += r.state == OK || r.state == SKIPPED;
    }
    ImGui::Dummy(ImVec2(0.0f, 4.0f));
    ImGui::ProgressBar((float)done / (float)(sRows.empty() ? 1 : sRows.size()), ImVec2(-FLT_MIN, 8.0f), "");
    bottom_row(1);
    if (big_button("Cancel", false)) {
        if (sDownloading) {
            Dl_Cancel();
            sDownloading = false;
            sIsoDownloaded = false; // nothing of ours stays on disk: the worker deletes its part-downloaded image
            SDL_strlcpy(sIso, "", sizeof(sIso));
            sPage = PAGE_PICK;
        } else if (sProc != NULL) {
            SDL_KillProcess(sProc, false);
            Native_Cancel();
            sError = "Cancelled. Finished steps are kept; Install continues from where it stopped.";
        } else {
            Native_Cancel();
            sError = "Cancelled. Finished steps are kept; Install continues from where it stopped.";
        }
    }
}

static void page_done() {
    char sub[128];
    if (sRunEnd > sRunStart) {
        snprintf(sub, sizeof(sub), "The game is installed and its demo fight ran correctly.  (%d min %d s)", (int)((sRunEnd - sRunStart) / 60000),
                 (int)((sRunEnd - sRunStart) / 1000 % 60));
    } else {
        snprintf(sub, sizeof(sub), "The game is installed.");
    }
    heading("Ready to play", sub);
    step_list(sRows);
    ImGui::Dummy(ImVec2(0.0f, 4.0f));
    ImGui::TextColored(kDim, "In the game, F1 opens the settings: resolution, widescreen, controls and sound.");
    bottom_row(2);
    if (big_button("Close", false)) {
        sQuit = true;
    }
    ImGui::SameLine();
    if (big_button("Play", true)) {
        const char *args[2] = {sLauncher.c_str(), NULL};
        SDL_PropertiesID props = SDL_CreateProperties();
        SDL_Environment *env = SDL_CreateEnvironment(true);
        SDL_SetEnvironmentVariable(env, "BT3_GS", "gpu", true); // the window and the GPU renderer
        SDL_SetEnvironmentVariable(env, "BT3_LAUNCH", "1", true); // a release game only starts from here (see src/main.c)
        SDL_UnsetEnvironmentVariable(env, "BT3_DEMO");
        SDL_UnsetEnvironmentVariable(env, "BT3_REPLAY");
        SDL_SetPointerProperty(props, SDL_PROP_PROCESS_CREATE_ARGS_POINTER, (void *)args);
        SDL_SetPointerProperty(props, SDL_PROP_PROCESS_CREATE_ENVIRONMENT_POINTER, env);
        SDL_SetStringProperty(props, SDL_PROP_PROCESS_CREATE_WORKING_DIRECTORY_STRING, sRoot.c_str());
        SDL_SetBooleanProperty(props, SDL_PROP_PROCESS_CREATE_BACKGROUND_BOOLEAN, true);
        SDL_Process *game = SDL_CreateProcessWithProperties(props);
        SDL_DestroyProperties(props);
        SDL_DestroyEnvironment(env);
        if (game != NULL) {
            SDL_DestroyProcess(game);
            sQuit = true;
        }
    }
}

static void page_failed() {
    heading("Setup stopped", "Nothing is broken: finished steps are kept, and Install continues from where it stopped.");
    ImGui::PushStyleColor(ImGuiCol_ChildBg, ImVec4(0.93f, 0.36f, 0.33f, 0.10f));
    ImGui::BeginChild("why", ImVec2(0.0f, sMissing.empty() ? 150.0f : 90.0f), ImGuiChildFlags_AlwaysUseWindowPadding);
    ImGui::TextWrapped("%s", sError.c_str());
    ImGui::EndChild();
    ImGui::PopStyleColor();
    if (!sMissing.empty() && ImGui::BeginTable("missing", 3, ImGuiTableFlags_RowBg | ImGuiTableFlags_BordersInnerV)) {
        ImGui::TableSetupColumn("Missing");
        ImGui::TableSetupColumn("Used for");
        ImGui::TableSetupColumn("Package to install");
        ImGui::TableHeadersRow();
        for (auto &m : sMissing) {
            ImGui::TableNextRow();
            ImGui::TableNextColumn(); ImGui::TextUnformatted(m.name.c_str());
            ImGui::TableNextColumn(); ImGui::TextWrapped("%s", m.why.c_str());
            ImGui::TableNextColumn(); ImGui::TextWrapped("%s", m.pkg.c_str());
        }
        ImGui::EndTable();
    }
    bottom_row(3);
    if (big_button("Open the log", false)) {
        SDL_OpenURL(("file://" + sRoot + "install.log").c_str());
    }
    ImGui::SameLine();
    if (big_button("Close", false)) {
        sQuit = true;
    }
    ImGui::SameLine();
    if (big_button("Back", true)) {
        sPage = PAGE_PICK;
    }
}

static void build_ui() {
    ImGuiIO &io = ImGui::GetIO();
    ImGui::SetNextWindowPos(ImVec2(0.0f, 0.0f));
    ImGui::SetNextWindowSize(io.DisplaySize);
    if (sPage == PAGE_BOOT) {
        ImGui::PushStyleColor(ImGuiCol_WindowBg, ImVec4(0.0f, 0.0f, 0.0f, 0.0f)); // the artwork shows through
    }
    ImGui::Begin("setup", NULL, ImGuiWindowFlags_NoDecoration | ImGuiWindowFlags_NoMove | ImGuiWindowFlags_NoSavedSettings);
    switch (sPage) {
    case PAGE_BOOT: page_boot(); break;
    case PAGE_PICK: page_pick(); break;
    case PAGE_RUN: page_run(); break;
    case PAGE_DONE: page_done(); break;
    case PAGE_FAILED: page_failed(); break;
    }
    ImGui::End();
    if (sPage == PAGE_BOOT) {
        ImGui::PopStyleColor();
    }
}

// ---------------------------------------------------------------------------------------------------- the window

// BT3_SETUP_SHOT: the picture drawn a second time into a texture and written as a PPM file.
static void shot(ImDrawData *dd, Uint32 w, Uint32 h, int number) {
    SDL_GPUTextureFormat fmt = SDL_GetGPUSwapchainTextureFormat(sDevice, sWindow);
    bool bgr = fmt == SDL_GPU_TEXTUREFORMAT_B8G8R8A8_UNORM || fmt == SDL_GPU_TEXTUREFORMAT_B8G8R8A8_UNORM_SRGB;
    SDL_GPUTextureCreateInfo ci;
    SDL_GPUTransferBufferCreateInfo ti;
    SDL_GPUColorTargetInfo target;
    SDL_GPUTextureRegion src;
    SDL_GPUTextureTransferInfo dst;
    char name[512];

    SDL_zero(ci);
    ci.type = SDL_GPU_TEXTURETYPE_2D;
    ci.format = fmt;
    ci.usage = SDL_GPU_TEXTUREUSAGE_COLOR_TARGET | SDL_GPU_TEXTUREUSAGE_SAMPLER;
    ci.width = w;
    ci.height = h;
    ci.layer_count_or_depth = 1;
    ci.num_levels = 1;
    SDL_GPUTexture *tex = SDL_CreateGPUTexture(sDevice, &ci);
    SDL_GPUCommandBuffer *cmd = SDL_AcquireGPUCommandBuffer(sDevice);
    ImGui_ImplSDLGPU3_PrepareDrawData(dd, cmd);
    SDL_zero(target);
    target.texture = tex;
    target.load_op = SDL_GPU_LOADOP_CLEAR;
    target.store_op = SDL_GPU_STOREOP_STORE;
    SDL_GPURenderPass *pass = SDL_BeginGPURenderPass(cmd, &target, 1, NULL);
    ImGui_ImplSDLGPU3_RenderDrawData(dd, cmd, pass);
    SDL_EndGPURenderPass(pass);
    SDL_zero(ti);
    ti.usage = SDL_GPU_TRANSFERBUFFERUSAGE_DOWNLOAD;
    ti.size = w * h * 4;
    SDL_GPUTransferBuffer *tb = SDL_CreateGPUTransferBuffer(sDevice, &ti);
    SDL_zero(src);
    src.texture = tex;
    src.w = w;
    src.h = h;
    src.d = 1;
    SDL_zero(dst);
    dst.transfer_buffer = tb;
    SDL_GPUCopyPass *cp = SDL_BeginGPUCopyPass(cmd);
    SDL_DownloadFromGPUTexture(cp, &src, &dst);
    SDL_EndGPUCopyPass(cp);
    SDL_GPUFence *fence = SDL_SubmitGPUCommandBufferAndAcquireFence(cmd);
    SDL_WaitForGPUFences(sDevice, true, &fence, 1);
    SDL_ReleaseGPUFence(sDevice, fence);
    const Uint8 *px = (const Uint8 *)SDL_MapGPUTransferBuffer(sDevice, tb, false);
    snprintf(name, sizeof(name), "%s_%d.ppm", getenv("BT3_SETUP_SHOT"), number);
    FILE *fp = fopen(name, "wb");
    if (fp != NULL && px != NULL) {
        fprintf(fp, "P6\n%u %u\n255\n", w, h);
        for (Uint32 i = 0; i < w * h; i++) {
            Uint8 rgb[3] = {px[i * 4 + (bgr ? 2 : 0)], px[i * 4 + 1], px[i * 4 + (bgr ? 0 : 2)]};
            fwrite(rgb, 1, 3, fp);
        }
        fclose(fp);
    }
    SDL_UnmapGPUTransferBuffer(sDevice, tb);
    SDL_ReleaseGPUTransferBuffer(sDevice, tb);
    SDL_ReleaseGPUTexture(sDevice, tex);
}

int main(int argc, char **argv) {
    static const char *fonts[] = {
        "/usr/share/fonts/TTF/DejaVuSans.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/usr/share/fonts/noto/NotoSans-Regular.ttf", "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf",
        "/usr/share/fonts/liberation/LiberationSans-Regular.ttf", "C:\\Windows\\Fonts\\segoeui.ttf",
    };
    ImGui_ImplSDLGPU3_InitInfo info;
    int shots = 0, shotKey = -1;

    if (argc > 2 && strcmp(argv[1], "--install") == 0) {
        // Without a window: DBT3 --install <disc.iso>. A release folder only; prints the event lines.
        std::string line;
        bool ok = false;
        SDL_Init(0);
        sRoot = find_root();
        if (!sRelease) {
            fprintf(stderr, LAUNCH " --install works in a release folder (" GAME ", " GAME ".dat next to it).\nIn a source checkout: python3 install.py --iso <file>\n");
            return 2;
        }
        Native_Start(sRoot, argv[2]);
        for (bool last = false; !last;) {
            last = !Native_Running(); // one more round after the worker has ended: nothing is left behind
            while (Native_Poll(line)) {
                printf("%s\n", line.c_str());
                fflush(stdout);
                ok = ok || line.rfind("@done", 0) == 0;
            }
            SDL_Delay(50);
        }
        Native_Join();
        return ok ? 0 : 1;
    }
    if (!SDL_Init(SDL_INIT_VIDEO)) {
        fprintf(stderr, LAUNCH ": %s\n", SDL_GetError());
        return 1;
    }
    const char *glsl = "#version 330";
    {
        const char *api = getenv("BT3_GPU_API");
        bool wantGl = api != NULL && (strcmp(api, "gl") == 0 || strcmp(api, "opengl") == 0);
        std::string why;
        if (!wantGl) {
            sWindow = SDL_CreateWindow(LAUNCH, 760, 560, SDL_WINDOW_HIGH_PIXEL_DENSITY);
            sDevice = SDL_CreateGPUDevice(SDL_GPU_SHADERFORMAT_SPIRV | SDL_GPU_SHADERFORMAT_DXIL | SDL_GPU_SHADERFORMAT_MSL, false, NULL);
            if (sWindow == NULL || sDevice == NULL || !SDL_ClaimWindowForGPUDevice(sDevice, sWindow)) {
                why = SDL_GetError();
                if (sDevice != NULL) {
                    SDL_DestroyGPUDevice(sDevice);
                    sDevice = NULL;
                }
                if (sWindow != NULL) {
                    SDL_DestroyWindow(sWindow);
                    sWindow = NULL;
                }
            }
        }
        if (sDevice == NULL) {
            // OpenGL 3.3 core, and failing that whatever 3.0 context the driver gives
            for (int attempt = 0; attempt < 2 && sGl == NULL; attempt++) {
                SDL_GL_ResetAttributes();
                SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3);
                SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, attempt == 0 ? 3 : 0);
                SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, attempt == 0 ? SDL_GL_CONTEXT_PROFILE_CORE : 0);
                SDL_GL_SetAttribute(SDL_GL_DOUBLEBUFFER, 1);
                glsl = attempt == 0 ? "#version 330" : "#version 130";
                sWindow = SDL_CreateWindow(LAUNCH, 760, 560, SDL_WINDOW_HIGH_PIXEL_DENSITY | SDL_WINDOW_OPENGL);
                sGl = sWindow != NULL ? SDL_GL_CreateContext(sWindow) : NULL;
                if (sGl == NULL && sWindow != NULL) {
                    SDL_DestroyWindow(sWindow);
                    sWindow = NULL;
                }
            }
            if (sGl == NULL) {
                fprintf(stderr, LAUNCH ": no window: %s%s%s\nThe same setup runs without a window: " LAUNCH " --install <disc image>\n",
                        why.c_str(), why.empty() ? "" : "; OpenGL: ", SDL_GetError());
                return 1;
            }
            if (!wantGl) {
                fprintf(stderr, LAUNCH ": %s; the window uses OpenGL\n", why.c_str());
            }
            SDL_GL_MakeCurrent(sWindow, sGl);
            SDL_GL_SetSwapInterval(1);
            sUseGl = true;
        } else {
            SDL_SetGPUSwapchainParameters(sDevice, sWindow, SDL_GPU_SWAPCHAINCOMPOSITION_SDR, SDL_GPU_PRESENTMODE_VSYNC);
        }
    }
    IMGUI_CHECKVERSION();
    ImGui::CreateContext();
    ImGuiIO &io = ImGui::GetIO();
    io.IniFilename = NULL;
    style();
    for (size_t i = 0; i < sizeof(fonts) / sizeof(fonts[0]); i++) {
        if (file_exists(fonts[i])) {
            io.Fonts->AddFontFromFileTTF(fonts[i], 18.0f);
            break;
        }
    }
    if (sUseGl) {
        ImGui_ImplSDL3_InitForOpenGL(sWindow, sGl);
        ImGui_ImplOpenGL3_Init(glsl);
    } else {
        ImGui_ImplSDL3_InitForSDLGPU(sWindow);
        info.Device = sDevice;
        info.ColorTargetFormat = SDL_GetGPUSwapchainTextureFormat(sDevice, sWindow);
        info.MSAASamples = SDL_GPU_SAMPLECOUNT_1;
        ImGui_ImplSDLGPU3_Init(&info);
    }
    sRoot = find_root();
    read_version();
    if (argc > 1) {
        SDL_strlcpy(sIso, argv[1], sizeof(sIso));
    }
    if (getenv("BT3_SETUP_ISO") != NULL) {
        SDL_strlcpy(sIso, getenv("BT3_SETUP_ISO"), sizeof(sIso));
        if (!sRoot.empty() && file_exists(sIso)) {
            start_install();
        }
    } else {
        load_backdrop(); // the window and its renderer are ready by now
        boot_begin();
    }
    while (!sQuit) {
        SDL_Event ev;
        while (SDL_PollEvent(&ev)) {
            ImGui_ImplSDL3_ProcessEvent(&ev);
            if (ev.type == SDL_EVENT_QUIT || ev.type == SDL_EVENT_WINDOW_CLOSE_REQUESTED) {
                sQuit = true;
            } else if (ev.type == SDL_EVENT_DROP_FILE && sPage == PAGE_PICK && ev.drop.data != NULL) {
                SDL_strlcpy(sIso, ev.drop.data, sizeof(sIso));
            }
        }
        {
            std::lock_guard<std::mutex> lock(sDialogLock);
            if (sDialogReady) {
                SDL_strlcpy(sIso, sDialogResult.c_str(), sizeof(sIso));
                sDialogReady = false;
            }
        }
        boot_tick();
        pump_update();
        pump_download();
        pump_install();
        if (sPage == PAGE_DONE && sIsoDownloaded) { // the image has been unpacked: only the game data is needed now
            remove(sIso);
            sIsoDownloaded = false;
        }
        if (sUseGl) {
            ImGui_ImplOpenGL3_NewFrame();
        } else {
            ImGui_ImplSDLGPU3_NewFrame();
        }
        ImGui_ImplSDL3_NewFrame();
        ImGui::NewFrame();
        build_ui();
        ImGui::Render();
        ImDrawData *dd = ImGui::GetDrawData();
        if (sUseGl) {
            int pw = 0, ph = 0;
            SDL_GetWindowSizeInPixels(sWindow, &pw, &ph);
            glViewport(0, 0, pw, ph);
            glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT);
            ImGui_ImplOpenGL3_RenderDrawData(dd);
            if (getenv("BT3_SETUP_SHOT") != NULL && ImGui::GetFrameCount() == 20 && pw > 0 && ph > 0) {
                // testing: the picture of the OpenGL window, <BT3_SETUP_SHOT>_gl.ppm (rows come bottom first)
                std::vector<unsigned char> px((size_t)pw * ph * 4);
                char name[512];
                glReadPixels(0, 0, pw, ph, GL_RGBA, GL_UNSIGNED_BYTE, px.data());
                snprintf(name, sizeof(name), "%s_gl.ppm", getenv("BT3_SETUP_SHOT"));
                FILE *fp = fopen(name, "wb");
                if (fp != NULL) {
                    fprintf(fp, "P6\n%d %d\n255\n", pw, ph);
                    for (int y = ph - 1; y >= 0; y--) {
                        for (int x = 0; x < pw; x++) {
                            fwrite(&px[((size_t)y * pw + x) * 4], 1, 3, fp);
                        }
                    }
                    fclose(fp);
                }
            }
            SDL_GL_SwapWindow(sWindow);
            if (getenv("BT3_SETUP_QUIT") != NULL && ImGui::GetFrameCount() > 30) {
                sQuit = true; // testing: a few frames of the OpenGL window, then leave
            }
            continue;
        }
        SDL_GPUCommandBuffer *cmd = SDL_AcquireGPUCommandBuffer(sDevice);
        SDL_GPUTexture *swap = NULL;
        Uint32 w = 0, h = 0;
        if (SDL_WaitAndAcquireGPUSwapchainTexture(cmd, sWindow, &swap, &w, &h) && swap != NULL) {
            SDL_GPUColorTargetInfo target;
            ImGui_ImplSDLGPU3_PrepareDrawData(dd, cmd);
            SDL_zero(target);
            target.texture = swap;
            target.load_op = SDL_GPU_LOADOP_CLEAR;
            target.store_op = SDL_GPU_STOREOP_STORE;
            SDL_GPURenderPass *pass = SDL_BeginGPURenderPass(cmd, &target, 1, NULL);
            ImGui_ImplSDLGPU3_RenderDrawData(dd, cmd, pass);
            SDL_EndGPURenderPass(pass);
        }
        SDL_SubmitGPUCommandBuffer(cmd);
        if (getenv("BT3_SETUP_SHOT") != NULL && w != 0 && ImGui::GetFrameCount() > 3) {
            int running = 0;
            for (size_t i = 0; i < sRows.size(); i++) {
                running += sRows[i].state != PENDING;
            }
            int key = (int)sPage * 100 + running;
            if (key != shotKey) {
                shotKey = key;
                shot(dd, w, h, shots++);
            }
            if (getenv("BT3_SETUP_QUIT") != NULL && (sPage == PAGE_DONE || sPage == PAGE_FAILED || (sPage == PAGE_PICK && sProc == NULL && shots > 0 && getenv("BT3_SETUP_ISO") == NULL))) {
                shot(dd, w, h, shots++);
                sQuit = true; // testing: leave once the result is on screen
            }
        }
    }
    if (sProc != NULL) {
        SDL_KillProcess(sProc, false);
        SDL_DestroyProcess(sProc);
    }
    Native_Join();
    Dl_Join(); // a download that is still running is cancelled and waited for before the window goes
    if (sUseGl) {
        ImGui_ImplOpenGL3_Shutdown();
        ImGui_ImplSDL3_Shutdown();
        ImGui::DestroyContext();
        SDL_GL_DestroyContext(sGl);
        SDL_DestroyWindow(sWindow);
        SDL_Quit();
        return 0;
    }
    SDL_WaitForGPUIdle(sDevice);
    ImGui_ImplSDL3_Shutdown();
    ImGui_ImplSDLGPU3_Shutdown();
    ImGui::DestroyContext();
    SDL_ReleaseWindowFromGPUDevice(sDevice, sWindow);
    SDL_DestroyGPUDevice(sDevice);
    SDL_DestroyWindow(sWindow);
    SDL_Quit();
    return 0;
}
