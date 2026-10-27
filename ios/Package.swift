// swift-tools-version: 5.9
import PackageDescription

let package = Package(name: "OpenTVRemoteCore", platforms: [.macOS(.v13), .iOS(.v17)], products: [.library(name: "OpenTVRemoteCore", targets: ["OpenTVRemoteCore"])], targets: [.target(name: "OpenTVRemoteCore", path: "Core"), .testTarget(name: "OpenTVRemoteCoreTests", dependencies: ["OpenTVRemoteCore"], path: "Tests/CoreTests")])
