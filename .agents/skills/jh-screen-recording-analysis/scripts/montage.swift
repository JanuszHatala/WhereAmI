// Tile frames into one labelled contact sheet (macOS AppKit only).
// Usage: montage <out.jpg> <columns> <frame1.jpg> [frame2.jpg ...]
// Each tile is labelled with its file name (= video time when produced by `frames`).
import AppKit

let a = CommandLine.arguments
guard a.count >= 4, let cols = Int(a[2]), cols > 0 else {
    print("usage: montage <out.jpg> <columns> <frames...>")
    exit(2)
}
let files = Array(a[3...])
let imgs = files.compactMap { NSImage(contentsOfFile: $0) }
guard let first = imgs.first else { print("no readable images"); exit(1) }
let w = first.size.width, h = first.size.height
let rows = (imgs.count + cols - 1) / cols
let rep = NSBitmapImageRep(
    bitmapDataPlanes: nil, pixelsWide: Int(w) * cols, pixelsHigh: Int(h) * rows,
    bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
    colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0
)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
for (i, im) in imgs.enumerated() {
    let c = i % cols, r = i / cols
    let origin = NSPoint(x: CGFloat(c) * w, y: CGFloat(rows - 1 - r) * h)
    im.draw(in: NSRect(x: origin.x, y: origin.y, width: w, height: h))
    let label = (files[i] as NSString).lastPathComponent as NSString
    label.draw(
        at: NSPoint(x: origin.x + 4, y: origin.y + h - 24),
        withAttributes: [.font: NSFont.boldSystemFont(ofSize: 16), .foregroundColor: NSColor.red, .backgroundColor: NSColor.white]
    )
}
NSGraphicsContext.restoreGraphicsState()
try! rep.representation(using: .jpeg, properties: [.compressionFactor: 0.6])!.write(to: URL(fileURLWithPath: a[1]))
print("montage=\(a[1]) tiles=\(imgs.count)")
