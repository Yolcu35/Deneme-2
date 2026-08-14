plugins {
    id("com.android.library")
    kotlin("android")
    id("com.lagradost.cloudstream3.gradle")
}

cloudstream {
    setRepo(System.getenv("GITHUB_REPOSITORY") ?: "https://github.com/KullanciAdiniz/DepoAdiniz")
}

android {
    namespace = "com.aykut.setfilmizle"
    compileSdk = 35

    defaultConfig {
        minSdk = 21
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        }
    }
}

dependencies {
    implementation("com.github.recloudstream.cloudstream:library:-SNAPSHOT")
}
