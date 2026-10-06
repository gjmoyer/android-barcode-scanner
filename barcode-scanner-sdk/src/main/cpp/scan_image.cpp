#include "scan_image.h"

// v3.x headers are flat under core/src (no ZXing/ prefix dir); core/src is on
// the include path via the ZXing::ZXing target. Read API lives in ReadBarcode.h
// (singular) — verified against the v3.1.1 tree.
#include "BarcodeFormat.h"
#include "ReadBarcode.h"
#include "ImageView.h"

#include <cstdio>
#include <sstream>
#include <vector>

using namespace ZXing;

static std::string EscapeJson(const std::string& s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if ((unsigned char)c < 0x20) {
                    char buf[7];
                    snprintf(buf, sizeof(buf), "\\u%04x", c);
                    out += buf;
                } else out += c;
        }
    }
    return out;
}

static std::string Trim(const std::string& s) {
    const char* ws = " \t\r\n";
    size_t b = s.find_first_not_of(ws);
    if (b == std::string::npos) return "";
    size_t e = s.find_last_not_of(ws);
    return s.substr(b, e - b + 1);
}

std::string DecodeImage(const int32_t* argb, int width, int height, const ScanOptions& opts) {
    if (!argb || width <= 0 || height <= 0) return "[]";
    try {
        // ARGB int[] -> 8-bit luminance. vector<uint8_t> (NOT std::string/char:
        // char is signed on ARM and corrupts luminances > 127).
        std::vector<uint8_t> lum(static_cast<size_t>(width) * height);
        for (int i = 0; i < width * height; ++i) {
            int32_t p = argb[i];
            int r = (p >> 16) & 0xFF, g = (p >> 8) & 0xFF, b = p & 0xFF;
            lum[i] = static_cast<uint8_t>((77 * r + 150 * g + 29 * b) >> 8);
        }
        ImageView view(lum.data(), width, height, ImageFormat::Lum);

        ReaderOptions ro;
        ro.setTryRotate(opts.tryRotate);
        ro.setTryInvert(opts.tryInvert);
        ro.setTryHarder(opts.tryHarder);
        // v3.x: BarcodeFormatFromString THROWS std::invalid_argument on unknown
        // names (v2.3 returned None), and is case-insensitive ignoring " -_/".
        // Unknown filter names are skipped so one stale entry can't poison the set.
        // v3.x BarcodeFormats is an encapsulated container (no push_back):
        // collect into a vector, then move-construct.
        if (!opts.enabledFormats.empty()) {
            std::vector<BarcodeFormat> parsed;
            size_t pos = 0;
            while (pos < opts.enabledFormats.size()) {
                size_t comma = opts.enabledFormats.find(',', pos);
                std::string name = Trim(opts.enabledFormats.substr(
                    pos, comma == std::string::npos ? std::string::npos : comma - pos));
                if (!name.empty()) {
                    try {
                        parsed.push_back(BarcodeFormatFromString(name));
                    } catch (...) { /* ignore unknown filter name */ }
                }
                if (comma == std::string::npos) break;
                pos = comma + 1;
            }
            if (!parsed.empty()) ro.setFormats(BarcodeFormats(std::move(parsed)));
            // Empty-after-filter -> fall through to all-formats scan rather than
            // a degenerate empty filter.
        }

        auto results = ReadBarcodes(view, ro);
        std::ostringstream json;
        json << "[";
        bool first = true;
        for (const auto& r : results) {
            if (!r.isValid()) continue;
            if (!first) json << ",";
            first = false;
            json << "{\"text\":\"" << EscapeJson(r.text()) << "\""
                 << ",\"format\":\"" << ToString(r.format()) << "\""
                 << ",\"orientation\":" << r.orientation() << "}";
        }
        json << "]";
        return json.str();
    } catch (...) {
        return "[]";
    }
}
