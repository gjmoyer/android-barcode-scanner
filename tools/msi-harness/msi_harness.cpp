// Host-side accuracy + speed harness for the clean-room MSI Plessey decoder.
//
// Builds the SHIPPED decoder (msi_decoder.cpp, zero Android deps) for the
// host with: clang++ -std=c++17 -O2 -lz. Runs:
//   1. synthetic vectors rendered from the SPEC (not from the decoder's own
//      tables): every checksum policy, multiple scales incl. close-up wide
//      modules, blur/contrast/brightness ladder, negatives;
//   2. shelf-tag format vectors (9-digit Mod1010, leading zeros,
//      text clutter) with and without a required checksum policy;
//   3. real shelf-photo sweeps (barcode-scanner-sdk/src/test/resources/
//      msi-shelf/*.png): each photo decoded across scales x photometric
//      variants x repeats; mild variants are ASSERTED, harsh combos reported;
//   4. native frame path (msi_frame): one-pass crop/stretch/rotate parity,
//      stride + offset handling, orientation attempts, scan-top override;
//   5. timing bench; still-fallback perf budgets are advisory by default
//      (MSI_PERF_STRICT=1 enforces them on a dedicated benchmark host).
//
// Exit code = number of asserted failures (0 = all green; advisory perf
// overruns do not change it).
// Usage: ./build.sh [path/to/cpp/dir]

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include <zlib.h>

#include "msi_decoder.h"
#include "msi_frame.h"

#if defined(__has_feature)
#if __has_feature(address_sanitizer) || __has_feature(undefined_behavior_sanitizer)
#define MSI_SANITIZER_BUILD 1
#endif
#endif
#if defined(__SANITIZE_ADDRESS__) || defined(__SANITIZE_THREAD__)
#define MSI_SANITIZER_BUILD 1
#endif

// ------------------------------------------------------------ test context
static int g_assertFails = 0;
static int g_assertPass = 0;
static int g_infoFails = 0;
static int g_perfOver = 0;

// Absolute wall-clock budgets are host-tuned; shared CI/VMs are routinely
// several times slower than the machine they were measured on. Perf overruns
// are advisory by default (printed as WARN, exit stays green). Set
// MSI_PERF_STRICT=1 on a dedicated benchmark host to enforce them.
static bool perfStrict() {
    static const bool strict = [] {
        const char* v = getenv("MSI_PERF_STRICT");
        return v != nullptr && *v != '\0' && std::strcmp(v, "0") != 0 &&
               std::strcmp(v, "false") != 0;
    }();
    return strict;
}

struct Gray {
    std::vector<uint8_t> px;
    int w = 0, h = 0;
    bool ok() const { return w > 0 && h > 0 && (int)px.size() == w * h; }
};

// ------------------------------------------------------------ minimal PNG
// 8-bit non-interlaced, color types 0 (gray) / 2 (RGB) / 6 (RGBA).
static uint32_t rd32(const uint8_t* p) {
    return ((uint32_t)p[0] << 24) | ((uint32_t)p[1] << 16) |
           ((uint32_t)p[2] << 8) | (uint32_t)p[3];
}

static bool readPngGray(const std::string& path, Gray& out, bool quietMissing = false) {
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) {
        // Absent files are routine (private photos stay local — the caller
        // prints SKIP); only complain about present-but-unreadable ones.
        if (!quietMissing) printf("PNG-ERR %s: cannot open\n", path.c_str());
        return false;
    }
    fseek(f, 0, SEEK_END);
    long sz = ftell(f);
    fseek(f, 0, SEEK_SET);
    if (sz < 64) {
        fclose(f);
        return false;
    }
    std::vector<uint8_t> buf(sz);
    if (fread(buf.data(), 1, sz, f) != (size_t)sz) {
        fclose(f);
        return false;
    }
    fclose(f);
    static const uint8_t kSig[8] = {137, 80, 78, 71, 13, 10, 26, 10};
    if (memcmp(buf.data(), kSig, 8) != 0) return false;
    int w = 0, h = 0, depth = 0, ctype = 0, interlace = 0;
    std::vector<uint8_t> idat;
    size_t pos = 8;
    while (pos + 8 <= buf.size()) {
        uint32_t len = rd32(&buf[pos]);
        char type[5] = {0};
        memcpy(type, &buf[pos + 4], 4);
        const uint8_t* data = &buf[pos + 8];
        if (pos + 8 + len + 4 > buf.size()) return false;
        if (!strcmp(type, "IHDR")) {
            if (len < 13) return false;
            w = rd32(data);
            h = rd32(data + 4);
            depth = data[8];
            ctype = data[9];
            interlace = data[12];
        } else if (!strcmp(type, "IDAT")) {
            idat.insert(idat.end(), data, data + len);
        } else if (!strcmp(type, "IEND")) {
            break;
        }
        pos += 8 + len + 4;
    }
    if (w <= 0 || h <= 0 || w > 8192 || h > 8192) return false;
    if (depth != 8 || interlace != 0) return false;
    int ch = 0;
    if (ctype == 0)
        ch = 1;
    else if (ctype == 2)
        ch = 3;
    else if (ctype == 6)
        ch = 4;
    else
        return false;
    const size_t stride = (size_t)w * ch;
    std::vector<uint8_t> raw((stride + 1) * h + 16);
    z_stream zs;
    memset(&zs, 0, sizeof(zs));
    if (inflateInit(&zs) != Z_OK) return false;
    zs.next_in = idat.data();
    zs.avail_in = (uInt)idat.size();
    zs.next_out = raw.data();
    zs.avail_out = (uInt)raw.size();
    int rc = inflate(&zs, Z_FINISH);
    inflateEnd(&zs);
    if (rc != Z_STREAM_END) return false;
    // Unfilter (Paeth included).
    std::vector<uint8_t> img(stride * h);
    auto paeth = [](int a, int b, int c) {
        int p = a + b - c, pa = abs(p - a), pb = abs(p - b), pc = abs(p - c);
        return pa <= pb && pa <= pc ? a : (pb <= pc ? b : c);
    };
    for (int y = 0; y < h; ++y) {
        const uint8_t* row = &raw[(size_t)y * (stride + 1)];
        uint8_t* dst = &img[(size_t)y * stride];
        int flt = row[0];
        for (size_t x = 0; x < stride; ++x) {
            int a = x >= (size_t)ch ? dst[x - ch] : 0;
            int b = y > 0 ? img[(size_t)(y - 1) * stride + x] : 0;
            int c = (x >= (size_t)ch && y > 0) ? img[(size_t)(y - 1) * stride + x - ch] : 0;
            int v = row[1 + x];
            switch (flt) {
                case 0:
                    break;
                case 1:
                    v += a;
                    break;
                case 2:
                    v += b;
                    break;
                case 3:
                    v += (a + b) >> 1;
                    break;
                case 4:
                    v += paeth(a, b, c);
                    break;
                default:
                    return false;
            }
            dst[x] = (uint8_t)(v & 0xFF);
        }
    }
    out.px.resize((size_t)w * h);
    out.w = w;
    out.h = h;
    for (int i = 0; i < w * h; ++i) {
        if (ch == 1) {
            out.px[i] = img[i];
        } else {
            // Rec.601 luma, same as the Kotlin bitmap->gray path.
            int r = img[(size_t)i * ch], g = img[(size_t)i * ch + 1], b = img[(size_t)i * ch + 2];
            out.px[i] = (uint8_t)((77 * r + 150 * g + 29 * b) >> 8);
        }
    }
    return true;
}

// ------------------------------------------------------------ spec renderer
// Independent construction from the MSI spec (Wikipedia "MSI Barcode"):
// bit 0 = narrow bar + wide space ("100"), bit 1 = wide bar + narrow space
// ("110"); START "110", STOP "1001". Deliberately NOT built from the
// decoder's kDigitPat table, so a table bug fails loudly here.
static std::string modulesFor(const std::string& full) {
    std::string m = "110";
    for (char d : full) {
        int v = d - '0';
        for (int b = 3; b >= 0; --b) m += (v & (1 << b)) ? "110" : "100";
    }
    return m + "1001";
}

static Gray renderMsi(const std::string& full, int modulePx, int w, int h,
                      int topY, int barH) {
    Gray g;
    g.w = w;
    g.h = h;
    g.px.assign((size_t)w * h, 255);
    std::string mods = modulesFor(full);
    int x0 = (w - (int)mods.size() * modulePx) / 2;
    if (x0 < 0) x0 = 0;
    for (size_t mi = 0; mi < mods.size(); ++mi) {
        uint8_t v = mods[mi] == '1' ? 0 : 255;
        for (int k = 0; k < modulePx; ++k) {
            int x = x0 + (int)mi * modulePx + k;
            if (x < 0 || x >= w) continue;
            for (int y = topY; y < topY + barH && y < h; ++y)
                if (y >= 0) g.px[(size_t)y * w + x] = v;
        }
    }
    return g;
}

static void boxBlur(Gray& g, int r) {
    if (r <= 0) return;
    std::vector<uint8_t> src = g.px;
    for (int y = 0; y < g.h; ++y) {
        for (int x = 0; x < g.w; ++x) {
            int s = 0, n = 0;
            for (int dy = -r; dy <= r; ++dy) {
                int yy = y + dy;
                if (yy < 0 || yy >= g.h) continue;
                for (int dx = -r; dx <= r; ++dx) {
                    int xx = x + dx;
                    if (xx < 0 || xx >= g.w) continue;
                    s += src[(size_t)yy * g.w + xx];
                    ++n;
                }
            }
            g.px[(size_t)y * g.w + x] = (uint8_t)(s / n);
        }
    }
}

static void photometric(Gray& g, float contrast, int brightness) {
    for (size_t i = 0; i < g.px.size(); ++i) {
        float v = 128.0f + (g.px[i] - 128.0f) * contrast + brightness;
        if (v < 0) v = 0;
        if (v > 255) v = 255;
        g.px[i] = (uint8_t)(v + 0.5f);
    }
}

// Pipeline-equivalent preprocessing: what the SDK runs before the native
// decoder (DownscaleTransform is a no-op here — all shelf photos are already
// <=1280px — so only ContrastNormalizationTransform's 2/98-percentile
// stretch is mirrored). Sweeps must run on THIS, not raw pixels.
static void contrastStretch(Gray& g) {
    int hist[256] = {0};
    for (uint8_t v : g.px) ++hist[v];
    float n = (float)g.px.size(), acc = 0;
    int lo = 0, hi = 255;
    for (int i = 0; i < 256; ++i) {
        acc += hist[i] / n;
        if (acc < 0.02f) lo = i;
        if (acc <= 0.98f) hi = i;
    }
    if (hi - lo < 12) return;  // flat frame: untouched, like the SDK
    float scale = 255.0f / (hi - lo);
    for (size_t i = 0; i < g.px.size(); ++i) {
        int v = (int)((g.px[i] - lo) * scale + 0.5f);
        if (v < 0) v = 0;
        if (v > 255) v = 255;
        g.px[i] = (uint8_t)v;
    }
}

