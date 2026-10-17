// WinHTTP (https) and WIC (the boot screen's picture) for the setup window; see net.h. Windows builds only.
#include "net.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <atomic>
#include <mutex>
#include <thread>

// The download's own state (see the second half of this file): declared up here because to_file, the Windows sink,
// has to be able to see the cancel flag.
static std::atomic<bool> sCancelled(false);

#ifdef _WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <winhttp.h>
#include <objbase.h>
#include <wincodec.h>

static const wchar_t *kAgent = L"DBT3-setup/1";

// The URLs here are plain ascii (https://host/path), so widening them byte by byte is enough.
static std::wstring widen(const std::string &s) { return std::wstring(s.begin(), s.end()); }

// host and path out of an https URL; an empty path is the root.
static bool split_url(const std::string &url, std::string &host, std::string &path) {
    size_t p = url.find("://");
    if (url.compare(0, 8, "https://") != 0 || p == std::string::npos) {
        return false;
    }
    size_t start = 8, slash = url.find('/', start);
    host = url.substr(start, slash == std::string::npos ? std::string::npos : slash - start);
    path = slash == std::string::npos ? "/" : url.substr(slash);
    return !host.empty();
}

// One request, streamed into `sink` (null: keep it in a string). `done`/`total` are filled in while it runs.
typedef bool (*Sink)(void *ctx, const char *data, size_t n);
static bool request(const std::string &url, Sink sink, void *ctx, long long *done, long long *total, std::string &error,
                    DWORD timeout = 30000) {
    std::string host, path;
    if (!split_url(url, host, path)) {
        error = "not an https link";
        return false;
    }
    *done = 0;
    *total = 0;
    HINTERNET ses = WinHttpOpen(kAgent, WINHTTP_ACCESS_TYPE_AUTOMATIC_PROXY, WINHTTP_NO_PROXY_NAME, WINHTTP_NO_PROXY_BYPASS, 0);
    if (ses == NULL) {
        error = "no network";
        return false;
    }
    DWORD connect = timeout / 2;
    WinHttpSetTimeouts(ses, connect, connect, timeout, timeout);
    HINTERNET con = WinHttpConnect(ses, widen(host).c_str(), INTERNET_DEFAULT_HTTPS_PORT, 0);
    HINTERNET req =
        con != NULL ? WinHttpOpenRequest(con, L"GET", widen(path).c_str(), NULL, WINHTTP_NO_REFERER, WINHTTP_DEFAULT_ACCEPT_TYPES,
                                         WINHTTP_FLAG_SECURE)
                    : NULL;
    bool ok = req != NULL;
    if (ok && !WinHttpSendRequest(req, WINHTTP_NO_ADDITIONAL_HEADERS, 0, WINHTTP_NO_REQUEST_DATA, 0, 0, 0)) {
        ok = false;
    }
    if (ok && !WinHttpReceiveResponse(req, NULL)) {
        ok = false;
    }
    DWORD status = 0, length = 0, size = sizeof(status);
    if (ok && (!WinHttpQueryHeaders(req, WINHTTP_QUERY_STATUS_CODE | WINHTTP_QUERY_FLAG_NUMBER, WINHTTP_HEADER_NAME_BY_INDEX, &status,
                                    &size, WINHTTP_NO_HEADER_INDEX) ||
               status < 200 || status > 299)) {
        ok = false;
    }
    size = sizeof(length);
    if (ok && WinHttpQueryHeaders(req, WINHTTP_QUERY_CONTENT_LENGTH | WINHTTP_QUERY_FLAG_NUMBER, WINHTTP_HEADER_NAME_BY_INDEX, &length,
                                  &size, WINHTTP_NO_HEADER_INDEX)) {
        *total = (long long)length;
    }
    char buf[1 << 16];
    while (ok) {
        DWORD avail = 0;
        if (!WinHttpQueryDataAvailable(req, &avail)) {
            ok = false;
            break;
        }
        if (avail == 0) {
            break;
        }
        DWORD got = 0;
        if (!WinHttpReadData(req, buf, avail < sizeof(buf) ? avail : (DWORD)sizeof(buf), &got) || got == 0) {
            ok = false;
            break;
        }
        if (!sink(ctx, buf, got)) {
            ok = false;
            break;
        }
        *done += got;
    }
    if (!ok && error.empty()) {
        if (sCancelled.load()) {
            error = "cancelled";
        } else {
            DWORD code = GetLastError();
            char text[128];
            snprintf(text, sizeof(text), "network error %lu", (unsigned long)code);
            error = text;
        }
    }
    if (req != NULL) {
        WinHttpCloseHandle(req);
    }
    if (con != NULL) {
        WinHttpCloseHandle(con);
    }
    WinHttpCloseHandle(ses);
    return ok;
}

static bool to_string(void *ctx, const char *data, size_t n) {
    ((std::string *)ctx)->append(data, n);
    return true;
}

// The text after a JSON key: spaces, then the value up to its closing quote or brace.
static std::string value_at(const char *p) {
    while (*p == ' ' || *p == ':' || *p == '\t' || *p == '\r' || *p == '\n') {
        p++;
    }
    if (*p == '"') {
        p++;
    }
    std::string out(p);
    size_t end = out.find_first_of("\"}");
    if (end != std::string::npos) {
        out.resize(end);
    }
    return out;
}

static bool to_file(void *ctx, const char *data, size_t n) {
    if (sCancelled.load()) { // Cancel: stop at the next chunk, not at the end of 2.8 GB
        return false;
    }
    return fwrite(data, 1, n, (FILE *)ctx) == n;
}
#endif

// --------------------------------------------------------------------------------------------- the build feed

