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
            url: "https://github.com/lauren2020/buoyient/releases/download/v0.1.1/Buoyient.xcframework.zip",
            checksum: "b35be34c52efa0d038028fe94524cbcac68bd7f7e8a32925dce468b3c6781593"
        ),
        // BUOYIENT-BINARY-TARGET-END
    ]
)
