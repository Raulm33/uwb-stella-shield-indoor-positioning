import Foundation
import SwiftUI
import Combine

/// Same fusion approach as the web version: whole-disc color driven by
/// absolute distance, plus a continuously-updated "which heading tends to
/// reduce distance" vector estimate for the target-direction arrow.
final class RadarModel: ObservableObject {
    // ----- Config -----
    static let nearM: Double = 0.3      // fully green at/below this
    static let farM: Double = 4.0       // fully red at/above this
    private let distSmooth = 0.25       // EMA factor for displayed distance
    private let headingSmooth = 0.20    // EMA factor for displayed phone heading
    private let arrowLerp = 0.12        // per-frame interpolation for arrows
    private let nbins = 36              // 10 deg per heading bin

    // ----- Published (drives the UI) -----
    @Published var displayedDistanceM: Double? = nil
    @Published var displayedColor: Color = Color(red: 0.9, green: 0.75, blue: 0.24) // neutral yellow
    @Published var displayedYouAngle: Double = 0
    @Published var displayedTargetAngle: Double = 0
    @Published var targetVisible: Bool = false
    @Published var confidencePct: Int = 0

    // ----- Internal state -----
    private var rawDistanceM: Double? = nil
    private var smoothDistanceM: Double? = nil
    private var rawHeadingDeg: Double? = nil
    private var smoothHeadingDeg: Double? = nil

    private var lastFusionDistanceM: Double? = nil
    private var lastFusionHeadingDeg: Double? = nil
    private var bins = [Double](repeating: 0, count: 36)

    private var displayedColorRGB: (Double, Double, Double) = (230, 190, 60)
    private var timer: Timer?

    private let colorRed:    (Double, Double, Double) = (225, 72, 77)
    private let colorYellow: (Double, Double, Double) = (230, 190, 60)
    private let colorGreen:  (Double, Double, Double) = (62, 207, 142)

    func start() {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1.0/60.0, repeats: true) { [weak self] _ in
            self?.tick()
        }
    }

    func stop() {
        timer?.invalidate()
        timer = nil
    }

    // Call whenever a new BLE distance reading arrives (meters).
    func onDistance(_ distanceM: Double) {
        rawDistanceM = distanceM
        smoothDistanceM = smoothDistanceM.map { lerp($0, distanceM, distSmooth) } ?? distanceM
        if let h = rawHeadingDeg { updateFusion(distanceM: distanceM, headingDeg: h) }
    }

    // Call whenever a new compass heading arrives (degrees, 0=N).
    func onHeading(_ headingDeg: Double) {
        rawHeadingDeg = headingDeg
        smoothHeadingDeg = smoothHeadingDeg.map { lerpAngle($0, headingDeg, headingSmooth) } ?? headingDeg
        if let d = rawDistanceM { updateFusion(distanceM: d, headingDeg: headingDeg) }
    }

    // ----- Fusion -----
    private func headingToBin(_ h: Double) -> Int {
        var b = Int((h / (360.0/Double(nbins))).rounded()) % nbins
        if b < 0 { b += nbins }
        return b
    }

    private func updateFusion(distanceM: Double, headingDeg: Double) {
        if let lastD = lastFusionDistanceM, let lastH = lastFusionHeadingDeg {
            let deltaD = distanceM - lastD
            let deltaH = abs((headingDeg - lastH + 540).truncatingRemainder(dividingBy: 360) - 180)
            if abs(deltaD) * 100 > 4 && deltaH < 60 {
                let score = clamp(-deltaD / 0.4, -1, 1)
                let b = headingToBin(headingDeg)
                bins[b] = bins[b]*0.6 + score*0.4
                lastFusionDistanceM = distanceM
                lastFusionHeadingDeg = headingDeg
            } else if abs(deltaD) * 100 > 4 {
                lastFusionDistanceM = distanceM
                lastFusionHeadingDeg = headingDeg
            }
        } else {
            lastFusionDistanceM = distanceM
            lastFusionHeadingDeg = headingDeg
        }
        for i in 0..<nbins { bins[i] *= 0.996 }
    }

    private func targetDirection() -> (angle: Double, confidence: Double)? {
        var sx = 0.0, sy = 0.0, mag = 0.0
        for i in 0..<nbins {
            let w = max(0, bins[i])
            if w <= 0 { continue }
            let ang = Double(i) * (360.0/Double(nbins)) * .pi/180
            sx += cos(ang) * w
            sy += sin(ang) * w
            mag += w
        }
        if mag < 0.15 { return nil }
        let angle = (atan2(sy, sx) * 180/(.pi) + 360).truncatingRemainder(dividingBy: 360)
        let confidence = clamp(mag / (Double(nbins)*0.3), 0, 1)
        return (angle, confidence)
    }

    private func colorFor(distanceM: Double) -> (Double, Double, Double) {
        var t = (distanceM - Self.nearM) / (Self.farM - Self.nearM)
        t = clamp(t, 0, 1)
        if t <= 0.5 {
            let u = t/0.5
            return (lerp(colorGreen.0, colorYellow.0, u), lerp(colorGreen.1, colorYellow.1, u), lerp(colorGreen.2, colorYellow.2, u))
        } else {
            let u = (t-0.5)/0.5
            return (lerp(colorYellow.0, colorRed.0, u), lerp(colorYellow.1, colorRed.1, u), lerp(colorYellow.2, colorRed.2, u))
        }
    }

    // ----- Per-frame animation -----
    private func tick() {
        if let h = smoothHeadingDeg {
            displayedYouAngle = lerpAngle(displayedYouAngle, h, arrowLerp)
        }
        if let t = targetDirection() {
            displayedTargetAngle = lerpAngle(displayedTargetAngle, t.angle, arrowLerp)
            targetVisible = true
            confidencePct = Int(t.confidence * 100)
        } else {
            targetVisible = false
            confidencePct = 0
        }
        if let d = smoothDistanceM {
            let target = colorFor(distanceM: d)
            displayedColorRGB = (
                lerp(displayedColorRGB.0, target.0, 0.15),
                lerp(displayedColorRGB.1, target.1, 0.15),
                lerp(displayedColorRGB.2, target.2, 0.15)
            )
            displayedColor = Color(red: displayedColorRGB.0/255, green: displayedColorRGB.1/255, blue: displayedColorRGB.2/255)
            displayedDistanceM = d
        }
    }
}

// ----- Small math helpers -----
func clamp(_ v: Double, _ a: Double, _ b: Double) -> Double { max(a, min(b, v)) }
func lerp(_ a: Double, _ b: Double, _ t: Double) -> Double { a + (b-a)*t }
func lerpAngle(_ a: Double, _ b: Double, _ t: Double) -> Double {
    let diff = ((b - a + 540).truncatingRemainder(dividingBy: 360)) - 180
    return (a + diff*t + 360).truncatingRemainder(dividingBy: 360)
}