static Gray downscaleNN(const Gray& g, float factor) {
    Gray o;
    o.w = (int)(g.w * factor + 0.5f);
    o.h = (int)(g.h * factor + 0.5f);
    if (o.w < 1) o.w = 1;
    if (o.h < 1) o.h = 1;
    o.px.resize((size_t)o.w * o.h);
    for (int y = 0; y < o.h; ++y)
        for (int x = 0; x < o.w; ++x)
            o.px[(size_t)y * o.w + x] = g.px[(size_t)(y * g.h / o.h) * g.w + (x * g.w / o.w)];
    return o;
}

// Mirrors decodeImpl's far-field retry input exactly (NN 2x + 1px shift so
// every even-parity pair spans a true source boundary).
static Gray upscaleRetryInput(const Gray& g) {
    Gray o;
    o.w = g.w * 2;
    o.h = g.h * 2;
    o.px.resize((size_t)o.w * o.h);
    for (int y = 0; y < o.h; ++y) {
        const uint8_t* srcRow = &g.px[(size_t)(y / 2) * g.w];
        uint8_t* dstRow = &o.px[(size_t)y * o.w];
        for (int x = 0; x < o.w; ++x) {
            int sx = (x + 1) / 2;
            if (sx >= g.w) sx = g.w - 1;
            dstRow[x] = srcRow[sx];
        }
    }
    return o;
}

// ------------------------------------------------------------ checks
// SDK-equivalent policy layer: mirrors MsiChecksumValidator.validate() +
// MsiNativeDecoder's stripChecksum=true exactly, so sweep assertions are at
// the payload level production emits (not raw native codewords).
static int kMod10(const std::string& p) {
    int s = 0;
    bool dbl = true;
    for (int i = (int)p.size() - 1; i >= 0; --i) {
        int d = p[i] - '0';
        if (dbl) {
            d *= 2;
            if (d > 9) d -= 9;
        }
        s += d;
        dbl = !dbl;
    }
    return (10 - (s % 10)) % 10;
}

static int kMod11(const std::string& p) {
    int s = 0, w = 2;
    for (int i = (int)p.size() - 1; i >= 0; --i) {
        s += (p[i] - '0') * w;
        w = (w == 7) ? 2 : w + 1;
    }
    int r = (11 - (s % 11)) % 11;
    return r == 10 ? -1 : r;
}

// Returns stripped payload, or "" when invalid under policy.
// policy: 0=MOD_10, 1=MOD_11, 2=MOD_10_10, 3=MOD_10_11, 4=NONE.
static std::string kValidate(const std::string& full, int policy) {
    for (char c : full)
        if (c < '0' || c > '9') return "";
    if (policy == 4) return full;
    if (policy == 0) {
        if (full.size() < 2) return "";
        std::string pay = full.substr(0, full.size() - 1);
        return (full.back() - '0') == kMod10(pay) ? pay : "";
    }
    if (policy == 1) {
        if (full.size() < 2) return "";
        std::string pay = full.substr(0, full.size() - 1);
        int c = kMod11(pay);
        return (c >= 0 && (full.back() - '0') == c) ? pay : "";
    }
    if (full.size() < 3) return "";
    std::string pay = full.substr(0, full.size() - 2);
    if (policy == 2) {
        int c1 = kMod10(pay);
        if ((full[full.size() - 2] - '0') != c1) return "";
        int c2 = kMod10(pay + full[full.size() - 2]);
        return ((full.back() - '0') == c2) ? pay : "";
    }
    int c1 = kMod11(pay);  // policy == 3 (MOD_10_11)
    if (c1 < 0 || (full[full.size() - 2] - '0') != c1) return "";
    int c2 = kMod10(pay + full[full.size() - 2]);
    return ((full.back() - '0') == c2) ? pay : "";
}

// `required` is passed straight to msi::decode (host policy pushdown).
static std::string check(const std::string& name, const Gray& g,
                         const char* wantDigits, const char* wantPolicy,
                         bool asserted,
                         msi::ChecksumPolicy required = msi::ChecksumPolicy::NONE) {
    if (getenv("MSI_DEBUG")) msi::setDebug(1);
    auto t0 = std::chrono::steady_clock::now();
    msi::DecodeResult r = msi::decode(g.px.data(), g.w, g.h, required);
    if (getenv("MSI_DEBUG")) msi::setDebug(0);
    auto t1 = std::chrono::steady_clock::now();
    long us = std::chrono::duration_cast<std::chrono::microseconds>(t1 - t0).count();
    bool ok;
    if (wantDigits == nullptr) {
        ok = !r.ok;  // negative: must be NotFound, must not crash
    } else {
        ok = r.ok && r.digits == wantDigits &&
             (wantPolicy == nullptr || msi::policyName(r.policy) == std::string(wantPolicy));
    }
    const char* verdict = ok ? (asserted ? "PASS" : "pass(info)") : (asserted ? "FAIL" : "fail(info)");
    printf("%s %-34s %dx%d want=%s/%s got=%s/%s votes=%d %ldus\n", verdict, name.c_str(), g.w, g.h,
           wantDigits ? wantDigits : "(none)",
           wantPolicy ? wantPolicy : "(any)",
           r.ok ? r.digits.c_str() : "(none)",
           r.ok ? msi::policyName(r.policy) : "-",
           r.ok ? r.votes : 0,
           us);
    if (asserted) {
        if (ok)
            ++g_assertPass;
        else
            ++g_assertFails;
    } else if (!ok) {
        ++g_infoFails;
    }
    return r.ok ? r.digits : std::string();
}

// Informational triple-run: stability observation without gating, for
// inputs outside the asserted envelope (pixel-crisp renders — see below).
static void checkInfo3(const std::string& name, const Gray& g, const char* wantDigits) {
    check((name + " [run1]").c_str(), g, wantDigits, nullptr, false);
    check((name + " [run2]").c_str(), g, wantDigits, nullptr, false);
    check((name + " [run3]").c_str(), g, wantDigits, nullptr, false);
}

// Determinism: same input 3x must agree (voting must be stable).
static void checkStable(const std::string& name, const Gray& g, const char* wantDigits) {
    std::string a = check((name + " [run1]").c_str(), g, wantDigits, nullptr, true);
    std::string b = check((name + " [run2]").c_str(), g, wantDigits, nullptr, true);
    std::string c = check((name + " [run3]").c_str(), g, wantDigits, nullptr, true);
    if (!(a == b && b == c)) {
        printf("FAIL %-34s unstable across repeats\n", name.c_str());
        ++g_assertFails;
    }
}

// ------------------------------------------------------------ frame path
static void assertTrue(const char* name, bool ok) {
    printf("%s %s\n", ok ? "PASS" : "FAIL", name);
    if (ok)
        ++g_assertPass;
    else
        ++g_assertFails;
}

// Upright image -> camera-like plane: the sensor buffer that
// ImageInfo.rotationDegrees=`deg` (clockwise) turns upright, padded to
// `stride` bytes per row with 0xEE junk the crop must never read.
static Gray toSensor(const Gray& up, int deg, int pad, int& stride) {
    Gray s;
    const bool swap = deg == 90 || deg == 270;
    s.w = swap ? up.h : up.w;
    s.h = swap ? up.w : up.h;
    stride = s.w + pad;
    s.px.assign((size_t)stride * s.h, 0xEE);
    for (int y = 0; y < up.h; ++y)
        for (int x = 0; x < up.w; ++x) {
            int sx = x, sy = y;  // sensor pixel that lands on upright (x, y)
            if (deg == 90) { sx = y; sy = up.w - 1 - x; }
            else if (deg == 180) { sx = up.w - 1 - x; sy = up.h - 1 - y; }
            else if (deg == 270) { sx = up.h - 1 - y; sy = x; }
            s.px[(size_t)sy * stride + sx] = up.px[(size_t)y * up.w + x];
        }
    return s;
}

// `g` rotated `deg` clockwise (toSensor(g, d) is g rotated 360-d clockwise).
static Gray rotateCw(const Gray& g, int deg) {
    int stride = 0;
    return toSensor(g, (360 - deg) % 360, 0, stride);
}

// Arbitrary clockwise rotation with an expanded white canvas, mirroring the
// still fallback's reverse-affine geometry. Used by the near-180 phantom
// regression, which is not a cardinal rotation.
static Gray rotateAny(const Gray& src, float deg) {
    const float a = deg * 3.14159265358979323846f / 180.0f;
    const float m0 = cosf(a), m1 = sinf(a), m3 = -sinf(a), m4 = cosf(a);
    const float cx = 0.5f * src.w, cy = 0.5f * src.h;
    float m2 = -cx * m0 - cy * m1 + cx;
    float m5 = -cx * m3 - cy * m4 + cy;
    float minx = 1e30f, miny = 1e30f, maxx = -1e30f, maxy = -1e30f;
    for (int k = 0; k < 4; ++k) {
        const float x = (k == 1 || k == 2) ? (float)src.w : 0.0f;
        const float y = (k >= 2) ? (float)src.h : 0.0f;
        const float u = m0 * x + m1 * y + m2;
        const float v = m3 * x + m4 * y + m5;
        minx = std::min(minx, u); maxx = std::max(maxx, u);
        miny = std::min(miny, v); maxy = std::max(maxy, v);
    }
    Gray out;
    out.w = (int)ceilf(maxx) - (int)floorf(minx);
    out.h = (int)ceilf(maxy) - (int)floorf(miny);
    const float tx = -(out.w - src.w) * 0.5f, ty = -(out.h - src.h) * 0.5f;
    m2 = m0 * tx + m1 * ty + m2;
    m5 = m3 * tx + m4 * ty + m5;
    out.px.assign((size_t)out.w * out.h, 255);
    for (int y = 0; y < out.h; ++y) {
        for (int x = 0; x < out.w; ++x) {
            const float sx = m0 * x + m1 * y + m2;
            const float sy = m3 * x + m4 * y + m5;
            if (sx < 0 || sy < 0 || sx >= src.w || sy >= src.h) continue;
            out.px[(size_t)y * out.w + x] =
                src.px[(size_t)(int)sy * src.w + (int)sx];
        }
    }
    return out;
}

