package com.elvdev.buoyient.testing

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

@OptIn(ExperimentalForeignApi::class)
internal actual fun loadResourceText(path: String): String {
    // Split "seeds/notes.json" into directory "seeds" and filename "notes" with extension "json"
    val lastSlash = path.lastIndexOf('/')
    val directory = if (lastSlash >= 0) path.substring(0, lastSlash) else null
    val fileName = if (lastSlash >= 0) path.substring(lastSlash + 1) else path
    val dotIndex = fileName.lastIndexOf('.')
    val name = if (dotIndex >= 0) fileName.substring(0, dotIndex) else fileName
    val ext = if (dotIndex >= 0) fileName.substring(dotIndex + 1) else null

    val filePath = NSBundle.mainBundle.pathForResource(name, ext, directory)
        ?: error(
            "Seed file '$path' not found in the main bundle. " +
                "Add it to your Xcode project's resources."
        )

    return NSString.stringWithContentsOfFile(filePath, NSUTF8StringEncoding, null)
        ?: error("Failed to read seed file '$path' from bundle.")
}
