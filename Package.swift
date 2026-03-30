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
        // For local development, replace the url/checksum target below with:
        //   .binaryTarget(
        //       name: "Buoyient",
        //       path: "syncable-objects/build/XCFrameworks/release/Buoyient.xcframework"
        //   ),
        // BUOYIENT-BINARY-TARGET-START
        .binaryTarget(
            name: "Buoyient",
            path: "syncable-objects/build/XCFrameworks/release/Buoyient.xcframework"
        ),
        // BUOYIENT-BINARY-TARGET-END
    ]
)