static void frameSuite() {
    printf("--- native frame path (msi_frame) ---\n");
    // 1. Stretch parity with the Kotlin transform (mirrored by
    //    contrastStretch above) on an offset region of a strided plane.
    Gray up = renderMsi("12345674", 5, 800, 400, 200, 120);
    photometric(up, 0.35f, 20);
    boxBlur(up, 1);
    const int pad = 64;
    Gray plane;
    plane.w = up.w + 40;
    plane.h = up.h + 30;
    int stride = plane.w + pad;
    plane.px.assign((size_t)stride * plane.h, 0xEE);
    for (int y = 0; y < up.h; ++y)
        memcpy(&plane.px[(size_t)(y + 30) * stride + 40], &up.px[(size_t)y * up.w], up.w);
    msi::LumaView view{plane.px.data(), plane.w, plane.h, stride};
    msi::Region reg{40, 30, 40 + up.w, 30 + up.h};
    assertTrue("frame clampRegion keeps in-bounds region", msi::clampRegion(view, reg) &&
               reg.left == 40 && reg.top == 30 && reg.width() == up.w && reg.height() == up.h);
    std::vector<uint8_t> out((size_t)up.w * up.h);
    Gray want = up;
    contrastStretch(want);
    assertTrue("frame prepare stretch == Kotlin transform (strided, offset)",
               msi::prepareRegion(view, reg, 0, true, out.data()) && out == want.px);
    assertTrue("frame prepare no-stretch == raw crop",
               msi::prepareRegion(view, reg, 0, false, out.data()) && out == up.px);

    // 2. Rotation fused into the same pass == explicit rotation.
    for (int deg : {90, 180, 270}) {
        Gray rot = rotateCw(want, deg);
        std::vector<uint8_t> o((size_t)up.w * up.h);
        char name[96];
        snprintf(name, sizeof name, "frame prepare rotate %d == explicit rotate", deg);
        assertTrue(name, msi::prepareRegion(view, reg, deg, true, o.data()) && o == rot.px);
    }

    // 3. Invalid input is rejected, never read out of bounds.
    msi::Region outside{plane.w + 5, 0, plane.w + 50, 10};
    assertTrue("frame clampRegion rejects off-plane region", !msi::clampRegion(view, outside));
    msi::Region over{0, 0, plane.w + 10, plane.h};
    assertTrue("frame prepare rejects unclamped region",
               !msi::prepareRegion(view, over, 0, true, out.data()));
    assertTrue("frame prepare rejects non-right-angle rotation",
               !msi::prepareRegion(view, reg, 45, true, out.data()));

    // 4. Camera flow: portrait sensor plane (rotationDegrees=90) -> one
    //    prepare upright -> one forward decode, read in place.
    Gray tag = renderMsi("12345674", 5, 800, 300, 90, 120);
    Gray sensor = toSensor(tag, 90, pad, stride);
    msi::LumaView sv{sensor.px.data(), sensor.w, sensor.h, stride};
    msi::Region full{0, 0, sensor.w, sensor.h};
    std::vector<uint8_t> upright((size_t)tag.w * tag.h);
    bool prepared = msi::prepareRegion(sv, full, 90, true, upright.data());
    msi::FrameOptions fo;
    fo.maxOrientations = 1;
    msi::FrameResult fr = msi::decodeFrame(upright.data(), tag.w, tag.h, fo);
    assertTrue("frame camera 90 -> prepare upright -> forward decode",
               prepared && fr.decode.ok && fr.decode.digits == "12345674" && fr.relativeRotation == 0);
    // Same plane, rotation left to decodeFrame: identical read.
    std::vector<uint8_t> raw((size_t)tag.w * tag.h);
    msi::prepareRegion(sv, full, 0, true, raw.data());
    fo.rotationDegrees = 90;
    msi::FrameResult late = msi::decodeFrame(raw.data(), sensor.w, sensor.h, fo);
    assertTrue("frame decodeFrame(rotationDegrees=90) == prepare-rotated read",
               late.decode.ok && late.decode.digits == fr.decode.digits && late.relativeRotation == 0);

    // 5. Orientation attempts follow 0, 180, 90, 270 and honour the cap.
    fo.rotationDegrees = 0;
    Gray flipped = rotateCw(tag, 180);
    fo.maxOrientations = 1;
    assertTrue("frame upside-down tag: 1 orientation -> none",
               !msi::decodeFrame(flipped.px.data(), flipped.w, flipped.h, fo).decode.ok);
    fo.maxOrientations = 2;
    msi::FrameResult f180 = msi::decodeFrame(flipped.px.data(), flipped.w, flipped.h, fo);
    assertTrue("frame upside-down tag: 2 orientations -> relativeRotation 180",
               f180.decode.ok && f180.decode.digits == "12345674" && f180.relativeRotation == 180);
    Gray side = rotateCw(tag, 90);
    fo.maxOrientations = 4;
    msi::FrameResult f270 = msi::decodeFrame(side.px.data(), side.w, side.h, fo);
    assertTrue("frame sideways tag: 4 orientations -> relativeRotation 270",
               f270.decode.ok && f270.decode.digits == "12345674" && f270.relativeRotation == 270);
    fo.maxOrientations = 2;
    msi::FrameResult f0 = msi::decodeFrame(tag.px.data(), tag.w, tag.h, fo);
    assertTrue("frame upright tag: 2 orientations (reverse confirmed) -> relativeRotation 0",
               f0.decode.ok && f0.decode.digits == "12345674" && f0.relativeRotation == 0);

    // 5a. Reversed-phantom regression. The 180-degree view of a blurred,
    // low-contrast tag can decode to a checksum-valid 9-digit phantom, so
    // decodeFrame must confirm the opposite view instead of trusting a
    // strong-looking first read. These are the reproductions that a fixed
    // "leading quiet zone >= 1.8" fast path got wrong (reversed phantom
    // lead 1.90). Keep the reverse confirmation mandatory here.
    {
        struct RevCase {
            const char* full;
            msi::ChecksumPolicy policy;
            int w, h, top, barH, blur;
            float contrast;
            int brightness;
        };
        const RevCase reversed[] = {
            {"800091001", msi::ChecksumPolicy::MOD10, 800, 400, 150, 120, 2, 0.5f, 0},
            {"900180100", msi::ChecksumPolicy::MOD10, 800, 400, 150, 120, 2, 0.5f, 0},
            {"809000011", msi::ChecksumPolicy::MOD10, 800, 400, 150, 120, 2, 0.5f, 0},
            // Forward 1x phantom, true reverse rescued only by a failure-path
            // retry. Disabling retries on the reverse confirmation made these
            // return the phantom, so they guard the confirmation contract.
            {"891801110", msi::ChecksumPolicy::MOD1010, 1100, 500, 150, 200, 3, 0.8f, 0},
            {"900800087", msi::ChecksumPolicy::MOD10, 800, 400, 150, 120, 3, 0.9f, -20},
        };
        msi::FrameOptions ro;
        ro.maxOrientations = 2;
        ro.scanTopFraction = 0.0f;
        ro.minDigits = 9;
        ro.maxDigits = 9;
        for (const RevCase& rc : reversed) {
            Gray g = renderMsi(rc.full, 3, rc.w, rc.h, rc.top, rc.barH);
            boxBlur(g, rc.blur);
            photometric(g, rc.contrast, rc.brightness);
            Gray flip = rotateCw(g, 180);
            ro.required = rc.policy;
            msi::FrameResult fr = msi::decodeFrame(flip.px.data(), flip.w, flip.h, ro);
            const std::string name =
                std::string("frame reversed phantom ") + rc.full + " -> true 180-degree read";
            assertTrue(name.c_str(),
                       fr.decode.ok && fr.decode.digits == rc.full && fr.relativeRotation == 180);
        }
        // The still fallbacks run after the sweeps; they must never bypass
        // this arbitration and emit the reversed phantom as an upright read.
        msi::FrameOptions rf = ro;
        rf.maxOrientations = 1;
        rf.scanTopFraction = 0.25f;
        rf.stretchRetry = true;
        for (const RevCase& rc : reversed) {
            Gray g = renderMsi(rc.full, 3, rc.w, rc.h, rc.top, rc.barH);
            boxBlur(g, rc.blur);
            photometric(g, rc.contrast, rc.brightness);
            Gray flip = rotateCw(g, 180);
            rf.required = rc.policy;
            msi::FrameResult fr = msi::decodeFrame(flip.px.data(), flip.w, flip.h, rf);
            const std::string name =
                std::string("frame fallback reversed phantom ") + rc.full + " not emitted as upright";
            if (fr.decode.ok && fr.decode.digits != rc.full)
                printf("  fallback phantom %s -> %s rel=%d\n", rc.full,
                       fr.decode.digits.c_str(), fr.relativeRotation);
            assertTrue(name.c_str(),
                       !fr.decode.ok ||
                           (fr.decode.digits == rc.full && fr.relativeRotation == 180));
        }
    }

    // 5a-2. Exact still-path reproducer for the fallback direction bypass.
    // A blurred, low-contrast, upside-down tag at 3px/module used to return
    // a different checksum-valid 9-digit value with relativeRotation=0.
    {
        Gray g = renderMsi("011578333", 3, 1000, 600, 220, 160);
        photometric(g, 0.40f, 0);
        boxBlur(g, 2);
        Gray flip = rotateCw(g, 180);
        msi::FrameOptions fo;
        fo.maxOrientations = 1;
        fo.scanTopFraction = 0.25f;
        fo.minDigits = 9;
        fo.maxDigits = 9;
        fo.stretchRetry = true;
        msi::FrameResult fr = msi::decodeFrame(flip.px.data(), flip.w, flip.h, fo);
        assertTrue("still fallback 180 phantom: true read, relativeRotation 180",
                   fr.decode.ok && fr.decode.digits == "011578333" &&
                   fr.relativeRotation == 180);
    }

    // 5a-3. Near-180 fallback phantom: a blurred, low-contrast tag rotated
    // 181 degrees used to return a checksum-valid wrong value as upright when
    // the reverse view failed. The score gate must reject it (a correct read
    // at relativeRotation 180 is also acceptable).
    {
        Gray g = renderMsi("900800087", 3, 800, 400, 150, 120);
        photometric(g, 0.45f, 0);
        boxBlur(g, 3);
        Gray tilted = rotateAny(g, 181.0f);
        msi::FrameOptions po;
        po.maxOrientations = 4;
        po.scanTopFraction = 0.25f;
        po.stretchRetry = true;
        po.required = msi::ChecksumPolicy::MOD10;
        for (int bounds : {0, 9}) {
            po.minDigits = bounds;
            po.maxDigits = bounds;
            msi::FrameResult pr = msi::decodeFrame(tilted.px.data(), tilted.w,
                                                   tilted.h, po);
            assertTrue(bounds == 0
                           ? "still 181-degree fallback: no wrong open-length read"
                           : "still 181-degree fallback: no wrong 9-digit read",
                       !pr.decode.ok ||
                           (pr.decode.digits == "900800087" &&
                            pr.relativeRotation == 180));
        }
    }

    // 5a-3b. Second near-180 phantom (846639722) from the stretched sweep.
    {
        Gray g = renderMsi("846639722", 3, 800, 400, 150, 120);
        photometric(g, 0.40f, 0);
        boxBlur(g, 3);
        Gray tilted = rotateAny(g, 181.0f);
        msi::FrameOptions po;
        po.maxOrientations = 4;
        po.scanTopFraction = 0.25f;
        po.stretchRetry = true;
        po.required = msi::ChecksumPolicy::MOD10;
        for (int bounds : {0, 9}) {
            po.minDigits = bounds;
            po.maxDigits = bounds;
            msi::FrameResult pr = msi::decodeFrame(tilted.px.data(), tilted.w,
                                                   tilted.h, po);
            assertTrue(bounds == 0
                           ? "still 181-degree sweep phantom: no wrong open read"
                           : "still 181-degree sweep phantom: no wrong 9-digit read",
                       !pr.decode.ok ||
                           (pr.decode.digits == "846639722" &&
                            pr.relativeRotation == 180));
        }
    }

    // 5a-3c. Attempts == 3 (0, 180, 90) is a legal still config but leaves
    // the 90-degree view without its 270 reverse. A stretched-sweep read
    // there must still clear the score gate: the 5a-3b phantom tag rotated
    // ~91 degrees used to emit the checksum-valid 446947731 as upright.
    // Attempting the reverse view is not what fixes it (4 orientations also
    // miss here); the gate must reject an unconfirmed weak read regardless
    // of pair position.
    {
        Gray g = renderMsi("846639722", 3, 800, 400, 150, 120);
        photometric(g, 0.40f, 0);
        boxBlur(g, 3);
        Gray sideways = rotateAny(g, 91.0f);
        msi::FrameOptions po;
        po.scanTopFraction = 0.25f;
        po.stretchRetry = true;
        po.required = msi::ChecksumPolicy::MOD10;
        for (int maxO : {3, 4}) {
            po.maxOrientations = maxO;
            for (int bounds : {0, 9}) {
                po.minDigits = bounds;
                po.maxDigits = bounds;
                msi::FrameResult pr = msi::decodeFrame(sideways.px.data(), sideways.w,
                                                       sideways.h, po);
                assertTrue(maxO == 3
                               ? (bounds == 0
                                      ? "3-orientation stretched sweep: no wrong open read"
                                      : "3-orientation stretched sweep: no wrong 9-digit read")
                               : (bounds == 0
                                      ? "4-orientation control: no wrong open read"
                                      : "4-orientation control: no wrong 9-digit read"),
                           !pr.decode.ok ||
                               (pr.decode.digits == "846639722" &&
                                pr.relativeRotation == 90));
            }
        }
    }

    // 5a-4. Direct angle-fallback contract: no reversed phantom may be
    // returned as an upright read.
#if MSI_ANGLE_MODEL
    {
        Gray g = renderMsi("011578333", 3, 1000, 600, 220, 160);
        photometric(g, 0.40f, 0);
        boxBlur(g, 2);
        Gray flip = rotateCw(g, 180);
        msi::DecodeOptions d;
        d.minDigits = 9;
        d.maxDigits = 9;
        int rel = -1;
        msi::DecodeResult ar = msi::decodeAngleFallback(
            flip.px.data(), flip.w, flip.h, d, &rel);
        assertTrue("angle fallback never returns reversed phantom as upright",
                   !ar.ok || (ar.digits == "011578333" && rel == 180));
    }

    // 5a-4b. Positive angle-fallback contract. The phantom test above only
    // proves the fallback stays silent; this proves it actually rescues a
    // tilt the plain sweeps miss — and that the learned prediction, not the
    // small fixed offset sweep, is doing the work: at 22 degrees a zeroed
    // predictor cannot read this frame at all.
    {
        Gray g = renderMsi("011578333", 5, 1600, 900, 300, 200);
        photometric(g, 0.45f, 0);
        boxBlur(g, 1);
        Gray tilted = rotateAny(g, 22.0f);  // clockwise
        msi::FrameOptions so;
        so.maxOrientations = 4;
        so.scanTopFraction = 0.25f;
        so.minDigits = 9;
        so.maxDigits = 9;
        so.stretchRetry = true;
        // The three still-only stages off: plain + stretch sweeps only.
        so.allowStillFallbacks = false;
        msi::FrameResult plain = msi::decodeFrame(tilted.px.data(), tilted.w,
                                                  tilted.h, so);
        assertTrue("22-degree tilt: plain sweeps miss", !plain.decode.ok);
        so.allowStillFallbacks = true;
        msi::FrameResult full = msi::decodeFrame(tilted.px.data(), tilted.w,
                                                 tilted.h, so);
        assertTrue("22-degree tilt: still fallbacks read upright",
                   full.decode.ok && full.decode.digits == "011578333" &&
                       full.relativeRotation == 0);
        msi::DecodeOptions d;
        d.minDigits = 9;
        d.maxDigits = 9;
        int rel = -1;
        msi::DecodeResult ar = msi::decodeAngleFallback(
            tilted.px.data(), tilted.w, tilted.h, d, &rel);
        assertTrue("22-degree tilt: angle fallback itself reads",
                   ar.ok && ar.digits == "011578333" && rel == 0);
        // Sign convention: predictAngle returns the correction to apply
        // (negative for a clockwise tilt), so the frame is not upright when
        // the predictor reports zero.
        const float pred = msi::predictAngle(tilted.px.data(), tilted.w,
                                             tilted.h);
        assertTrue("angle predictor tracks a 22-degree clockwise tilt",
                   pred > -30.0f && pred < -14.0f);
    }

    // 5a-4c. Golden vectors pin the committed msi_angle_model.h weights.
    // The header is generated and not reproducible in this repo, so any
    // stray edit (or a re-train) must fail loudly and update these numbers
    // deliberately. Tolerance covers float contraction differences only.
    {
        auto close = [](float got, float want, float tol) {
            return std::fabs(got - want) <= tol;
        };
        Gray g = renderMsi("011578333", 6, 1600, 800, 250, 200);
        photometric(g, 0.45f, 0);
        boxBlur(g, 1);
        const float upright = msi::predictAngle(g.px.data(), g.w, g.h);
        assertTrue("angle golden: upright 1600x800", close(upright, -2.026f, 0.75f));
        Gray cw = rotateAny(g, 12.0f);
        assertTrue("angle golden: 12-degree clockwise",
                   close(msi::predictAngle(cw.px.data(), cw.w, cw.h), -13.061f, 0.75f));
        Gray ccw = rotateAny(g, -12.0f);
        assertTrue("angle golden: 12-degree counter-clockwise",
                   close(msi::predictAngle(ccw.px.data(), ccw.w, ccw.h), 10.837f, 0.75f));
        Gray blank;
        blank.w = 400;
        blank.h = 300;
        blank.px.assign(120000, 255);
        assertTrue("angle golden: flat frame predicts ~0",
                   close(msi::predictAngle(blank.px.data(), blank.w, blank.h),
                         0.152f, 0.75f));
    }
#endif
    // 5a-5. Small-module still path: a blurred 9-digit tag downscaled to
    // ~2.5px/module must read through the scale fallback without a phantom.
    {
        Gray g = renderMsi("011578333", 5, 900, 400, 200, 120);
        boxBlur(g, 1);
        photometric(g, 0.5f, 0);
        Gray small = downscaleNN(g, 0.5f);
        msi::FrameOptions so;
        so.maxOrientations = 1;
        so.scanTopFraction = 0.25f;
        so.minDigits = 9;
        so.maxDigits = 9;
        so.stretchRetry = true;
        msi::FrameResult fr = msi::decodeFrame(small.px.data(), small.w, small.h, so);
        assertTrue("still small-module fallback reads 9-digit tag",
                   fr.decode.ok && fr.decode.digits == "011578333");
    }

    // 5b. Leading quiet zone (direction arbitration evidence). A 9px module
    //     puts its 6 quiet modules (54px) past the 20px the ROI keeps before
    //     its first column: without the verification head only a window
    //     shifted into the blurred Start bar sees 3 of them, scoring well
    //     under 1.5. A Start bar 1 module from the frame edge leaves it unseen.
    msi::DecodeOptions lo;
    Gray big = renderMsi("12345674", 9, 1600, 400, 100, 200);
    boxBlur(big, 1);
    boxBlur(big, 1);
    msi::DecodeResult bl = msi::decode(big.px.data(), big.w, big.h, lo);
    assertTrue("frame leadingQuiet measured through the verification head (9px module)",
               bl.ok && bl.digits == "12345674" && bl.leadingQuiet > 1.5f);
    Gray crisp = renderMsi("12345674", 7, 1100, 300, 90, 120);
    const int symX0 = (crisp.w - (int)modulesFor("12345674").size() * 7) / 2;
    const int cut = symX0 - 7;
    Gray edge;
    edge.w = crisp.w - cut;
    edge.h = crisp.h;
    edge.px.resize((size_t)edge.w * edge.h);
    for (int y = 0; y < edge.h; ++y)
        std::copy_n(crisp.px.data() + (size_t)y * crisp.w + cut, edge.w, edge.px.data() + (size_t)y * edge.w);
    msi::DecodeResult el = msi::decode(edge.px.data(), edge.w, edge.h, lo);
    assertTrue("frame leadingQuiet unseen when the Start bar touches the frame edge",
               el.ok && el.digits == "12345674" && el.leadingQuiet < -1e8f);
    // The same edge-cut tag through decodeFrame must still read with an
    // unseen lead. This covers the unseen-lead branch; it is not a guard for
    // the retry/reverse-confirmation contract above.
    msi::FrameOptions eo;
    eo.maxOrientations = 2;
    eo.scanTopFraction = 0.0f;
    msi::FrameResult ef = msi::decodeFrame(edge.px.data(), edge.w, edge.h, eo);
    assertTrue("frame edge-cut tag (unseen lead) still confirms and reads",
               ef.decode.ok && ef.decode.digits == "12345674" && ef.relativeRotation == 0);

    // 6. Aim-box crops put the tag anywhere, including the top quarter the
    //    full-frame band search skips (kScanTopFraction).
    Gray high = renderMsi("12345674", 5, 800, 300, 8, 60);
    fo.maxOrientations = 1;
    msi::DecodeOptions legacy;
    assertTrue("frame scanTop 0 reads a tag in the top quarter",
               msi::decodeFrame(high.px.data(), high.w, high.h, fo).decode.ok &&
               !msi::decode(high.px.data(), high.w, high.h, legacy).ok);

    // 7. Still conversion (bitmap -> gray), the core of nativePrepareBitmap.
    assertTrue("still gray dims: even trim, integer scale, invalid -> 0",
               msi::stillGrayDim(4000, 1) == 4000 && msi::stillGrayDim(4001, 1) == 4000 &&
               msi::stillGrayDim(8000, 2) == 4000 && msi::stillGrayDim(7, 2) == 2 &&
               msi::stillGrayDim(1, 1) == 0 && msi::stillGrayDim(0, 1) == 0 &&
               msi::stillGrayDim(100, 0) == 0);
    {
        // Neutral RGBA (R=G=B=v) round-trips exactly (weights sum to 256);
        // row padding and alpha must be ignored.
        const int rowBytes = tag.w * 4 + 12;
        std::vector<uint8_t> rgba((size_t)rowBytes * tag.h, 0xEE);
        for (int y = 0; y < tag.h; ++y)
            for (int x = 0; x < tag.w; ++x) {
                uint8_t* p = &rgba[(size_t)y * rowBytes + (size_t)x * 4];
                p[0] = p[1] = p[2] = tag.px[(size_t)y * tag.w + x];
                p[3] = 0x7F;
            }
        msi::PixelView pv{rgba.data(), tag.w, tag.h, rowBytes, msi::PixelFormat::RGBA_8888};
        std::vector<uint8_t> g((size_t)tag.w * tag.h);
        assertTrue("still RGBA_8888 neutral -> exact luma (strided)",
                   msi::grayFromPixels(pv, 1, g.data()) && g == tag.px);
        // Full still flow: convert, rotate upright (sensor 90), 4-way decode.
        Gray side90 = rotateCw(tag, 270);  // a 90-degree still of the tag
        std::vector<uint8_t> rgba90((size_t)side90.w * side90.h * 4);
        for (size_t i = 0; i < side90.px.size(); ++i)
            rgba90[i * 4] = rgba90[i * 4 + 1] = rgba90[i * 4 + 2] = side90.px[i];
        msi::PixelView pv90{rgba90.data(), side90.w, side90.h, side90.w * 4,
                            msi::PixelFormat::RGBA_8888};
        std::vector<uint8_t> g90(side90.px.size()), up90(side90.px.size());
        msi::LumaView lv{g90.data(), side90.w, side90.h, side90.w};
        msi::Region all{0, 0, side90.w, side90.h};
        msi::FrameOptions so;
        so.maxOrientations = 4;
        so.scanTopFraction = 0.25f;
        const bool ok = msi::grayFromPixels(pv90, 1, g90.data()) &&
                        msi::prepareRegion(lv, all, 90, true, up90.data());
        msi::FrameResult sr = msi::decodeFrame(up90.data(), side90.h, side90.w, so);
        assertTrue("still RGBA rotation 90 -> convert, prepare upright, decode upright",
                   ok && sr.decode.ok && sr.decode.digits == "12345674" && sr.relativeRotation == 0);
    }
    {
        uint8_t px[3 * 4] = {200, 100, 50, 255, 0, 0, 0, 0, 255, 255, 255, 0};
        msi::PixelView pv{px, 2, 1, 12, msi::PixelFormat::RGBA_8888};
        uint8_t g[2] = {0, 0};
        assertTrue("still rejects image under 2 px after even trim", !msi::grayFromPixels(pv, 1, g));
        msi::PixelView pv2{px, 2, 2, 4 * 2 - 4, msi::PixelFormat::RGBA_8888};
        assertTrue("still rejects stride < width * 4", !msi::grayFromPixels(pv2, 1, g));
    }
    {
        // 2x2 RGBA: weights (77R + 150G + 29B) >> 8, alpha ignored.
        const uint8_t px[16] = {200, 100, 50, 0, 255, 255, 255, 255,
                                0, 0, 0, 255, 255, 0, 0, 255};
        msi::PixelView pv{px, 2, 2, 8, msi::PixelFormat::RGBA_8888};
        uint8_t g[4] = {0, 0, 0, 0};
        assertTrue("still RGBA weights 77/150/29",
                   msi::grayFromPixels(pv, 1, g) && g[0] == 124 && g[1] == 255 && g[2] == 0 &&
                   g[3] == 76);
        // Premultiplied RGBA reads as unpremultiplied (what getPixels gave):
        // opaque gray bar vs half-transparent white space stay distinct.
        const uint8_t pm[16] = {128, 128, 128, 255, 128, 128, 128, 128,
                                0, 0, 0, 0, 64, 32, 16, 128};
        msi::PixelView ppm{pm, 2, 2, 8, msi::PixelFormat::RGBA_8888};
        ppm.premultiplied = true;
        assertTrue("still premultiplied RGBA is unpremultiplied (a=255, a=128, a=0)",
                   msi::grayFromPixels(ppm, 1, g) && g[0] == 128 && g[1] == 255 && g[2] == 0 &&
                   g[3] == 79);
        ppm.premultiplied = false;
        assertTrue("still straight RGBA ignores alpha",
                   msi::grayFromPixels(ppm, 1, g) && g[0] == 128 && g[1] == 128 && g[2] == 0 &&
                   g[3] == 39);
        // RGB_565 little-endian: red 0xF800, white 0xFFFF, black, green 0x07E0.
        const uint8_t p565[8] = {0x00, 0xF8, 0xFF, 0xFF, 0x00, 0x00, 0xE0, 0x07};
        msi::PixelView pv565{p565, 2, 2, 4, msi::PixelFormat::RGB_565};
        assertTrue("still RGB_565 bit-replicated expand",
                   msi::grayFromPixels(pv565, 1, g) && g[0] == 76 && g[1] == 255 && g[2] == 0 &&
                   g[3] == 149);
        msi::PixelView pv565s{p565, 2, 2, 3, msi::PixelFormat::RGB_565};
        assertTrue("still rejects RGB_565 stride < width * 2", !msi::grayFromPixels(pv565s, 1, g));
    }
    {
        // A_8 is luma; odd 5x3 trims to 4x2 from the top-left.
        uint8_t a8[15];
        for (int i = 0; i < 15; ++i) a8[i] = (uint8_t)(i * 10);
        msi::PixelView pv{a8, 5, 3, 5, msi::PixelFormat::A_8};
        uint8_t g[8] = {0};
        const uint8_t want[8] = {0, 10, 20, 30, 50, 60, 70, 80};
        assertTrue("still A_8 identity, odd edge trimmed",
                   msi::grayFromPixels(pv, 1, g) && memcmp(g, want, 8) == 0);
        // Scale 2 box mean (rounded): 4x4 -> 2x2; 5x5 -> 2x2 (edge dropped).
        const uint8_t b[16] = {10, 11, 0, 0,
                               12, 13, 0, 1,
                               255, 255, 7, 7,
                               255, 254, 7, 8};
        msi::PixelView pb{b, 4, 4, 4, msi::PixelFormat::A_8};
        uint8_t m[4] = {0};
        assertTrue("still box downscale 2: rounded block means",
                   msi::grayFromPixels(pb, 2, m) && m[0] == 12 && m[1] == 0 && m[2] == 255 &&
                   m[3] == 7);
        assertTrue("still rejects scale 0", !msi::grayFromPixels(pb, 0, m));
    }
    {
        // A tag rendered at 10 px modules survives a 2x box downscale.
        Gray big = renderMsi("12345674", 10, 1600, 600, 180, 240);
        msi::PixelView pv{big.px.data(), big.w, big.h, big.w, msi::PixelFormat::A_8};
        const int dw = msi::stillGrayDim(big.w, 2), dh = msi::stillGrayDim(big.h, 2);
        std::vector<uint8_t> small((size_t)dw * dh);
        msi::FrameOptions so;
        so.maxOrientations = 1;
        assertTrue("still 2x box downscale keeps a 10px-module tag readable",
                   msi::grayFromPixels(pv, 2, small.data()) &&
                   msi::decodeFrame(small.data(), dw, dh, so).decode.digits == "12345674");
    }
    {
        // 8. Still stretch retry: a soft, washed-out tag (~51 gray levels,
        //    3x3 blur) misses plain and reads only through the 2%/98%
        //    stretch, which decodeFrame applies after every plain
        //    orientation failed — keeping the orientation it read under.
        Gray soft = renderMsi("12345674", 5, 800, 300, 90, 120);
        boxBlur(soft, 1);
        photometric(soft, 0.2f, 0);
        msi::FrameOptions so;
        so.maxOrientations = 4;
        so.scanTopFraction = 0.25f;
        assertTrue("still soft low-contrast tag: no read without stretchRetry",
                   !msi::decodeFrame(soft.px.data(), soft.w, soft.h, so).decode.ok);
        so.stretchRetry = true;
        msi::FrameResult sr = msi::decodeFrame(soft.px.data(), soft.w, soft.h, so);
        assertTrue("still soft low-contrast tag: stretchRetry reads upright",
                   sr.decode.ok && sr.decode.digits == "12345674" && sr.relativeRotation == 0);
        Gray softFlip = rotateCw(soft, 180);
        msi::FrameResult sf = msi::decodeFrame(softFlip.px.data(), softFlip.w, softFlip.h, so);
        assertTrue("still soft low-contrast tag upside down: stretchRetry -> relativeRotation 180",
                   sf.decode.ok && sf.decode.digits == "12345674" && sf.relativeRotation == 180);
        // A sensor-rotated still must run the fallbacks on the upright frame;
        // otherwise the angle predictor sees a sideways tag.
        Gray sensorSoft = rotateCw(soft, 270);
        msi::FrameOptions sro = so;
        sro.rotationDegrees = 90;
        msi::FrameResult sr90 = msi::decodeFrame(sensorSoft.px.data(), sensorSoft.w,
                                                 sensorSoft.h, sro);
        assertTrue("still sensor rotation 90: fallback reads upright frame",
                   sr90.decode.ok && sr90.decode.digits == "12345674" &&
                   sr90.relativeRotation == 0);
        // The stretched sweep never touches the caller's buffer.
        Gray before = soft;
        msi::decodeFrame(soft.px.data(), soft.w, soft.h, so);
        assertTrue("still stretchRetry leaves the input buffer untouched", soft.px == before.px);
        // A flat frame (range under 12 levels) has no stretch to retry.
        Gray flat = soft;
        photometric(flat, 0.04f, 0);
        assertTrue("still flat frame: stretchRetry -> no read",
                   !msi::decodeFrame(flat.px.data(), flat.w, flat.h, so).decode.ok);
    }
}

