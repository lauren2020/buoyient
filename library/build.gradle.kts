plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
    id("maven-publish")
}

group = "com.les.databuoy"
version = "0.1.0-SNAPSHOT"

kotlin {
    androidTarget {
        compilations.all {
            kotlinOptions {
                jvmTarget = "1.8"
            }
        }
        publishLibraryVariants("release")
    }

    jvm()

    // iOS targets — requires full Xcode installation (not just Command Line Tools).
    // Uncomment when Xcode with iOS SDK is installed:
    // iosX64()
    // iosArm64()
    // iosSimulatorArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.ktor.client.core)
                implementation(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.serialization.json)
                implementation(libs.sqldelight.runtime)
                implementation(libs.kotlinx.datetime)
            }
        }

        val androidMain by getting {
            dependencies {
                implementation(libs.ktor.client.okhttp)
                implementation(libs.sqldelight.android.driver)
                implementation(libs.androidx.work)
                implementation(libs.androidx.startup)
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation(libs.sqldelight.sqlite.driver)
                implementation(libs.ktor.client.cio)
            }
        }

        val jvmTest by getting {
            kotlin.srcDir("../examples/todo/src/main/kotlin")
            kotlin.srcDir("../examples/todo/src/test/kotlin")
            dependencies {
                implementation(kotlin("test"))
                implementation(libs.ktor.client.mock)
                implementation(libs.kotlinx.coroutines.test)
                implementation(project(":testing"))
            }
        }

        // iOS source sets — uncomment together with iOS targets above:
        // val iosX64Main by getting
        // val iosArm64Main by getting
        // val iosSimulatorArm64Main by getting
        // val iosMain by creating {
        //     dependsOn(commonMain)
        //     iosX64Main.dependsOn(this)
        //     iosArm64Main.dependsOn(this)
        //     iosSimulatorArm64Main.dependsOn(this)
        //     dependencies {
        //         implementation(libs.ktor.client.darwin)
        //         implementation(libs.sqldelight.native.driver)
        //     }
        // }
    }
}

sqldelight {
    databases {
        create("SyncDatabase") {
            packageName.set("com.les.databuoy.db")
            dialect(libs.sqldelight.dialect)
        }
    }
}

android {
    namespace = "com.les.databuoy"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

publishing {
    repositories {
        mavenLocal()

        // Uncomment and configure when ready for remote publication:
        // maven {
        //     name = "GitHubPackages"
        //     url = uri("https://maven.pkg.github.com/OWNER/data-buoy")
        //     credentials {
        //         username = project.findProperty("gpr.user") as String?
        //             ?: System.getenv("GITHUB_ACTOR")
        //         password = project.findProperty("gpr.key") as String?
        //             ?: System.getenv("GITHUB_TOKEN")
        //     }
        // }
    }
    publications.withType<MavenPublication> {
        pom {
            name.set("data-buoy")
            description.set("Kotlin Multiplatform offline-first sync library with bidirectional sync, conflict resolution, and automatic retries.")
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
