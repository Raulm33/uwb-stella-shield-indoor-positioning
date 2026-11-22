import SwiftUI

struct RadarView: View {
    @ObservedObject var model: RadarModel

    var body: some View {
        GeometryReader { geo in
            let size = min(geo.size.width, geo.size.height)
            let R = size * 0.42

            Canvas { context, canvasSize in
                let cx = canvasSize.width / 2
                let cy = canvasSize.height / 2

                // Disc fill
                let discRect = CGRect(x: cx - R, y: cy - R, width: R*2, height: R*2)
                context.fill(Path(ellipseIn: discRect), with: .color(model.displayedColor))

                // Range rings
                for frac in [1.0/3, 2.0/3, 1.0] {
                    let r = R * frac
                    let ringRect = CGRect(x: cx - r, y: cy - r, width: r*2, height: r*2)
                    context.stroke(Path(ellipseIn: ringRect), with: .color(.white.opacity(0.10)), lineWidth: 1)
                }

                // Target arrow (anchor direction) - white, fades with confidence
                if model.targetVisible {
                    let alpha = 0.35 + 0.65 * (Double(model.confidencePct)/100.0)
                    drawArrow(context: context, cx: cx, cy: cy, R: R,
                              angleDeg: model.displayedTargetAngle,
                              color: .white.opacity(alpha), lengthFrac: 0.85, headSize: 9)
                }

                // "You" facing arrow - blue
                drawArrow(context: context, cx: cx, cy: cy, R: R,
                          angleDeg: model.displayedYouAngle,
                          color: Color(red: 0.29, green: 0.62, blue: 1.0), lengthFrac: 0.55, headSize: 7)

                // Center dot
                let dotR: CGFloat = 5
                context.fill(Path(ellipseIn: CGRect(x: cx-dotR, y: cy-dotR, width: dotR*2, height: dotR*2)),
                             with: .color(Color(white: 0.9)))

                // Compass labels
                drawLabel(context: context, cx: cx, cy: cy, text: "N", angleDeg: 0,   dist: R+14, size: 11, color: .gray)
                drawLabel(context: context, cx: cx, cy: cy, text: "S", angleDeg: 180, dist: R+18, size: 11, color: .gray)
                drawLabel(context: context, cx: cx, cy: cy, text: "E", angleDeg: 90,  dist: R+14, size: 11, color: .gray)
                drawLabel(context: context, cx: cx, cy: cy, text: "W", angleDeg: 270, dist: R+14, size: 11, color: .gray)
                drawLabel(context: context, cx: cx, cy: cy, text: "NE", angleDeg: 45,  dist: R-18, size: 10, color: .gray.opacity(0.7))
                drawLabel(context: context, cx: cx, cy: cy, text: "SE", angleDeg: 135, dist: R-18, size: 10, color: .gray.opacity(0.7))
                drawLabel(context: context, cx: cx, cy: cy, text: "SW", angleDeg: 225, dist: R-18, size: 10, color: .gray.opacity(0.7))
                drawLabel(context: context, cx: cx, cy: cy, text: "NW", angleDeg: 315, dist: R-18, size: 10, color: .gray.opacity(0.7))
            }
            .frame(width: size, height: size)
            .position(x: geo.size.width/2, y: geo.size.height/2)
        }
        .aspectRatio(1, contentMode: .fit)
        .background(Color(red: 0.067, green: 0.086, blue: 0.102))
        .clipShape(Circle())
        .overlay(Circle().stroke(Color(white: 0.15), lineWidth: 1))
    }

    private func drawArrow(context: GraphicsContext, cx: CGFloat, cy: CGFloat, R: CGFloat,
                            angleDeg: Double, color: Color, lengthFrac: CGFloat, headSize: CGFloat) {
        let rad = (angleDeg - 90) * .pi/180
        let len = R * lengthFrac
        let ax = cx + CGFloat(cos(rad)) * len
        let ay = cy + CGFloat(sin(rad)) * len

        var line = Path()
        line.move(to: CGPoint(x: cx, y: cy))
        line.addLine(to: CGPoint(x: ax, y: ay))
        context.stroke(line, with: .color(color), lineWidth: 3)

        var head = Path()
        let hx = CGFloat(cos(rad)), hy = CGFloat(sin(rad))
        let px = -hy, py = hx  // perpendicular
        let tip = CGPoint(x: ax + hx*headSize, y: ay + hy*headSize)
        let left = CGPoint(x: ax - hx*headSize*0.6 + px*headSize*0.6, y: ay - hy*headSize*0.6 + py*headSize*0.6)
        let right = CGPoint(x: ax - hx*headSize*0.6 - px*headSize*0.6, y: ay - hy*headSize*0.6 - py*headSize*0.6)
        head.move(to: tip); head.addLine(to: left); head.addLine(to: right); head.closeSubpath()
        context.fill(head, with: .color(color))
    }

    private func drawLabel(context: GraphicsContext, cx: CGFloat, cy: CGFloat, text: String,
                            angleDeg: Double, dist: CGFloat, size: CGFloat, color: Color) {
        let rad = (angleDeg - 90) * .pi/180
        let x = cx + CGFloat(cos(rad)) * dist
        let y = cy + CGFloat(sin(rad)) * dist
        context.draw(Text(text).font(.system(size: size)).foregroundColor(color), at: CGPoint(x: x, y: y))
    }
}
