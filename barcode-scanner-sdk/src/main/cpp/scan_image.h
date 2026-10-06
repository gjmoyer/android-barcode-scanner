#pragma once
#include <cstdint>
#include <string>

struct ScanOptions {
    bool tryRotate = true;
    bool tryInvert = true;
    bool tryHarder = true;
    std::string enabledFormats; // comma-separated, e.g. "DataBarExpanded"; empty = all
};

// Decodes ARGB pixels (row-major, width*height) and returns a JSON array:
// [{"text":"...","format":"DataBarExpanded","orientation":180}]
// Returns "[]" when nothing is found or on error (never throws across JNI).
std::string DecodeImage(const int32_t* argb, int width, int height, const ScanOptions& opts);
