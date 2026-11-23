import AVFoundation
import SwiftUI
import UIKit

struct CameraView: UIViewRepresentable {
    @ObservedObject var controller: MsiCameraController

    func makeUIView(context: Context) -> ScannerPreviewView {
        let view = ScannerPreviewView()
        // Install the callback before attaching the session so an immediate
        // first result cannot arrive before the overlay can draw it.
        controller.onResult = { [weak view] result in
            view?.show(result)
        }
        controller.attach(previewLayer: view.previewLayer)
        view.setFrozenFrame(controller.frozenFrame)
        return view
    }

    func updateUIView(_ uiView: ScannerPreviewView, context: Context) {
        uiView.setFrozenFrame(controller.frozenFrame)
    }
}

final class ScannerPreviewView: UIView {
    override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }

    var previewLayer: AVCaptureVideoPreviewLayer {
        layer as! AVCaptureVideoPreviewLayer
    }

    private let frozenImageView = UIImageView()
    private let overlay = BarcodeOverlayView()

    override init(frame: CGRect) {
        super.init(frame: frame)
        backgroundColor = .black
        previewLayer.videoGravity = .resizeAspectFill

        frozenImageView.contentMode = .scaleAspectFill
        frozenImageView.clipsToBounds = true
        frozenImageView.isHidden = true
        frozenImageView.isUserInteractionEnabled = false
        addSubview(frozenImageView)

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

    override func layoutSubviews() {
        super.layoutSubviews()
        frozenImageView.frame = bounds
    }

    func setFrozenFrame(_ image: UIImage?) {
        guard let image else {
            frozenImageView.image = nil
            frozenImageView.isHidden = true
            overlay.clear()
            return
        }
        frozenImageView.image = image
        frozenImageView.isHidden = false
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
        shape.fillColor = UIColor.systemGreen.withAlphaComponent(0.20).cgColor
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

    func clear() {
        shape.removeAllAnimations()
        shape.path = nil
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

        // A short pop-in, then leave the box visible on the frozen frame.
        let pop = CABasicAnimation(keyPath: "opacity")
        pop.fromValue = 0.2
        pop.toValue = 1
        pop.duration = 0.15
        shape.add(pop, forKey: "pop")
    }
}
