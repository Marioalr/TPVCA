package io.github.marioalr.tpvca.documents

import io.github.marioalr.tpvca.data.security.DataGate
import io.github.marioalr.tpvca.data.security.DataGateClosedException
import io.github.marioalr.tpvca.data.security.LabFileCache
import io.github.marioalr.tpvca.sync.hrana.HranaConnection
import io.github.marioalr.tpvca.sync.link.LabFetch
import io.github.marioalr.tpvca.sync.link.LabFileDownloader
import io.github.marioalr.tpvca.sync.link.LabFileRules

/**
 * Abrir un resultado: de la caché cifrada o, si no está, de la nube a pedido. Bloquea: corre en un hilo de fondo. **Nunca lanza:** un `ContentSha256` que no es un SHA-256, un Keystore que no
 * responde, un disco lleno o un borrado a la mitad terminan en un [LabFetch] con su mensaje. Si la caché falla se sigue sin ella: el archivo ya está en memoria.
 */
class LabFiles(private val cache: LabFileCache, private val gate: DataGate = DataGate(), private val connection: () -> HranaConnection?) {
    /** [ticket] es de antes de empezar: si hubo un borrado mientras bajaba, nada se escribe. */
    fun open(sha256: String, mimeType: String, ticket: Long = gate.ticket()): LabFetch {
        if (!LabFileRules.isSha256(sha256)) return LabFetch.Broken
        return try {
            val cached = try {
                gate.write(ticket) { cache.read(sha256) }
            } catch (e: DataGateClosedException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (cached != null) return LabFetch.Ok(cached, mimeType)
            val client = connection() ?: return LabFetch.Failed(null)
            val fetched = LabFileDownloader(client).download(sha256)
            if (fetched is LabFetch.Ok) {
                try {
                    gate.write(ticket) { cache.write(sha256, fetched.bytes) }
                } catch (e: DataGateClosedException) {
                    throw e
                } catch (_: Exception) {
                    // Disco lleno o Keystore ocupado: esta vez se ve sin guardarse
                }
            }
            fetched
        } catch (_: Exception) {
            // Un borrado a la mitad o una URL guardada que ya no sirve
            LabFetch.Failed(null)
        }
    }
}
