// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "Buoyient",
    platforms: [
        .iOS(.v15),
        .macOS(.v12),
    ],
    products: [
        .library(
            name: "Buoyient",
            targets: ["Buoyient"]
        ),
    ],
    targets: [
        // Binary target pointing to the XCFramework built by Gradle.
        //
        // For local development:
        //   1. Run `./gradlew :syncable-objects:assembleXCFramework`
        //   2. Reference this package locally in Xcode
        //
        // For distribution via GitHub release, replace `path` with `url` and `checksum`:
        //   .binaryTarget(
        //       name: "Buoyient",
        //       url: "https://github.com/lauren2020/buoyient/releases/download/v0.1.0/Buoyient.xcframework.zip",
        //       checksum: "<sha256-checksum>"
        //   ),
        .binaryTarget(
            name: "Buoyient",
            path: "syncable-objects/build/XCFrameworks/release/Buoyient.xcframework"
        ),
    ]
)
