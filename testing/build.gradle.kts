plugins {
    kotlin("jvm")
    id("maven-publish")
}

group = "com.les.databuoy"
version = "0.1.0-SNAPSHOT"

dependencies {
    api(project(":library")) {
        // Exclude Android-specific transitive dependencies since this is a JVM module.
        // Consumers on Android will already have these from :library directly.
        targetConfiguration = "jvmRuntimeElements"
    }
    implementation("io.ktor:ktor-client-mock:2.3.12")
    implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
}

publishing {
    repositories {
        mavenLocal()
    }
}
