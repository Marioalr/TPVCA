package io.github.marioalr.tpvca.data.security

import android.content.Context
import androidx.sqlite.db.SupportSQLiteOpenHelper
import io.github.marioalr.tpvca.data.RoomSyncStore
import io.github.marioalr.tpvca.data.TpvcaDatabase
import io.github.marioalr.tpvca.sync.engine.SyncStore
import io.github.marioalr.tpvca.sync.link.LocalDatabase
import java.io.File
import java.security.SecureRandom

/**
 * La base local y su llave (plan §4.8): una frase aleatoria de 32 bytes, envuelta por el Keystore, que pasa a [helperFactory]. En la app
 * [helperFactory] es la de SQLCipher ([io.github.marioalr.tpvca.data.SqlCipher]); en las pruebas de la JVM, la de SQLite.
 *
 * Si la llave no está o se perdió de verdad, lo que hay en la base ya no se puede leer: se borra y se empieza otra vez, que es bajar todo. Un error pasajero
 * del Keystore sube como excepción y no borra nada.
 */
class EncryptedLocalDatabase(
    context: Context,
    private val name: String,
    private val keyFile: WrappedFile,
    private val assetsDir: File,
    private val helperFactory: (ByteArray) -> SupportSQLiteOpenHelper.Factory,
    private val random: SecureRandom = SecureRandom(),
) : LocalDatabase {
    private val app = context.applicationContext
    private var database: TpvcaDatabase? = null
    private var store: RoomSyncStore? = null

    @Synchronized
    override fun store(): SyncStore = store ?: RoomSyncStore(open(), assetsDir).also { store = it }

    /** La base abierta, para las lecturas de las pantallas. Es la misma que usa el motor y se cierra al borrarse. */
    @Synchronized
    fun database(): TpvcaDatabase {
        store()
        return checkNotNull(database)
    }

    private fun open(): TpvcaDatabase {
        val opened = TpvcaDatabase.open(app, name, helperFactory(passphrase()))
        database = opened
        return opened
    }

    private fun passphrase(): ByteArray {
        val saved = try {
            keyFile.read()
        } catch (_: SecretUnreadableException) {
            null
        }
        if (saved != null) return saved
        // Una base sin su llave no se puede abrir: se borra antes de crear otra llave
        app.deleteDatabase(name)
        return ByteArray(PASSPHRASE_BYTES).also {
            random.nextBytes(it)
            keyFile.write(it)
        }
    }

    @Synchronized
    override fun erase() {
        database?.close()
        database = null
        store = null
        app.deleteDatabase(name)
        assetsDir.deleteRecursively()
        keyFile.destroy()
    }

    private companion object {
        const val PASSPHRASE_BYTES = 32
    }
}