// ------------------------------------------------------------ suites
static void syntheticSuite() {
    printf("--- synthetic (spec-rendered) ---\n");
    // Ground-truth literature vectors: 1234567->4, 8052->3 (Mod10).
    // 5px modules: representative of real captures; 4px pixel-crisp renders
    // can phase-lock against the xstep=2 ROI sampler (known limitation, see
    // findRoiCandidates) so the 4px case runs as an informational probe.
    Gray clean = renderMsi("12345674", 5, 800, 400, 200, 120);
    checkStable("syn mod10 clean 5px", clean, "12345674");
    check("syn mod10 policy", clean, "12345674", "mod10", true);
    Gray lock = renderMsi("12345674", 4, 800, 400, 200, 120);
    check("syn mod10 crisp-4px phaselock-probe", lock, "12345674", nullptr, false);

    // Short/multi-policy vectors at crisp 4px: tracked info (pixel-crisp
    // renders sit outside the blur-assuming template model; real captures
    // never look like this — see phaselock note in findRoiCandidates).
    Gray small = renderMsi("80523", 4, 600, 300, 150, 100);
    checkInfo3("syn mod10 80523", small, "80523");

    Gray alld = renderMsi("01234567897", 4, 1000, 400, 200, 120);
    checkInfo3("syn mod10 alldigits", alld, "01234567897");

    // Pure-Mod11 case: 1111111 -> check 4; must NOT validate as Mod10.
    Gray m11 = renderMsi("11111114", 4, 800, 400, 200, 120);
    checkInfo3("syn mod11 11111114", m11, "11111114");
    check("syn mod11 policy", m11, "11111114", "mod11", false);

    // Double-check round-trips.
    Gray m1010 = renderMsi("123456741", 4, 900, 400, 200, 120);
    checkInfo3("syn mod1010", m1010, "123456741");
    Gray m1110 = renderMsi("111111142", 4, 900, 400, 200, 120);
    checkInfo3("syn mod1110", m1110, "111111142");

    // Scale ladder: close-up wide modules are an informational probe, not a
    // gate — 12px-crisp input is outside the blur-assuming template model
    // (misses honestly). The tmplLen clamp above is crash insurance for the
    // fixed-[96] template/seg arrays (unclamped 12*module could index past
    // them); it is behavior-identical on every input that ever decoded.
    Gray big = renderMsi("12345674", 12, 1402, 700, 350, 200);
    checkInfo3("syn mod10 closeup 12px", big, "12345674");
    Gray mid = renderMsi("12345674", 7, 1000, 500, 250, 150);
    checkInfo3("syn mod10 7px", mid, "12345674");

    // Degradation ladder.
    Gray b1 = clean;
    boxBlur(b1, 1);
    checkStable("syn mod10 blur1", b1, "12345674");
    Gray b2 = clean;
    boxBlur(b2, 2);
    check("syn mod10 blur2", b2, "12345674", nullptr, false);
    Gray lc = clean;
    photometric(lc, 0.5f, 0);
    check("syn mod10 lowcontrast", lc, "12345674", nullptr, false);
    Gray dk = clean;
    photometric(dk, 1.0f, -40);
    checkStable("syn mod10 dark-40", dk, "12345674");

    // Off-center positions.
    Gray top;
    top = renderMsi("12345674", 4, 800, 400, 130, 100);
    check("syn pos band@130", top, "12345674", nullptr, false);
    Gray bot = renderMsi("12345674", 4, 800, 400, 260, 100);
    checkInfo3("syn pos band@260", bot, "12345674");

    // Negatives: must be honest NotFound, must not crash.
    Gray blank;
    blank.w = 640;
    blank.h = 400;
    blank.px.assign((size_t)640 * 400, 255);
    check("neg blank", blank, nullptr, nullptr, true);
    Gray black = blank;
    std::fill(black.px.begin(), black.px.end(), 0);
    check("neg black", black, nullptr, nullptr, true);
    Gray noise = blank;
    uint32_t s = 0x12345678;
    for (size_t i = 0; i < noise.px.size(); ++i) {
        s = s * 1664525u + 1013904223u;
        noise.px[i] = (uint8_t)(s >> 24);
    }
    check("neg noise", noise, nullptr, nullptr, true);
}

