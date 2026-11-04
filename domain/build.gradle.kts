// :domain, Kotlin puro (JVM): reglas portadas de la PC, cada una comprobada con sus vectores de contracts/golden.
plugins {
    alias(libs.plugins.kotlin.jvm)
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
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.serialization.json)
}

val contractsDir = rootProject.layout.projectDirectory.dir("contracts")
val docsDir = rootProject.layout.projectDirectory.dir("docs")

tasks.test {
    inputs.dir(contractsDir).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("contracts")
    systemProperty("tpvca.contracts", contractsDir.asFile.absolutePath)
    // GoldenCoverageTest revisa que docs/REGLAS.md nombre cada vector
    inputs.file(docsDir.file("REGLAS.md")).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("reglas")
    systemProperty("tpvca.docs", docsDir.asFile.absolutePath)
}
