// swift-tools-version: 5.9
import PackageDescription

/// The parts of Kalloq that are pure Swift: the end-to-end crypto layer (and later the signalling
/// client). Free of UIKit and CallKit so `swift test` runs the Android vectors on the Mac itself.
let package = Package(
    name: "KalloqCore",
    platforms: [.iOS(.v16), .macOS(.v13)],
    products: [
        .library(name: "KalloqCore", targets: ["KalloqCore"]),
    ],
    targets: [
        .target(name: "KalloqCore"),
        .testTarget(name: "KalloqCoreTests", dependencies: ["KalloqCore"]),
    ]
)
