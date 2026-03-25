plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    id("maven-publish")
}

group = "com.les.databuoy"
version = "0.1.0-SNAPSHOT"

ksp {
    arg("correctErrorTypes", "true")
}

android {
    namespace = "com.les.databuoy.hilt"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
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
    implementation(project(":data-buoy"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.startup)
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "data-buoy-hilt"
                pom {
                    name.set("data-buoy-hilt")
                    description.set("Optional Hilt integration for data-buoy: auto-registers SyncableObjectService instances via @IntoSet multibinding.")
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
}
