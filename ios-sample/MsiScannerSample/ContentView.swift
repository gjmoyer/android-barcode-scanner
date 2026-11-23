import SwiftUI

struct ContentView: View {
    @StateObject private var controller = MsiCameraController()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            CameraView(controller: controller)
                .ignoresSafeArea()

            VStack(spacing: 12) {
                statusBar
                Spacer()
                resultCard
            }
            .padding()
        }
        .onAppear { controller.start() }
        .onDisappear { controller.stop() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                controller.start()
            } else {
                controller.stop()
            }
        }
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

            Text(controller.lastNativeDetail ?? "Point the camera at an MSI Plessey barcode")
                .font(.caption)
                .foregroundStyle(.secondary)
                .lineLimit(2)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }
}

#Preview {
    ContentView()
}
