import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

// Explicit RGBX drawing avoids an unsupported three-channel AppKit bitmap context.
// No display, Retina scale, or ambient NSGraphicsContext is involved.
let root = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
let folder = root.appendingPathComponent("OpenTVRemote/Assets.xcassets/AppIcon.appiconset")
try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
let size = 1024
let context = CGContext(
  data: nil, width: size, height: size, bitsPerComponent: 8, bytesPerRow: size * 4,
  space: CGColorSpaceCreateDeviceRGB(), bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)!
let ink = CGColor(red: 0.06, green: 0.08, blue: 0.075, alpha: 1)
let accent = CGColor(red: 0.835, green: 0.965, blue: 0.604, alpha: 1)
context.setFillColor(ink)
context.fill(CGRect(x: 0, y: 0, width: size, height: size))
context.setFillColor(accent)
context.addPath(
  CGPath(
    roundedRect: CGRect(x: 318, y: 125, width: 388, height: 774), cornerWidth: 110,
    cornerHeight: 110, transform: nil))
context.fillPath()
context.setFillColor(ink)
context.fillEllipse(in: CGRect(x: 488, y: 777, width: 48, height: 48))
context.fillEllipse(in: CGRect(x: 395, y: 402, width: 234, height: 234))
context.setFillColor(accent)
context.fillEllipse(in: CGRect(x: 453, y: 460, width: 118, height: 118))
context.setFillColor(ink)
for x in [411, 535] {
  context.addPath(
    CGPath(
      roundedRect: CGRect(x: x, y: 253, width: 78, height: 42), cornerWidth: 15, cornerHeight: 15,
      transform: nil))
  context.fillPath()
}
// Guard against the original silent all-black output before replacing the asset.
let pixels = context.data!.assumingMemoryBound(to: UInt8.self)
precondition(pixels[(500 * size + 350) * 4 + 1] > 180, "Remote body was not drawn")
precondition(pixels[(20 * size + 20) * 4 + 1] < 80, "Background was not drawn")
let image = context.makeImage()!
let output = folder.appendingPathComponent("AppIcon.png")
let destination = CGImageDestinationCreateWithURL(
  output as CFURL, UTType.png.identifier as CFString, 1, nil)!
CGImageDestinationAddImage(destination, image, nil)
precondition(CGImageDestinationFinalize(destination), "PNG encoding failed")
let contents =
  #"{"images":[{"filename":"AppIcon.png","idiom":"universal","platform":"ios","size":"1024x1024"}],"info":{"author":"xcode","version":1}}"#
try contents.write(
  to: folder.appendingPathComponent("Contents.json"), atomically: true, encoding: .utf8)
