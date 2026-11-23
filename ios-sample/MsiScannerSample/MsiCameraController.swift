import AVFoundation
import Combine
import Foundation

final class MsiCameraController: NSObject, ObservableObject {

    @Published private(set) var status = "Starting camera…"
    @Published private(set) var lastPayload: String?
    @Published private(set) var lastNativeDetail: String?
    @Published var policy: MsiChecksumPolicy = .mod10 {
        didSet {
            policyLock.lock()
            lockedPolicy = policy
            policyLock.unlock()
        }
    }

    let session = AVCaptureSession()

    /// Called on the main thread after a validated MSI read.
    var onResult: ((MsiDecodeResult) -> Void)?

    private let sessionQueue = DispatchQueue(label: "com.barcodescanner.msiscanner.session")
    private let videoQueue = DispatchQueue(label: "com.barcodescanner.msiscanner.video")
    private let policyLock = NSLock()
    private var lockedPolicy: MsiChecksumPolicy = .mod10
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var videoOutput: AVCaptureVideoDataOutput?
    private var configured = false
    private var lastAccepted = ""
    private var lastAcceptedAt = Date.distantPast

    /// The app is portrait-only and uses the back camera, whose native sensor
    /// orientation needs a 90-degree clockwise turn to be upright.
    private let bufferRotationDegrees: Int32 = 90

    func attach(previewLayer: AVCaptureVideoPreviewLayer) {
        self.previewLayer = previewLayer
        previewLayer.session = session
        previewLayer.videoGravity = .resizeAspectFill
        applyPreviewRotation()
    }

    func start() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureAndStart()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                guard let self else { return }
                if granted {
                    self.configureAndStart()
                } else {
                    self.publishStatus("Camera access denied — enable it in Settings")
                }
            }
        case .denied, .restricted:
            publishStatus("Camera access denied — enable it in Settings")
        @unknown default:
            publishStatus("Camera authorization status is unknown")
        }
    }

    func stop() {
        sessionQueue.async { [session] in
            if session.isRunning { session.stopRunning() }
        }
    }

    private func configureAndStart() {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            do {
                if !self.configured {
                    try self.configureSession()
                }
                if !self.session.isRunning {
                    self.session.startRunning()
                }
                DispatchQueue.main.async {
                    self.applyPreviewRotation()
                    self.status = "Aim at an MSI Plessey barcode"
                }
            } catch {
                self.publishStatus(error.localizedDescription)
            }
        }
    }

    private func configureSession() throws {
        session.beginConfiguration()
        defer { session.commitConfiguration() }

        session.sessionPreset = session.canSetSessionPreset(.hd1280x720) ? .hd1280x720 : .high

        guard let device = AVCaptureDevice.default(
            .builtInWideAngleCamera,
            for: .video,
            position: .back
        ) else {
            throw CameraError.noBackCamera
        }
        let input = try AVCaptureDeviceInput(device: device)
        guard session.canAddInput(input) else { throw CameraError.cannotAddInput }
        session.addInput(input)

        let output = AVCaptureVideoDataOutput()
        output.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String:
                kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
        ]
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: videoQueue)
        guard session.canAddOutput(output) else { throw CameraError.cannotAddOutput }
        session.addOutput(output)
        self.videoOutput = output

        // Keep the data output in sensor orientation: the C++ decoder rotates
        // it internally and maps its box back into the buffer. Only the
        // preview layer is rotated for display.
        configured = true
    }

    private func applyPreviewRotation() {
        guard let connection = previewLayer?.connection else { return }
        let angle: CGFloat = 90
        if connection.isVideoRotationAngleSupported(angle) {
            connection.videoRotationAngle = angle
        }
    }

    private func publishStatus(_ text: String) {
        DispatchQueue.main.async { [weak self] in
            self?.status = text
        }
    }

    private func currentPolicy() -> MsiChecksumPolicy {
        policyLock.lock()
        defer { policyLock.unlock() }
        return lockedPolicy
    }
}

extension MsiCameraController: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        guard CVPixelBufferIsPlanar(pixelBuffer), CVPixelBufferGetPlaneCount(pixelBuffer) > 0 else {
            return
        }

        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }

        guard let base = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0) else { return }
        let width = CVPixelBufferGetWidthOfPlane(pixelBuffer, 0)
        let height = CVPixelBufferGetHeightOfPlane(pixelBuffer, 0)
        let rowStride = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0)
        guard width > 0, height > 0, rowStride >= width else { return }

        let policy = currentPolicy()
        guard let result = MsiBridge.decodePlane(
            base.assumingMemoryBound(to: UInt8.self),
            width: Int32(width),
            height: Int32(height),
            rowStride: Int32(rowStride),
            rotationDegrees: bufferRotationDegrees,
            requiredPolicy: policy.nativeCode,
            minDigits: 3,
            maxDigits: 0,
            maxOrientations: 2
        ) else {
            return
        }

        // The native engine already selected a policy-valid codeword. Keep the
        // same server-side tail as Android: validate, strip checks, reject
        // degenerate payloads, then suppress rapid identical frames.
        let validation = MsiChecksumValidator.validate(result.digits, policy: policy)
        guard validation.valid,
              validation.payloadWithoutChecksum.count >= 3,
              Set(validation.payloadWithoutChecksum).count >= 2
        else {
            return
        }

        let now = Date()
        if validation.payloadWithoutChecksum == lastAccepted,
           now.timeIntervalSince(lastAcceptedAt) < 0.5 {
            return
        }
        lastAccepted = validation.payloadWithoutChecksum
        lastAcceptedAt = now

        let payload = validation.payloadWithoutChecksum
        let nativeDetail =
            "\(result.digits) / \(result.nativePolicy) / \(result.votes) votes"
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            self.lastPayload = payload
            self.lastNativeDetail = nativeDetail
            self.status = "Detected MSI Plessey"
            self.onResult?(result)
        }
    }
}

private enum CameraError: LocalizedError {
    case noBackCamera
    case cannotAddInput
    case cannotAddOutput

    var errorDescription: String? {
        switch self {
        case .noBackCamera:
            return "No back camera found — this sample needs a physical device"
        case .cannotAddInput:
            return "Could not add the camera input"
        case .cannotAddOutput:
            return "Could not add the video output"
        }
    }
}
