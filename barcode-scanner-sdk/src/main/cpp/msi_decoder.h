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
// Does not retain the buffer. Thread-safe (no shared mutable state).
DecodeResult decode(const uint8_t* gray, int width, int height);

} // namespace msi
