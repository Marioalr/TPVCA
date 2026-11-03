// :sync, Kotlin puro (JVM): contrato de sincronización, y más adelante el cliente Hrana y el motor v2.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.lint)
}

// Bytecode de Java 17 con cualquier JDK 17 o más nuevo (la CI usa el 17)
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

val contractsDir = rootProject.layout.projectDirectory.dir("contracts")

tasks.test {
    // Las pruebas leen los mismos archivos de contracts/ que la PC
    inputs.dir(contractsDir).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("contracts")
    systemProperty("tpvca.contracts", contractsDir.asFile.absolutePath)
}
