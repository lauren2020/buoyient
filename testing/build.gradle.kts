plugins {
    alias(libs.plugins.kotlin.jvm)
    id("maven-publish")
    id("signing")
}

group = property("GROUP") as String
version = property("VERSION_NAME") as String

kotlin {
    explicitApi()
}

dependencies {
    // Depend on the JVM variant of the KMP :data-buoy module.
    api(project(":data-buoy")) {
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

signing {
    isRequired = !version.toString().endsWith("SNAPSHOT")
    sign(publishing.publications)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("data-buoy-testing")
                description.set("Test utilities for data-buoy: mock server, in-memory database, and test doubles.")
                url.set("https://github.com/lauren2020/data-buoy")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("lauren2020")
                        name.set("Lauren Shultz")
                        email.set("lauren@elizabethvaildev.com")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/lauren2020/data-buoy.git")
                    developerConnection.set("scm:git:ssh://github.com:lauren2020/data-buoy.git")
                    url.set("https://github.com/lauren2020/data-buoy")
                }
            }
        }
    }
    repositories {
        mavenLocal()
        maven {
            name = "mavenCentral"
            url = if (version.toString().endsWith("SNAPSHOT")) {
                uri("https://s01.oss.sonatype.org/content/repositories/snapshots/")
            } else {
                uri("https://s01.oss.sonatype.org/service/local/staging/deploy/maven2/")
            }
            credentials {
                username = findProperty("mavenCentralUsername") as String? ?: ""
                password = findProperty("mavenCentralPassword") as String? ?: ""
            }
        }
    }
}
