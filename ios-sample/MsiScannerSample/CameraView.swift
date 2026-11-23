import AVFoundation
import SwiftUI
import UIKit

struct CameraView: UIViewRepresentable {
    @ObservedObject var controller: MsiCameraController

    func makeUIView(context: Context) -> ScannerPreviewView {
        let view = ScannerPreviewView()
        controller.attach(previewLayer: view.previewLayer)
        controller.onResult = { [weak view] result in
            view?.show(result)
        }
        return view
    }

    func updateUIView(_ uiView: ScannerPreviewView, context: Context) {}
}

final class ScannerPreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

    var previewLayer: AVCaptureVideoPreviewLayer {
        layer as! AVCaptureVideoPreviewLayer
    }

    private let overlay = BarcodeOverlayView()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        previewLayer.videoGravity = .resizeAspectFill
        overlay.translatesAutoresizingMaskIntoConstraints = false
        addSubview(overlay)
        NSLayoutConstraint.activate([
            overlay.leadingAnchor.constraint(equalTo: leadingAnchor),
            overlay.trailingAnchor.constraint(equalTo: trailingAnchor),
            overlay.topAnchor.constraint(equalTo: topAnchor),
            overlay.bottomAnchor.constraint(equalTo: bottomAnchor),
        ])
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    func show(_ result: MsiDecodeResult) {
        overlay.show(result, in: previewLayer)
    }
}

private final class BarcodeOverlayView: UIView {
    private let shape = CAShapeLayer()

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        backgroundColor = .clear
        shape.fillColor = UIColor.systemGreen.withAlphaComponent(0.18).cgColor
        shape.strokeColor = UIColor.systemGreen.cgColor
        shape.lineWidth = 3
        shape.lineJoin = .round
        layer.addSublayer(shape)
    }

    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        shape.frame = bounds
    }

    func show(_ result: MsiDecodeResult, in previewLayer: AVCaptureVideoPreviewLayer) {
        let frame = result.frameSize
        guard frame.width > 0, frame.height > 0 else { return }

        let box = result.box
        guard box.width > 0, box.height > 0 else { return }

        // `box` is in the unrotated camera buffer's pixels. The preview-layer
        // conversion applies video gravity, aspect-fill cropping and the
        // preview connection's 90-degree rotation for us.
        let normalized = CGRect(
            x: box.minX / frame.width,
            y: box.minY / frame.height,
            width: box.width / frame.width,
            height: box.height / frame.height
        )
        let layerRect = previewLayer
            .layerRectConverted(fromMetadataOutputRect: normalized)
            .intersection(bounds)
        guard !layerRect.isNull, layerRect.width > 1, layerRect.height > 1 else { return }

        shape.path = UIBezierPath(roundedRect: layerRect, cornerRadius: 6).cgPath
        shape.removeAllAnimations()
        shape.opacity = 1

        let fade = CABasicAnimation(keyPath: "opacity")
        fade.fromValue = 1
        fade.toValue = 0
        fade.beginTime = CACurrentMediaTime() + 0.45
        fade.duration = 0.45
        fade.fillMode = .forwards
        fade.isRemovedOnCompletion = false
        shape.add(fade, forKey: "fade")
    }
}