// Text-like clutter (glyph stems): dark strokes 1-3px wide, 2-9px apart.
static void addStrokes(Gray& g, int x0, int x1, int y0, int y1, uint32_t seed) {
    auto rnd = [&seed]() {
        seed = seed * 1664525u + 1013904223u;
        return seed >> 16;
    };
    for (int x = std::max(0, x0); x < std::min(g.w, x1);) {
        int w = 1 + (int)(rnd() % 3);
        for (int y = std::max(0, y0); y < std::min(g.h, y1); ++y)
            for (int k = 0; k < w && x + k < g.w; ++k) g.px[(size_t)y * g.w + x + k] = 40;
        x += w + 2 + (int)(rnd() % 8);
    }
}

// Shelf-tag format, rendered from the spec: 7-digit item + Mod10 +
// Mod10 over (item + first check) = 9 digits, 39 bars (real-photo truth:
// 101 of 126 measurable tags; e.g. item 0115783 -> 011578333). Every prefix
// of such a codeword is itself Mod10-valid, and Luhn ignores leading zeros,
// so these cases guard the truncation / dropped-leading-0 failures
// (prefix-drop tie rule, quiet-zone gate) and the required-policy pushdown.
// Base decoder 4c9be3b read 011578333 as 11578333. Blurred like real
// captures (crisp renders sit outside the template model, see
// syntheticSuite); text clutter is glyph-height, at a 10-module quiet zone.
// Robustness: 108/108 over blur 1-2 x band y 200-240 x bar height 90-130.
static void shelfFormatSuite() {
    printf("--- shelf format (spec-rendered 9-digit mod1010) ---\n");
    using P = msi::ChecksumPolicy;
    struct Tag { const char* full; int modulePx; };
    const Tag tags[] = {{"011578333", 5}, {"024307670", 4}, {"082814799", 3}};
    for (const Tag& t : tags) {
        const int barsW = (int)modulesFor(t.full).size() * t.modulePx;
        const int w = barsW + 60 * t.modulePx, h = 400, top = 220, barH = 110;
        const int bx0 = (w - barsW) / 2, bx1 = bx0 + barsW;
        std::string tag = std::string(t.full) + " " + std::to_string(t.modulePx) + "px";
        Gray g = renderMsi(t.full, t.modulePx, w, h, top, barH);
        boxBlur(g, 1);
        check("fmt " + tag + " any", g, t.full, nullptr, true);
        check("fmt " + tag + " req1010", g, t.full, "mod1010", true, P::MOD1010);
        // Item text line above the bars, words on both sides of the symbol.
        Gray c = renderMsi(t.full, t.modulePx, w, h, top, barH);
        addStrokes(c, bx0, bx1, top - 70, top - 30, 7);
        addStrokes(c, bx1 + 10 * t.modulePx, bx1 + 25 * t.modulePx, top + 20, top + 60, 11);
        addStrokes(c, bx0 - 25 * t.modulePx, bx0 - 10 * t.modulePx, top + 20, top + 60, 13);
        boxBlur(c, 1);
        check("fmt " + tag + " text any", c, t.full, nullptr, true);
        check("fmt " + tag + " text req1010", c, t.full, "mod1010", true, P::MOD1010);
    }
    // Required-policy contract: an unsatisfied scheme is NotFound (no
    // fallback to another scheme); a satisfied one is reported as required.
    // (No "MOD11 on a Mod1010 tag" negative: 01157833 is a genuinely
    // Mod11-valid prefix of 011578333, so a misconfigured host CAN read it.)
    Gray m10 = renderMsi("12345674", 5, 800, 400, 200, 120);  // Mod10 only
    boxBlur(m10, 1);
    check("req mod1010 on mod10-only", m10, nullptr, nullptr, true, P::MOD1010);
    check("req mod10 on mod10-only", m10, "12345674", "mod10", true, P::MOD10);
    Gray d = renderMsi("011578333", 5, 900, 400, 200, 120);
    boxBlur(d, 1);
    check("req mod10 on mod1010", d, "011578333", "mod10", true, P::MOD10);
}

