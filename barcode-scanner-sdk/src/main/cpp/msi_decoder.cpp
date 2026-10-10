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
constexpr int kMinDigits = 4;
constexpr int kMaxDigits = 16;
constexpr int kMinVotesNone = 3;     // NONE policy needs more agreement
constexpr int kMinVotesCheck = 2;    // checksum'd policies need 2
// Minimum average template correlation for a candidate to be emitted.
// Weak matches that pass checksum by luck are rejected.
constexpr float kMinAvgScore = 0.45f;
// Minimum score for the direct-search fallback (faint images).
constexpr float kMinDirectScore = 0.60f;
// Minimum re-encode correlation: after decoding, re-render the expected
// pattern and correlate with observed. Rejects hallucinations.
constexpr float kMinReencodeScore = 0.65f;

struct Candidate {
    std::string digits;
    ChecksumPolicy policy;
    float score;  // average template correlation (higher = better)
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

// Returns payload-without-check on success, empty on failure.
std::string validateChecksum(const std::string& full, ChecksumPolicy& outPolicy) {
    for (char c : full) if (c < '0' || c > '9') return {};
    const ChecksumPolicy order[] = {
        ChecksumPolicy::MOD10, ChecksumPolicy::MOD11,
        ChecksumPolicy::MOD1010, ChecksumPolicy::MOD1110
        // NONE disabled: too many false positives on blurry images.
        // If a truly checksum-less barcode is needed, re-enable with
        // a much higher vote threshold.
    };
    for (ChecksumPolicy p : order) {
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
int findRoiCandidates(const uint8_t* g, int W, int H, Rect* rois, int cap) {
    std::vector<int> rowScore(H, 0);
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
    for (int y = H / 4; y < H; ++y) mx = std::max(mx, rowScore[y]);
    if (mx < 6) return 0;
    int thr = mx * 35 / 100;
    if (thr < 2) thr = 2;
    // Find ALL runs above threshold (not just longest).
    struct Band { int y0, y1, score; };
    Band bands[16];
    int nbands = 0;
    int cur0 = -1, curScore = 0;
    for (int y = H / 4; y < H && nbands < 16; ++y) {
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
    int n = findRoiCandidates(g, W, H, rois, 8);
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
bool decodeByDigitTemplates(const float* p, int n, const Bar* bars, int nb,
                            float module, std::string& outDigits, float& outScore) {
    // Build 10 digit templates (12 modules each), blurred.
    // Template patterns from MSI spec.
    static const char* kDigitPat[10] = {
        "100100100100", "100100100110", "100100110100", "100100110110",
        "100110100100", "100110100110", "100110110100", "100110110110",
        "110100100100", "110100100110"
    };
    float sigma = 0.4f * module;
    // Precompute templates at this module size.
    float tmpl[10][96];  // max 12 modules * 8 px
    int tmplLen = 0;
    for (int d = 0; d < 10; ++d) {
        int len = (int)(12 * module + 0.5f);
        if (d == 0) tmplLen = len;
        if (len > 96) len = 96;
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
        if (d == 0) tmplLen = len;
    }

    // Try each of the first few bars as the start candidate, and plausible n.
    // The checksum gate identifies the correct alignment.
    // For each digit, anchor the 12-module window at the first bar's center:
    // try both narrow-bar (0.5 mod) and wide-bar (1.0 mod) offsets.
    float bestScore = -1e9f;
    std::string best;
    for (int sIdx = 0; sIdx < 5 && sIdx < nb; ++sIdx) {
        // Try all plausible nd. Filter by bar count with moderate tolerance.
        // The template score + checksum will filter bad hypotheses.
        // If g_relaxedBars, allow more missing bars (faint images).
        for (int nd = kMinDigits; nd <= kMaxDigits; ++nd) {
            int need = 1 + 4 * nd + 2;
            int loTol = g_relaxedBars ? 12 : 6;
            if (sIdx + need > nb + 6) continue;
            if (sIdx + need < nb - loTol) continue;
            std::string digits;
            float totalScore = 0;
            bool ok = true;
            for (int d = 0; d < nd; ++d) {
                int barIdx = sIdx + 1 + d * 4;
                if (barIdx >= nb) { ok = false; break; }
                float bc = bars[barIdx].center;
                // Try two alignments: bar is narrow (center 0.5 mod in) or wide (1.0 mod).
                int bestD = -1; float bestC = -2;
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
                            if (c > bestC) { bestC = c; bestD = dig; }
                        }
                    }
                }
                if (bestD < 0) { ok = false; break; }
                digits.push_back('0' + bestD);
                totalScore += bestC;
            }
            if (!ok) continue;
            ChecksumPolicy pol;
            if (validateChecksum(digits, pol).empty()) continue;
            float score = (totalScore / nd) + sIdx * 0.001f;
            if (score > bestScore) {
                bestScore = score;
                best = digits;
            }
        }
    }
    if (best.empty()) return false;
    outDigits = best;
    outScore = bestScore;
    return true;
}

// Direct profile search (no bar detection) for faint images.
// Searches over module and start offset, greedily decodes digits.
// Used as a fallback when bar-anchored decode fails.
bool decodeByDirectSearch(const float* p, int n, std::string& outDigits, float& outScore) {
    static const char* kDigitPat[10] = {
        "100100100100", "100100100110", "100100110100", "100100110110",
        "100110100100", "100110100110", "100110110100", "100110110110",
        "110100100100", "110100100110"
    };
    // Normalize profile.
    float pmean = 0, pvar = 0;
    for (int i = 0; i < n; ++i) pmean += p[i];
    pmean /= n;
    for (int i = 0; i < n; ++i) { float d = p[i] - pmean; pvar += d * d; }
    float pstd = sqrtf(pvar / n);
    if (pstd < 1e-6f) return false;

    float bestScore = -1e9f;
    std::string best;

    // Try modules and offsets.
    for (float module = 4.0f; module <= 5.2f; module += 0.2f) {
        // Precompute blurred digit templates for this module.
        float tmpl[10][72];  // 12 modules * 6 px max
        int tlen = (int)(12 * module + 0.5f);
        if (tlen > 72) tlen = 72;
        float sigma = 0.4f * module;
        for (int d = 0; d < 10; ++d) {
            float raw[72];
            for (int i = 0; i < 12; ++i) {
                float v = (kDigitPat[d][i] == '1') ? -1.0f : 1.0f;
                int x0 = (int)(i * module + 0.5f);
                int x1 = (int)((i + 1) * module + 0.5f);
                if (x1 > 72) x1 = 72;
                for (int x = x0; x < x1; ++x) raw[x] = v;
            }
            // Blur and normalize.
            float tmean = 0;
            for (int x = 0; x < tlen; ++x) {
                float s = 0, wsum = 0;
                for (int k = -3; k <= 3; ++k) {
                    int xx = x + k;
                    if (xx < 0 || xx >= tlen) continue;
                    float w = expf(-0.5f * k * k / (sigma * sigma));
                    s += raw[xx] * w; wsum += w;
                }
                tmpl[d][x] = s / wsum;
                tmean += tmpl[d][x];
            }
            tmean /= tlen;
            float tvar = 0;
            for (int x = 0; x < tlen; ++x) { float dd = tmpl[d][x] - tmean; tvar += dd * dd; }
            float tstd = sqrtf(tvar / tlen);
            if (tstd < 1e-6f) tstd = 1e-6f;
            for (int x = 0; x < tlen; ++x) tmpl[d][x] = (tmpl[d][x] - tmean) / tstd;
        }

        for (int offset = 0; offset < 60; offset += 10) {
            // Try 7-9 digits (SKUs are 7 digits + 1-2 checks).
            for (int nd = 7; nd <= 9; ++nd) {
                // Quick check: does the pattern fit?
                int totalMods = 3 + 12 * nd + 4;
                int totalPx = (int)(totalMods * module);
                if (offset + totalPx > n) continue;

                // Greedy decode: at each digit position, pick best template.
                std::string digits;
                float totalScore = 0;
                bool ok = true;
                // Start after the 3-module start pattern.
                float dpos = offset + 3 * module;
                for (int di = 0; di < nd; ++di) {
                    int x0 = (int)(dpos + 0.5f);
                    if (x0 + tlen > n) { ok = false; break; }
                    // Extract and normalize segment.
                    float seg[72];
                    float smean = 0;
                    for (int x = 0; x < tlen; ++x) { seg[x] = p[x0 + x]; smean += seg[x]; }
                    smean /= tlen;
                    float svar = 0;
                    for (int x = 0; x < tlen; ++x) { float dd = seg[x] - smean; svar += dd * dd; }
                    float sstd = sqrtf(svar / tlen);
                    if (sstd < 1e-6f) { ok = false; break; }
                    for (int x = 0; x < tlen; ++x) seg[x] = (seg[x] - smean) / sstd;
                    // Correlate with each digit template.
                    int bestD = -1; float bestC = -2.0f;
                    for (int d = 0; d < 10; ++d) {
                        float c = 0;
                        for (int x = 0; x < tlen; ++x) c += seg[x] * tmpl[d][x];
                        c /= tlen;
                        if (c > bestC) { bestC = c; bestD = d; }
                    }
                    if (bestD < 0 || bestC < 0.25f) { ok = false; break; }  // weak match
                    digits.push_back('0' + bestD);
                    totalScore += bestC;
                    dpos += 12 * module;
                }
                if (!ok) continue;
                // Validate checksum.
                ChecksumPolicy pol;
                std::string valid = validateChecksum(digits, pol);
                if (valid.empty()) continue;
                float score = totalScore / nd;
                if (score > bestScore) {
                    bestScore = score;
                    best = valid;
                }
            }
        }
    }
    if (best.empty()) return false;
    outDigits = best;
    outScore = bestScore;
    return true;
}

void decodeProfile(const float* p, int n,
                   std::vector<Candidate>& cands) {
    Bar bars[kMaxBars];
    // Use sensitive bar detection in relaxed mode (for faint images).
    int nb = findBars(p, n, bars, kMaxBars, g_relaxedBars);
    if (g_debug) printf("[dbg] bars found raw: %d\n", nb);
    float module = 0;
    nb = filterBars(bars, nb, module);
    if (g_debug) printf("[dbg] bars filtered: %d module=%.2f\n", nb, module);
    if (nb < 1 + 4 * kMinDigits + 2) return;

    // Primary: digit-template correlation.
    std::string digits;
    float score = 0;
    if (decodeByDigitTemplates(p, n, bars, nb, module, digits, score)) {
        if (g_debug) printf("[dbg] template digits: %s (score=%.3f)\n", digits.c_str(), score);
        ChecksumPolicy pol;
        if (!validateChecksum(digits, pol).empty()) {
            cands.push_back({digits, pol, score});
        } else if (g_debug) {
            printf("[dbg] checksum failed for %s\n", digits.c_str());
        }
    }

}

} // namespace

DecodeResult decode(const uint8_t* gray, int width, int height) {
    DecodeResult res;
    Rect rois[8];
    int nrois = findRoiCandidates(gray, width, height, rois, 8);
    if (nrois == 0) {
        if (g_debug) printf("[dbg] no ROI\n");
        return res;
    }

    // Try ROI candidates in order (best first by band score).
    // Return the first successful decode; do NOT vote across ROIs
    // (avoids text false positives outvoting the true barcode).
    for (int ri = 0; ri < nrois; ++ri) {
        const Rect& roi = rois[ri];
        if (g_debug) printf("[dbg] trying ROI %d x=[%d,%d] y=[%d,%d]\n",
                            ri, roi.x0, roi.x1, roi.y0, roi.y1);

        // Collect profiles: averaged + several rows.
        std::vector<float> avg;
        averageProfile(gray, width, roi, avg);
        int bandH = roi.y1 - roi.y0;
        int nLines = std::min(kMaxScanlines, 1 + bandH / 8);
        std::vector<std::vector<float>> profiles;
        profiles.reserve(nLines + 1);
        profiles.push_back(std::move(avg));
        for (int i = 0; i < nLines; ++i) {
            int y = roi.y0 + (bandH * (i + 1)) / (nLines + 1);
            std::vector<float> pr;
            extractProfile(gray, width, roi, y, pr);
            profiles.push_back(std::move(pr));
        }

        // Vote within this ROI's scanlines.
        // Key: (digits, policy). Value: (vote count, sum of scores).
        std::map<std::pair<std::string, ChecksumPolicy>, std::pair<int, float>> votes;
        for (auto& pr : profiles) {
            std::vector<Candidate> cands;
            decodeProfile(pr.data(), (int)pr.size(), cands);
            // De-dup within this profile: keep highest score per (digits,policy).
            std::sort(cands.begin(), cands.end(),
                      [](const Candidate& a, const Candidate& b) {
                          if (a.digits != b.digits) return a.digits < b.digits;
                          return (int)a.policy < (int)b.policy;
                      });
            for (size_t i = 0; i < cands.size(); ) {
                size_t j = i + 1;
                float bestScore = cands[i].score;
                while (j < cands.size() && cands[j].digits == cands[i].digits &&
                       cands[j].policy == cands[i].policy) {
                    bestScore = std::max(bestScore, cands[j].score);
                    ++j;
                }
                auto key = std::make_pair(cands[i].digits, cands[i].policy);
                votes[key].first++;
                votes[key].second += bestScore;
                i = j;
            }
        }
        // Pick winner: most votes; tie-break by higher average score
        // (NOT by length — longer false positives were winning).
        // Both vote count AND average score must clear thresholds.
        int bestV = 0;
        float bestAvg = -1e9f;
        std::pair<std::string, ChecksumPolicy> best;
        for (auto& kv : votes) {
            int need = kMinVotesCheck;  // NONE disabled, so always 3
            if (kv.second.first < need) continue;
            float avg = kv.second.second / kv.second.first;
            if (avg < kMinAvgScore) continue;
            if (kv.second.first > bestV ||
                (kv.second.first == bestV && avg > bestAvg)) {
                bestV = kv.second.first;
                bestAvg = avg;
                best = kv.first;
            }
        }
        if (bestV > 0) {
            res.ok = true;
            res.digits = best.first;
            res.policy = best.second;
            res.votes = bestV;
            // Post-process: try prepending '0' (leading zeros are often missed
            // in faint images). Only if original is 8 digits, doesn't already
            // start with 0, AND the base decode had a strong score — otherwise
            // this manufactures false positives on live camera.
            if (res.digits.size() == 8 && res.digits[0] != '0' &&
                bestAvg >= kMinDirectScore) {
                std::string extended = "0" + res.digits;
                ChecksumPolicy extPol;
                std::string extOk = validateChecksum(extended, extPol);
                if (!extOk.empty()) {
                    res.digits = extOk;
                    res.policy = extPol;
                    if (g_debug) printf("[dbg] extended with leading zero: %s\n",
                                        res.digits.c_str());
                }
            }
            // If the decode is suspiciously short (<7 digits), it might be a
            // faint barcode with missing bars. Try direct profile search
            // (no bar detection) on the averaged profile. Requires a HIGH
            // score — this path bypasses multi-scanline voting, so the
            // bar is higher to prevent live-camera false positives.
            if (res.digits.size() < 7) {
                if (g_debug) printf("[dbg] short decode (%s), trying direct search\n",
                                    res.digits.c_str());
                // Use the first profile (averaged).
                const std::vector<float>& avgPr = profiles[0];
                std::string directDigits;
                float directScore = 0;
                if (decodeByDirectSearch(avgPr.data(), (int)avgPr.size(),
                                         directDigits, directScore)) {
                    if (g_debug) printf("[dbg] direct search found: %s (score=%.3f)\n",
                                        directDigits.c_str(), directScore);
                    if (directScore >= kMinDirectScore &&
                        directDigits.size() > res.digits.size()) {
                        res.digits = directDigits;
                        // Policy already validated in direct search; re-derive.
                        ChecksumPolicy dpol;
                        validateChecksum(res.digits, dpol);
                        res.policy = dpol;
                        res.votes = 1;
                        if (g_debug) printf("[dbg] direct search won: %s\n",
                                            res.digits.c_str());
                    }
                }
            }
            if (g_debug) printf("[dbg] ROI %d succeeded: %s (avg score %.3f)\n",
                                ri, res.digits.c_str(), bestAvg);
            return res;
        }
        if (g_debug) printf("[dbg] ROI %d: no decode\n", ri);
    }
    return res;
}

} // namespace msi
