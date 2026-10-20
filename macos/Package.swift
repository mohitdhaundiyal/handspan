// swift-tools-version:6.0
import PackageDescription

let package = Package(
    name: "Handspan",
    platforms: [.macOS(.v13)],
    products: [
        .library(name: "HandspanCore", targets: ["HandspanCore"]),
        .executable(name: "Handspan", targets: ["Handspan"]),
        .executable(name: "handspan-host", targets: ["HandspanHostCLI"]),
    ],
    targets: [
        .target(name: "HandspanCore", swiftSettings: [.swiftLanguageMode(.v5)]),
        .executableTarget(
            name: "Handspan",
            dependencies: ["HandspanCore"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .executableTarget(
            name: "HandspanHostCLI",
            dependencies: ["HandspanCore"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
        .testTarget(
            name: "HandspanCoreTests",
            dependencies: ["HandspanCore"],
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
    ]
)
