plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
    id("app.cash.sqldelight")
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

    // iOS targets disabled until Xcode is installed. Uncomment to re-enable:
    // iosX64()
    // iosArm64()
    // iosSimulatorArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-core:2.3.13")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
                implementation("app.cash.sqldelight:runtime:2.0.2")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
            }
        }

        val androidMain by getting {
            dependencies {
                implementation("io.ktor:ktor-client-okhttp:2.3.13")
                implementation("app.cash.sqldelight:android-driver:2.0.2")
                implementation("androidx.work:work-runtime-ktx:2.10.0")
                implementation("androidx.startup:startup-runtime:1.2.0")
            }
        }

        val jvmMain by getting {
            dependencies {
                implementation("app.cash.sqldelight:sqlite-driver:2.0.2")
                implementation("io.ktor:ktor-client-cio:2.3.13")
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("io.ktor:ktor-client-mock:2.3.13")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
                implementation(project(":testing"))
            }
        }

        // iOS source sets disabled until Xcode is installed. Uncomment to re-enable:
        // val iosX64Main by getting
        // val iosArm64Main by getting
        // val iosSimulatorArm64Main by getting
        // val iosMain by creating {
        //     dependsOn(commonMain)
        //     iosX64Main.dependsOn(this)
        //     iosArm64Main.dependsOn(this)
        //     iosSimulatorArm64Main.dependsOn(this)
        //     dependencies {
        //         implementation("io.ktor:ktor-client-darwin:2.3.13")
        //         implementation("app.cash.sqldelight:native-driver:2.0.2")
        //     }
        // }
    }
}

sqldelight {
    databases {
        create("SyncDatabase") {
            packageName.set("com.les.databuoy.db")
            dialect("app.cash.sqldelight:sqlite-3-30-dialect:2.0.2")
        }
    }
}

android {
    namespace = "com.les.databuoy"
    compileSdk = 34

    defaultConfig {
        minSdk = 27
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
}
