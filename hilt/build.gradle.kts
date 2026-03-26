plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    id("maven-publish")
    id("signing")
}

group = property("GROUP") as String
version = property("VERSION_NAME") as String

kotlin {
    explicitApi()
}

ksp {
    arg("correctErrorTypes", "true")
}

android {
    namespace = "com.les.databuoy.hilt"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
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
    signing {
        isRequired = !version.toString().endsWith("SNAPSHOT")
        sign(publishing.publications)
    }

    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                artifactId = "data-buoy-hilt"
                pom {
                    name.set("data-buoy-hilt")
                    description.set("Optional Hilt integration for data-buoy: auto-registers SyncableObjectService instances via @IntoSet multibinding.")
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
}
