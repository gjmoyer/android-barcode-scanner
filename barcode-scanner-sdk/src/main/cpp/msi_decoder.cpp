// Clean-room MSI Plessey decoder implementation.
// Algorithm (from public spec + signal-processing first principles):
//
//  1. ROI: rows with dense thin vertical gradients => barcode band; columns
//     with sustained vertical edges => horizontal extent.
//  2. Scanlines: several horizontal profiles across the band (averaged profile
//     plus individual rows). Blur varies per row; voting across rows wins.
//  3. Per profile: find bar centers as prominent local minima of luminance.
//  4. Module = median(bar-center spacing) / 3  (each MSI bit is 3 modules).
//  5. Bar width = distance between |gradient| peaks flanking the bar center
//     (inflection points are blur-invariant for symmetric blur).
//  6. 2-means cluster widths into narrow/wide (no hand-tuned threshold).
//  7. Hypothesis test: expected bar sequence is W(start) + 4n bits + n,n(stop).
//     Try plausible (start trim, digit count) combos to absorb false/missed
//     bar detections; decode bits -> BCD -> digits.
//  8. Checksum gate: try MOD10/MOD11/MOD1010/MOD1110/NONE; only candidates
//     passing a policy (or NONE with multi-row agreement) become votes.
//  9. Winner = most-voted digit string across scanlines.
#include "msi_decoder.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <map>
#include <string>
#include <vector>

