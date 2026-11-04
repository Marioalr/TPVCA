package io.github.marioalr.tpvca.data

import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/** La fábrica de SQLCipher para Room (plan §4.8). Solo corre en Android: la biblioteca nativa no existe en la JVM de las pruebas. */
object SqlCipher {
    private val loaded by lazy { System.loadLibrary("sqlcipher") }

    fun factory(passphrase: ByteArray): SupportSQLiteOpenHelper.Factory {
        loaded
        return SupportOpenHelperFactory(passphrase)
    }
}
