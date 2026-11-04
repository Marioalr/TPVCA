package io.github.marioalr.tpvca.data.security

import io.github.marioalr.tpvca.sync.link.CredentialVault
import io.github.marioalr.tpvca.sync.link.DeviceCredential
import io.github.marioalr.tpvca.sync.link.StoredCredential

/** La credencial en un archivo envuelto con una llave del Keystore (plan §4.8): sin `EncryptedSharedPreferences`, que está en desuso. */
class EncryptedCredentialVault(private val file: WrappedFile) : CredentialVault {
    override fun load(): StoredCredential {
        // Sin archivo no se toca el Keystore
        if (!file.exists) return StoredCredential.Empty
        return try {
            val credential = file.read()?.let { DeviceCredential.fromJson(String(it, Charsets.UTF_8)) }
            credential?.let(StoredCredential::Present) ?: StoredCredential.Unreadable
        } catch (_: SecretUnreadableException) {
            StoredCredential.Unreadable
        }
    }

    override fun save(credential: DeviceCredential) = file.write(credential.toJson().toByteArray(Charsets.UTF_8))

    override fun clear() = file.destroy()
}