Feed Net_Feed(const std::string &url) {
    Feed f;
#ifdef _WIN32
    std::string text;
    long long done = 0, total = 0;
    // eight seconds: this one runs while the boot screen waits for it, so a server that is not there must not hold
    // the window still for the download's half minute
    if (request(url, to_string, &text, &done, &total, f.error, 8000)) {
        // {"version":"0.6.0","versionCode":600,"apkUrl":"https://…apk","winUrl":"https://…zip","size":1,"notes":"…"}
        // A missing piece means the file is not one of ours: it counts as no answer, not as a broken answer.
        const char *v = strstr(text.c_str(), "\"version\"");
        const char *c = strstr(text.c_str(), "\"versionCode\"");
        const char *u = strstr(text.c_str(), "\"winUrl\""); // this program's own archive, not the phone app's
        if (v != NULL && c != NULL && u != NULL) {
            f.version = value_at(v + 9);
            f.code = atoll(c + 13);
            f.win_url = value_at(u + 8);
            f.ok = f.code >= 0 && f.win_url.compare(0, 8, "https://") == 0;
            if (!f.ok) {
                f.error = "the build feed is not readable";
            }
            const char *n = strstr(text.c_str(), "\"notes\"");
            if (n != NULL) {
                f.notes = value_at(n + 7);
            }
            const char *s = strstr(text.c_str(), "\"size\"");
            f.size = s != NULL ? atoll(s + 6) : -1;
        } else {
            f.error = "the build feed is not readable";
        }
    }
#else
    (void)url;
    f.error = "unavailable"; // no TLS here; see net.h
#endif
    return f;
}

// ----------------------------------------------------------------------------------------- the ISO download

static std::thread sWorker;
static std::mutex sLock;
static std::atomic<bool> sRunning(false);
static std::string sError;
static long long sDone = 0, sTotal = 0;

bool Dl_Start(const std::string &url, const std::string &dest, std::string &error) {
    if (sRunning.load()) {
        error = "a download is already running";
        return false;
    }
    if (sWorker.joinable()) { // the last one has ended (or was cancelled): a thread can only be joined once
        sWorker.join();
    }
    sCancelled = false;
    sError.clear();
    sDone = sTotal = 0;
    sRunning = true;
    sWorker = std::thread([url, dest]() {
#ifdef _WIN32
        FILE *fp = fopen(dest.c_str(), "wb");
        std::string e;
        bool ok = fp != NULL && request(url, to_file, fp, &sDone, &sTotal, e);
        if (fp != NULL) {
            fclose(fp);
        }
        if (!ok) {
            remove(dest.c_str()); // a half image is worth nothing: no Install button will ever open it
        }
        {
            std::lock_guard<std::mutex> lock(sLock);
            sError = ok ? "" : (e.empty() ? "the download failed" : e);
        }
#else
        (void)url;
        (void)dest;
        std::lock_guard<std::mutex> lock(sLock);
        sError = "unavailable"; // no TLS here; see net.h
#endif
        sRunning = false;
    });
    return true;
}

void Dl_Poll(long long &done, long long &total, bool &running, std::string &error) {
    done = sDone;
    total = sTotal;
    running = sRunning.load();
    std::lock_guard<std::mutex> lock(sLock);
    error = sError;
}

void Dl_Cancel(void) { sCancelled.store(true); }

bool Dl_Active(void) { return sRunning.load(); }

void Dl_Join(void) {
    sCancelled.store(true);
    if (sWorker.joinable()) {
        sWorker.join();
    }
}

// ------------------------------------------------------------------------------------------------- the picture

Image Img_Load(const std::string &path) {
    Image img;
#ifdef _WIN32
    UINT pw = 0, ph = 0; // WIC measures in UINT; img keeps ints for the callers
    CoInitializeEx(NULL, COINIT_APARTMENTTHREADED);
    IWICImagingFactory *factory = NULL;
    if (FAILED(CoCreateInstance(CLSID_WICImagingFactory, NULL, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&factory)))) {
        return img;
    }
    IWICBitmapDecoder *decoder = NULL;
    IWICBitmapFrameDecode *frame = NULL;
    IWICFormatConverter *converter = NULL;
    if (SUCCEEDED(factory->CreateDecoderFromFilename(widen(path).c_str(), NULL, GENERIC_READ, WICDecodeMetadataCacheOnLoad, &decoder)) &&
        SUCCEEDED(decoder->GetFrame(0, &frame)) &&
        SUCCEEDED(factory->CreateFormatConverter(&converter)) &&
        SUCCEEDED(converter->Initialize(frame, GUID_WICPixelFormat32bppRGBA, WICBitmapDitherTypeNone, NULL, 0.0, WICBitmapPaletteTypeCustom)) &&
        SUCCEEDED(converter->GetSize(&pw, &ph))) {
        img.width = (int)pw;
        img.height = (int)ph;
        img.rgba = (unsigned char *)malloc((size_t)pw * ph * 4);
        if (img.rgba == NULL) {
            img.width = img.height = 0;
        } else {
            WICRect rect = {0, 0, img.width, img.height};
            // (rect, buffer size, stride, buffer): the picture is drawn opaque, so straight RGBA is what the texture wants
            if (FAILED(converter->CopyPixels(&rect, (UINT)pw * ph * 4, pw * 4, img.rgba))) {
                free(img.rgba);
                img.rgba = NULL;
                img.width = img.height = 0;
            }
        }
    }
    if (converter != NULL) {
        converter->Release();
    }
    if (frame != NULL) {
        frame->Release();
    }
    if (decoder != NULL) {
        decoder->Release();
    }
    factory->Release();
    CoUninitialize();
#else
    (void)path; // no JPEG decoder here; the boot screen draws its background without one
#endif
    return img;
}

void Img_Free(Image &img) {
    free(img.rgba);
    img = Image();
}
