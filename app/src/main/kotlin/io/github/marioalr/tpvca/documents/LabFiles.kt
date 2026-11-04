package io.github.marioalr.tpvca.documents

import io.github.marioalr.tpvca.data.security.LabFileCache
import io.github.marioalr.tpvca.sync.hrana.HranaConnection
import io.github.marioalr.tpvca.sync.link.LabFetch
import io.github.marioalr.tpvca.sync.link.LabFileDownloader

/** Abrir un resultado: de la caché cifrada o, si no está, de la nube a pedido. Bloquea: corre en un hilo de fondo. */
class LabFiles(private val cache: LabFileCache, private val connection: () -> HranaConnection?) {
    /** [LabFetch.Ok] con el archivo revisado contra su tamaño y su SHA-256. */
    fun open(sha256: String, mimeType: String): LabFetch {
        cache.read(sha256)?.let { return LabFetch.Ok(it, mimeType) }
        val client = connection() ?: return LabFetch.Failed(null)
        val fetched = LabFileDownloader(client).download(sha256)
        if (fetched is LabFetch.Ok) cache.write(sha256, fetched.bytes)
        return fetched
    }
}
