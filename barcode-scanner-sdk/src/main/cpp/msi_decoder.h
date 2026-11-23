// Clean-room MSI Plessey barcode decoder.
// Written from the public MSI specification (Wikipedia "MSI Barcode").
// No third-party barcode libraries used.
//
// API: feed a grayscale image buffer (0=black, 255=white, row-major),
// get back the decoded digit string and the checksum policy that validated,
// or a failure indication.
#pragma once

#include <cstdint>
#include <string>

// Clean-room synthetic orientation model. Runtime is C++ (msi_angle_model.h).
#ifndef MSI_ANGLE_MODEL
#define MSI_ANGLE_MODEL 1
#endif

namespace msi {

// Checksum policies the decoder will try (in order) for each candidate.
enum class ChecksumPolicy {
    MOD10,    // Luhn, most common
    MOD11,    // IBM weights 2..7
    MOD1010,  // double Mod10
    MOD1110,  // Mod11 then Mod10
    NONE      // no check digit
};

struct DecodeResult {
    bool ok = false;
    std::string digits;          // full decoded digit string (includes check digits)
    ChecksumPolicy policy = ChecksumPolicy::NONE;
    // Number of independent scanline observations that agreed on `digits`.
    int votes = 0;
    // Contrast of the quiet zone before the read's Start bar (digit-field
    // standard deviations; larger = cleaner), best agreeing scanline. Below
    // -1e8 when fewer than 3 modules of it were visible. Not a gate: the
    // frame decoder compares it between a read and a conflicting read of
    // the 180-degree view (msi::decodeFrame).
    float leadingQuiet = -1e9f;
    // Mean digit-template correlation of the winning vote (0..1; 0 when the
    // result comes from a path that does not compute it). Frame-level
    // fallbacks use it as a confidence gate on derived scales.
    float score = 0.0f;
    // Failure-path evidence, valid when ok == false: at least one scanline
    // produced a checksum-valid candidate (or a candidate rejected only by
    // the caller's required scheme), and the smallest observed module. The
    // frame decoder uses this to keep the expensive scale fallback off
    // text-only frames.
    bool sawCandidate = false;
    float minModule = 0.0f;
};

const char* policyName(ChecksumPolicy p);

// --- shared direction arbitration and fallback score gates -----------------
// Both the frame decoder's orientation sweep (msi_frame.cpp) and the angle
// fallback's per-derived-image confirmation (msi_decoder.cpp) arbitrate a read
// against its exact 180-degree view. Keep ONE implementation: a second copy
// would silently drift from the tuned margin when either is re-tuned.

// Minimum difference in leadingQuiet between a read and its conflicting
// 180-degree view before one of them wins. Over the shelf still crops at
// 0/+-6/+-10 degrees, MOD10 open lengths, all 17 measured conflicts had true
// reads at 1.91-3.04 vs reversed phantoms at 0.85-1.66, each pair by >= 0.61;
// 0.3 sits well inside that. Below it the two reads are undecidable and the
// frame yields no read.
constexpr float kMinReverseLeadMargin = 0.3f;

// Minimum mean digit-template correlation for a fallback read. The gates are
// load-bearing: clean reversed phantoms agree on 10 rows at score ~0.68 while
// true angle-fallback reads measured 0.79-0.89 (hence 0.70), and a stretched
// still that turns a low-contrast phantom into the first read sits between the
// two (hence the weaker 0.55 where the reverse view is merely missing rather
// than conflicting).
constexpr float kFallbackScoreGate = 0.70f;
constexpr float kStretchedScoreGate = 0.55f;

// Returns 1 when `first` wins, -1 when `reverse` wins, 0 when there is no
// clear winner (an undecidable conflict = no read). Both results must be ok:
// a caller only arbitrates two reads that both decoded.
inline int pickDirection(const DecodeResult& first, const DecodeResult& reverse) {
    constexpr float kUnseen = -1e8f;
    if (first.leadingQuiet < kUnseen || reverse.leadingQuiet < kUnseen) return 0;
    const float d = first.leadingQuiet - reverse.leadingQuiet;
    if (d >= kMinReverseLeadMargin) return 1;
    if (d <= -kMinReverseLeadMargin) return -1;
    return 0;
}

// Set debug verbosity (0=off, 1=trace decode stages). For diagnostics only.
void setDebug(int v);

// Main entry point. gray: row-major grayscale, 0=black, 255=white.
// `required`: checksum scheme the caller will enforce. When not NONE, only
// digit strings validating under it compete in candidate ranking and
// scanline voting, and `policy` in the result equals it. NONE (default)
// accepts any supported scheme (MOD10 -> MOD11 -> MOD1010 -> MOD1110,
// first match reported; every MOD1010/MOD1110 codeword also passes MOD10).
// Does not retain the buffer. Thread-safe (per-call state is thread-local).
DecodeResult decode(const uint8_t* gray, int width, int height,
                    ChecksumPolicy required = ChecksumPolicy::NONE);

// Full option set for decode().
struct DecodeOptions {
    // See decode(gray, width, height, required).
    ChecksumPolicy required = ChecksumPolicy::NONE;
    // Share of the image height skipped at the top during barcode band
    // search (clamped to 0..0.9). 0.25 suits full camera frames (overlays and
    // competing print live up there); aim-box crops pass 0 because the box
    // itself is the target area. Close-up labels filling the frame still
    // decode (their band extends below the cut); anything entirely in the top
    // quarter is missed by design. Change with care: widening the search adds
    // ROI candidates that compete in voting.
    float scanTopFraction = 0.25f;
    // Accepted symbol lengths in digits, check digits included (the whole
    // codeword in the bars, e.g. 9 for a 7-digit SKU + 2 checks). 0 leaves a
    // side unbounded; the engine itself reads 7..10 digits. Out-of-range
    // lengths are skipped before template matching, so they never outvote
    // or displace an in-range read and cost no decode time.
    int minDigits = 0;
    int maxDigits = 0;
    // Allow the decoder's far-field 2x retry. The sharpened still retry clears
    // it for the sharpened frame (and the angle fallback clears it for its own
    // explicit 2x plane), so those derived images cannot compound. The 2x/3x
    // scale legs pass the caller's value through unchanged instead — a host
    // that disables upscale disables it everywhere, including the ensemble.
    bool allowUpscale = true;
};

DecodeResult decode(const uint8_t* gray, int width, int height,
                    const DecodeOptions& options);

#if MSI_ANGLE_MODEL
// Predicts the in-plane rotation of the tag in `gray` from a 32x32
// area-averaged view of it (degrees; sign matches decodeAngleFallback's
// rotation). Returns 0 for null input or dimensions below 32 px, where the
// bins degenerate. Exposed for the host harness, which pins the committed
// weights with golden vectors — msi_angle_model.h is generated and not
// reproducible from this repo, so a stray edit must fail a test rather than
// silently change what the fallback rescues.
float predictAngle(const uint8_t* gray, int width, int height);

// Experimental fallback: predict tag rotation, rotate the original frame,
// and retry the normal decoder. Used only after both decodeFrame sweeps.
// Every accepted candidate is checked against its own 180-degree view; a
// reverse-failing candidate below the score gate is rejected. `relativeRotation`
// is 180 when that reverse view wins, otherwise 0.
DecodeResult decodeAngleFallback(const uint8_t* gray, int width, int height,
                                 const DecodeOptions& options,
                                 int* relativeRotation = nullptr);
#endif

// Live tilt rescue. Live aim-box crops have no full-label context for
// predictAngle (a barcode-only 972x252 crop measured +17.9 deg on a true
// ~-6 deg tilt), so this uses the projection estimate (estimateSkew) and a
// coarse bicubic rotation sweep instead. Intended only after the normal live
// decode misses; the caller gates it to live frames. Requires a strong
// projection gain and rotates the whole frame (no ROI needed). Each winning
// orientation goes through the same 180-degree reverse confirmation as
// decodeAngleFallback (shared confirmReverseView): a reverse-failing read
// needs the fallback score gate, and a conflicting reverse read is arbitrated
// on leading quiet zone (undecidable = no read). Returns the read with
// relativeRotation 0/180, or {} if the projection is weak or no angle reads.
// Does not retain `gray`. Thread-safe.
DecodeResult decodeDeskew(const uint8_t* gray, int width, int height,
                          const DecodeOptions& options,
                          int* relativeRotation = nullptr);

} // namespace msi
