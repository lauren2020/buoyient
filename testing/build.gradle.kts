plugins {
    alias(libs.plugins.kotlin.jvm)
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
    implementation(libs.ktor.client.mock)
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("data-buoy-testing")
                description.set("Test utilities for data-buoy: mock server, in-memory database, and test doubles.")
                url.set("https://github.com/les-corp/data-buoy")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                scm {
                    url.set("https://github.com/les-corp/data-buoy")
                }
            }
        }
    }
    repositories {
        mavenLocal()
    }
}
