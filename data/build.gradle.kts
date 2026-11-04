// :data, biblioteca de Android: la base local con Room. SQLCipher llega en K3 (ver data/.../TpvcaDatabase.kt).
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                // Robolectric toca FileDescriptor por dentro: en JDK 21+ hace falta abrirle java.base (en el 17 no estorba)
                it.jvmArgs(
                    "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--enable-native-access=ALL-UNNAMED",
                )
                // Las pruebas leen los mismos archivos de contracts/ que la PC
                it.systemProperty("tpvca.contracts", rootProject.layout.projectDirectory.dir("contracts").asFile.absolutePath)
            }
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

dependencies {
    api(project(":domain"))
    api(project(":sync"))

    api(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(testFixtures(project(":sync")))
}
