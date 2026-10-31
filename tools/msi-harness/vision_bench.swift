// vision_bench.swift — macOS Vision MSI Plessey timing/accuracy probe.
// Usage: ./vision_bench <image> [<image> ...]
// Prints TSV: <file>\t<median_ms>\t<payload>
import Vision
import CoreGraphics
import ImageIO
import Foundation

func load(_ url: URL) -> CGImage? {
    guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
    return CGImageSourceCreateImageAtIndex(src, 0, nil)
}

func detect(_ img: CGImage) -> [(String, String)] {
    var out: [(String, String)] = []
    let req = VNDetectBarcodesRequest { req, _ in
        for r in (req.results as? [VNBarcodeObservation] ?? []) {
            out.append((r.symbology.rawValue, r.payloadStringValue ?? ""))
        }
    }
    let handler = VNImageRequestHandler(cgImage: img, options: [:])
    try? handler.perform([req])
    return out
}

func median(_ xs: [Double]) -> Double {
    let s = xs.sorted()
    return s[s.count / 2]
}

let files = Array(CommandLine.arguments.dropFirst())
if files.count == 0 { exit(2) }
// Warm the Vision pipeline once so the first measured file is not cold.
if let warm = load(URL(fileURLWithPath: files[0])) { _ = detect(warm) }
for arg in files {
    let url = URL(fileURLWithPath: arg)
    guard let img = load(url) else {
        print("\(arg)\t-1\tLOAD_ERROR")
        continue
    }
    var times: [Double] = []
    var last: [(String, String)] = []
    for _ in 0..<3 {
        let t0 = DispatchTime.now().uptimeNanoseconds
        last = detect(img)
        let t1 = DispatchTime.now().uptimeNanoseconds
        times.append(Double(t1 - t0) / 1_000_000.0)
    }
    let msi = last.first { $0.0 == "VNBarcodeSymbologyMSIPlessey" } ?? last.first
    print(String(format: "%@\t%.3f\t%@", arg, median(times), msi?.1 ?? ""))
}