// `g` rotated `deg` clockwise about its centre (bilinear, white fill): a
// hand-held tilt, unlike rotateCw's 90-degree sensor rotations.
static Gray rotateSmall(const Gray& g, float deg) {
    const float t = deg * 3.14159265f / 180.0f, c = std::cos(t), s = std::sin(t);
    const float cx = 0.5f * (g.w - 1), cy = 0.5f * (g.h - 1);
    Gray o;
    o.w = g.w;
    o.h = g.h;
    o.px.assign(g.px.size(), 255);
    for (int y = 0; y < g.h; ++y)
        for (int x = 0; x < g.w; ++x) {
            const float sx = cx + (x - cx) * c + (y - cy) * s;
            const float sy = cy - (x - cx) * s + (y - cy) * c;
            const int x0 = (int)std::floor(sx), y0 = (int)std::floor(sy);
            if (x0 < 0 || y0 < 0 || x0 + 1 >= g.w || y0 + 1 >= g.h) continue;
            const float fx = sx - x0, fy = sy - y0;
            auto at = [&](int xx, int yy) { return (float)g.px[(size_t)yy * g.w + xx]; };
            const float v = (at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx) * (1 - fy) +
                            (at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx) * fy;
            o.px[(size_t)y * g.w + x] = (uint8_t)(v + 0.5f);
        }
    return o;
}

// Hand-held tilt on the shelf format (9-digit mod1010, blur 1, with and
// without text clutter). The upright path tolerates about +-7 degrees at
// 4-5px modules and misses +-10-12 (2f5406b: 12 of these 48 missed); the
// skew retry reads all of them. Text strokes tilted the same way must stay
// silent: the retry deskews clutter too.
static void tiltSuite() {
    printf("--- tilt (spec-rendered shelf format, rotated) ---\n");
    const struct { const char* full; int m; } tags[] = {{"011578333", 5}, {"024307670", 4}, {"082814799", 3}};
    for (auto& t : tags) {
        const int barsW = (int)modulesFor(t.full).size() * t.m;
        const int w = barsW + 60 * t.m, h = 400, top = 220, barH = 110;
        const int bx0 = (w - barsW) / 2, bx1 = bx0 + barsW;
        for (int txt = 0; txt < 2; ++txt)
            for (float a : {-12.f, -10.f, -7.f, -3.f, 3.f, 7.f, 10.f, 12.f}) {
                Gray g = renderMsi(t.full, t.m, w, h, top, barH);
                if (txt) {
                    addStrokes(g, bx0, bx1, top - 70, top - 30, 7);
                    addStrokes(g, bx1 + 10 * t.m, bx1 + 25 * t.m, top + 20, top + 60, 11);
                    addStrokes(g, bx0 - 25 * t.m, bx0 - 10 * t.m, top + 20, top + 60, 13);
                }
                boxBlur(g, 1);
                char nm[64];
                snprintf(nm, sizeof nm, "tilt %s %dpx %s %+.0f", t.full, t.m, txt ? "text" : "clean", a);
                check(nm, rotateSmall(g, a), t.full, nullptr, true);
            }
    }
    for (float a : {-8.f, 8.f}) {
        Gray g;
        g.w = 700;
        g.h = 400;
        g.px.assign((size_t)g.w * g.h, 255);
        addStrokes(g, 60, 640, 150, 260, 21);
        boxBlur(g, 1);
        char nm[64];
        snprintf(nm, sizeof nm, "neg tilt strokes %+.0f", a);
        check(nm, rotateSmall(g, a), nullptr, nullptr, true);
    }
}

