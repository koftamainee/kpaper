import java.util.Base64
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val keystorePath = System.getenv("KEYSTORE_PATH")
val keystoreBase64 = System.getenv("KEYSTORE_BASE64")
val useReleaseKeystore = !keystorePath.isNullOrBlank() || !keystoreBase64.isNullOrBlank()

android {
    namespace = "com.koftamainee.kpaper"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.koftamainee.kpaper"
        minSdk = 26
        targetSdk = 36
        versionCode = (project.findProperty("versionCode") as String? ?: "1").toInt()
        versionName = project.findProperty("versionName") as String? ?: "0.1.0"
    }

    signingConfigs {
        create("release") {
            if (useReleaseKeystore) {
                val keystoreDir = File(
                    System.getenv("RUNNER_TEMP")
                        ?: (rootProject.layout.buildDirectory.asFile.get().absolutePath + "/keystore")
                )
                keystoreDir.mkdirs()
                val keystoreFile = keystorePath?.let { File(it) }
                    ?: File(keystoreDir, "kpaper-release.jks")
                        .also { f ->
                            f.writeBytes(
                                Base64.getDecoder().decode(keystoreBase64!!.replace("\\s".toRegex(), ""))
                            )
                        }
                storeFile = keystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (useReleaseKeystore) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
}
