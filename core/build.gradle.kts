plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    id("maven-publish")
    id("signing")
    alias(libs.plugins.nmcp)
}

group = property("GROUP") as String
version = property("VERSION_NAME") as String

kotlin {
    explicitApi()

    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "1.8"
            }
        }
        publishLibraryVariants("release")
    }

    jvm()

    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.ktor.client.core)
                implementation(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.datetime)
            }
        }

        val androidMain by getting

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
    }
}

android {
    namespace = "com.elvdev.buoyient.core"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
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
            name.set("core")
            description.set("Core infrastructure for buoyient: logging, connectivity, HTTP, and shared service abstractions.")
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
