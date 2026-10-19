// Extract JPEG frames from a screen recording (macOS, AVFoundation only; no ffmpeg needed).
// Usage: frames <video> <outDir> <stepSec> [startSec=0] [endSec=-1 (end)] [maxWidth=540]
// Output names: f_<seconds, 7.2>.jpg, so the file name is the video time.
import AVFoundation
import AppKit

let a = CommandLine.arguments
guard a.count >= 4, let step = Double(a[3]), step > 0 else {
    print("usage: frames <video> <outDir> <stepSec> [startSec] [endSec] [maxWidth]")
    exit(2)
}
let asset = AVURLAsset(url: URL(fileURLWithPath: a[1]))
let dur = CMTimeGetSeconds(asset.duration)
let start = a.count > 4 ? (Double(a[4]) ?? 0) : 0
let endArg = a.count > 5 ? (Double(a[5]) ?? -1) : -1
let maxW = a.count > 6 ? (Double(a[6]) ?? 540) : 540
let end = endArg < 0 ? dur : min(endArg, dur)
print("duration=\(dur)")

let gen = AVAssetImageGenerator(asset: asset)
gen.appliesPreferredTrackTransform = true
gen.requestedTimeToleranceBefore = CMTime(seconds: 0.04, preferredTimescale: 600)
gen.requestedTimeToleranceAfter = CMTime(seconds: 0.04, preferredTimescale: 600)
gen.maximumSize = CGSize(width: maxW, height: maxW * 2.4)
try? FileManager.default.createDirectory(atPath: a[2], withIntermediateDirectories: true)

var t = start
var n = 0
while t < end {
    if let cg = try? gen.copyCGImage(at: CMTime(seconds: t, preferredTimescale: 600), actualTime: nil),
       let data = NSBitmapImageRep(cgImage: cg).representation(using: .jpeg, properties: [.compressionFactor: 0.55]) {
        try? data.write(to: URL(fileURLWithPath: String(format: "%@/f_%07.2f.jpg", a[2], t)))
        n += 1
    }
    t += step
}
print("frames=\(n)")
