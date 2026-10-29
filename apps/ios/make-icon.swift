// swift make-icon.swift Kalloq/Assets.xcassets/AppIcon.appiconset/AppIcon.png
// Renders docs/logo/kalloq.svg as a full-bleed, opaque 1024 px PNG (iOS rounds the corners itself).
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let size = 1024
let cs = CGColorSpaceCreateDeviceRGB()
let ctx = CGContext(data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: 0, space: cs,
                    bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
ctx.translateBy(x: 0, y: CGFloat(size)); ctx.scaleBy(x: 2, y: -2)  // SVG units, y down

func rgb(_ hex: UInt32) -> CGColor {
    CGColor(red: CGFloat(hex >> 16 & 255) / 255, green: CGFloat(hex >> 8 & 255) / 255, blue: CGFloat(hex & 255) / 255, alpha: 1)
}
let gradient = CGGradient(colorsSpace: cs, colors: [rgb(0x14B8A6), rgb(0x0E5A73)] as CFArray, locations: [0, 1])!
ctx.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: 512, y: 512), options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])

// Arc as points: center, radius, angles in degrees measured in y-down space.
func arc(_ c: CGPoint, _ r: CGFloat, from a0: CGFloat, to a1: CGFloat) -> [CGPoint] {
    (0...64).map { i in
        let a = (a0 + (a1 - a0) * CGFloat(i) / 64) * .pi / 180
        return CGPoint(x: c.x + r * cos(a), y: c.y + r * sin(a))
    }
}
func stroke(_ pts: [CGPoint], _ color: CGColor, _ width: CGFloat) {
    let p = CGMutablePath(); p.addLines(between: pts)
    ctx.addPath(p); ctx.setStrokeColor(color); ctx.setLineWidth(width); ctx.setLineCap(.round); ctx.setLineJoin(.round); ctx.strokePath()
}
let white = rgb(0xFFFFFF), teal = rgb(0x0F766E)
// shackle: up from (176,236), half circle over the top, down to (336,236)
stroke([CGPoint(x: 176, y: 236)] + arc(CGPoint(x: 256, y: 184), 80, from: 180, to: 360) + [CGPoint(x: 336, y: 236)], white, 40)
// lock body
ctx.addPath(CGPath(roundedRect: CGRect(x: 128, y: 224, width: 256, height: 192), cornerWidth: 40, cornerHeight: 40, transform: nil))
ctx.setFillColor(white); ctx.fillPath()
// voice: dot and two waves each side (chords of 60 and 104 at x = 292/318 and 220/194, centered on y = 320)
ctx.setFillColor(teal)
ctx.fillEllipse(in: CGRect(x: 240, y: 304, width: 32, height: 32))
for (x, r, half) in [(292.0, 44.0, 30.0), (318.0, 76.0, 52.0)] {
    let d = (r * r - half * half).squareRoot(), sweep = asin(half / r) * 180 / .pi
    stroke(arc(CGPoint(x: x - d, y: 320), r, from: -sweep, to: sweep), teal, 16)
    stroke(arc(CGPoint(x: 512 - x + d, y: 320), r, from: 180 - sweep, to: 180 + sweep), teal, 16)
}

let out = URL(fileURLWithPath: CommandLine.arguments[1])
let dest = CGImageDestinationCreateWithURL(out as CFURL, UTType.png.identifier as CFString, 1, nil)!
CGImageDestinationAddImage(dest, ctx.makeImage()!, nil)
CGImageDestinationFinalize(dest)
