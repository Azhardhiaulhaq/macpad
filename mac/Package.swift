// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "MacPad",
    platforms: [.macOS(.v14)],
    targets: [
        .target(name: "CGVirtualDisplayPrivate", path: "Sources/CGVirtualDisplayPrivate"),
        .executableTarget(
            name: "MacPad",
            dependencies: ["CGVirtualDisplayPrivate"],
            path: "Sources/MacPad",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
    ]
)
