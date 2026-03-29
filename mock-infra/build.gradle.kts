plugins {
    alias(libs.plugins.kotlin.jvm)
    id("maven-publish")
    id("signing")
    alias(libs.plugins.nmcp)
}

group = property("GROUP") as String
version = property("VERSION_NAME") as String

kotlin {
    explicitApi()
}

dependencies {
    // compileOnly so the JVM variant doesn't leak into Android consumers' classpaths.
    // Consumers already depend on syncable-objects-android (or another platform variant)
    // which provides these classes at runtime.
    compileOnly(project(":syncable-objects")) {
        attributes {
            attribute(
                org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute,
                org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm,
            )
        }
    }
    // api so consumers get HttpClient on their classpath (needed for Buoyient.httpClient setter
    // and MockEndpointRouter.buildHttpClient())
    api(libs.ktor.client.mock)
    api(libs.kotlinx.serialization.json)

    testImplementation(project(":syncable-objects")) {
        attributes {
            attribute(
                org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.attribute,
                org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.jvm,
            )
        }
    }
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}

java {
    withSourcesJar()
    withJavadocJar()
}

signing {
    isRequired = !version.toString().endsWith("SNAPSHOT")
    val signingKey = findProperty("signingKey") as? String
    val signingPassword = findProperty("signingPassword") as? String
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
    sign(publishing.publications)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("syncable-objects-mock-infra")
                description.set("Shared mock infrastructure for buoyient: mock HTTP routing, stateful server store, and test doubles.")
                url.set("https://github.com/lauren2020/buoyient")
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
                    connection.set("scm:git:git://github.com/lauren2020/buoyient.git")
                    developerConnection.set("scm:git:ssh://github.com:lauren2020/buoyient.git")
                    url.set("https://github.com/lauren2020/buoyient")
                }
            }
        }
    }
}
