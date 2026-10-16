// The setup window's network and its boot-time version check: WinHTTP on Windows, nothing anywhere else.
//
// A GET that returns a whole file (the build feed's version.json) is one call and blocks for a moment; a download of
// a few gigabytes runs on a thread of its own, so the window keeps drawing and Cancel works. Where there is no HTTPS
// (the Linux build links no TLS library, and adding one is not this program's job), the calls fail at once with
// `unavailable`, and the boot screen says the server could not be reached instead of hanging.
#ifndef PORT_SETUP_NET_H
#define PORT_SETUP_NET_H
#include <string>

// The build feed on the project's own server (one small JSON, next to the ISO): which build is the newest one, and
// where its archive is. Fails with `unavailable` where there is no HTTPS.
struct Feed {
    bool ok = false;
    std::string version, notes, apk_url, error;
    long long code = -1, size = -1;
};
Feed Net_Feed(const std::string &url);

// A download on a thread of its own. Dl_Start hands it over, Dl_Poll is asked every frame for what it has done so far.
bool Dl_Start(const std::string &url, const std::string &dest, std::string &error);
void Dl_Poll(long long &done, long long &total, bool &running, std::string &error);
void Dl_Cancel(void);
void Dl_Join(void); // at exit: cancel and wait
bool Dl_Active(void);

// A picture as pixels: width, height and RGBA bytes (the caller uploads it to a texture). Null where there is no
// JPEG decoder (the Linux build has none; the boot screen draws its background without one).
struct Image {
    int width = 0, height = 0;
    unsigned char *rgba = NULL;
};
Image Img_Load(const std::string &path);
void Img_Free(Image &img);

#endif
