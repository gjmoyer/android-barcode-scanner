import AVFoundation
import Combine
import CoreImage
import Foundation
import UIKit

final class MsiCameraController: NSObject, ObservableObject {

    @Published private(set) var status = "Ready — tap Scan to scan an MSI barcode"
    @Published private(set) var lastPayload: String?
    @Published private(set) var lastNativeDetail: String?
    @Published private(set) var isScanning = false
    /// Camera frame captured at the winning decode, shown frozen after the
    /// session stops so the highlighted barcode stays on screen.
    @Published private(set) var frozenFrame: UIImage?
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
    private let ciContext = CIContext()
    private let policyLock = NSLock()
    private var lockedPolicy: MsiChecksumPolicy = .mod10
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var configured = false

    /// Scan-session state shared with the video queue. The camera is only
    /// running while `scanActive` is true, and a scan accepts exactly one hit.
    private let stateLock = NSLock()
    private var scanToken = 0
    private var scanActive = false
    private var acceptedForScan = false
    private var pendingFinish: DispatchWorkItem?

    /// Portrait-only app using the back camera: the sensor buffer needs a
    /// 90-degree clockwise turn to be upright.
    private let bufferRotationDegrees: Int32 = 90

    /// How long the result stays visible before the camera is shut off.
    private let highlightMilliseconds = 1_000

    func attach(previewLayer: AVCaptureVideoPreviewLayer) {
        self.previewLayer = previewLayer
        previewLayer.session = session
        previewLayer.videoGravity = .resizeAspectFill
        applyPreviewRotation()
    }

    /// Starts a one-shot scan. The camera session and decode work only while a
    /// scan is armed; calling this again after a result works normally.
    func startScan() {
        guard Thread.isMainThread else {
            DispatchQueue.main.async { [weak self] in self?.startScan() }
            return
        }
        guard !isScanning else { return }

        pendingFinish?.cancel()
        pendingFinish = nil
        let token = beginScan()
        isScanning = true
        status = "Starting camera…"
        lastPayload = nil
        lastNativeDetail = nil
        frozenFrame = nil

        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configureAndStart(token: token)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                DispatchQueue.main.async {
                    guard let self, self.isScanning, self.currentToken() == token else {
                        return
                    }
                    if granted {
                        self.configureAndStart(token: token)
                    } else {
                        self.finishScan(status: "Camera access denied — enable it in Settings")
                    }
                }
            }
        case .denied, .restricted:
            finishScan(status: "Camera access denied — enable it in Settings")
        @unknown default:
            finishScan(status: "Camera authorization status is unknown")
        }
    }

    /// Cancels an active scan or ends the post-result highlight immediately.
    func stopScan() {
        guard Thread.isMainThread else {
            DispatchQueue.main.async { [weak self] in self?.stopScan() }
            return
        }
        guard isScanning else { return }
        finishScan(status: "Ready — tap Scan to scan an MSI barcode")
    }

    private func configureAndStart(token: Int) {
        sessionQueue.async { [weak self] in
            guard let self, self.isScanActive() else { return }
            do {
                if !self.configured {
                    try self.configureSession()
                }
                guard self.isScanActive() else { return }
                if !self.session.isRunning {
                    self.session.startRunning()
                }
                DispatchQueue.main.async {
                    guard self.isScanning, self.currentToken() == token else {
                        self.stopSession()
                        return
                    }
                    self.applyPreviewRotation()
                    self.status = "Scanning… point at an MSI barcode"
                }
            } catch {
                DispatchQueue.main.async {
                    self.finishScan(status: error.localizedDescription)
                }
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
                kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        ]
        output.alwaysDiscardsLateVideoFrames = true
        output.setSampleBufferDelegate(self, queue: videoQueue)
        guard session.canAddOutput(output) else { throw CameraError.cannotAddOutput }
        session.addOutput(output)

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

    private func finishScan(status text: String) {
        guard Thread.isMainThread else {
            DispatchQueue.main.async { [weak self] in self?.finishScan(status: text) }
            return
        }
        _ = endScan()
        pendingFinish?.cancel()
        pendingFinish = nil
        isScanning = false
        status = text
        stopSession()
    }

    private func stopSession() {
        sessionQueue.async { [session] in
            if session.isRunning { session.stopRunning() }
        }
    }

    private func scheduleFinish(token: Int) {
        pendingFinish?.cancel()
        let work = DispatchWorkItem { [weak self] in
            guard let self, self.isScanning, self.currentToken() == token else { return }
            self.finishScan(status: "Scanned — tap Scan for the next barcode")
        }
        pendingFinish = work
        DispatchQueue.main.asyncAfter(
            deadline: .now() + .milliseconds(highlightMilliseconds),
            execute: work
        )
    }

    private func currentPolicy() -> MsiChecksumPolicy {
        policyLock.lock()
        defer { policyLock.unlock() }
        return lockedPolicy
    }

    // MARK: - Scan-session state

    private func beginScan() -> Int {
        stateLock.lock()
        defer { stateLock.unlock() }
        scanToken += 1
        scanActive = true
        acceptedForScan = false
        return scanToken
    }

    private func endScan() -> Int {
        stateLock.lock()
        defer { stateLock.unlock() }
        scanToken += 1
        scanActive = false
        return scanToken
    }

    private func currentToken() -> Int {
        stateLock.lock()
        defer { stateLock.unlock() }
        return scanToken
    }

    private func isScanActive() -> Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        return scanActive
    }

    /// True exactly once per armed scan: the one-shot result gate.
    private func markAcceptedForScan() -> Bool {
        stateLock.lock()
        defer { stateLock.unlock() }
        guard scanActive, !acceptedForScan else { return false }
        acceptedForScan = true
        return true
    }
}

extension MsiCameraController: AVCaptureVideoDataOutputSampleBufferDelegate {
    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        guard isScanActive() else { return }
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
        // same tail as Android: validate, strip checks, reject degenerate
        // payloads, and accept only the first hit of this scan.
        let validation = MsiChecksumValidator.validate(result.digits, policy: policy)
        guard validation.valid,
              validation.payloadWithoutChecksum.count >= 3,
              Set(validation.payloadWithoutChecksum).count >= 2,
              markAcceptedForScan()
        else {
            return
        }

        let token = currentToken()
        let payload = validation.payloadWithoutChecksum
        let nativeDetail =
            "\(result.digits) / \(result.nativePolicy) / \(result.votes) votes"

        // Render this exact winning frame while the pixel buffer is still
        // locked, then keep it behind the highlight after the session stops.
        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        let frozen: UIImage? = ciContext
            .createCGImage(ciImage, from: ciImage.extent)
            .map { UIImage(cgImage: $0, scale: 1, orientation: .right) }

        DispatchQueue.main.async { [weak self] in
            guard let self, self.isScanning, self.currentToken() == token else { return }
            self.lastPayload = payload
            self.lastNativeDetail = nativeDetail
            self.frozenFrame = frozen
            self.status = "Detected MSI Plessey"
            self.onResult?(result)
            UINotificationFeedbackGenerator().notificationOccurred(.success)
            self.scheduleFinish(token: token)
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
