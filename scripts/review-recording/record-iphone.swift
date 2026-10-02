// Records a USB-connected iPhone's screen to a .mov file, as QuickTime's "New Movie
// Recording" does, for the App Review screen recording.
//
//   swift record-iphone.swift list               show capturable iOS devices
//   swift record-iphone.swift out.mov [seconds]  record until Ctrl-C (or for N seconds)
//
// macOS exposes an iPhone's screen as a capture device only after a process opts in to
// screen-capture devices through CoreMediaIO; the device then appears a moment later.
import AVFoundation
import CoreMediaIO
import Foundation

func allowScreenCaptureDevices() {
    var address = CMIOObjectPropertyAddress(
        mSelector: CMIOObjectPropertySelector(kCMIOHardwarePropertyAllowScreenCaptureDevices),
        mScope: CMIOObjectPropertyScope(kCMIOObjectPropertyScopeGlobal),
        mElement: CMIOObjectPropertyElement(kCMIOObjectPropertyElementMain))
    var allow: UInt32 = 1
    CMIOObjectSetPropertyData(CMIOObjectID(kCMIOObjectSystemObject), &address, 0, nil,
                              UInt32(MemoryLayout<UInt32>.size), &allow)
}

func iosDevices() -> [AVCaptureDevice] {
    AVCaptureDevice.DiscoverySession(deviceTypes: [.external], mediaType: .muxed, position: .unspecified)
        .devices.filter { $0.modelID.contains("iOS") || $0.localizedName.lowercased().contains("iphone") }
}

allowScreenCaptureDevices()
var devices: [AVCaptureDevice] = []
for _ in 0..<20 {
    devices = iosDevices()
    if !devices.isEmpty { break }
    RunLoop.current.run(until: Date().addingTimeInterval(0.5))
}

let args = CommandLine.arguments
if args.count < 2 || args[1] == "list" {
    if devices.isEmpty { print("No iPhone screen found. Is it unlocked, trusted and connected by cable?") }
    for d in devices { print("\(d.localizedName)  model=\(d.modelID)  id=\(d.uniqueID)") }
    exit(devices.isEmpty ? 1 : 0)
}

guard let device = devices.first else {
    FileHandle.standardError.write("No iPhone screen found.\n".data(using: .utf8)!)
    exit(1)
}
let outURL = URL(fileURLWithPath: args[1])
try? FileManager.default.removeItem(at: outURL)
let seconds = args.count > 2 ? Double(args[2]) : nil

final class Recorder: NSObject, AVCaptureFileOutputRecordingDelegate {
    let session = AVCaptureSession()
    let output = AVCaptureMovieFileOutput()
    var finished = false
    func fileOutput(_ o: AVCaptureFileOutput, didFinishRecordingTo url: URL, from c: [AVCaptureConnection], error: Error?) {
        if let error { print("Recording ended: \(error.localizedDescription)") }
        print("Saved \(url.path)")
        finished = true
    }
}

let recorder = Recorder()
do {
    let input = try AVCaptureDeviceInput(device: device)
    guard recorder.session.canAddInput(input), recorder.session.canAddOutput(recorder.output) else {
        print("Cannot attach the iPhone to a capture session."); exit(1)
    }
    recorder.session.addInput(input)
    recorder.session.addOutput(recorder.output)
} catch {
    print("Could not open the iPhone screen: \(error.localizedDescription)"); exit(1)
}
// macOS treats an iPhone's screen like a camera: the first run asks for permission.
if AVCaptureDevice.authorizationStatus(for: .video) != .authorized {
    print("macOS will ask to allow camera access for this tool; choose Allow.")
    var answered = false
    var granted = false
    AVCaptureDevice.requestAccess(for: .video) { ok in granted = ok; answered = true }
    while !answered { RunLoop.current.run(until: Date().addingTimeInterval(0.2)) }
    if !granted {
        print("Camera access was refused. Allow it in System Settings, Privacy & Security, Camera, then run again.")
        exit(1)
    }
}
recorder.session.startRunning()
// The iPhone needs a moment after the session starts before it delivers frames.
RunLoop.current.run(until: Date().addingTimeInterval(2))
recorder.output.startRecording(to: outURL, recordingDelegate: recorder)
print("Recording \(device.localizedName) to \(outURL.path). Press Ctrl-C to stop.")

signal(SIGINT, SIG_IGN)
let stop = DispatchSource.makeSignalSource(signal: SIGINT, queue: .main)
stop.setEventHandler { recorder.output.stopRecording() }
stop.resume()
if let seconds {
    DispatchQueue.main.asyncAfter(deadline: .now() + seconds) { recorder.output.stopRecording() }
}
while !recorder.finished { RunLoop.current.run(until: Date().addingTimeInterval(0.2)) }
recorder.session.stopRunning()
