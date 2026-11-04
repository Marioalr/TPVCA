import java.util.Properties

// :app, la app de Android: una sola para la tablet (copia de la PC con interfaz táctil) y el celular.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Solo para la pantalla de desarrollo de las compilaciones de depuración: `tpvc.dev.url`, `tpvc.dev.token` y `tpvc.dev.deviceId` de
 * local.properties, que nunca se sube. La compilación de publicación no lleva estos campos ni la pantalla.
 */
val devProperties = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText.orNull?.let { load(it.reader()) }
}

fun devField(key: String): String = "\"" + devProperties.getProperty(key, "").replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "io.github.marioalr.tpvca"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "io.github.marioalr.tpvca"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            buildConfigField("String", "TPVC_DEV_URL", devField("tpvc.dev.url"))
            buildConfigField("String", "TPVC_DEV_TOKEN", devField("tpvc.dev.token"))
            buildConfigField("String", "TPVC_DEV_DEVICE_ID", devField("tpvc.dev.deviceId"))
        }
        release {
            // La firma de publicación se configura fuera del repositorio (nunca se suben llaves)
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // La interfaz es solo en español (es-MX)
        localeFilters += listOf("es")
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
            }
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkDependencies = true
    }
}

/** Solo `golden/link-qr.json` para la prueba en un equipo: los demás vectores llevan datos reales del médico y no hacen falta en ese APK. */
abstract class CopyLinkVector : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val source: RegularFileProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @TaskAction
    fun copy() {
        source.get().asFile.copyTo(output.get().file("link-qr.json").asFile, overwrite = true)
    }
}

val copyLinkVector = tasks.register<CopyLinkVector>("copyLinkVector") {
    source.set(rootProject.layout.projectDirectory.file("contracts/golden/link-qr.json"))
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.androidTest?.sources?.assets?.addGeneratedSourceDirectory(copyLinkVector, CopyLinkVector::output)
    }
}

dependencies {
    implementation(project(":sync"))
    implementation(project(":domain"))
    implementation(project(":data"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.okhttp)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode.scanning)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.windowsizeclass)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Lo que solo se comprueba en un equipo: ML Kit leyendo un QR como el de la PC (./gradlew :app:connectedDebugAndroidTest)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.zxing.core)
    androidTestImplementation(libs.androidx.work.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    // La que trae Compose no corre en Android 16 o más nuevo (`InputManager.getInstance` ya no existe)
    androidTestImplementation(libs.androidx.test.espresso.core)

    testImplementation(testFixtures(project(":sync")))
    testImplementation(libs.junit)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
