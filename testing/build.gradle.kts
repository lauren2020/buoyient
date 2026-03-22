plugins {
    kotlin("jvm")
    id("maven-publish")
}

group = "com.les.databuoy"
version = "0.1.0-SNAPSHOT"

dependencies {
    // Depend on the JVM variant of the KMP :library module.
    api(project(":library")) {
        attributes {
            attribute(
                org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute,
                org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm,
            )
        }
    }
    implementation("io.ktor:ktor-client-mock:2.3.13")
    implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}

publishing {
    repositories {
        mavenLocal()
    }
}
