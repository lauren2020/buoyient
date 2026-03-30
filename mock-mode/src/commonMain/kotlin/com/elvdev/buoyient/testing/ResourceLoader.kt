package com.elvdev.buoyient.testing

/**
 * Loads the text content of a resource file by its path.
 *
 * On JVM, this loads from the classpath via the context class loader.
 * On iOS, this loads from the main bundle.
 *
 * @param path the resource path (e.g. "seeds/notes.json").
 * @return the file's text content.
 * @throws IllegalStateException if the resource is not found.
 */
internal expect fun loadResourceText(path: String): String
