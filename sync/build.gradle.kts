// :sync, Kotlin puro (JVM): contrato de sincronización, cliente Hrana y motor de lectura. Las pruebas de conformidad
// (testFixtures) las usa también :data con la base real de Room.
plugins {
    `java-test-fixtures`
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
    api(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    // El resumen derivado del expediente se recalcula con la regla portada
    implementation(project(":domain"))

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)

    testFixturesApi(libs.junit)
    testFixturesApi(libs.okhttp.mockwebserver)
    testFixturesApi(libs.kotlinx.serialization.json)
}

val contractsDir = rootProject.layout.projectDirectory.dir("contracts")

// El manifiesto incluido en la app (§7.3): el mismo contracts/sync/schema.json, sin copia a mano
tasks.processResources {
    from(contractsDir.file("sync/schema.json")) { into("tpvca/contracts") }
}

tasks.test {
    // Las pruebas leen los mismos archivos de contracts/ que la PC
    inputs.dir(contractsDir).withPathSensitivity(PathSensitivity.RELATIVE).withPropertyName("contracts")
    systemProperty("tpvca.contracts", contractsDir.asFile.absolutePath)
}
