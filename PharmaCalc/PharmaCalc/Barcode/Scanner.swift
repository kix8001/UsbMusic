import AVFoundation
import SafariServices
import SwiftUI
import UIKit

/// カメラでバーコードを読む（GS1データバー限定型・二層型、GS1-128、JAN、DataMatrix）
struct BarcodeScanner: UIViewControllerRepresentable {
    var torch: Bool
    var paused: Bool
    var onScan: (String) -> Void

    func makeUIViewController(context: Context) -> ScannerViewController {
        let vc = ScannerViewController()
        vc.onScan = onScan
        return vc
    }

    func updateUIViewController(_ vc: ScannerViewController, context: Context) {
        vc.onScan = onScan
        vc.paused = paused
        vc.setTorch(torch)
    }
}

final class ScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onScan: ((String) -> Void)?
    var paused = false

    private let session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer?
    private var device: AVCaptureDevice?
    private var lastCode = ""
    private var lastSeen = Date.distantPast
    private let sessionQueue = DispatchQueue(label: "scanner.session")

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        configure()
    }

    private func configure() {
        guard let dev = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
              let input = try? AVCaptureDeviceInput(device: dev),
              session.canAddInput(input) else { return }
        device = dev
        session.beginConfiguration()
        if session.canSetSessionPreset(.hd1920x1080) { session.sessionPreset = .hd1920x1080 }
        session.addInput(input)

        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { session.commitConfiguration(); return }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        let wanted: [AVMetadataObject.ObjectType] = [
            .gs1DataBar, .gs1DataBarLimited, .gs1DataBarExpanded,
            .code128, .ean13, .ean8, .dataMatrix, .qr,
        ]
        output.metadataObjectTypes = wanted.filter { output.availableMetadataObjectTypes.contains($0) }
        session.commitConfiguration()

        setupFocus(dev)

        let pl = AVCaptureVideoPreviewLayer(session: session)
        pl.videoGravity = .resizeAspectFill
        view.layer.addSublayer(pl)
        preview = pl
    }

    /// PTPの小さいバーコードに近づいても焦点が合うよう、近距離優先にして少しズームする
    private func setupFocus(_ dev: AVCaptureDevice) {
        guard (try? dev.lockForConfiguration()) != nil else { return }
        defer { dev.unlockForConfiguration() }
        if dev.isAutoFocusRangeRestrictionSupported { dev.autoFocusRangeRestriction = .near }
        if dev.isFocusModeSupported(.continuousAutoFocus) { dev.focusMode = .continuousAutoFocus }

        // 最短撮影距離が長い機種（Proなど）では、ズームして離れた位置から読めるようにする
        let minFocus = Float(dev.minimumFocusDistance)  // mm
        guard minFocus > 0 else { return }
        let fov = dev.activeFormat.videoFieldOfView
        let codeSize: Float = 25  // 想定するバーコードの幅（mm）
        let fill: Float = 0.5     // 画面幅に対する割合
        let radians = (fov / 2) * Float.pi / 180
        let subjectDistance = (codeSize / fill) / tan(radians)
        if subjectDistance < minFocus {
            let zoom = CGFloat(minFocus / subjectDistance)
            dev.videoZoomFactor = min(max(1, zoom), min(dev.activeFormat.videoMaxZoomFactor, 4))
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        sessionQueue.async { [session] in
            if !session.isRunning { session.startRunning() }
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        sessionQueue.async { [session] in
            if session.isRunning { session.stopRunning() }
        }
    }

    func setTorch(_ on: Bool) {
        guard let dev = device, dev.hasTorch, (try? dev.lockForConfiguration()) != nil else { return }
        dev.torchMode = on ? .on : .off
        dev.unlockForConfiguration()
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard !paused,
              let obj = objects.compactMap({ $0 as? AVMetadataMachineReadableCodeObject }).first,
              let code = obj.stringValue, !code.isEmpty else { return }
        let now = Date()
        // 同じコードを映し続けている間は繰り返し反応しない
        if code == lastCode, now.timeIntervalSince(lastSeen) < 4 {
            lastSeen = now
            return
        }
        lastCode = code
        lastSeen = now
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        onScan?(code)
    }
}

struct SafariView: UIViewControllerRepresentable {
    let url: URL
    func makeUIViewController(context: Context) -> SFSafariViewController {
        SFSafariViewController(url: url)
    }
    func updateUIViewController(_ vc: SFSafariViewController, context: Context) {}
}

struct WebPage: Identifiable {
    let url: URL
    var id: String { url.absoluteString }
}
