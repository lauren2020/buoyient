plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    id("maven-publish")
    id("signing")
    alias(libs.plugins.nmcp)
}

group = property("GROUP") as String
version = property("VERSION_NAME") as String

kotlin {
    explicitApi()

    jvm()

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":mock-infra"))
                api(project(":syncable-objects"))
                implementation(libs.kotlinx.serialization.json)
            }
        }

        val jvmMain by getting

        val iosX64Main by getting
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosMain by creating {
            dependsOn(commonMain)
            iosX64Main.dependsOn(this)
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.kotlinx.coroutines.test)
            }
        }
    }
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
    publications.withType<MavenPublication> {
        val pubName = name
        artifact(tasks.register("${pubName}JavadocJar", Jar::class) {
            archiveClassifier.set("javadoc")
            archiveAppendix.set(pubName)
        })
        pom {
            name.set("syncable-objects-mock-mode")
            description.set("Mock mode builder for buoyient: run apps against fake server responses without a real backend.")
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