namespace msi {
namespace {

// Debug verbosity, set via msi::setDebug().
int g_debug = 0;
// When true, decodeByDigitTemplates allows more missing bars (for faint images).
// Set by decode() for the fallback pass.
bool g_relaxedBars = false;

} // namespace

void setDebug(int v) { g_debug = v; }

namespace {

// ---------------------------------------------------------------- tunables
constexpr int kMaxBars = 128;        // max bar centers per scanline
constexpr int kMaxScanlines = 9;     // profiles tried per image
constexpr int kMinDigits = 7;   // shelf SKUs are 7-digit + 1-2 checks; shorter
                              // candidates are texture false positives that pass
                              // Mod10 by 1-in-10 chance (verified vs commercial
                              // truth 2026-10-11: 75 short FPs, zero real <7).
constexpr int kMaxDigits = 10;   // real MSI payloads + checks top out here
                               // (7-digit SKU + 2 checks = 9; meat 6+2 = 8).
                               // Longer "valid" strings are template noise
                               // that happened to pass checks (verified FPs
                               // with 11-15 digits on rails).
constexpr int kMinVotesNone = 3;     // NONE policy needs more agreement
constexpr int kMinVotesCheck = 2;    // checksum'd policies need 2
// Default fraction of the frame height skipped at the top during ROI band
// search (DecodeOptions::scanTopFraction). Full frames only: close-up labels
// filling the frame still decode (their band extends below the cut); anything
// living entirely in the top quarter is missed by design (status overlays /
// competing print live there on a full-frame aim UI). Aim-box crops pass 0 —
// the box IS the target area. Change with care: widening the search adds ROI
// candidates that compete in voting.
constexpr float kScanTopFraction = 0.25f;
// Long-edge ceiling for the far-field 2x upscale retry (decodeImpl): small
// frames only, so the 4x-pixel retry stays cheap. 1280px+ frames already
// carry >=2px modules when a barcode is plausibly framed.
constexpr int kUpscaleMaxLongEdge = 800;
// Module ceiling (px at 1x) for the 1D 2x profile retry (decodeImpl). Shelf
// set gains plateau at 4.5 (3.5 -> 4.5: +1 near, +5 far, +1 still; 6.0 and
// open: identical to 4.5): module estimates on failed ROIs skew high when
// false bars join the median, so the cap sits well above the true ~2-3px.
constexpr float kProfileRetryMaxModule = 4.5f;
// Digit-grid residual ceiling (modules) for decodeByDigitTemplates. MSI digits
// sit on a uniform 12-module pitch, so the chosen digit windows must fall on a
// line. Window/alignment search leaves true reads within ~1 module (shelf set:
// max 0.63); one slipped anchor bar moves a window >=2.5 modules, and
// checksum-valid phantoms that ride dropped/extra bars sat at 6-52 modules.
constexpr float kMaxDigitGridResidual = 1.5f;
// Start/Stop/quiet-zone confirmation floors (guardEvidence units: digit-field
// intensity standard deviations). The template decode never checks the
// guards, so a checksum-valid string can be a reversed (180-degree) scan, a
// shifted grid, texture, or a truncated symbol. Truncation is the dangerous
// case for MOD10: an 8-digit prefix of a 9-digit MOD1010 code always passes
// MOD10, and digits 0-7 begin with "1001", so the cut lands on a perfect fake
// Stop. Each element must be confirmed on at least one agreeing scanline.
// Shelf set (crops at 9 digits, full photos at open lengths; MOD10, MOD1010
// and NONE pooled), per read best scanline, true reads vs checksum-valid
// phantoms with the gate off:
//   Start "110" (2-module bar mean vs space):   true >= 0.76; phantoms <= 0.49
//   Stop "1001", each space module vs the
//   brighter bar (strict; a mean let reversed
//   reads through at ~1.0):                     true >= 0.41; phantoms <= 0.08
//   Quiet zone, darkest of kQuietModules after
//   the Stop vs the brighter Stop bar:          true >= 0.52; 2 truncations <= -1.3
constexpr float kMinStartContrast = 0.6f;
constexpr float kMinStopContrast = 0.25f;
constexpr float kMinQuietContrast = 0.3f;
// Six modules always reach the next bar after a fake Stop (a truncated digit
// continues within 3 modules) with slack for the +/-0.5-module placement.
constexpr int kQuietModules = 6;
constexpr float kGuardUnseen = -1e9f;

// Guard evidence of one decoded hypothesis; kGuardUnseen marks an element
// that falls outside the profile.
struct GuardEvidence {
    float start = kGuardUnseen;
    float stop = kGuardUnseen;
    float quiet = kGuardUnseen;
    void keepBest(const GuardEvidence& o) {
        start = std::max(start, o.start);
        stop = std::max(stop, o.stop);
        quiet = std::max(quiet, o.quiet);
    }
};

struct Candidate {
    std::string digits;
    ChecksumPolicy policy;
    float score;  // average template correlation (higher = better)
    float module;  // bar-module size in profile pixels for this observation
    GuardEvidence guard;  // guardEvidence() of the winning hypothesis
};

const char* policyNameImpl(ChecksumPolicy p) {
    switch (p) {
        case ChecksumPolicy::MOD10: return "mod10";
        case ChecksumPolicy::MOD11: return "mod11";
        case ChecksumPolicy::MOD1010: return "mod1010";
        case ChecksumPolicy::MOD1110: return "mod1110";
        case ChecksumPolicy::NONE: return "none";
    }
    return "?";
}

} // namespace

const char* policyName(ChecksumPolicy p) { return policyNameImpl(p); }

namespace {

// ------------------------------------------------------------ checksums
int mod10Check(const std::string& payload) {
    int sum = 0;
    bool dbl = true;
    for (int i = (int)payload.size() - 1; i >= 0; --i) {
        int d = payload[i] - '0';
        if (dbl) { d *= 2; if (d > 9) d -= 9; }
        sum += d;
        dbl = !dbl;
    }
    return (10 - (sum % 10)) % 10;
}

int mod11Check(const std::string& payload) {
    int sum = 0, w = 2;
    for (int i = (int)payload.size() - 1; i >= 0; --i) {
        sum += (payload[i] - '0') * w;
        if (++w > 7) w = 2;
    }
    int c = (11 - (sum % 11)) % 11;
    return c == 10 ? -1 : c;  // 10 unrepresentable as single digit
}

// Strength of the STRONGEST checksum the string validates under (a string
// can satisfy several policies at once — e.g. every mod1010 code trivially
// satisfies the single mod10 leg over its full length — so the reported
// first-match policy understates it). Double-check strength (2) beats
// single (1): passing two independent checks by chance is ~1-in-100.
int checksumStrength(const std::string& full) {
    if (full.size() < 2) return 0;
    bool single = false;
    if ((full.back() - '0') == mod10Check(full.substr(0, full.size() - 1))) single = true;
    int c11 = mod11Check(full.substr(0, full.size() - 1));
    if (c11 >= 0 && (full.back() - '0') == c11) single = true;
    if (full.size() >= 3) {
        std::string pay = full.substr(0, full.size() - 2);
        if ((full[full.size() - 2] - '0') == mod10Check(pay) &&
            (full.back() - '0') == mod10Check(pay + full[full.size() - 2]))
            return 2;
        int c1 = mod11Check(pay);
        if (c1 >= 0 && (full[full.size() - 2] - '0') == c1 &&
            (full.back() - '0') == mod10Check(pay + full[full.size() - 2]))
            return 2;
    }
    return single ? 1 : 0;
}

// Caller-required checksum scheme for the decode() call in progress on this
// thread (NONE = accept any supported scheme). Thread-local so concurrent
// decodes stay independent; only decode() sets and restores it.
thread_local ChecksumPolicy t_required = ChecksumPolicy::NONE;
// Caller symbol-length bounds for the decode() call in progress (0 = open).
thread_local int t_minDigits = 0;
thread_local int t_maxDigits = 0;
// Running count of template hypotheses that failed only the caller-required
// checksum (valid under another supported scheme). MSI evidence for the
// small-module retry trigger; compared before/after a ROI pass, so it never
// needs resetting.
thread_local int t_policyRejected = 0;

bool lengthAllowed(int nd) {
    return (t_minDigits <= 0 || nd >= t_minDigits) && (t_maxDigits <= 0 || nd <= t_maxDigits);
}

// True when a symbol longer than nd digits would also be accepted.
bool longerSymbolAllowed(int nd) {
    return nd < (t_maxDigits > 0 ? t_maxDigits : kMaxDigits);
}

// Returns payload-without-check on success, empty on failure. `required`
// defaults to the caller's scheme for this decode; NONE = any supported one.
std::string validateChecksum(const std::string& full, ChecksumPolicy& outPolicy,
                             ChecksumPolicy required = t_required) {
    for (char c : full) if (c < '0' || c > '9') return {};
    // Reject uniform strings ("00000000", "8888"): blank/periodic texture
    // decodes to repeats that pass Mod10 by chance (verified FPs on rails).
    // A real code always differs in its check digit(s) — except the
    // degenerate all-zero payload, which is not a shippable SKU.
    if (full.size() >= 4) {
        bool uniform = true;
        for (size_t i = 1; i < full.size(); ++i)
            if (full[i] != full[0]) { uniform = false; break; }
        if (uniform) return {};
    }
    const ChecksumPolicy anyOrder[] = {
        ChecksumPolicy::MOD10, ChecksumPolicy::MOD11,
        ChecksumPolicy::MOD1010, ChecksumPolicy::MOD1110
        // NONE disabled: too many false positives on blurry images.
        // If a truly checksum-less barcode is needed, re-enable with
        // a much higher vote threshold.
    };
    // A caller-required scheme restricts candidates BEFORE ranking and
    // voting. Filtering only the final winner (host re-validation) let a
    // Mod10-valid truncation of a Mod1010 tag outvote the true read and
    // then be rejected (or accepted under a looser host policy). Real
    // photos, MOD1010 required: 378 -> 392/755 ok, wrong 55 -> 20.
    const ChecksumPolicy* order = anyOrder;
    size_t norder = sizeof(anyOrder) / sizeof(anyOrder[0]);
    if (required != ChecksumPolicy::NONE) {
        order = &required;
        norder = 1;
    }
    for (size_t k = 0; k < norder; ++k) {
        const ChecksumPolicy p = order[k];
        bool ok = false;
        switch (p) {
            case ChecksumPolicy::MOD10:
                ok = full.size() >= 2 &&
                     (full.back() - '0') == mod10Check(full.substr(0, full.size() - 1));
                break;
            case ChecksumPolicy::MOD11: {
                if (full.size() < 2) break;
                int c = mod11Check(full.substr(0, full.size() - 1));
                ok = c >= 0 && (full.back() - '0') == c;
                break;
            }
            case ChecksumPolicy::MOD1010: {
                if (full.size() < 3) break;
                std::string pay = full.substr(0, full.size() - 2);
                ok = (full[full.size() - 2] - '0') == mod10Check(pay) &&
                     (full.back() - '0') == mod10Check(pay + full[full.size() - 2]);
                break;
            }
            case ChecksumPolicy::MOD1110: {
                if (full.size() < 3) break;
                std::string pay = full.substr(0, full.size() - 2);
                int c1 = mod11Check(pay);
                if (c1 < 0) break;
                std::string withC1 = pay + full[full.size() - 2];
                ok = (full[full.size() - 2] - '0') == c1 &&
                     (full.back() - '0') == mod10Check(withC1);
                break;
            }
            case ChecksumPolicy::NONE:
                ok = true;
                break;
        }
        if (ok) { outPolicy = p; return full; }
    }
    return {};
}

// ---------------------------------------------------------------- ROI
struct Rect { int x0, x1, y0, y1; };

// Find candidate barcode bands: rows with dense vertical gradients.
// Returns multiple candidates; the decoder tries each and the checksum picks.
int findRoiCandidates(const uint8_t* g, int W, int H, Rect* rois, int cap,
                      float scanTopFraction) {
    std::vector<int> rowScore(H, 0);
    // NOTE: xstep=2 subsampling is blind to edges landing exactly between
    // samples — a pixel-crisp, axis-aligned capture (screenshot, NN-upscaled
    // image) can phase-lock so a perfect barcode scores 0 (verified with a
    // host harness: 4px-aligned synthetic invisible, 5px+ visible). xstep=1
    // was tried and REVERTED: it rescues phase-locked inputs but shifts band
    // fringes enough to flip real-photo outcomes both ways (dixie full-res
    // lost, yakult/silkalm gained — see tools/msi-harness runs). Real captures
    // always carry enough noise/blur to break the lock, so keep the validated
    // operating point; revisit only with the device photo benchmark green.
    const int xstep = 2;
    const int gradThr = 24;
    for (int y = 0; y < H; ++y) {
        int s = 0;
        const uint8_t* row = g + y * W;
        for (int x = 0; x + 1 < W; x += xstep) {
            int d = (int)row[x + 1] - (int)row[x];
            if (d < 0) d = -d;
            if (d > gradThr) ++s;
        }
        rowScore[y] = s;
    }
    int mx = 0;
    const int yStart = static_cast<int>(H * scanTopFraction);
    for (int y = yStart; y < H; ++y) mx = std::max(mx, rowScore[y]);
    if (mx < 6) return 0;
    int thr = mx * 35 / 100;
    if (thr < 2) thr = 2;
    // Find ALL runs above threshold (not just longest).
    struct Band { int y0, y1, score; };
    Band bands[16];
    int nbands = 0;
    int cur0 = -1, curScore = 0;
    for (int y = yStart; y < H && nbands < 16; ++y) {
        if (rowScore[y] >= thr) {
            if (cur0 < 0) { cur0 = y; curScore = 0; }
            curScore += rowScore[y];
        } else if (cur0 >= 0) {
            if (y - cur0 >= 10) bands[nbands++] = {cur0, y, curScore};
            cur0 = -1;
        }
    }
    if (cur0 >= 0 && H - cur0 >= 10 && nbands < 16)
        bands[nbands++] = {cur0, H, curScore};
    // Sort bands by score descending (strongest edge density first).
    for (int i = 0; i < nbands; ++i)
        for (int j = i + 1; j < nbands; ++j)
            if (bands[j].score > bands[i].score) {
                Band t = bands[i]; bands[i] = bands[j]; bands[j] = t;
            }
    // For each band, find horizontal extent.
    int nrois = 0;
    for (int b = 0; b < nbands && nrois < cap; ++b) {
        int y0 = bands[b].y0, y1 = bands[b].y1;
        std::vector<int> colScore(W, 0);
        for (int y = y0; y < y1; ++y) {
            const uint8_t* row = g + y * W;
            for (int x = 0; x + 1 < W; ++x) {
                int d = (int)row[x + 1] - (int)row[x];
                if (d < 0) d = -d;
                if (d > gradThr) ++colScore[x];
            }
        }
        int bandH = y1 - y0;
        int need = bandH / 3;
        if (need < 3) need = 3;
        int x0 = 0, x1 = W - 1;
        while (x0 < W && colScore[x0] < need) ++x0;
        while (x1 > x0 && colScore[x1] < need) --x1;
        if (x1 - x0 < 60) continue;
        x0 = std::max(0, x0 - 20);
        rois[nrois++] = {x0, x1, y0, y1};
    }
    return nrois;
}

// Legacy single-ROI wrapper (kept for compatibility).
bool findRoi(const uint8_t* g, int W, int H, Rect& roi) {
    Rect rois[8];
    int n = findRoiCandidates(g, W, H, rois, 8, kScanTopFraction);
    if (n == 0) return false;
    roi = rois[0];
    return true;
}

// ------------------------------------------------------- profile utils
// Extract horizontal profile (float, 0=black..255=white), light smoothing.
void extractProfile(const uint8_t* g, int W, const Rect& roi, int y,
                    std::vector<float>& out) {
    out.resize(roi.x1 - roi.x0);
    const uint8_t* row = g + y * W;
    int n = roi.x1 - roi.x0;
    for (int i = 0; i < n; ++i) {
        int x = roi.x0 + i;
        float v = row[x];
        if (i > 0 && i + 1 < n) v = (row[x - 1] + 2 * row[x] + row[x + 1]) * 0.25f;
        out[i] = v;
    }
}

void averageProfile(const uint8_t* g, int W, const Rect& roi,
                    std::vector<float>& out) {
    int n = roi.x1 - roi.x0;
    out.assign(n, 0.0f);
    int cnt = 0;
    for (int y = roi.y0; y < roi.y1; ++y) {
        const uint8_t* row = g + y * W;
        for (int i = 0; i < n; ++i) out[i] += row[roi.x0 + i];
        ++cnt;
    }
    for (int i = 0; i < n; ++i) out[i] /= cnt;
    // smooth
    std::vector<float> s(n);
    for (int i = 0; i < n; ++i) {
        float v = out[i];
        if (i > 0 && i + 1 < n) v = (out[i - 1] + 2 * out[i] + out[i + 1]) * 0.25f;
        s[i] = v;
    }
    out.swap(s);
}

// Appends the verification tail to a scanline (y >= 0) or band-average
// (y < 0) profile: image columns from roi.x1 on, smoothed like the profile,
// for guardEvidence() only — decoding never sees them. findRoiCandidates cuts
// the ROI at the last strong gradient column, which can fall inside the Stop
// (shelf set: 10 true reads had no measurable Stop) and never covers the
// quiet zone. Padding the ROI itself was tried and perturbed marginal decodes
// both ways; the tail checks without decoding. Length max(48px, ROI/6) covers
// the Stop plus kQuietModules (a symbol spans ~115 modules, so ROI/6 >= 19);
// a frame edge shortens it, which is how guardEvidence sees a cut.
void appendVerifyTail(const uint8_t* g, int W, const Rect& roi, int y,
                      std::vector<float>& pr) {
    const int ext = std::min(std::max(48, (roi.x1 - roi.x0) / 6), W - roi.x1);
    if (ext <= 0) return;
    auto at = [&](int x) {
        if (y >= 0) return (float)g[y * W + x];
        float m = 0;
        for (int r = roi.y0; r < roi.y1; ++r) m += g[r * W + x];
        return m / (roi.y1 - roi.y0);
    };
    pr.reserve(pr.size() + ext);
    for (int x = roi.x1; x < roi.x1 + ext; ++x) {
        const float c = at(x);
        pr.push_back(x - 1 >= 0 && x + 1 < W ? (at(x - 1) + 2 * c + at(x + 1)) * 0.25f : c);
    }
}

// ------------------------------------------------------- bar detection
struct Bar { float center; float width; };  // width via inflection points

// Find bar centers as prominent local minima. Returns count.
int findBars(const float* p, int n, Bar* bars, int cap, bool sensitive = false) {
    float mean = 0;
    for (int i = 0; i < n; ++i) mean += p[i];
    mean /= n;
    // Adaptive depth threshold: for low-contrast images, bars are shallow.
    // Use stddev to estimate contrast; scale threshold accordingly.
    float var = 0;
    for (int i = 0; i < n; ++i) {
        float d = p[i] - mean;
        var += d * d;
    }
    float stddev = sqrtf(var / n);
    // Threshold: 0.4 * stddev, clamped to [3, 8].
    // Sensitive mode: use 0.25 * stddev, clamped to [2, 6].
    float depthThr = sensitive ? 0.25f * stddev : 0.4f * stddev;
    float lo = sensitive ? 2.0f : 3.0f;
    float hi = sensitive ? 6.0f : 8.0f;
    if (depthThr < lo) depthThr = lo;
    if (depthThr > hi) depthThr = hi;
    int nb = 0;
    for (int i = 1; i + 1 < n && nb < cap; ++i) {
        if (p[i] < p[i - 1] && p[i] <= p[i + 1] && p[i] < mean - depthThr) {
            // merge with previous if too close (keep deeper)
            if (nb > 0 && i - bars[nb - 1].center < 3.0f) {
                if (p[i] < p[(int)bars[nb - 1].center]) bars[nb - 1].center = (float)i;
                continue;
            }
            bars[nb++].center = (float)i;
        }
    }
    // Measure widths via inflection points: max |gradient| on each side.
    for (int b = 0; b < nb; ++b) {
        int c = (int)bars[b].center;
        int le = c, re = c;
        float best = -1;
        for (int i = std::max(1, c - 14); i < c; ++i) {
            float gd = fabsf(p[i + 1] - p[i - 1]) * 0.5f;
            if (gd > best) { best = gd; le = i; }
        }
        best = -1;
        for (int i = c; i < std::min(n - 1, c + 14); ++i) {
            float gd = fabsf(p[i + 1] - p[i - 1]) * 0.5f;
            if (gd > best) { best = gd; re = i; }
        }
        bars[b].width = (float)(re - le);
    }
    return nb;
}

// 2-means on widths -> label each bar narrow(0)/wide(1). Returns threshold.
float clusterWidths(Bar* bars, int nb, uint8_t* labels) {
    float lo = 1e9f, hi = -1e9f;
    for (int i = 0; i < nb; ++i) {
        lo = std::min(lo, bars[i].width);
        hi = std::max(hi, bars[i].width);
    }
    float c0 = lo, c1 = hi;
    for (int it = 0; it < 25; ++it) {
        float s0 = 0, s1 = 0; int n0 = 0, n1 = 0;
        float thr = (c0 + c1) * 0.5f;
        for (int i = 0; i < nb; ++i) {
            if (bars[i].width < thr) { s0 += bars[i].width; ++n0; }
            else { s1 += bars[i].width; ++n1; }
        }
        if (!n0 || !n1) break;
        float nc0 = s0 / n0, nc1 = s1 / n1;
        if (fabsf(nc0 - c0) < 1e-3f && fabsf(nc1 - c1) < 1e-3f) break;
        c0 = nc0; c1 = nc1;
    }
    float thr = (c0 + c1) * 0.5f;
    for (int i = 0; i < nb; ++i) labels[i] = bars[i].width >= thr ? 1 : 0;
    return thr;
}

// BCD table: index = 4-bit value, value = char or 0 if invalid (all 0-9 valid).
inline char bcdToChar(int v) { return (v >= 0 && v <= 9) ? (char)('0' + v) : 0; }

// Try decoding bars[s .. s+count) as an MSI symbol. labels: 1=wide,0=narrow.
// Expected: bars[s]=W(start), bars[s+1 .. s+4n]=digits, last two narrow(stop).
// Returns decoded string or empty.
std::string tryDecodeBars(const uint8_t* labels, int s, int ndigits) {
    int count = 1 + 4 * ndigits + 2;
    if (labels[s] != 1) return {};                       // start must be wide
    if (labels[s + count - 2] != 0) return {};           // stop bars narrow
    if (labels[s + count - 1] != 0) return {};
    std::string out;
    out.reserve(ndigits);
    for (int d = 0; d < ndigits; ++d) {
        int v = 0;
        for (int b = 0; b < 4; ++b) {
            v = (v << 1) | labels[s + 1 + d * 4 + b];
        }
        char c = bcdToChar(v);
        if (!c) return {};
        out.push_back(c);
    }
    return out;
}

// Decode one profile. Returns candidate (digits, policy) pairs found.
// Filter bars by spacing sanity: consecutive bars should be ~3 modules apart.
// Removes false splits (too close) and flags gaps (missed bars).
// Returns filtered count. Module estimate is output.
int filterBars(Bar* bars, int nb, float& moduleOut) {
    if (nb < 8) return nb;
    // Estimate module from median spacing.
    float spacings[kMaxBars];
    for (int i = 0; i + 1 < nb; ++i)
        spacings[i] = bars[i + 1].center - bars[i].center;
    std::sort(spacings, spacings + nb - 1);
    float med = spacings[(nb - 1) / 2];
    float module = med / 3.0f;
    moduleOut = module;
    // Remove bars that are too close (< 1.8 modules) to previous: keep the one
    // with larger width (more likely a real bar, not noise).
    // Actually simpler: keep deeper minimum. We don't have depth here, so keep
    // the wider one (false splits tend to be narrow).
    Bar filtered[kMaxBars];
    int nf = 0;
    for (int i = 0; i < nb; ++i) {
        if (nf > 0 && bars[i].center - filtered[nf - 1].center < 1.8f * module) {
            // Too close: keep the wider bar.
            if (bars[i].width > filtered[nf - 1].width)
                filtered[nf - 1] = bars[i];
            continue;
        }
        filtered[nf++] = bars[i];
    }
    memcpy(bars, filtered, nf * sizeof(Bar));
    return nf;
}

// Classify bars by correlating the signal around each bar center against
// narrow-bar and wide-bar templates. More robust than width thresholding
// under blur because it matches signal SHAPE, not absolute sizes.
void classifyBarsByCorrelation(const float* p, int n, Bar* bars, int nb,
                               float module, uint8_t* labels) {
    // Build templates: narrow (1 module black) and wide (2 modules black),
    // each in a 4-module window, blurred to match observed blur.
    // We estimate blur sigma from the data: narrow bars' measured widths.
    const int winMods = 4;
    int winPx = (int)(winMods * module + 0.5f);
    if (winPx < 8) winPx = 8;
    if (winPx > 64) winPx = 64;

    // Template: square wave (-1 for black, +1 for white), then blur.
    // Narrow: black in middle 1 module. Wide: black in middle 2 modules.
    float tmplN[64], tmplW[64];
    // Estimate sigma: use 0.35 * module as blur (typical for these images).
    float sigma = 0.35f * module;
    for (int i = 0; i < winPx; ++i) {
        float x = (i - winPx / 2.0f) / module;  // in modules, centered
        float sqN = (fabsf(x) < 0.5f) ? -1.0f : 1.0f;
        float sqW = (fabsf(x) < 1.0f) ? -1.0f : 1.0f;
        tmplN[i] = sqN;
        tmplW[i] = sqW;
    }
    // Gaussian blur the templates.
    float kern[13];
    int kh = (int)(sigma * 3);
    if (kh > 6) kh = 6;
    if (kh < 1) kh = 1;
    float ks = 0;
    for (int i = -kh; i <= kh; ++i) {
        kern[i + kh] = expf(-0.5f * i * i / (sigma * sigma));
        ks += kern[i + kh];
    }
    for (int i = 0; i <= 2 * kh; ++i) kern[i] /= ks;
    float bN[64], bW[64];
    for (int i = 0; i < winPx; ++i) {
        float sN = 0, sW = 0;
        for (int k = -kh; k <= kh; ++k) {
            int j = i + k;
            if (j < 0) j = 0;
            if (j >= winPx) j = winPx - 1;
            sN += tmplN[j] * kern[k + kh];
            sW += tmplW[j] * kern[k + kh];
        }
        bN[i] = sN;
        bW[i] = sW;
    }
    // Normalize templates (zero mean, unit variance).
    float mN = 0, mW = 0;
    for (int i = 0; i < winPx; ++i) { mN += bN[i]; mW += bW[i]; }
    mN /= winPx; mW /= winPx;
    float vN = 0, vW = 0;
    for (int i = 0; i < winPx; ++i) {
        bN[i] -= mN; bW[i] -= mW;
        vN += bN[i] * bN[i]; vW += bW[i] * bW[i];
    }
    vN = sqrtf(vN); vW = sqrtf(vW);
    if (vN > 1e-6f) for (int i = 0; i < winPx; ++i) bN[i] /= vN;
    if (vW > 1e-6f) for (int i = 0; i < winPx; ++i) bW[i] /= vW;

    // Classify each bar.
    for (int b = 0; b < nb; ++b) {
        int c = (int)(bars[b].center + 0.5f);
        int i0 = c - winPx / 2;
        if (i0 < 0 || i0 + winPx > n) {
            // Fallback to width-based if near edge.
            labels[b] = bars[b].width > 1.5f * module ? 1 : 0;
            continue;
        }
        // Extract and normalize signal window.
        float seg[64];
        float ms = 0;
        for (int i = 0; i < winPx; ++i) {
            seg[i] = p[i0 + i];
            ms += seg[i];
        }
        ms /= winPx;
        float vs = 0;
        for (int i = 0; i < winPx; ++i) {
            seg[i] -= ms;
            vs += seg[i] * seg[i];
        }
        vs = sqrtf(vs);
        if (vs < 1e-6f) {
            labels[b] = 0;
            continue;
        }
        for (int i = 0; i < winPx; ++i) seg[i] /= vs;
        float cN = 0, cW = 0;
        for (int i = 0; i < winPx; ++i) {
            cN += seg[i] * bN[i];
            cW += seg[i] * bW[i];
        }
        labels[b] = (cW > cN) ? 1 : 0;
    }
}

// Digit-template correlation decode: for each digit position, correlate the
// 12-module signal window against all 10 digit templates. Uses bar positions
// only for start alignment and module estimation; no per-bar width labels.
//
// Template cache: the blurred templates are a pure function of
// (`module`, sigma), and this runs once per scanline profile (up to 10 per
// ROI) with adjacent profiles usually agreeing bit-exactly on the estimate.
// One slot per sigma hypothesis: a single shared slot was overwritten by
// each of the three sigmas in turn and never hit. An exact float-bit match
// reuses the previous build bit-identically; anything else rebuilds exactly
// as before. Thread-local: decode() runs on shared worker threads.
struct DigitTemplateCache {
    uint32_t moduleBits = 0;
    uint32_t sigmaBits = 0;
    float tmpl[10][96];
    int tmplLen = 0;
    bool valid = false;
};
constexpr int kNumSigmas = 3;
thread_local DigitTemplateCache t_digitCache[kNumSigmas];

// Start, Stop and trailing quiet-zone contrast of a decoded hypothesis, in
// units of the digit field's intensity standard deviation. MSI Start is "110"
// (2-module bar, 1-module space) ending where the first digit window starts;
// Stop is "1001" (bar, 2-module space, bar) starting where the last digit
// window ends, then kQuietModules of quiet zone. Positions come from the
// fitted digit windows, not from bar centres (a bar-based estimate biased an
// earlier guard ranking toward shifted grids). Each element takes the best of
// three +/-0.5-module placements, matching the window search slack.
// p[0, n) is the decoded profile; p[n, nVerify) is its verification tail
// (appendVerifyTail), so a Stop cut tight by the ROI is still measured.
// Elements past nVerify (the frame edge) stay kGuardUnseen.
GuardEvidence guardEvidence(const float* p, int n, int nVerify, float firstStart,
                            float lastEnd, float module) {
    GuardEvidence g;
    const int a = std::max(0, (int)firstStart), b = std::min(n, (int)lastEnd);
    if (b - a < 2 || module < 0.5f) return g;
    float mu = 0, var = 0;
    for (int i = a; i < b; ++i) mu += p[i];
    mu /= (b - a);
    for (int i = a; i < b; ++i) var += (p[i] - mu) * (p[i] - mu);
    const float sd = std::max(sqrtf(var / (b - a)), 1e-3f);
    auto mean = [&](float x0, float x1, float& m) {
        const int i0 = (int)(x0 + 0.5f), i1 = (int)(x1 + 0.5f);
        if (i0 < 0 || i1 > nVerify || i1 <= i0) return false;
        m = 0;
        for (int i = i0; i < i1; ++i) m += p[i];
        m /= (i1 - i0);
        return true;
    };
    for (int sh = -1; sh <= 1; ++sh) {
        const float s = firstStart + sh * 0.5f * module, e = lastEnd + sh * 0.5f * module;
        // module k after the Stop's leading edge
        auto stopModule = [&](int k, float& m) { return mean(e + k * module, e + (k + 1) * module, m); };
        float bar, space;
        if (mean(s - 3 * module, s - module, bar) && mean(s - module, s, space))
            g.start = std::max(g.start, (space - bar) / sd);
        float b0, s1, s2, b3;
        if (!stopModule(0, b0) || !stopModule(1, s1) || !stopModule(2, s2) || !stopModule(3, b3))
            continue;
        // Strict: both space modules must clear the brighter bar. A mean over
        // the pair let 180-degree reads through, whose "Stop" is a digit cell
        // with one dark module inside the space.
        const float barMax = std::max(b0, b3);
        g.stop = std::max(g.stop, (std::min(s1, s2) - barMax) / sd);
        float darkest = 1e9f, m;
        bool seen = true;
        for (int k = 4; k < 4 + kQuietModules && seen; ++k) {
            seen = stopModule(k, m);
            darkest = std::min(darkest, m);
        }
        if (seen) g.quiet = std::max(g.quiet, (darkest - barMax) / sd);
    }
    return g;
}

// Largest distance (px) of any digit window start from the least-squares line
// through all of them (index -> position).
float maxDigitGridResidual(const float* pos, int nd) {
    float sx = 0, sy = 0, sxx = 0, sxy = 0;
    for (int d = 0; d < nd; ++d) {
        sx += d; sy += pos[d]; sxx += (float)d * d; sxy += d * pos[d];
    }
    float den = nd * sxx - sx * sx;
    float slope = den != 0 ? (nd * sxy - sx * sy) / den : 0;
    float icp = (sy - slope * sx) / nd;
    float mr = 0;
    for (int d = 0; d < nd; ++d) mr = std::max(mr, fabsf(pos[d] - (icp + slope * d)));
    return mr;
}

bool decodeByDigitTemplates(const float* p, int n, const Bar* bars, int nb,
                            float module, std::string& outDigits, float& outScore,
                            float& outFirstStart, float& outLastEnd) {
    // Build 10 digit templates (12 modules each), blurred.
    // Template patterns from MSI spec.
    static const char* kDigitPat[10] = {
        "100100100100", "100100100110", "100100110100", "100100110110",
        "100110100100", "100110100110", "100110110100", "100110110110",
        "110100100100", "110100100110"
    };
    // Multi-sigma templates: real shelf blur varies per shot/row, and a
    // fixed 0.4*module assumption misranks digits under heavier blur
    // (commercial truth: correct string found once at 0.916, outvoted by
    // phantoms at 0.924). Each sigma rebuilds templates and contributes
    // hypotheses; selection below picks across the pooled set. Template
    // build is ~0.5% of correlation cost, so rebuilds are cheap; the
    // (module, sigma) cache still hits across adjacent agreeing profiles.
    static const float kSigmaMul[kNumSigmas] = {0.35f, 0.6f, 0.95f};
    struct Valid {
        std::string digits;
        float score;
        ChecksumPolicy policy;
        int strength;
        int need;  // expected bar count: 1 + 4*nd + 2
        int sIdx;  // start-bar hypothesis index
        float firstStart;  // first digit window start (px)
        float lastEnd;     // last digit window end (px)
    };
    // Every bar may now host a Start hypothesis, so the pool is unbounded;
    // reused per thread to keep the per-scanline path allocation-free.
    static thread_local std::vector<Valid> all;
    all.clear();
    for (int sgi = 0; sgi < kNumSigmas; ++sgi) {
    DigitTemplateCache& cache = t_digitCache[sgi];
    uint32_t bits = 0;
    static_assert(sizeof(bits) == sizeof(module), "float bits");
    memcpy(&bits, &module, sizeof(bits));
    uint32_t sbits = 0;
    float sigmaMul = kSigmaMul[sgi];
    static_assert(sizeof(sbits) == sizeof(sigmaMul), "float bits");
    memcpy(&sbits, &sigmaMul, sizeof(sbits));
    float (*tmpl)[96] = cache.tmpl;
    int tmplLen = 0;
    if (cache.valid && cache.moduleBits == bits && cache.sigmaBits == sbits) {
        tmplLen = cache.tmplLen;
    } else {
        float sigma = sigmaMul * module;
        // Precompute templates at this module size.
        // max 12 modules * 8 px
        for (int d = 0; d < 10; ++d) {
            int len = (int)(12 * module + 0.5f);
            // Clamp BEFORE recording tmplLen: wide close-up modules would
            // otherwise set tmplLen > 96 and overflow seg[96]/tmpl[d] below
            // (stack smash on labels filling the frame).
            if (len > 96) len = 96;
            if (d == 0) tmplLen = len;
        // Render square wave.
        float sq[96];
        for (int i = 0; i < len; ++i) {
            int modIdx = (int)(i / module);
            if (modIdx > 11) modIdx = 11;
            sq[i] = (kDigitPat[d][modIdx] == '1') ? -1.0f : 1.0f;
        }
        // Blur.
        int kh = (int)(sigma * 3);
        if (kh < 1) kh = 1; if (kh > 6) kh = 6;
        for (int i = 0; i < len; ++i) {
            float s = 0, ks = 0;
            for (int k = -kh; k <= kh; ++k) {
                int j = i + k;
                if (j < 0) j = 0; if (j >= len) j = len - 1;
                float w = expf(-0.5f * k * k / (sigma * sigma));
                s += sq[j] * w; ks += w;
            }
            tmpl[d][i] = s / ks;
        }
        // Normalize.
        float m = 0;
        for (int i = 0; i < len; ++i) m += tmpl[d][i];
        m /= len;
        float v = 0;
        for (int i = 0; i < len; ++i) { tmpl[d][i] -= m; v += tmpl[d][i]*tmpl[d][i]; }
        v = sqrtf(v);
        if (v > 1e-6f) for (int i = 0; i < len; ++i) tmpl[d][i] /= v;
        }  // for (int d...) — one blurred template per digit
    }  // else — cache miss: rebuilt above
    // Publish the build for the next profile with an identical estimate.
    cache.moduleBits = bits;
    cache.sigmaBits = sbits;
    cache.tmplLen = tmplLen;
    cache.valid = true;

    // Best digit match of the 12-module window anchored at one bar, memoized
    // per sigma: Start s and s+4 share windows, and every length repeats the
    // shorter lengths' windows, so recomputing per hypothesis multiplied the
    // dominant cost by (Starts x lengths). Depends only on the bar, module and
    // templates, so results are bit-identical to recomputing.
    struct BarMatch { int digit; float corr; int i0; };
    BarMatch match[kMaxBars];
    bool matched[kMaxBars];
    std::fill(matched, matched + nb, false);
    auto matchAt = [&](int barIdx) -> const BarMatch& {
        BarMatch& mt = match[barIdx];
        if (matched[barIdx]) return mt;
        matched[barIdx] = true;
        const float bc = bars[barIdx].center;
        mt = {-1, -2.0f, 0};
        // Try two alignments: bar is narrow (center 0.5 mod in) or wide (1.0 mod).
        for (int w = 0; w < 2; ++w) {
            float dStart = bc - (w == 0 ? 0.5f : 1.0f) * module;
            // Small offset search ±0.5 module.
            for (int so = -1; so <= 1; ++so) {
                float ds = dStart + so * 0.5f * module;
                int i0 = (int)(ds + 0.5f);
                if (i0 < 0 || i0 + tmplLen > n) continue;
                float seg[96];
                float m = 0;
                for (int i = 0; i < tmplLen; ++i) { seg[i] = p[i0 + i]; m += seg[i]; }
                m /= tmplLen;
                float v = 0;
                for (int i = 0; i < tmplLen; ++i) { seg[i] -= m; v += seg[i]*seg[i]; }
                v = sqrtf(v);
                if (v < 1e-6f) continue;
                for (int i = 0; i < tmplLen; ++i) seg[i] /= v;
                for (int dig = 0; dig < 10; ++dig) {
                    float c = 0;
                    for (int i = 0; i < tmplLen; ++i) c += seg[i] * tmpl[dig][i];
                    if (c > mt.corr) { mt.corr = c; mt.digit = dig; mt.i0 = i0; }
                }
            }
        }
        return mt;
    };

    // Try every bar as the start candidate, and plausible n.
    // The checksum gate identifies the correct alignment. Shelf tags often
    // carry printed clutter left of the symbol (price text, rules), pushing
    // the true Start past the first few bars (a former 5-bar cap was the
    // dominant miss: shelf set MOD1010 near/far/still 124/125/123 -> 135/
    // 135/133). The start-guard and quiet-zone gates below keep the
    // hypothesis count small.
    // For each digit, anchor the 12-module window at the first bar's center:
    // try both narrow-bar (0.5 mod) and wide-bar (1.0 mod) offsets.
    // Start-guard prefilter: bars[sIdx] must be a WIDE bar (MSI Start is a
    // double-width bar). Shifted hypotheses that begin inside the digit
    // field usually start on a narrow bar — commercial truth shows the
    // winners then drop a real leading 0 and append a phantom digit
    // (02990356 -> 29903564). Threshold is soft (1.2 modules) to survive
    // blur, which shrinks measured width ratios.
    // Selection prefers the LONGEST checksum-valid candidate: a shorter valid
    // string that is a strict prefix of a longer valid one is almost always a
    // truncated read (commercial truth: 02739407 vs true 027394071/mod1010).
    // Ties break by score.
    for (int sIdx = 0; sIdx < nb; ++sIdx) {
        // No shortest symbol fits in the remaining bars (upper tolerance
        // below), and later Starts only leave fewer.
        if (sIdx + 1 + 4 * kMinDigits + 2 > nb + 6) break;
        // Start-guard prefilter (see above). Skip only when clearly narrow;
        // keep borderline cases for the checksum/score machinery.
        if (module > 0.5f && bars[sIdx].width < 1.2f * module) continue;
        // Quiet-zone gate: inside a symbol, adjacent bar centres are at most
        // 3.5 modules apart (<= 7 with one missed bar), so a Start hypothesis
        // whose predecessor bar sits closer than 7 modules is inside the
        // digit field. Those shifted hypotheses were the dominant source of
        // confident wrong reads (real photos: wrong 168 -> 55/755; synthetic
        // sweep 568 -> 139/1440). Noise in a genuine quiet zone fails safe:
        // NotFound, never a wrong value.
        if (sIdx > 0 && bars[sIdx].center - bars[sIdx - 1].center < 7.0f * module) continue;
        // Try all plausible nd. The template score, checksum, grid and guard
        // gates filter bad hypotheses.
        for (int nd = kMinDigits; nd <= kMaxDigits; ++nd) {
            // Host length bounds (DecodeOptions::minDigits/maxDigits) drop a
            // foreign length before template matching (the dominant decode
            // cost), so a lucky phantom of that length can neither win nor
            // displace the in-range read (shelf set, MOD1010 + 9 digits: two
            // far-field 7-digit phantoms became true reads, a 10-digit one a
            // miss). Skipping here rather than after the checksum also halves
            // miss-frame cost (clutter mean 2.8 -> 1.4ms).
            if (!lengthAllowed(nd)) continue;
            int need = 1 + 4 * nd + 2;
            // Up to 6 missed bars. There is deliberately no floor on the bars
            // left after the symbol. Shelf tags often carry print or a
            // neighbouring tag right of the symbol, and the former floor (at
            // most 6 trailing bars) never tried the true hypothesis there
            // (shelf set, MOD1010 9 digits near/far/still 141/135/138 ->
            // 147/139/143; full photos 64 -> 85 ok; 0 wrong). Overruns and
            // truncations are left to the Stop/quiet-zone gate.
            if (sIdx + need > nb + 6) continue;
            std::string digits;
            float totalScore = 0;
            float winPos[kMaxDigits];
            bool ok = true;
            for (int d = 0; d < nd; ++d) {
                int barIdx = sIdx + 1 + d * 4;
                if (barIdx >= nb) { ok = false; break; }
                const BarMatch& mt = matchAt(barIdx);
                if (mt.digit < 0) { ok = false; break; }
                digits.push_back('0' + mt.digit);
                winPos[d] = (float)mt.i0;
                totalScore += mt.corr;
            }
            if (!ok) continue;
            ChecksumPolicy pol;
            if (validateChecksum(digits, pol).empty()) {
                // Valid under another scheme = still MSI-shaped evidence for
                // the small-module retry trigger in decodeImpl (shelf set,
                // MOD1010 + 9 digits: far-field 114 -> 121 ok with the profile
                // retry). Counting is all it does; the hypothesis stays dropped.
                if (t_required != ChecksumPolicy::NONE &&
                    !validateChecksum(digits, pol, ChecksumPolicy::NONE).empty())
                    ++t_policyRejected;
                continue;
            }
            // Digit-grid consistency: each digit window is anchored to its
            // own bar (sIdx+1+4d), so dropped or extra bars can push one
            // window off the symbol's uniform pitch onto a lucky match that
            // still closes the checksum (shelf set: MOD10 scored wrong 16 ->
            // 4, a MOD1010 phantom on an unreadable tag gone; the one true
            // read lost read its check digit two digit-pitches past the
            // symbol by chance). Applied after the checksum so off-grid
            // MSI-shaped hypotheses still count as retry evidence above.
            if (module > 0.5f &&
                maxDigitGridResidual(winPos, nd) > kMaxDigitGridResidual * module)
                continue;
            // Sigma parsimony: heavier-blur templates match noise more
            // easily, so they must win decisively (>0.03/0.06) to outrank
            // the native 0.35 hypothesis. Without this, 0.95-sigma
            // phantoms outscored true reads on sharp tags (0442, 0465).
            float score = (totalScore / nd) + sIdx * 0.001f - 0.03f * sgi;
            // Strength = strongest checksum satisfied (see checksumStrength):
            // first-match policy understates double-check codes, which
            // validate single-check legs trivially.
            int strength = checksumStrength(digits);
            all.push_back({digits, score, pol, strength, 1 + 4 * nd + 2, sIdx,
                           winPos[0], winPos[nd - 1] + 12 * module});
        }
    }  // for sIdx — end start-candidate hypotheses for this sigma
    }  // for sgi — end sigma hypotheses; selection pools across sigmas
    const int nall = (int)all.size();
    if (nall == 0) return false;
    // Prefix duplicates: shorter is a strict PREFIX of longer. Every prefix
    // of a Mod1010 code passes Mod10 (grocery shelf tags are 7-digit item +
    // Mod10 + Mod10: 39 bars on 101/126 measured real tags), so a tie in
    // strength must NOT drop the longer — that discarded the true 9-digit
    // read for its 8-digit truncation (real photos: 237 -> 298/755 ok).
    // Hard-drop only when strength strictly differs in the longer's favour;
    // the bar-count fit then decides whether it is a true extension or a
    // phantom trailing digit. Ties fall through to the fit-first ranking.
    // Phantom LEADING digits (quiet zone as "0") are NOT hard-dropped:
    // real shelf SKUs often start with 0 — bar-fit ranking below tells
    // them apart (phantoms exceed the observed bar count).
    static thread_local std::vector<char> drop;
    drop.assign(nall, 0);
    for (int i = 0; i < nall; ++i)
        for (int j = 0; j < nall; ++j) {
            if (i == j || all[j].digits.size() <= all[i].digits.size()) continue;
            const std::string& sup = all[j].digits;
            const std::string& sub = all[i].digits;
            bool isPref = sup.compare(0, sub.size(), sub) == 0;
            if (!isPref) continue;
            // Bar fits: does the longer hypothesis account for the observed
            // bars at least as well? True extensions consume more real
            // bars; phantoms (truth + lucky digit) exceed them.
            int fitL = all[j].sIdx + all[j].need - nb;
            if (fitL < 0) fitL = -fitL;
            int fitS = all[i].sIdx + all[i].need - nb;
            if (fitS < 0) fitS = -fitS;
            if (all[j].strength > all[i].strength && fitL <= fitS)
                drop[i] = true;  // true extension
            else if (all[j].strength > all[i].strength && fitL > fitS)
                drop[j] = true;  // phantom trailing digit(s)
        }
    // Rank survivors: closest bar-count fit first (a true hypothesis
    // accounts for the observed bars; phantoms over/under-run them),
    // then stronger checks, then correlation score. (Strength-first was
    // tried 2026-10-11: phantoms validating double-checks by chance then
    // outrank true single-check reads, e.g. 01157833 lost to 804556044.)
    // Guard contrast is NOT a ranking key (bar-estimated guard ranking
    // regressed synthetic 12345674 and 2 shelf truths, 2026-10-11); it is
    // measured for the winner only and gated per read in voteScanlines.
    int bi = -1;
    int bfit = 0;
    for (int i = 0; i < nall; ++i) {
        if (drop[i]) continue;
        int fit = all[i].sIdx + all[i].need - nb;
        if (fit < 0) fit = -fit;
        if (bi < 0 || fit < bfit ||
            (fit == bfit &&
             (all[i].strength > all[bi].strength ||
              (all[i].strength == all[bi].strength && all[i].score > all[bi].score)))) {
            bi = i;
            bfit = fit;
        }
    }
    if (bi < 0) return false;  // all dropped (should not happen)
    outDigits = all[bi].digits;
    outScore = all[bi].score;
    outFirstStart = all[bi].firstStart;
    outLastEnd = all[bi].lastEnd;
    return true;
}

// Decodes p[0, n); p[n, nVerify) is the verification tail read only by
// guardEvidence().
void decodeProfile(const float* p, int n, int nVerify,
                   std::vector<Candidate>& cands, float* moduleOut = nullptr) {
    Bar bars[kMaxBars];
    // Use sensitive bar detection in relaxed mode (for faint images).
    int nb = findBars(p, n, bars, kMaxBars, g_relaxedBars);
    if (g_debug) printf("[dbg] bars found raw: %d\n", nb);
    float module = 0;
    nb = filterBars(bars, nb, module);
    if (g_debug) printf("[dbg] bars filtered: %d module=%.2f\n", nb, module);
    if (moduleOut && nb >= 8) {
        if (*moduleOut <= 0 || module < *moduleOut) *moduleOut = module;
    }
    if (nb < 1 + 4 * kMinDigits + 2) return;

    // Primary: digit-template correlation.
    std::string digits;
    float score = 0, firstStart = 0, lastEnd = 0;
    if (decodeByDigitTemplates(p, n, bars, nb, module, digits, score, firstStart, lastEnd)) {
        const GuardEvidence guard = guardEvidence(p, n, nVerify, firstStart, lastEnd, module);
        if (g_debug)
            printf("[dbg] template digits: %s (score=%.3f start=%.2f stop=%.2f quiet=%.2f)\n",
                   digits.c_str(), score, guard.start, guard.stop, guard.quiet);
        ChecksumPolicy pol;
        if (!validateChecksum(digits, pol).empty()) {
            cands.push_back({digits, pol, score, module, guard});
        } else if (g_debug) {
            printf("[dbg] checksum failed for %s\n", digits.c_str());
        }
    }

}

// Outcome of one ROI's scanline vote.
struct RoiVote {
    int votes = 0;              // winner's agreeing scanlines; 0 = no winner
    float avgScore = -1e9f;
    std::pair<std::string, ChecksumPolicy> best;
    bool anyCandidate = false;  // any scanline produced a checksum-valid read
    float minModule = 0;        // smallest plausible module seen (px)
};

// One ROI's scanline profiles. Every line holds n decode samples followed by
// its verification tail (appendVerifyTail).
struct RoiScanlines {
    std::vector<std::vector<float>> lines;
    int n = 0;
};

// Why a voted read's best guard evidence fails, or nullptr if it passes.
const char* guardFailure(const GuardEvidence& g, int nd) {
    if (g.start < kMinStartContrast) return "start";
    if (g.stop > kGuardUnseen && g.stop < kMinStopContrast) return "stop";
    if (g.quiet > kGuardUnseen && g.quiet < kMinQuietContrast) return "quiet zone";
    // Stop or quiet zone past the frame edge (photo border or aim-box crop):
    // unconfirmable, and the cut may hide the rest of a longer symbol (shelf
    // photo: a 9-digit tag cut by the border read as its MOD10-valid 8-digit
    // prefix). Accepted on the Start alone only when no longer symbol is
    // allowed; two tight shelf crops with a 9-digit bound rely on this.
    if (g.quiet == kGuardUnseen && longerSymbolAllowed(nd)) return "cut by frame edge";
    return nullptr;
}

// Decodes every scanline profile of one ROI and votes on (digits, policy).
RoiVote voteScanlines(const RoiScanlines& scan) {
    struct Votes { int n = 0; float score = 0; float mod = 0; GuardEvidence guard; };
    std::map<std::pair<std::string, ChecksumPolicy>, Votes> votes;
    RoiVote out;
    for (const auto& pr : scan.lines) {
        std::vector<Candidate> cands;
        float profModule = 0;
        decodeProfile(pr.data(), scan.n, (int)pr.size(), cands, &profModule);
        if (profModule > 0.5f &&
            (out.minModule <= 0 || profModule < out.minModule))
            out.minModule = profModule;
        // De-dup within this profile: keep highest score per (digits,policy).
        std::sort(cands.begin(), cands.end(),
                  [](const Candidate& a, const Candidate& b) {
                      if (a.digits != b.digits) return a.digits < b.digits;
                      return (int)a.policy < (int)b.policy;
                  });
        for (size_t i = 0; i < cands.size(); ) {
            size_t j = i + 1;
            float bestScore = cands[i].score;
            float bestMod = cands[i].module;
            GuardEvidence bestGuard = cands[i].guard;
            while (j < cands.size() && cands[j].digits == cands[i].digits &&
                   cands[j].policy == cands[i].policy) {
                bestGuard.keepBest(cands[j].guard);
                if (cands[j].score > bestScore) {
                    bestScore = cands[j].score;
                    bestMod = cands[j].module;
                }
                ++j;
            }
            auto key = std::make_pair(cands[i].digits, cands[i].policy);
            votes[key].n++;
            votes[key].score += bestScore;
            votes[key].mod += bestMod;
            votes[key].guard.keepBest(bestGuard);
            i = j;
        }
    }
    // Pick winner: most votes; tie-break by higher average score
    // (NOT by length — longer false positives were winning).
    // Small-module reads (<2.5px) need 6 agreeing scanlines instead of
    // 2: at ~2px/module quantization noise dominates and confident
    // wrong values outvote truth 4-to-1 (verified 0115783-hand).
    for (auto& kv : votes) {
        float avgMod = kv.second.mod / kv.second.n;
        int need = (avgMod < 2.5f) ? 6 : kMinVotesCheck;
        if (kv.second.n < need) continue;
        // Start/Stop/quiet-zone confirmation (see kMinStartContrast). Each
        // element is taken from its best agreeing scanline, not required on
        // one line: blur and clutter weaken single lines. Shelf set, MOD10 at
        // open lengths (the SDK default): a Start/Stop-only gate still let 7
        // crop wrong reads on readable tags and 5 full-photo wrong reads
        // through (mostly 8-digit prefixes cut at the aim-box or photo edge);
        // this gate leaves 0. Unreadable tags cut by the photo edge still
        // give 6 crop false reads (open item; none under MOD1010 or 9-digit
        // bounds). True reads at 9-digit bounds unchanged; open lengths lose
        // the 2 frame-cut crops in guardFailure().
        if (const char* why = guardFailure(kv.second.guard, (int)kv.first.first.size())) {
            if (g_debug)
                printf("[dbg] guard reject %s (%s; start=%.2f stop=%.2f quiet=%.2f)\n",
                       kv.first.first.c_str(), why, kv.second.guard.start,
                       kv.second.guard.stop, kv.second.guard.quiet);
            continue;
        }
        float avg = kv.second.score / kv.second.n;
        if (kv.second.n > out.votes ||
            (kv.second.n == out.votes && avg > out.avgScore)) {
            out.votes = kv.second.n;
            out.avgScore = avg;
            out.best = kv.first;
        }
    }
    out.anyCandidate = !votes.empty();
    return out;
}

// 2x linear upsample of a scanline profile at quarter-sample phase
// (outputs at i-0.25 and i+0.25). No output duplicates an input, so every
// output pair straddles a true source edge — the 1D analogue of the +1px
// shift that keeps the 2D NN retry from phase-locking. The decode samples
// [0, n) and the verification tail are upsampled separately, so the decode
// part is identical to upsampling it alone.
void upsampleProfile2x(std::vector<float>& pr, int n) {
    std::vector<float> up(pr.size() * 2);
    auto run = [&](int a, int b) {
        for (int i = a; i < b; ++i) {
            const float l = pr[i > a ? i - 1 : a], c = pr[i], r = pr[i + 1 < b ? i + 1 : b - 1];
            up[2 * i] = 0.75f * c + 0.25f * l;
            up[2 * i + 1] = 0.75f * c + 0.25f * r;
        }
    };
    run(0, n);
    run(n, (int)pr.size());
    pr.swap(up);
}

} // namespace

// Forward: decode() wraps decodeImpl() with the upscale retry enabled.
DecodeResult decodeImpl(const uint8_t* gray, int width, int height, bool allowUpscale,
                        float scanTopFraction);

DecodeResult decode(const uint8_t* gray, int width, int height, ChecksumPolicy required) {
    DecodeOptions options;
    options.required = required;
    return decode(gray, width, height, options);
}

DecodeResult decode(const uint8_t* gray, int width, int height, const DecodeOptions& options) {
    // Scope the requirement to this call, restoring on every exit path.
    struct RequiredScope {
        ChecksumPolicy prev;
        int prevMin, prevMax;
        ~RequiredScope() {
            t_required = prev;
            t_minDigits = prevMin;
            t_maxDigits = prevMax;
        }
    } scope{t_required, t_minDigits, t_maxDigits};
    t_required = options.required;
    t_minDigits = std::max(options.minDigits, 0);
    t_maxDigits = std::max(options.maxDigits, 0);
    const float top = std::min(std::max(options.scanTopFraction, 0.0f), 0.9f);
    return decodeImpl(gray, width, height, /*allowUpscale=*/true, top);
}

// Far-field fallback, factored so the success path is untouched.
DecodeResult decodeImpl(const uint8_t* gray, int width, int height, bool allowUpscale,
                        float scanTopFraction) {
    DecodeResult res;
    Rect rois[8];
    int nrois = findRoiCandidates(gray, width, height, rois, 8, scanTopFraction);
    if (nrois == 0) {
        if (g_debug) printf("[dbg] no ROI\n");
        return res;
    }

    // Try ROI candidates in order (best first by band score).
    // Return the first successful decode; do NOT vote across ROIs
    // (avoids text false positives outvoting the true barcode).
    float minModuleSeen = 0;  // for the small-module upscale trigger below
    bool sawCandidate = false;  // any checksum-valid scanline read, any ROI
    // Scanlines of failed small-module ROIs, moved here for the 1D retry.
    std::vector<RoiScanlines> retryScans;
    for (int ri = 0; ri < nrois; ++ri) {
        const Rect& roi = rois[ri];
        if (g_debug) printf("[dbg] trying ROI %d x=[%d,%d] y=[%d,%d]\n",
                            ri, roi.x0, roi.x1, roi.y0, roi.y1);

        // Collect profiles: averaged + several rows, each with its tail.
        RoiScanlines scan;
        scan.n = roi.x1 - roi.x0;
        std::vector<float> avg;
        averageProfile(gray, width, roi, avg);
        appendVerifyTail(gray, width, roi, -1, avg);
        int bandH = roi.y1 - roi.y0;
        int nLines = std::min(kMaxScanlines, 1 + bandH / 8);
        scan.lines.reserve(nLines + 1);
        scan.lines.push_back(std::move(avg));
        for (int i = 0; i < nLines; ++i) {
            int y = roi.y0 + (bandH * (i + 1)) / (nLines + 1);
            std::vector<float> pr;
            extractProfile(gray, width, roi, y, pr);
            appendVerifyTail(gray, width, roi, y, pr);
            scan.lines.push_back(std::move(pr));
        }

        const int policyRejectedBefore = t_policyRejected;
        const RoiVote v = voteScanlines(scan);
        if (v.anyCandidate || t_policyRejected != policyRejectedBefore) sawCandidate = true;
        if (v.minModule > 0.5f &&
            (minModuleSeen <= 0 || v.minModule < minModuleSeen))
            minModuleSeen = v.minModule;
        if (v.votes > 0) {
            res.ok = true;
            res.digits = v.best.first;
            res.policy = v.best.second;
            res.votes = v.votes;
            if (g_debug) printf("[dbg] ROI %d succeeded: %s (avg score %.3f)\n",
                                ri, res.digits.c_str(), v.avgScore);
            return res;
        }
        // The 1D retry runs at the top level only: inside the 2D recursion it
        // compounds to 4x interpolation and bred phantoms (shelf set: an
        // unreadable tag's 022000046) at the cost of one far read.
        if (allowUpscale && v.minModule > 0.5f && v.minModule < kProfileRetryMaxModule)
            retryScans.push_back(std::move(scan));
        if (g_debug) printf("[dbg] ROI %d: no decode\n", ri);
    }
    // Every ROI failed. Small-module retry, 1D: re-vote each small-module
    // ROI's own scanlines upsampled 2x (upsampleProfile2x). Below ~2.5px the
    // bar detector and templates lose edges to quantization; doubling the
    // samples restores them without touching ROI detection. A 2D upscale
    // (below) re-detects ROIs on the new frame and, bilinear, bred confident
    // phantoms (shelf set: 0516-4, 0440-3); this pass reuses the 1x ROIs and
    // their row averaging, so it adds no new search area. Runs before the 2D
    // retry, in ROI order, first winner returns; the vote gate is unchanged.
    // Shelf set, MOD1010 + 9 digits (with the length skip and policy
    // evidence above): near 118 -> 124, far 88 -> 126, still 116 -> 123 of
    // 141, zero new wrong reads; barcode-free clutter mean 1.5 -> 2.5ms.
    for (auto& scan : retryScans) {
        for (auto& pr : scan.lines) upsampleProfile2x(pr, scan.n);
        scan.n *= 2;
        const RoiVote v = voteScanlines(scan);
        if (v.votes > 0) {
            res.ok = true;
            res.digits = v.best.first;
            res.policy = v.best.second;
            res.votes = v.votes;
            if (g_debug) printf("[dbg] 1D 2x retry succeeded: %s\n", res.digits.c_str());
            return res;
        }
    }
    // Still nothing. 2D far-field retry: modules below ~2px (distant shelf
    // tags at native resolution) fall under the bar detector's working range
    // while the information is still there — one NN 2x upscale brings them
    // back. Runs ONLY on the failure path, so passing inputs never pay for
    // it and can never change outcome; recursion is single-depth by flag.
    // NN (not bilinear) keeps edges crisp for template correlation.
    // Trigger: either a small frame (original far-field case) OR observed
    // small modules at any frame size (verified 0299692: 839px-wide crop
    // with 3px modules, 10 disagreeing rows, zero consensus).
    bool smallFrame = std::max(width, height) < kUpscaleMaxLongEdge;
    // Threshold 3.5px (not 3.0): module estimates skew high when false bars
    // join the median, so a true ~2.5px barcode can report 3.0 (verified
    // 0299692: six rows at exactly 3.00, zero consensus, no retry fired).
    // Also requires MSI evidence: at least one scanline produced a
    // checksum-valid read that then failed the vote gate, or a hypothesis
    // that failed only the caller-required scheme (t_policyRejected). Text
    // and texture
    // clutter also yields small module estimates, so without this every
    // 1280x720 miss frame paid a 4x-pixel retry (3.1ms vs 0.4ms) for no
    // reads; real-photo regression keeps every rescued read (755 crops, 0
    // rows changed) because a genuine small-module tag always leaves
    // disagreeing candidates behind (0299692: 10 rows, zero consensus).
    bool smallModule =
        sawCandidate &&
        minModuleSeen > 0.5f && minModuleSeen < 3.5f &&
        std::max(width, height) < 2 * kUpscaleMaxLongEdge;
    if (allowUpscale && width > 0 && height > 0 && (smallFrame || smallModule)) {
        if (g_debug) printf("[dbg] all ROIs missed, trying 2x upscale\n");
        // NN 2x with a +1px source shift: plain 2x duplicates every pixel, so
        // even-parity adjacent pairs are identical and the xstep=2 row sampler
        // would see only zeros (perfect phase lock — the retry could never
        // fire). Reading src[(x+1)/2] puts a true source boundary inside every
        // even pair instead. Rows keep plain y/2 (duplicated rows only
        // lengthen band runs; the column pass already samples every pixel).
        const int W2 = width * 2, H2 = height * 2;
        std::vector<uint8_t> up((size_t)W2 * (size_t)H2);
        for (int y = 0; y < H2; ++y) {
            const uint8_t* srcRow = gray + (size_t)(y / 2) * width;
            uint8_t* dstRow = &up[(size_t)y * W2];
            for (int x = 0; x < W2; ++x) {
                int sx = (x + 1) / 2;
                if (sx >= width) sx = width - 1;
                dstRow[x] = srcRow[sx];
            }
        }
        return decodeImpl(up.data(), W2, H2, /*allowUpscale=*/false, scanTopFraction);
    }
    return res;
}

} // namespace msi