// Ink bleed: single-module spaces (between a wide bar and the next bar) inked
// solid, so the bar detector merges the neighbours and the bar-anchored
// template decode loses its window alignment for every later digit. The grid
// retry (decodeByDigitGrid) re-tracks the digit windows by pitch; without it,
// 8 of these 9 read nothing.
static void bleedSuite() {
    printf("--- ink bleed (merged bars, grid retry) ---\n");
    const struct { const char* full; int m; } tags[] = {{"026416032", 3}, {"029904224", 4}, {"011578333", 5}};
    const struct { int a, b; } fills[] = {{2, -1}, {9, -1}, {5, 12}};
    for (auto& t : tags) {
        const std::string mods = modulesFor(t.full);
        const int barsW = (int)mods.size() * t.m;
        const int w = barsW + 60 * t.m, h = 400, top = 220, barH = 110;
        const int bx0 = (w - barsW) / 2;
        for (auto& f : fills) {
            Gray g = renderMsi(t.full, t.m, w, h, top, barH);
            int k = 0;
            for (size_t i = 1; i + 1 < mods.size(); ++i) {
                if (mods[i] != '0' || mods[i - 1] != '1' || mods[i + 1] != '1') continue;
                if (k == f.a || k == f.b)
                    for (int x = bx0 + (int)i * t.m; x < bx0 + (int)(i + 1) * t.m; ++x)
                        for (int y = top; y < top + barH; ++y) g.px[(size_t)y * w + x] = 0;
                ++k;
            }
            boxBlur(g, 1);
            char nm[64];
            snprintf(nm, sizeof nm, "bleed %s %dpx space %d%s", t.full, t.m, f.a,
                     f.b >= 0 ? (std::string("+") + std::to_string(f.b)).c_str() : "");
            check(nm, g, t.full, nullptr, true);
        }
    }
}

// Area-average downscale by f (<1): fractional module widths with
// camera-like grey edge pixels, unlike renderMsi's whole-pixel modules.
static Gray downscaleArea(const Gray& g, float f) {
    Gray o;
    o.w = (int)(g.w * f);
    o.h = (int)(g.h * f);
    o.px.resize((size_t)o.w * o.h);
    const float inv = 1.0f / f;
    for (int y = 0; y < o.h; ++y)
        for (int x = 0; x < o.w; ++x) {
            const float sx0 = x * inv, sx1 = (x + 1) * inv, sy0 = y * inv, sy1 = (y + 1) * inv;
            double acc = 0, wsum = 0;
            for (int yy = (int)sy0; yy < (int)std::ceil(sy1) && yy < g.h; ++yy) {
                const float wy = std::min<float>(yy + 1, sy1) - std::max<float>(yy, sy0);
                for (int xx = (int)sx0; xx < (int)std::ceil(sx1) && xx < g.w; ++xx) {
                    const float wx = std::min<float>(xx + 1, sx1) - std::max<float>(xx, sx0);
                    acc += wx * wy * g.px[(size_t)yy * g.w + xx];
                    wsum += wx * wy;
                }
            }
            o.px[(size_t)y * o.w + x] = (uint8_t)(acc / wsum + 0.5);
        }
    return o;
}

// Small modules (1.9-2.5px, fractional) with and without ink bleed, MOD1010
// required (the shelf-tag scheme). Near 2px/module the bar-spacing module
// estimate runs high and the grid retry needs the spectral pitch; grid reads
// there also need only 3 agreeing lines. Asserts 0 wrong reads and a read
// floor: 59dc46a (bar pitches, 6 votes) reads 196 of these 240, D10 220.
static void smallBleedSuite() {
    printf("--- small-module bleed (fractional 1.9-2.5px, MOD1010) ---\n");
    const char* tags[] = {"026416032", "029904224", "011578333", "024948473", "008010688"};
    const struct { int a, b; } fills[] = {{-1, -1}, {2, -1}, {9, -1}, {5, 12}};
    int reads = 0, wrong = 0, total = 0;
    for (const char* t : tags)
        for (float mpx : {1.9f, 2.0f, 2.1f, 2.2f, 2.35f, 2.5f})
            for (auto& f : fills)
                for (int blurR = 0; blurR < 2; ++blurR) {
                    // Rendered at 10px/module, bled, then area-downscaled.
                    const int M = 10;
                    const std::string mods = modulesFor(t);
                    const int barsW = (int)mods.size() * M;
                    const int w = barsW + 60 * M, h = 600, top = 250, barH = 160;
                    const int bx0 = (w - barsW) / 2;
                    Gray g = renderMsi(t, M, w, h, top, barH);
                    int k = 0;
                    for (size_t i = 1; i + 1 < mods.size(); ++i) {
                        if (mods[i] != '0' || mods[i - 1] != '1' || mods[i + 1] != '1') continue;
                        if (k == f.a || k == f.b)
                            for (int x = bx0 + (int)i * M; x < bx0 + (int)(i + 1) * M; ++x)
                                for (int y = top; y < top + barH; ++y) g.px[(size_t)y * w + x] = 0;
                        ++k;
                    }
                    Gray s = downscaleArea(g, mpx / M);
                    if (blurR) boxBlur(s, blurR);
                    const msi::DecodeResult r =
                        msi::decode(s.px.data(), s.w, s.h, msi::ChecksumPolicy::MOD1010);
                    ++total;
                    if (r.ok && r.digits == t) {
                        ++reads;
                    } else {
                        if (r.ok) ++wrong;
                        printf("%s small %s %.2fpx fill %d,%d blur %d got=%s\n",
                               r.ok ? "WRONG" : "miss(info)", t, mpx, f.a, f.b, blurR,
                               r.ok ? r.digits.c_str() : "(none)");
                    }
                }
    printf("small-module bleed: %d/%d read, %d wrong\n", reads, total, wrong);
    assertTrue("small-module bleed: 0 wrong reads", wrong == 0);
    assertTrue("small-module bleed: >= 220 reads", reads >= 220);
}

struct ShelfCase {
    const char* file;
    const char* full;  // expected full codeword (payload + checks)
    int policy;        // SDK policy for the payload-level assertion (see kValidate)
    const char* payload;
    bool assertStretched;  // raw 1.0 is a full-frame miss (no ROI host path);
                           // assert the production contrast-stretch variant
};

// SDK-level sweep: native decode, then the exact Kotlin revalidation+strip,
// asserted at the payload production emits. `variant`: 0 = asserted
// operating envelope, 1 = informational beyond-envelope probe.
static void checkSdk(const std::string& name, const Gray& g, const ShelfCase& sc,
                     bool asserted) {
    auto t0 = std::chrono::steady_clock::now();
    msi::DecodeResult r = msi::decode(g.px.data(), g.w, g.h);
    auto t1 = std::chrono::steady_clock::now();
    long us = std::chrono::duration_cast<std::chrono::microseconds>(t1 - t0).count();
    std::string payload = r.ok ? kValidate(r.digits, sc.policy) : "";
    // Assert the full codeword too (when the table supplies one); the old
    // table only checked the stripped payload, so a wrong check digit could
    // hide behind a valid payload.
    bool fullOk = sc.full[0] == '\0' || (r.ok && r.digits == sc.full);
    bool ok = fullOk && r.ok && payload == sc.payload;
    const char* verdict = ok ? (asserted ? "PASS" : "pass(info)") : (asserted ? "FAIL" : "fail(info)");
    printf("%s %-34s %dx%d want=%s got=%s/%s votes=%d %ldus\n", verdict, name.c_str(), g.w, g.h,
           sc.payload, payload.empty() ? "(none)" : payload.c_str(),
           r.ok ? r.digits.c_str() : "-",
           r.ok ? r.votes : 0, us);
    if (asserted) {
        if (ok)
            ++g_assertPass;
        else
            ++g_assertFails;
    } else if (!ok) {
        ++g_infoFails;
    }
}

// Honest-miss gate: asserts NO valid payload escapes under the policy —
// either a native miss or a truncated/phantom read the revalidation rejects.
// Guards the most-feared regression class (a future change emitting wrong
// values for these inputs). Fails loudly on any emitted payload.
static void checkSdkNone(const std::string& name, const Gray& g, int policy,
                         bool asserted) {
    auto t0 = std::chrono::steady_clock::now();
    msi::DecodeResult r = msi::decode(g.px.data(), g.w, g.h);
    auto t1 = std::chrono::steady_clock::now();
    long us = std::chrono::duration_cast<std::chrono::microseconds>(t1 - t0).count();
    std::string payload = r.ok ? kValidate(r.digits, policy) : "";
    bool ok = payload.empty();
    const char* verdict = ok ? (asserted ? "PASS" : "pass(info)") : (asserted ? "FAIL" : "fail(info)");
    printf("%s %-34s %dx%d want=(none) got=%s/%s votes=%d %ldus\n", verdict, name.c_str(),
           g.w, g.h, payload.empty() ? "(none)" : payload.c_str(),
           r.ok ? r.digits.c_str() : "-", r.ok ? r.votes : 0, us);
    if (asserted) {
        if (ok)
            ++g_assertPass;
        else
            ++g_assertFails;
    } else if (!ok) {
        ++g_infoFails;
    }
}

static void shelfSuite(const std::string& dir) {
    printf("--- shelf photos (real captures) ---\n");
    // Shelf-photo sweeps. Real photos stay OUT of the repo (private labels);
    // drop local msi-<label>-<sku>.png captures in the sweep dir and they are
    // swept here automatically. ABSENT FILES ARE SKIPPED, not failed, so a
    // public checkout (no photos) stays green on the committed suites above.
    // (Why no committed photo-equivalent PNGs? Real captures are private.
    // gen_shelf_synthetics.py can render full-codeword equivalents into a
    // local directory; the suite consumes them by filename. The committed
    // equivalents are the generator script + JVM vectors + runtime suite.)
    static const ShelfCase cases[] = {
        // Full 9-digit Mod1010 codewords (7-digit payload + two checks),
        // identical to what macOS Vision reports for these captures and
        // consistent with the printed SKUs. The old table pinned 8-digit
        // Mod10 codewords; the decoder now reads the full symbol, and the
        // 8-digit prefix ending at the first check is Mod10-valid, so
        // asserting both is the strong form.
        {"msi-yakult-0828147.png", "082814799", 2, "0828147", false},
        {"msi-quakotml-0186477.png", "018647768", 2, "0186477", false},
        {"msi-ondeg-0243523.png", "024352387", 2, "0243523", false},
        // dixie: raw full-frame at 1.0 is an honest miss (Vision misses it
        // too); the production contrast stretch resolves it.
        {"msi-dixie-0087573.png", "008757320", 2, "0087573", true},
        {"msi-starbucks-0168971.png", "016897100", 2, "0168971", false},
        {"msi-silkalm-0826593.png", "082659368", 2, "0826593", false},
    };
    static const float kScales[] = {1.0f, 0.75f, 0.5f};
    for (const ShelfCase& sc : cases) {
        Gray base;
        if (!readPngGray(dir + "/" + sc.file, base, /*quietMissing=*/true)) {
            printf("SKIP %-34s photo absent (private, local-only)\n", sc.file);
            continue;
        }
        printf("photo %s %dx%d\n", sc.file, base.w, base.h);
        // Asserted operating envelope per photo (3x stable), verified on the
        // real captures: all six are 9-digit Mod1010 codewords Vision reads
        // identically. Raw 1.0 is asserted except for dixie, whose full-frame
        // 1.0 miss is honest (Vision misses it too) — its gate rides the
        // production contrast stretch. Stretched/@0.50/harsh: informational
        // recall signal elsewhere.
        std::string file = sc.file;
        // Empty payload = honest-NotFound gate (see table); otherwise exact.
        bool wantNone = sc.payload[0] == '\0';
        for (float f : kScales) {
            Gray v = (f == 1.0f) ? base : downscaleNN(base, f);
            char nm[96];
            snprintf(nm, sizeof(nm), "%s @%.2f", sc.file, f);
            bool asserted = (f == 1.0f) && !sc.assertStretched;
            std::string a = nm, b = nm, c = nm;
            if (wantNone) {
                checkSdkNone(a + " [run1]", v, sc.policy, asserted);
                checkSdkNone(b + " [run2]", v, sc.policy, asserted);
                checkSdkNone(c + " [run3]", v, sc.policy, asserted);
            } else {
                checkSdk(a + " [run1]", v, sc, asserted);
                checkSdk(b + " [run2]", v, sc, asserted);
                checkSdk(c + " [run3]", v, sc, asserted);
            }
        }
        Gray s = base;
        contrastStretch(s);  // production-equivalent preprocessing
        // Stretched variants: asserted only for cases whose gate rides the
        // stretch (see table); otherwise informational recall signal.
        bool sAsserted = sc.assertStretched;
        if (wantNone) {
            checkSdkNone(std::string(sc.file) + " stretched [run1]", s, sc.policy, sAsserted);
            checkSdkNone(std::string(sc.file) + " stretched [run2]", s, sc.policy, sAsserted);
            checkSdkNone(std::string(sc.file) + " stretched [run3]", s, sc.policy, sAsserted);
        } else {
            checkSdk(std::string(sc.file) + " stretched [run1]", s, sc, sAsserted);
            checkSdk(std::string(sc.file) + " stretched [run2]", s, sc, sAsserted);
            checkSdk(std::string(sc.file) + " stretched [run3]", s, sc, sAsserted);
        }
        // Harsh scale+photometric combos: informational recall signal.
        for (float f : kScales) {
            if (f == 1.0f) continue;
            Gray v = downscaleNN(base, f);
            photometric(v, 0.75f, -25);
            char nm[96];
            snprintf(nm, sizeof(nm), "%s @%.2f harsh", sc.file, f);
            if (wantNone)
                checkSdkNone(nm, v, sc.policy, false);
            else
                checkSdk(nm, v, sc, false);
        }
    }
}

