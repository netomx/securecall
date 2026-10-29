import AVFoundation
import SwiftUI

/// Reads one QR code with the camera, for adding a contact and for in-person verification.
/// Nothing is recorded or kept: the frames go straight to the detector.
struct QRScannerSheet: View {
    @Environment(\.dismiss) private var dismiss
    let prompt: LocalizedStringKey
    let onCode: (String) -> Void

    @State private var allowed: Bool?

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()
                switch allowed {
                case .some(true):
                    QRCameraView { code in
                        dismiss()
                        onCode(code)
                    }
                    .ignoresSafeArea()
                    VStack {
                        Spacer()
                        Text(prompt)
                            .font(.callout)
                            .foregroundStyle(.white)
                            .multilineTextAlignment(.center)
                            .padding(12)
                            .background(.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 10))
                            .padding(24)
                    }
                case .some(false):
                    VStack(spacing: 16) {
                        Text("Kalloq necesita la cámara para leer el código QR.")
                            .foregroundStyle(.white)
                            .multilineTextAlignment(.center)
                        Button("Abrir Ajustes") {
                            if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                        }
                        .buttonStyle(.borderedProminent)
                    }
                    .padding(32)
                case .none:
                    ProgressView().tint(.white)
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancelar") { dismiss() }
                }
            }
            .toolbarBackground(.visible, for: .navigationBar)
        }
        .task {
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized: allowed = true
            case .notDetermined: allowed = await AVCaptureDevice.requestAccess(for: .video)
            default: allowed = false
            }
        }
    }
}

private struct QRCameraView: UIViewControllerRepresentable {
    let onCode: (String) -> Void

    func makeUIViewController(context: Context) -> QRCameraController {
        let controller = QRCameraController()
        controller.onCode = onCode
        return controller
    }

    func updateUIViewController(_ controller: QRCameraController, context: Context) {}
}

final class QRCameraController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onCode: ((String) -> Void)?
    private let session = AVCaptureSession()
    private var preview: AVCaptureVideoPreviewLayer?
    private var done = false

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        guard let camera = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: camera), session.canAddInput(input) else { return }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { return }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]

        let layer = AVCaptureVideoPreviewLayer(session: session)
        layer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(layer)
        preview = layer
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        preview?.frame = view.bounds
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        let session = session
        DispatchQueue.global(qos: .userInitiated).async { session.startRunning() }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        let session = session
        DispatchQueue.global(qos: .userInitiated).async { session.stopRunning() }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        guard !done, let code = objects.compactMap({ ($0 as? AVMetadataMachineReadableCodeObject)?.stringValue }).first
        else { return }
        done = true
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        onCode?(code)
    }
}
