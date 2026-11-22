import SwiftUI

struct ContentView: View {
    @StateObject private var ble = BLEManager()
    @StateObject private var heading = HeadingManager()
    @StateObject private var model = RadarModel()

    var body: some View {
        ZStack {
            Color(red: 0.043, green: 0.059, blue: 0.078).ignoresSafeArea()

            VStack(spacing: 10) {
                Text(distanceText)
                    .font(.system(size: 42, weight: .semibold))
                    .foregroundColor(.white)
                    .padding(.top, 20)

                Text(statusText)
                    .font(.system(size: 13))
                    .foregroundColor(Color(white: 0.55))

                RadarView(model: model)
                    .padding(.horizontal, 24)
                    .padding(.top, 4)

                HStack(spacing: 16) {
                    legendItem(color: Color(red: 0.29, green: 0.62, blue: 1.0), label: "you're facing")
                    legendItem(color: .white, label: "anchor direction")
                }
                .font(.system(size: 12))
                .foregroundColor(Color(white: 0.6))

                HStack(spacing: 14) {
                    dotLegend(color: Color(red: 0.88, green: 0.28, blue: 0.30), label: "far")
                    dotLegend(color: Color(red: 0.90, green: 0.79, blue: 0.24), label: "getting closer")
                    dotLegend(color: Color(red: 0.24, green: 0.81, blue: 0.56), label: "close")
                }
                .font(.system(size: 12))
                .foregroundColor(Color(white: 0.6))

                Text(confidenceText)
                    .font(.system(size: 12))
                    .foregroundColor(Color(white: 0.35))

                Spacer()
            }
            .padding()
        }
        .onAppear {
            heading.start()
            model.start()
        }
        .onDisappear {
            model.stop()
        }
        .onChange(of: ble.distanceM) { newValue in
            if let d = newValue { model.onDistance(d) }
        }
        .onChange(of: heading.headingDeg) { newValue in
            if let h = newValue { model.onHeading(h) }
        }
        .preferredColorScheme(.dark)
    }

    private var distanceText: String {
        guard let d = model.displayedDistanceM else { return "-- m" }
        return String(format: "%.2f m", d)
    }

    private var statusText: String {
        if heading.authorizationDenied {
            return "Location access needed for compass — check Settings"
        }
        switch ble.connectionState {
        case .disconnected: return "searching for Stella…"
        case .scanning:     return "scanning…"
        case .connecting:   return "connecting…"
        case .connected:    return "live"
        case .bluetoothOff: return "Bluetooth is off"
        }
    }

    private var confidenceText: String {
        model.targetVisible
            ? "direction: \(model.confidencePct)% confident"
            : "direction: calibrating — walk around a bit"
    }

    private func legendItem(color: Color, label: String) -> some View {
        HStack(spacing: 5) {
            Rectangle().fill(color).frame(width: 14, height: 2)
            Text(label)
        }
    }

    private func dotLegend(color: Color, label: String) -> some View {
        HStack(spacing: 5) {
            Circle().fill(color).frame(width: 9, height: 9)
            Text(label)
        }
    }
}

#Preview {
    ContentView()
}