static void benchSuite(const std::string& dir) {
    printf("--- bench (20 iterations each) ---\n");
    Gray photo;
    bool hasPhoto = readPngGray(dir + "/msi-dixie-0087573.png", photo);
    Gray synth = renderMsi("12345674", 5, 1280, 720, 360, 200);
    auto bench = [](const char* nm, const Gray& g) {
        // Warmup (caches, branch predictors) then timed mean.
        for (int i = 0; i < 3; ++i) msi::decode(g.px.data(), g.w, g.h);
        long total = 0, worst = 0;
        const int kIters = 20;
        for (int i = 0; i < kIters; ++i) {
            auto t0 = std::chrono::steady_clock::now();
            volatile bool ok = msi::decode(g.px.data(), g.w, g.h).ok;
            (void)ok;
            auto t1 = std::chrono::steady_clock::now();
            long us = std::chrono::duration_cast<std::chrono::microseconds>(t1 - t0).count();
            total += us;
            if (us > worst) worst = us;
        }
        long mean = total / kIters;
        printf("BENCH %-28s %dx%d mean=%ldus worst=%ldus\n", nm, g.w, g.h, mean, worst);
        // Generous host budget: devices do this in single-digit ms; the
        // harness fails loudly on a 100x regression.
        if (mean > 500000) {
            printf("FAIL %-28s over 500ms budget\n", nm);
            ++g_assertFails;
        } else {
            ++g_assertPass;
        }
    };
    bench("synth 1280x720", synth);
    if (hasPhoto) bench("photo dixie", photo);
}

// ----------------------------------------------------- live deskew rescue
// The live tilt rescue consumes an unstretched wide-short crop, estimates the
// projection skew, and tries bicubic rotations. Positive: a clean 5 px/module
// tag rotated 10 degrees in a 972x252 crop. Negative: flat and noise crops
// must decline before any rotation/decode.
static void liveDeskewSuite() {
    printf("--- live deskew rescue (unstretched wide-short crop) ---\n");
    {
        Gray g = renderMsi("011578333", 5, 972, 252, 81, 90);
        boxBlur(g, 1);
        Gray tilted = rotateAny(g, 10.0f);  // clockwise, matches the camera crop
        msi::DecodeOptions d;
        d.minDigits = 9;
        d.maxDigits = 9;
        d.required = msi::ChecksumPolicy::MOD10;
        msi::DecodeResult r = msi::decodeDeskew(tilted.px.data(), tilted.w,
                                                tilted.h, d);
        assertTrue("live deskew: 10-degree crop reads",
                   r.ok && r.digits == "011578333" && r.score >= 0.70f);
    }
    {
        Gray blank;
        blank.w = 972;
        blank.h = 252;
        blank.px.assign(972 * 252, 255);
        msi::DecodeOptions d;
        d.minDigits = 9;
        d.maxDigits = 9;
        d.required = msi::ChecksumPolicy::MOD10;
        msi::DecodeResult r = msi::decodeDeskew(blank.px.data(), blank.w,
                                                blank.h, d);
        assertTrue("live deskew: flat crop declines", !r.ok);

        Gray noise;
        noise.w = 972;
        noise.h = 252;
        noise.px.resize(972 * 252);
        uint32_t s = 0x12345678u;
        for (uint8_t& v : noise.px) {
            s = s * 1664525u + 1013904223u;
            v = (uint8_t)(s >> 24);
        }
        r = msi::decodeDeskew(noise.px.data(), noise.w, noise.h, d);
        assertTrue("live deskew: noise crop declines", !r.ok);
    }
}

// ------------------------------------------------------------ perf guards
// Single-shot all-failure guards for the bounded still fallback. Budgets are
// deliberately generous host limits: they target the old 1.5 s/565 MB class
// of regression, not a device benchmark. They are ADVISORY by default because
// shared CI/VMs are often 2-4x slower than the reference host; only the
// all-failure correctness assertion always gates the exit code.
// MSI_PERF_STRICT=1 makes an overrun a real failure (dedicated bench host).
static void perfSuite() {
    printf("--- still fallback budgets (single shot, host; %s) ---\n",
           perfStrict() ? "enforced" : "advisory");
    auto one = [](const char* name, int w, int h, bool noise, long budgetMs) {
        Gray g;
        g.w = w; g.h = h;
        g.px.assign((size_t)w * h, 255);
        if (noise) {
            uint32_t s = 0x12345678u;
            for (uint8_t& v : g.px) {
                s = s * 1664525u + 1013904223u;
                v = (uint8_t)(s >> 24);
            }
        }
        msi::FrameOptions o;
        o.maxOrientations = 4;
        o.scanTopFraction = 0.25f;
        o.minDigits = 9;
        o.maxDigits = 9;
        o.stretchRetry = true;
        auto t0 = std::chrono::steady_clock::now();
        msi::FrameResult r = msi::decodeFrame(g.px.data(), g.w, g.h, o);
        auto t1 = std::chrono::steady_clock::now();
        const long ms = (long)std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
        printf("PERF %-24s %dx%d %ldms (budget %ldms)\n", name, w, h, ms, budgetMs);
        const bool correct = !r.decode.ok;
#ifdef MSI_SANITIZER_BUILD
        // Sanitizer instrumentation makes wall-clock budgets meaningless;
        // keep the all-failure correctness assertion only.
        assertTrue(name, correct);
#else
        if (!correct) {
            printf("FAIL %-24s decoded an all-failure frame\n", name);
            ++g_assertFails;
        } else if (ms <= budgetMs) {
            assertTrue(name, true);
        } else if (perfStrict()) {
            printf("FAIL %-24s %ldms over %ldms budget (MSI_PERF_STRICT)\n",
                   name, ms - budgetMs, budgetMs);
            ++g_assertFails;
        } else {
            printf("WARN %-24s %ldms over %ldms budget (advisory; "
                   "MSI_PERF_STRICT=1 enforces)\n",
                   name, ms - budgetMs, budgetMs);
            ++g_assertPass;
            ++g_perfOver;
        }
#endif
    };
    one("perf 16MP blank", 4000, 4000, false, 1000);
    one("perf 4MP noise", 2000, 2000, true, 2000);
    one("perf 1000px noise", 1000, 1000, true, 1200);
    one("perf 550px noise", 550, 550, true, 1000);
    one("perf 400px ensemble noise", 400, 400, true, 1200);
}

int main(int argc, char** argv) {
    if (argc > 1 && std::string(argv[1]) == "diag") {
        // Forensics: ./msi_harness diag photo.png [--contrast] [--up2]
        std::string png = (argc > 2) ? argv[2] : "";
        Gray g;
        if (!readPngGray(png, g)) return 2;
        for (int i = 2; i < argc; ++i) {
            std::string flag = argv[i];
            if (flag == "--contrast")
                contrastStretch(g);
            else if (flag == "--up2")
                g = upscaleRetryInput(g);  // decoder's retry input, not plain NN
        }
        printf("diag %s %dx%d\n", png.c_str(), g.w, g.h);
        // Mirror of findRoiCandidates row scoring (xstep=2, thr=24).
        int H = g.h, W = g.w, yStart = H / 4, mx = 0, mxY = 0;
        for (int y = yStart; y < H; ++y) {
            int s = 0;
            const uint8_t* row = &g.px[(size_t)y * W];
            for (int x = 0; x + 1 < W; x += 2) {
                int d = (int)row[x + 1] - (int)row[x];
                if (d < 0) d = -d;
                if (d > 24) ++s;
            }
            if (s > mx) {
                mx = s;
                mxY = y;
            }
        }
        printf("diag rowscore max=%d at y=%d thr=%d\n", mx, mxY, mx * 35 / 100 < 2 ? 2 : mx * 35 / 100);
        msi::setDebug(1);
        msi::DecodeResult r = msi::decode(g.px.data(), g.w, g.h);
        printf("diag ok=%d digits=%s policy=%s votes=%d\n", r.ok,
               r.ok ? r.digits.c_str() : "(none)",
               r.ok ? msi::policyName(r.policy) : "-",
               r.ok ? r.votes : 0);
        return 0;
    }
    std::string shelfDir = (argc > 1) ? argv[1]
                                      : "../../barcode-scanner-sdk/src/test/resources/msi-shelf";
    syntheticSuite();
    shelfFormatSuite();
    tiltSuite();
    bleedSuite();
    smallBleedSuite();
    shelfSuite(shelfDir);
    frameSuite();
    benchSuite(shelfDir);
    liveDeskewSuite();
    perfSuite();
    printf("ASSERTS pass=%d fail=%d (info-only misses=%d)\n", g_assertPass, g_assertFails,
           g_infoFails);
    if (g_perfOver > 0) {
        printf("PERF %d advisory budget overrun(s): host-tuned wall-clock "
               "limits, not asserted (MSI_PERF_STRICT=1 enforces)\n",
               g_perfOver);
    }
    return g_assertFails == 0 ? 0 : 1;
}
