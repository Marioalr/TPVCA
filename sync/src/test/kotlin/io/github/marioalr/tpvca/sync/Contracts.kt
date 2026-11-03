package io.github.marioalr.tpvca.sync

import java.io.File

/** La carpeta `contracts/` del repositorio (Gradle la pasa en `tpvca.contracts`). */
internal object Contracts {
    val dir: File by lazy {
        val path = System.getProperty("tpvca.contracts")
            ?: error("Falta la propiedad tpvca.contracts: corra las pruebas con Gradle")
        File(path).also { require(it.isDirectory) { "No existe la carpeta de contratos $it" } }
    }

    fun file(relative: String): File = File(dir, relative).also { require(it.isFile) { "No existe $it" } }
}
