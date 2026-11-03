// :data, biblioteca de Android: Room + SQLCipher y sus DAO (desde K2). En K0 solo existe el módulo.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "io.github.marioalr.tpvca.data"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    api(project(":domain"))
    implementation(project(":sync"))
}
