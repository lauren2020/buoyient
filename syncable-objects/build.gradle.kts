import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.library)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.skie)
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

    val xcf = XCFramework("Buoyient")
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Buoyient"
            isStatic = true
            export(project(":core"))
            xcf.add(this)
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":core"))
                api(libs.ktor.client.core)
                implementation(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.serialization.json)
                implementation(libs.sqldelight.runtime)
                implementation(libs.sqldelight.coroutines.extensions)
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

        val iosX64Main by getting
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosMain by creating {
            dependsOn(commonMain)
            iosX64Main.dependsOn(this)
            iosArm64Main.dependsOn(this)
            iosSimulatorArm64Main.dependsOn(this)
            dependencies {
                implementation(libs.ktor.client.darwin)
                implementation(libs.sqldelight.native.driver)
            }
        }
    }
}

// Bundle agent instruction files into the published JAR so agents consuming the dependency can find them.
tasks.withType<Jar> {
    from(rootProject.file("CLAUDE.md")) { into("META-INF") }
    from(rootProject.file("CODEX.md")) { into("META-INF") }
}

skie {
    features {
        defaultArgumentInterop.enabled = true
    }
}

sqldelight {
    databases {
        create("SyncDatabase") {
            packageName.set("com.elvdev.buoyient.db")
            dialect(libs.sqldelight.dialect)
        }
    }
}

android {
    namespace = "com.elvdev.buoyient"
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
            name.set("syncable-objects")
            description.set("Kotlin Multiplatform offline-first sync library with bidirectional sync, conflict resolution, and automatic retries.")
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
