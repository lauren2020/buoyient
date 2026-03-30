package com.elvdev.buoyient.testing

internal actual fun loadResourceText(path: String): String {
    val resourceUrl = Thread.currentThread().contextClassLoader
        ?.getResource(path)
        ?: error(
            "Seed file '$path' not found on the classpath. " +
                "Place it in src/main/resources/ or src/debug/resources/."
        )
    return resourceUrl.readText()
}
