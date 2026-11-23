import SwiftUI

struct ContentView: View {
    @StateObject private var controller = MsiCameraController()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            // Keep the preview layer attached for the app's lifetime. Only the
            // capture session is stopped while idle, so arming a scan does not
            // have to rebuild the preview connection.
            CameraView(controller: controller)
                .ignoresSafeArea()

            if !controller.isScanning && controller.frozenFrame == nil {
                idleBackground
                    .transition(.opacity)
            }

            VStack(spacing: 12) {
                statusBar
                Spacer()
                if controller.isScanning {
                    scanningPanel
                } else {
                    idlePanel
                }
            }
            .padding()
        }
        .animation(.easeInOut(duration: 0.2), value: controller.isScanning)
        .onDisappear { controller.stopScan() }
        .onChange(of: scenePhase) { _, phase in
            if phase != .active { controller.stopScan() }
        }
    }

    private var idleBackground: some View {
        ZStack {
            LinearGradient(
                colors: [Color(red: 0.03, green: 0.08, blue: 0.07), .black],
                startPoint: .topLeading,
                endPoint: .bottomTrailing
            )
            RadialGradient(
                colors: [.green.opacity(0.16), .clear],
                center: .top,
                startRadius: 20,
                endRadius: 520
            )
        }
        .ignoresSafeArea()
    }

    private var statusBar: some View {
        HStack(spacing: 8) {
            Image(systemName: "barcode.viewfinder")
                .font(.title3)
            Text(controller.status)
                .font(.subheadline.weight(.medium))
                .lineLimit(2)
            Spacer()
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 10)
        .background(.ultraThinMaterial, in: Capsule())
    }

    private var idlePanel: some View {
        VStack(spacing: 18) {
            resultCard
            scanButton
            Text("Camera and decoder stay off until you tap Scan.")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }

    private var resultCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("MSI Plessey")
                    .font(.headline)
                Spacer()
                Picker("Checksum", selection: $controller.policy) {
                    ForEach(MsiChecksumPolicy.allCases) { policy in
                        Text(policy.title).tag(policy)
                    }
                }
                .pickerStyle(.menu)
                .tint(.primary)
            }

            Text(controller.lastPayload ?? "No barcode yet")
                .font(.system(.title2, design: .monospaced).weight(.semibold))
                .textSelection(.enabled)
                .lineLimit(1)
                .minimumScaleFactor(0.6)

            Text(controller.lastNativeDetail ?? "Tap Scan and point at a barcode")
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    private var scanButton: some View {
        Button(action: controller.startScan) {
            ZStack {
                Circle()
                    .fill(
                        AngularGradient(
                            colors: [
                                Color(red: 0.00, green: 0.92, blue: 0.58),
                                Color(red: 0.00, green: 0.68, blue: 1.00),
                                Color(red: 0.00, green: 0.92, blue: 0.58),
                            ],
                            center: .center
                        )
                    )
                    .frame(width: 108, height: 108)
                    .shadow(color: .green.opacity(0.55), radius: 22)

                Circle()
                    .stroke(.white.opacity(0.35), lineWidth: 2)
                    .frame(width: 90, height: 90)

                VStack(spacing: 4) {
                    Image(systemName: "barcode.viewfinder")
                        .font(.system(size: 31, weight: .semibold))
                    Text("SCAN")
                        .font(.system(size: 11, weight: .black))
                        .tracking(2)
                }
                .foregroundStyle(.white)
                .shadow(color: .black.opacity(0.25), radius: 2, y: 1)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Scan MSI barcode")
    }

    private var scanningPanel: some View {
        VStack(spacing: 12) {
            Text("Scanning…")
                .font(.headline)
            Text("Point the camera at an MSI Plessey barcode.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            Button("Cancel") {
                controller.stopScan()
            }
            .buttonStyle(.borderedProminent)
            .tint(.black.opacity(0.55))
        }
        .frame(maxWidth: .infinity)
        .padding(16)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }
}

#Preview {
    ContentView()
}
