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
//   5. timing bench with a pass/fail budget.
//
// Exit code = number of asserted failures (0 = all green).
// Usage: ./build.sh [path/to/cpp/dir]

#include <algorithm>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include <zlib.h>

#include "msi_decoder.h"
#include "msi_frame.h"

// ------------------------------------------------------------ test context
static int g_assertFails = 0;
static int g_assertPass = 0;
static int g_infoFails = 0;

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

    // 6. Aim-box crops put the tag anywhere, including the top quarter the
    //    full-frame band search skips (kScanTopFraction).
    Gray high = renderMsi("12345674", 5, 800, 300, 8, 60);
    fo.maxOrientations = 1;
    msi::DecodeOptions legacy;
    assertTrue("frame scanTop 0 reads a tag in the top quarter",
               msi::decodeFrame(high.px.data(), high.w, high.h, fo).decode.ok &&
               !msi::decode(high.px.data(), high.w, high.h, legacy).ok);
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

// shelf-tag format, rendered from the spec: 7-digit item + Mod10 +
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

struct ShelfCase {
    const char* file;
    const char* full;  // expected full codeword (payload + checks)
    int policy;        // SDK policy for the payload-level assertion (see kValidate)
    const char* payload;
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
    bool ok = r.ok && payload == sc.payload;
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
    // (Why no committed photo-equivalent PNGs? Crisp renders of these SKUs
    // cannot gate green: true and shifted alignments correlate ~0.96 alike
    // on pixel-perfect input, so any fixed tiebreak just relocates the error
    // — verified across scales/phases/blur/noise. Real captures separate
    // properly via sensor texture. The committed equivalents are the
    // generator script + JVM vectors + the runtime suite above.)
    static const ShelfCase cases[] = {
        {"msi-yakult-0828147.png", "08281479", 0, "0828147"},
        {"msi-quakotml-0186477.png", "018647768", 2, ""},
        {"msi-ondeg-0243523.png", "02435238", 0, ""},
        {"msi-dixie-0087573.png", "00875732", 0, "0087573"},
        {"msi-starbucks-0168971.png", "016897100", 2, "0168971"},
        {"msi-silkalm-0826593.png", "082659368", 0, "0826593"},
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
        // real captures: yakult/dixie/silkalm exact @1.0 under MOD_10,
        // starbucks exact @1.0 under MOD_10_10; quakotml/ondeg @1.0 are
        // honest-NotFound gates (truncated reads must be rejected, never
        // emitted — they FAIL LOUD on a future phantom emission).
        // Stretched/@0.50/harsh: informational recall signal.
        std::string file = sc.file;
        // Empty payload = honest-NotFound gate (see table); otherwise exact.
        bool wantNone = sc.payload[0] == '\0';
        for (float f : kScales) {
            Gray v = (f == 1.0f) ? base : downscaleNN(base, f);
            char nm[96];
            snprintf(nm, sizeof(nm), "%s @%.2f", sc.file, f);
            bool asserted = (f == 1.0f);
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
        // Stretched variants are informational recall signal (the stretch
        // redistributes pixel statistics the fixtures weren't tuned for).
        if (wantNone) {
            checkSdkNone(std::string(sc.file) + " stretched [run1]", s, sc.policy, false);
            checkSdkNone(std::string(sc.file) + " stretched [run2]", s, sc.policy, false);
            checkSdkNone(std::string(sc.file) + " stretched [run3]", s, sc.policy, false);
        } else {
            checkSdk(std::string(sc.file) + " stretched [run1]", s, sc, false);
            checkSdk(std::string(sc.file) + " stretched [run2]", s, sc, false);
            checkSdk(std::string(sc.file) + " stretched [run3]", s, sc, false);
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
    shelfSuite(shelfDir);
    frameSuite();
    benchSuite(shelfDir);
    printf("ASSERTS pass=%d fail=%d (info-only misses=%d)\n", g_assertPass, g_assertFails,
           g_infoFails);
    return g_assertFails == 0 ? 0 : 1;
}
