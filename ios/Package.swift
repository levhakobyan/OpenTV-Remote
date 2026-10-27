// swift-tools-version: 5.9
import PackageDescription

let package = Package(name: "LumaCore", platforms: [.macOS(.v13), .iOS(.v17)], products: [.library(name: "LumaCore", targets: ["LumaCore"])], targets: [.target(name: "LumaCore", path: "Core"), .testTarget(name: "LumaCoreTests", dependencies: ["LumaCore"], path: "Tests/CoreTests")])
