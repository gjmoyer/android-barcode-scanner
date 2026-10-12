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
};

const char* policyName(ChecksumPolicy p);

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
    // itself is the target area.
    float scanTopFraction = 0.25f;
    // Accepted symbol lengths in digits, check digits included (the whole
    // codeword in the bars, e.g. 9 for a 7-digit SKU + 2 checks). 0 leaves a
    // side unbounded; the engine itself reads 7..10 digits. Out-of-range
    // lengths are skipped before template matching, so they never outvote
    // or displace an in-range read and cost no decode time.
    int minDigits = 0;
    int maxDigits = 0;
};

DecodeResult decode(const uint8_t* gray, int width, int height,
                    const DecodeOptions& options);

} // namespace msi
