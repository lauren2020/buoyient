plugins {
    id("com.android.library")
    kotlin("android")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
    id("maven-publish")
}

group = "com.les.databuoy"
version = "0.1.0-SNAPSHOT"

android {
    namespace = "com.example.sync.hilt"
    compileSdk = 34

    defaultConfig {
        minSdk = 27
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(project(":library"))
    implementation("com.google.dagger:hilt-android:2.51")
    ksp("com.google.dagger:hilt-compiler:2.51")
    implementation("androidx.startup:startup-runtime:1.1.1")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "data-buoy-hilt"
            }
        }
        repositories {
            mavenLocal()
        }
    }
}
