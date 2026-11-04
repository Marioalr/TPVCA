package io.github.marioalr.tpvca.sync.engine

import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.devices.DevicePermissions
import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.ProtocolDate
import io.github.marioalr.tpvca.sync.Statements
import io.github.marioalr.tpvca.sync.SyncContract
import io.github.marioalr.tpvca.sync.SyncRules
import io.github.marioalr.tpvca.sync.SyncSchema
import io.github.marioalr.tpvca.sync.SyncTable
import io.github.marioalr.tpvca.sync.hrana.HranaConnection
import io.github.marioalr.tpvca.sync.hrana.HranaValue
import io.github.marioalr.tpvca.sync.hrana.HranaException
import io.github.marioalr.tpvca.sync.hrana.ResultSet
import io.github.marioalr.tpvca.sync.hrana.Row
import io.github.marioalr.tpvca.sync.hrana.Statement
import io.github.marioalr.tpvca.sync.hrana.ValueCodec
import io.github.marioalr.tpvca.sync.hrana.text
import io.github.marioalr.tpvca.sync.rules.CanonicalJson
import io.github.marioalr.tpvca.sync.rules.PatientFieldStamps
import io.github.marioalr.tpvca.sync.rules.PatientMerge
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Quién pidió sincronizar: lo automático se detiene con la cuota agotada o el freno; lo que pide el médico sigue intentando. */
enum class SyncTrigger { AUTOMATIC, USER }

data class SyncEngineConfig(
    val deviceId: String,
    /** El `clinicId` de la credencial (K3). Sin él, se adopta el de la nube la primera vez. */
    val expectedClinicId: String? = null,
    /** Para las pruebas: un mismo tamaño de página en todas las tablas. */
    val pageSizeOverride: Int? = null,
    /** Cuántas instrucciones van en cada transacción de la subida (`PushChunkSize` de la PC). */
    val pushChunkSize: Int = 80,
    /** Los roles con los que este equipo trabaja. Una PC de prueba en los escenarios lleva el suyo. */
    val allowedRoles: Set<DeviceRole> = setOf(DeviceRole.AndroidTablet, DeviceRole.AndroidMobileAdmin),
)

/** El latido al final de una subida, con `LastPushUtc`: la marca de cambios que leen los demás (regla «Latido»). Lo arma quien tiene el nonce. */
interface PushHeartbeat {
    /** [pending] y [oldest]: lo que queda sin subir después de esta subida. */
    fun statements(pending: Int, oldest: String?): List<Statement>

    /** [before] es lo que leyó de su fila antes de escribirla. */
    fun onWritten(before: ResultSet)
}

/** Lo que tiene que pasar antes de borrar: desde [closing], nada nuevo entra a la cola. [reopen] si al final no se borró. */
interface EraseGuard {
    fun closing()

    fun reopen()

    companion object {
        val NONE = object : EraseGuard {
            override fun closing() = Unit

            override fun reopen() = Unit
        }
    }
}

/**
 * El motor (plan §7.3 y §7.5): primer arranque, descarga completa, bajada incremental, LWW, lápidas, filas apartadas, generación, freno, revocación y 401 y, desde K5, la subida: la cola de salida,
 * el desempate, la cerca de generación, la combinación de expedientes y «Volver a aplicar». **Ninguna instrucción es DDL.** Es la misma lógica que `TursoSyncService` de la PC, sin Android: corre en
 * la JVM con un [SyncStore] de prueba. Lo que escriben los casos de uso entra por [WriteEngine].
 */
class SyncEngine(
    private val connection: HranaConnection,
    private val store: SyncStore,
    private val config: SyncEngineConfig,
    private val schema: SyncSchema = SyncContract.bundledSchema,
    private val clock: Clock = Clock.systemUTC(),
    /** Un reloj que no cambia con la hora del equipo (`SystemClock.elapsedRealtime`), para los 10 min del 401. Vuelve a cero al reiniciar. */
    private val elapsedMillis: () -> Long = { clock.millis() },
    private val pushHeartbeat: PushHeartbeat? = null,
    private val eraseGuard: EraseGuard = EraseGuard.NONE,
) {
    private val writer = LocalWriter(store, { config.deviceId }, schema, clock)
    private val brake = ConsumptionBrake(store, clock)
    private val tombstonesTable = schema.tombstones.table
    private val allPages = schema.tables.map { it.table } + tombstonesTable
    private val failedAssets = HashSet<String>()

    /** *Reanudar* después del freno. */
    fun resumeAfterBrake() = brake.resume()

    /** Se pegó otro token: vuelve a intentar sin esperar y sin contar los 401 anteriores. */
    fun tokenReplaced() = store.apply(StoreChanges(metaRemoved = setOf(AUTH_FIRST_401, AUTH_FIRST_401_ELAPSED, AUTH_MALFORMED)))

    /** Corre una sincronización completa de principio a fin. Nunca lanza un error de Turso: lo regresa como resultado. */
    fun sync(trigger: SyncTrigger = SyncTrigger.AUTOMATIC, uploadIfRevoked: Boolean = true): SyncOutcome {
        pausedOutcome(trigger)?.let { return it }
        val run = Run()
        val outcome = try {
            when {
                store.meta(REVOKED_REASON) != null -> revokedCheck(run)
                store.meta(FIRST_DOWNLOAD_DONE) == null -> firstStart(run)
                else -> incremental(run)
            }
        } catch (stop: Stop) {
            stop.outcome
        } catch (e: HranaException) {
            outcomeOf(e)
        }.let { first -> if (uploadIfRevoked && first is SyncOutcome.Revoked && !first.wiped) drainRevoked(run, first) else first }
        if (run.rowsRead > 0 || run.rowsWritten > 0) brake.recordUsage(run.rowsRead, run.rowsWritten)
        store.apply(
            if (outcome is SyncOutcome.Completed) {
                StoreChanges(
                    meta = mapOf(LAST_SUCCESS to clock.instant().toString()),
                    metaRemoved = setOf(LAST_ERROR, QUOTA_RETRY_AT, AUTH_FIRST_401, AUTH_FIRST_401_ELAPSED, AUTH_MALFORMED),
                )
            } else {
                StoreChanges(meta = mapOf(LAST_ERROR to outcome.message.orEmpty()))
            },
        )
        return outcome
    }

    /** Lo automático no llega a Turso con la cuota agotada, el freno activo o el token rechazado. */
    private fun pausedOutcome(trigger: SyncTrigger): SyncOutcome? {
        if (trigger != SyncTrigger.AUTOMATIC) return null
        if (brake.isTripped) return brakedOutcome()
        val retryAt = store.meta(QUOTA_RETRY_AT)?.let(Instant::parse)
        if (retryAt != null && clock.instant().isBefore(retryAt)) return SyncOutcome.QuotaPaused(retryAt)
        if (store.meta(AUTH_MALFORMED) != null) return SyncOutcome.TokenMalformed
        if (store.meta(AUTH_FIRST_401) == null) return null
        // Con algo pendiente no se vuelve a intentar hasta tener token; sin nada, una vez más a los 10 min, para confirmar y borrarse
        val confirming = store.unsyncedChanges() == 0 && first401IsOldEnough()
        return if (confirming) null else SyncOutcome.TokenRejected(wiped = false)
    }

    /**
     * Con el freno puesto no se baja nada, pero una revocación no debe esperar a *Reanudar*: una sola lectura de su fila (§4.7) en cada
     * revisión. Si el token está en pausa no toca la red. Sin red o sin respuesta, sigue en pausa.
     */
    private fun brakedOutcome(): SyncOutcome {
        if (store.meta(AUTH_FIRST_401) != null || store.meta(AUTH_MALFORMED) != null) return SyncOutcome.BrakePaused
        return try {
            checkRegistry(connection.query(listOf(Statements.registryOwn(config.deviceId))).single())
            SyncOutcome.BrakePaused
        } catch (stop: Stop) {
            if (stop.outcome == SyncOutcome.NotApproved) SyncOutcome.BrakePaused else stop.outcome
        } catch (e: HranaException) {
            if (e.code == HranaException.UNAUTHORIZED || e.code == HranaException.TOKEN_MALFORMED) outcomeOf(e) else SyncOutcome.BrakePaused
        }
    }

    /**
     * Los 10 min entre dos 401 se miden con [elapsedMillis], que una hora adelantada no mueve. Después de reiniciar el equipo ese reloj vuelve a
     * cero y solo queda la hora normal: adelantarla y reiniciar sigue acortando la espera (`docs/SINCRONIZACION.md`).
     */
    private fun first401IsOldEnough(): Boolean {
        val started = store.meta(AUTH_FIRST_401_ELAPSED)?.toLongOrNull()
        val now = elapsedMillis()
        if (started != null && now >= started) return Duration.ofMillis(now - started) >= TOKEN_WIPE_AFTER
        val first = store.meta(AUTH_FIRST_401)?.let(Instant::parse) ?: return false
        return Duration.between(first, clock.instant()) >= TOKEN_WIPE_AFTER
    }

    private fun outcomeOf(e: HranaException): SyncOutcome = when (e.code) {
        HranaException.BLOCKED -> {
            val retryAt = clock.instant().plus(QUOTA_RETRY_INTERVAL)
            store.apply(StoreChanges(meta = mapOf(QUOTA_RETRY_AT to retryAt.toString())))
            SyncOutcome.QuotaPaused(retryAt)
        }
        HranaException.ARCHIVED -> SyncOutcome.Archived
        HranaException.UNAUTHORIZED -> onUnauthorized()
        HranaException.TOKEN_MALFORMED -> {
            store.apply(StoreChanges(meta = mapOf(AUTH_MALFORMED to clock.instant().toString())))
            SyncOutcome.TokenMalformed
        }
        else -> SyncOutcome.Failed(e.code, e.message.orEmpty())
    }

    /** §4.7: con algo pendiente se conserva todo; sin nada pendiente se borra tras dos 401 con 10 min de diferencia. */
    private fun onUnauthorized(): SyncOutcome {
        if (store.meta(AUTH_FIRST_401) == null) {
            store.apply(StoreChanges(meta = mapOf(AUTH_FIRST_401 to clock.instant().toString(), AUTH_FIRST_401_ELAPSED to elapsedMillis().toString())))
            return SyncOutcome.TokenRejected(wiped = false)
        }
        if (first401IsOldEnough() && wipeIfNothingPending()) return SyncOutcome.TokenRejected(wiped = true)
        return SyncOutcome.TokenRejected(wiped = false)
    }

    /**
     * Borrar solo con nada sin subir, revisado **con la escritura ya cerrada**: lo que se anotó justo antes cuenta, y después de esto nadie anota nada. Si apareció algo, se abre otra vez y se conserva todo.
     */
    private fun wipeIfNothingPending(): Boolean {
        eraseGuard.closing()
        if (store.unsyncedChanges() > 0) {
            eraseGuard.reopen()
            return false
        }
        store.wipeEverything()
        return true
    }

    // ---------------------------------------------------------------------------------------------------------------------------
    // Primer arranque
    // ---------------------------------------------------------------------------------------------------------------------------

    /** §7.3, en orden: claves, manifiesto, su fila de `DeviceRegistry`, marca de agua y descarga. Sin escribir nada en la nube. */
    private fun firstStart(run: Run): SyncOutcome {
        checkManifestAndIdentity(run)
        checkRegistry(run.query(listOf(Statements.registryOwn(config.deviceId))).single())
        return download(run)
    }

    private fun checkManifestAndIdentity(run: Run) {
        val meta = try {
            run.query(listOf(Statements.metaAll)).single().rows.associate { it.text("Key").orEmpty() to it.text("Value") }
        } catch (e: HranaException) {
            // Una base recién creada: sin `_tpvc_meta`. Esta app nunca la crea
            if (e.message.orEmpty().contains("no such table", ignoreCase = true)) throw Stop(SyncOutcome.SchemaMissing)
            throw e
        }
        if (meta["schema"].isNullOrEmpty()) throw Stop(SyncOutcome.SchemaMissing)
        val minimum = meta["minClientProtocol"]?.toIntOrNull()
        if (minimum != null && minimum > SyncContract.CLIENT_PROTOCOL) throw Stop(SyncOutcome.ClientOutdated(minimum))
        val missing = listOf("clinicId", "fullUploadAtUtc", "generationReady").filter { meta[it].isNullOrEmpty() }
        if (missing.isNotEmpty()) throw Stop(SyncOutcome.CloudNotReady(missing))
        checkClinic(meta["clinicId"])

        val diff = ManifestDiff.compare(schema, meta["manifest"])
        store.apply(
            StoreChanges(
                meta = buildMap {
                    put(MANIFEST_CONTRACT, SyncContract.REVISION.toString())
                put(CLOUD_SCHEMA_SEEN, meta["schema"].orEmpty())
                    if (!diff.isEmpty) put(READ_ONLY, diff.describe())
                },
                metaRemoved = if (diff.isEmpty) setOf(READ_ONLY) else emptySet(),
            ),
        )
        run.manifest = diff
    }

    /** La huella de la nube cambió desde el último manifiesto: se vuelve a leer, y el solo lectura se pone o se quita con lo que diga. */
    private fun recheckManifest(run: Run, version: ResultSet) {
        val schemaNow = version.rows.firstOrNull()?.text("Value")
        if (store.meta(CLOUD_SCHEMA_SEEN) != schemaNow) checkManifestAndIdentity(run)
    }

    private fun checkClinic(cloudClinicId: String?) {
        val local = config.expectedClinicId ?: store.meta(CLINIC_ID)
        if (cloudClinicId.isNullOrEmpty()) {
            // Este equipo ya es de un consultorio: una nube sin identidad no decide nada sobre él
            if (local != null) throw Stop(SyncOutcome.CloudNotReady(listOf("clinicId")))
            return
        }
        if (local != null && !local.equals(cloudClinicId, ignoreCase = true)) throw Stop(SyncOutcome.OtherCloud(cloudClinicId, local))
        if (store.meta(CLINIC_ID) == null) store.apply(StoreChanges(meta = mapOf(CLINIC_ID to cloudClinicId)))
    }

    /** Sin fila o con `RevokedAtUtc`, no baja datos (regla «Equipos» de `schema.json`). */
    private fun checkRegistry(result: ResultSet) {
        val row = result.rows.firstOrNull() ?: run {
            // Sin fila ya no hay un rol confirmado: las escrituras se detienen hasta volver a leerlo
            forgetRole()
            throw Stop(SyncOutcome.NotApproved)
        }
        val code = row.text("DeviceCode")
        // Rechazar deja una fila con código X y ya revocada: basta con cualquiera de las dos para no bajar nada
        val rejected = code?.startsWith(REJECTED_CODE_PREFIX) == true
        if (!row.text("RevokedAtUtc").isNullOrEmpty() || rejected) {
            val reason = row.text("RevokedReason") ?: if (rejected) SyncOutcome.Revoked.REASON_REJECTED else null
            // "Perdido" borra sin subir nada. Lo demás, solo sin nada pendiente: si no, se guarda que está revocado (sobrevive a reiniciar sin red) y `drainRevoked` lo intenta subir
            if (reason == SyncOutcome.Revoked.REASON_LOST) {
                eraseGuard.closing()
                store.wipeEverything()
                throw Stop(SyncOutcome.Revoked(reason, wiped = true))
            }
            if (wipeIfNothingPending()) throw Stop(SyncOutcome.Revoked(reason, wiped = true))
            store.apply(StoreChanges(meta = mapOf(REVOKED_REASON to (reason ?: SyncOutcome.Revoked.REASON_REMOVED))))
            throw Stop(SyncOutcome.Revoked(reason, wiped = false))
        }
        if (store.meta(REVOKED_REASON) != null) store.apply(StoreChanges(metaRemoved = setOf(REVOKED_REASON)))
        // §7.3: el rol sale de su fila, como el nombre del enum de la PC. Es la autoridad: la PC puede aprobar con el rol que no se pidió
        val roleName = row.text("Role")
        val role = DeviceRole.fromName(roleName)
        if (role !in config.allowedRoles) {
            forgetRole()
            throw Stop(SyncOutcome.WrongRole(roleName))
        }
        store.apply(StoreChanges(meta = buildMap {
            put(DEVICE_ROLE, role!!.name)
            if (code != null) put(DEVICE_CODE, code)
        }))
    }

    private fun forgetRole() {
        if (store.meta(DEVICE_ROLE) != null) store.apply(StoreChanges(metaRemoved = setOf(DEVICE_ROLE)))
    }

    /** Revocado y con algo pendiente: solo su fila, nada de bajar. Si cambió a perdido, `checkRegistry` borra. */
    private fun revokedCheck(run: Run): SyncOutcome {
        checkRegistry(run.query(listOf(Statements.registryOwn(config.deviceId))).single())
        // Ya no está revocado (no debería pasar: la PC no regresa una fila revocada): sigue como siempre
        return if (store.meta(FIRST_DOWNLOAD_DONE) == null) firstStart(run) else incremental(run)
    }

    /**
     * Quitado con algo pendiente (§4.7): sube lo que pueda y, si ya no queda nada, se borra. Rechazado no sube: un equipo sin aprobar no pudo anotar nada, y si algo hay, se conserva para exportarlo. Si no se
     * puede subir, sigue como está y el médico puede exportarlo a un archivo. Nunca se borra aquí con algo pendiente.
     */
    private fun drainRevoked(run: Run, revoked: SyncOutcome.Revoked): SyncOutcome {
        if (revoked.reason != null && revoked.reason != SyncOutcome.Revoked.REASON_REMOVED) return revoked
        if (store.pendingChanges() == 0) return if (store.unsyncedChanges() == 0 && wipeIfNothingPending()) revoked.copy(wiped = true) else revoked
        return try {
            val before = clock.instant()
            val handshake = Handshake.parse(run.query(listOf(Statements.handshake)).single())
            checkCloud(run, handshake, before)
            when (generationState(handshake)) {
                // Otra generación: lo pendiente ya no le toca a esa nube; se guarda y se puede exportar
                GenerationState.Same -> Unit
                else -> {
                    stashPending(store.meta(GENERATION) ?: normalizeGeneration(handshake.generationPrevious))
                    return revoked
                }
            }
            run.generation = normalizeGeneration(handshake.generation)
            if (store.meta(READ_ONLY) != null) return revoked
            push(run)
            if (wipeIfNothingPending()) revoked.copy(wiped = true) else revoked
        } catch (_: Stop) {
            revoked
        } catch (_: HranaException) {
            revoked
        }
    }

    /**
     * La descarga completa, o la que sigue después de un corte: la hora y el perfil en una petición y luego una tabla por petición, en
     * el orden de `schema.json`, sin omitir lo propio. Una descarga cortada sigue desde sus cursores y no vuelve a contar para el freno.
     */
    private fun download(run: Run): SyncOutcome {
        if (store.meta(FULL_IN_PROGRESS) == null && !brake.allowFullPull()) throw Stop(SyncOutcome.BrakePaused)

        val before = clock.instant()
        val results = run.query(listOf(Statements.handshake, Statements.profile))
        val handshake = Handshake.parse(results[0])
        checkCloud(run, handshake, before)
        when (generationState(handshake)) {
            GenerationState.Same -> Unit
            GenerationState.Pending -> {
                stashPending(store.meta(GENERATION) ?: normalizeGeneration(handshake.generationPrevious))
                throw Stop(SyncOutcome.GenerationPending)
            }
            GenerationState.Replaced -> {
                // Lo que había bajado era de la nube anterior. Otra generación no se mezcla con esto
                stashPending(store.meta(GENERATION) ?: normalizeGeneration(handshake.generationPrevious))
                store.clearSyncedData(KEPT_META_ON_CLEAR)
                run.allPatients = true
            }
        }
        adoptGeneration(run, handshake)

        if (store.meta(FULL_IN_PROGRESS) == null) {
            brake.recordFullPull()
            store.apply(StoreChanges(meta = mapOf(FULL_IN_PROGRESS to "1")))
        }
        run.allPatients = true
        pullEverything(run, handshake.watermark, firstPages = null, skipOwnRows = false)
        finish(run, results[1].rows.firstOrNull())
        store.apply(StoreChanges(meta = mapOf(FIRST_DOWNLOAD_DONE to clock.instant().toString()), metaRemoved = setOf(FULL_IN_PROGRESS)))
        return completed(run, fullDownload = true)
    }

    // ---------------------------------------------------------------------------------------------------------------------------
    // Bajada incremental
    // ---------------------------------------------------------------------------------------------------------------------------

    /**
     * Una sola petición (8.6): su fila de `DeviceRegistry` (§7.3: en cada sincronización) y, en una foto, la hora, la primera página de
     * las 13 tablas y de las lápidas y el perfil. Solo se piden más páginas de las tablas que llenaron la suya.
     */
    private fun incremental(run: Run): SyncOutcome {
        if (store.meta(MANIFEST_CONTRACT) != SyncContract.REVISION.toString()) checkManifestAndIdentity(run)
        else run.manifest = store.meta(READ_ONLY)?.let { ManifestDiff(listOf(it), emptyList()) }

        val own = Statements.registryOwn(config.deviceId)
        // Con algo que subir: su fila antes que nada ("perdido" borra sin subir nada) y la hora y la generación antes de escribir
        if (hasPendingPush()) pushFirst(run, own)?.let { return it }

        val cursors = loadCursors()
        val pages = allPages.map { pageStatement(it, cursors.getValue(it), skipOwnRows = true, pageSize(run, it)) }
        val before = clock.instant()

        var firstPages: Map<String, List<Row>>? = null
        val registry: ResultSet
        val version: ResultSet
        val handshake: Handshake
        var profile: Row?
        try {
            val snapshot = run.snapshot(listOf(own, Statements.metaSchema), listOf(Statements.handshake) + pages + Statements.profile)
            registry = snapshot.before[0]
            version = snapshot.before[1]
            handshake = Handshake.parse(snapshot.statements.first())
            profile = snapshot.statements.last().rows.firstOrNull()
            firstPages = allPages.withIndex().associate { (i, table) -> table to snapshot.statements[i + 1].rows }
        } catch (e: HranaException) {
            if (!e.isTooLargeOrSlow) throw e
            // Demasiado para una sola respuesta: como la descarga completa, una tabla por petición
            return incrementalTableByTable(run, own)
        }

        // Primero de quién es esta nube: una ajena no aprueba, no revoca ni recibe un latido
        checkClinic(handshake.clinicId)
        checkRegistry(registry)
        recheckManifest(run, version)
        checkCloud(run, handshake, before)
        when (generationState(handshake)) {
            GenerationState.Same -> Unit
            GenerationState.Pending -> {
                stashPending(store.meta(GENERATION) ?: normalizeGeneration(handshake.generationPrevious))
                throw Stop(SyncOutcome.GenerationPending)
            }
            GenerationState.Replaced -> return reloadGeneration(run, handshake)
        }
        adoptGeneration(run, handshake)
        pullEverything(run, handshake.watermark, firstPages, skipOwnRows = true)
        finish(run, profile)
        // Lo que una combinación dejó distinto de la nube sube en esta misma sincronización, una sola vez
        if (run.mergedForUpload > 0) {
            run.mergedForUpload = 0
            if (store.meta(READ_ONLY) == null) push(run)
        }
        noteArrivals(run)
        return completed(run, fullDownload = false)
    }

    private fun incrementalTableByTable(run: Run, own: Statement): SyncOutcome {
        val before = clock.instant()
        val results = run.query(listOf(own, Statements.handshake, Statements.profile))
        val handshake = Handshake.parse(results[1])
        checkClinic(handshake.clinicId)
        checkRegistry(results[0])
        checkCloud(run, handshake, before)
        when (generationState(handshake)) {
            GenerationState.Same -> Unit
            GenerationState.Pending -> {
                stashPending(store.meta(GENERATION) ?: normalizeGeneration(handshake.generationPrevious))
                throw Stop(SyncOutcome.GenerationPending)
            }
            GenerationState.Replaced -> return reloadGeneration(run, handshake)
        }
        adoptGeneration(run, handshake)
        pullEverything(run, handshake.watermark, firstPages = null, skipOwnRows = true)
        finish(run, results[2].rows.firstOrNull())
        // Lo que una combinación dejó distinto de la nube sube en esta misma sincronización, una sola vez
        if (run.mergedForUpload > 0) {
            run.mergedForUpload = 0
            if (store.meta(READ_ONLY) == null) push(run)
        }
        noteArrivals(run)
        return completed(run, fullDownload = false)
    }

    /**
     * Otra PC reemplazó la nube y ya terminó (8.9): lo que no se había subido se guarda para «Volver a aplicar», se vacía y baja todo una sola vez, en esta misma sincronización.
     */
    private fun reloadGeneration(run: Run, handshake: Handshake): SyncOutcome {
        stashPending(store.meta(GENERATION) ?: normalizeGeneration(handshake.generationPrevious))
        if (!brake.allowFullPull()) throw Stop(SyncOutcome.BrakePaused)
        store.clearSyncedData(KEPT_META_ON_CLEAR)
        adoptGeneration(run, handshake)
        return download(run)
    }

    // ---------------------------------------------------------------------------------------------------------------------------
    // Hora, identidad y generación
    // ---------------------------------------------------------------------------------------------------------------------------

    private fun checkCloud(run: Run, handshake: Handshake, sent: Instant) {
        if (handshake.watermark.isBlank()) throw HranaException(HranaException.BAD_RESPONSE, "Turso no regresó su hora para la marca de agua.")
        handshake.minClientProtocol?.let { minimum ->
            if (minimum > SyncContract.CLIENT_PROTOCOL) throw Stop(SyncOutcome.ClientOutdated(minimum))
        }
        checkClinic(handshake.clinicId)
        measureClock(run, handshake, sent)
    }

    /** Desfase: la hora del servidor contra la de aquí a la mitad del viaje (8.7). Las marcas locales de K5 lo usan. */
    private fun measureClock(run: Run, handshake: Handshake, sent: Instant) {
        val server = ProtocolDate.parse(handshake.now) ?: return
        val serverInstant = server.toInstant(java.time.ZoneOffset.UTC)
        val received = clock.instant()
        val middle = sent.plus(Duration.between(sent, received).dividedBy(2))
        val offset = Duration.between(middle, serverInstant).toMillis()
        val previous = store.meta(CLOCK_OFFSET_MS)?.toLongOrNull()
        if (previous == null || kotlin.math.abs(previous - offset) > CLOCK_OFFSET_TOLERANCE_MS) {
            store.apply(StoreChanges(meta = mapOf(CLOCK_OFFSET_MS to offset.toString())))
        }
        if (Duration.ofMillis(offset).abs() > CLOCK_WARNING) {
            run.clockWarning = "La hora de este equipo difiere ${Duration.ofMillis(offset).abs().toMinutes()} min de la nube."
        }
        // Una marca en el futuro impediría aplicar las ediciones de los demás; se repara con la hora del servidor, sin subir nada
        val limit = ProtocolDate.format(server.plus(CLOCK_WARNING))
        val repaired = store.repairFutureStamps(schema.tables.map { it.table }, limit, ProtocolDate.format(server))
        if (repaired > 0) repairFieldStamps(limit, ProtocolDate.format(server))
    }

    /** Una marca por campo nunca vale más que su fila, pero una que se queda en el futuro subiría con cada edición posterior de otro campo. */
    private fun repairFieldStamps(limit: String, now: String) {
        val writes = store.select(PatientSummaryRecalculator.PATIENTS, listOf("SyncId", "FieldStampsJson")).mapNotNull { row ->
            val stamps = PatientFieldStamps.parse(row["FieldStampsJson"] as String?)
            val future = stamps.all.filter { (_, value) -> value > limit && PatientFieldStamps.parseStamp(value) != null }.keys
            if (future.isEmpty()) return@mapNotNull null
            future.forEach { stamps[it] = now }
            RowWrite(PatientSummaryRecalculator.PATIENTS, row["SyncId"] as String, mapOf("FieldStampsJson" to stamps.toJson()), isNew = false)
        }
        if (writes.isNotEmpty()) store.apply(StoreChanges(writes = writes))
    }

    private enum class GenerationState { Same, Pending, Replaced }

    /** `CheckGenerationAsync` de la PC. Un equipo sin generación adopta la de la nube solo si nunca se reemplazó. */
    private fun generationState(handshake: Handshake): GenerationState {
        val cloud = normalizeGeneration(handshake.generation)
        val local = store.meta(GENERATION)
        if (cloud == null || local == cloud) return GenerationState.Same
        val neverReplaced = local == null && normalizeGeneration(handshake.generationPrevious) == null
        val ready = cloud == handshake.generationReady
        if (neverReplaced && ready) return GenerationState.Same
        return if (ready) GenerationState.Replaced else GenerationState.Pending
    }

    private fun adoptGeneration(run: Run, handshake: Handshake) {
        val cloud = normalizeGeneration(handshake.generation)
        run.generation = cloud
        if (cloud != null && store.meta(GENERATION) != cloud) store.apply(StoreChanges(meta = mapOf(GENERATION to cloud)))
    }

    private fun normalizeGeneration(value: String?): String? = value?.takeUnless { it.isBlank() || it == NO_GENERATION }

    // ---------------------------------------------------------------------------------------------------------------------------
    // Páginas
    // ---------------------------------------------------------------------------------------------------------------------------

    private fun loadCursors(): Map<String, PullCursor> {
        val saved = store.metaByPrefix(CURSOR_PREFIX)
        return allPages.associateWith { PullCursor.parse(saved[CURSOR_PREFIX + it]) ?: PullCursor.START }
    }

    private fun pullEverything(run: Run, watermark: String, firstPages: Map<String, List<Row>>?, skipOwnRows: Boolean) {
        val cursors = loadCursors()
        val upTo = PullCursor(watermark, "")
        val idle = LinkedHashMap<String, String>()
        for (table in schema.tables) {
            pullTable(run, table.table, cursors.getValue(table.table), firstPages?.get(table.table), upTo, skipOwnRows, idle) { page, meta ->
                applyRows(run, table, page, meta, dead = emptySet())
            }
        }
        pullTable(run, tombstonesTable, cursors.getValue(tombstonesTable), firstPages?.get(tombstonesTable), upTo, skipOwnRows, idle) { page, meta ->
            applyTombstones(run, page.map(::tombstoneOf), meta)
        }
        // Las tablas sin nada nuevo avanzan hasta la marca de agua, todas en una escritura
        if (idle.isNotEmpty()) store.apply(StoreChanges(meta = idle))
        retryParked(run)
    }

    /** `PullTableAsync`: el cursor guardado nunca pasa de la marca de agua; se sigue leyendo desde lo último que llegó. */
    private fun pullTable(
        run: Run,
        table: String,
        from: PullCursor,
        firstPage: List<Row>?,
        upTo: PullCursor,
        skipOwnRows: Boolean,
        idle: MutableMap<String, String>,
        apply: (List<Row>, Map<String, String>) -> Unit,
    ) {
        var position = from
        var page = firstPage
        val key = CURSOR_PREFIX + table
        while (true) {
            val rows = page ?: readPage(run, table, position, skipOwnRows)
            val size = pageSize(run, table)
            val full = rows.size >= size
            rows.lastOrNull()?.let { last -> position = PullCursor(last.text("SyncedAtUtc").orEmpty(), last.text("SyncId").orEmpty()) }

            // Lo aplicado, sin pasar de la marca de agua: algo escrito justo antes de ella puede llegar todavía
            val cursor = if (full) minOf(position, upTo) else upTo
            if (rows.isNotEmpty()) {
                apply(rows, mapOf(key to cursor.serialize()))
                idle.remove(key)
            } else {
                idle[key] = cursor.serialize()
            }
            if (!full) return
            page = null
        }
    }

    /** Una página y la generación. Demasiado grande o tardada: la mitad de filas, hasta 10. */
    private fun readPage(run: Run, table: String, from: PullCursor, skipOwnRows: Boolean): List<Row> {
        while (true) {
            val size = pageSize(run, table)
            try {
                val results = run.query(listOf(pageStatement(table, from, skipOwnRows, size), Statements.generation))
                if (normalizeGeneration(results[1].rows.firstOrNull()?.text("generation")) != run.generation) {
                    throw Stop(SyncOutcome.GenerationChanged)
                }
                return results[0].rows
            } catch (e: HranaException) {
                if (!e.isTooLargeOrSlow || size <= MIN_PAGE_SIZE) throw e
                run.pageSizes[table] = maxOf(MIN_PAGE_SIZE, size / 2)
            }
        }
    }

    private fun pageStatement(table: String, from: PullCursor, skipOwnRows: Boolean, size: Int): Statement =
        Statements.page(table, size, skipOwnRows, from.syncedAtUtc, from.key, config.deviceId)

    private fun pageSize(run: Run, table: String): Int = run.pageSizes[table] ?: config.pageSizeOverride ?: defaultPageSize(table)

    // ---------------------------------------------------------------------------------------------------------------------------
    // Aplicar
    // ---------------------------------------------------------------------------------------------------------------------------

    /**
     * `ApplyPageGenericAsync`: LWW por fila, y una fila cuyo principal todavía no existe aquí se aparta en la misma transacción. Las
     * referencias sin principal con lápida ([dead]): una opcional queda en nulo y sin la obligatoria la fila se descarta.
     * Un expediente que ya existe aquí no sigue el LWW por fila: se combina por campo, y lo que queda distinto de la nube se anota para subir en la misma transacción.
     * [afterReject]: desempate, se aplica también con la misma marca. [force]: «Volver a aplicar», sin importar la marca.
     */
    private fun applyRows(
        run: Run,
        table: SyncTable,
        rows: List<Row>,
        meta: Map<String, String>,
        dead: Set<Pair<String, String>>,
        afterReject: Boolean = false,
        force: Boolean = false,
    ) = store.transaction {
        val incoming = rows.mapNotNull { row -> row.text("SyncId")?.lowercase(java.util.Locale.ROOT)?.takeIf { it.isNotEmpty() }?.let { it to row } }
        val ids = incoming.map { it.first }
        val stamps = store.stamps(table.table, ids)
        val parked = store.parkedRows()
            .filter { it.direction == ParkDirection.PULL && it.entityType == table.entityType }
            .map { it.syncId }.toSet()
        val principals = table.references.associateWith { reference ->
            val principalTable = schema.tableOf(reference.principal)
            val wanted = incoming.mapNotNull { (_, row) -> referenceId(row, reference.column) }.toSet()
            if (principalTable == null || wanted.isEmpty()) emptySet() else store.existing(principalTable.table, wanted)
        }

        val writes = ArrayList<RowWrite>()
        val park = ArrayList<ParkedRow>()
        val unpark = ArrayList<Triple<ParkDirection, String, String>>()
        val marks = ArrayList<OutboxMark>()
        val mergePatients = table.table == PatientSummaryRecalculator.PATIENTS && !force
        // Lo que aquí se borró, aunque no haya subido: una fila que no es más nueva que su lápida no revive
        val absent = ids.filter { it !in stamps }
        val deletedHere = if (force || absent.isEmpty()) emptyMap() else store.select(tombstonesTable, listOf("EntityType", "SyncId", "DeletedAtUtc"), "SyncId", absent)
            .filter { it["EntityType"] == table.entityType }.associate { it["SyncId"] as String to it["DeletedAtUtc"] as String }
        for ((syncId, row) in incoming) {
            val remoteStamp = ProtocolDate.canonical(row.text("UpdatedAtUtc"))
            val local = stamps[syncId]
            val parkedHere = syncId in parked
            val key = Triple(ParkDirection.PULL, table.entityType, syncId)
            if (mergePatients && local != null) {
                if (parkedHere) unpark += key
                mergePatient(run, table, syncId, row)?.let { (write, mark) ->
                    writes += write
                    mark?.let { marks += it }
                    run.touch(table.table, syncId, row)
                    run.arrivedPatients += syncId
                }
                continue
            }
            // La misma regla que la nube (`DeletedAtUtc >= marca`, `golden/lww.json`): con la misma marca gana el borrado
            val deletedAt = deletedHere[syncId]
            if (deletedAt != null && remoteStamp <= deletedAt) {
                if (parkedHere) unpark += key
                continue
            }
            // Una versión apartada con la misma marca es la misma versión: se aplica al reintentar
            val wins = force || (if (afterReject) SyncRules.serverRowAppliesAfterReject(local, remoteStamp) else SyncRules.remoteRowWins(local, remoteStamp)) || (parkedHere && local == remoteStamp)
            if (!wins) {
                if (parkedHere) unpark += key
                continue
            }

            var parkReason: String? = null
            var drop = false
            val nulled = ArrayList<String>()
            for (reference in table.references) {
                val principal = referenceId(row, reference.column)
                if (principal == null) {
                    if (reference.required) {
                        drop = true
                        break
                    }
                    continue
                }
                if (principal in principals.getValue(reference)) continue
                if (reference.principal to principal in dead) {
                    if (reference.required) {
                        drop = true
                        break
                    }
                    nulled += reference.column
                } else {
                    parkReason = "Todavía no llega a este equipo su ${reference.principal} $principal."
                    break
                }
            }
            if (parkReason != null) {
                // «Volver a aplicar» no aparta: sin aplicar, su copia guardada se queda
                if (force) continue
                park += ParkedRow(ParkDirection.PULL, table.entityType, syncId, RowMapper.serialize(row), parkReason)
                continue
            }
            if (parkedHere) unpark += key
            if (drop) continue

            val isNew = local == null
            val values = if (isNew) RowMapper.newRow(table, row) else RowMapper.update(table, row)
            writes += RowWrite(table.table, syncId, values + nulled.associateWith { null } + ("UpdatedAtUtc" to remoteStamp), isNew)
            run.touch(table.table, syncId, row)
            if (force) run.forceApplied += syncId
            if (!force && table.table == PatientSummaryRecalculator.PATIENTS) run.arrivedPatients += syncId
            if (!force && table.table == PatientSummaryRecalculator.APPOINTMENTS) run.arrivedAppointments += syncId
        }
        store.apply(StoreChanges(writes = writes, park = park, unpark = unpark, meta = meta, outbox = marks))
        run.applied += writes.size
        run.mergedForUpload += marks.size
    }

    /** `PatientFieldMerge`: la fila de aquí contra la de la nube. `null` si no cambia nada. Sin marcas nuevas para lo que ya tenía la nube. */
    private fun mergePatient(run: Run, table: SyncTable, syncId: String, row: Row): Pair<RowWrite, OutboxMark?>? {
        val localRow = writer.find(table, syncId) ?: return null
        val columns = LocalWriter.patientColumns(table)
        val remoteLocal = columns.filter { it.name in row }.associate { it.name to RowMapper.localValue(it, row[it.name]) } +
            ("UpdatedAtUtc" to ProtocolDate.canonical(row.text("UpdatedAtUtc"))) + ("FieldStampsJson" to row.text("FieldStampsJson")) +
            ("BirthDate" to (if ("BirthDate" in row) RowMapper.localValue(table.column("BirthDate")!!, row["BirthDate"]) else localRow["BirthDate"]))
        val merged = PatientMerge.merge(LocalWriter.patientVersion(table, localRow, syncId, clock), LocalWriter.patientVersion(table, remoteLocal, syncId, clock).let { remote ->
            // Una columna que la nube no tiene cuenta como la de aquí
            PatientMerge.Version(syncId, remote.updatedAtUtc, remote.fieldStampsJson, remote.values.filterKeys { it in row })
        }, columns.map { it.name })
        if (!merged.differsFromLocal && merged.updatedAtUtc == localRow["UpdatedAtUtc"] && merged.fieldStampsJson == localRow["FieldStampsJson"]) return null
        val values = LinkedHashMap<String, Any?>()
        for (column in columns) {
            when (merged.sources[column.name]) {
                PatientMerge.Source.REMOTE -> if (column.name in row) values[column.name] = RowMapper.localValue(column, row[column.name])
                PatientMerge.Source.MERGED -> values[column.name] = CanonicalJson.asString(merged.values[column.name]).orEmpty()
                else -> Unit
            }
        }
        values["FieldStampsJson"] = merged.fieldStampsJson
        values["UpdatedAtUtc"] = merged.updatedAtUtc
        run.applied++
        // Un equipo que no puede escribir expedientes nunca los anota: se queda con lo combinado aquí, y la tablet o la PC, que sí pueden, lo hacen converger
        val mayUpload = DeviceRole.fromName(store.meta(DEVICE_ROLE))?.let(DevicePermissions::canManageClinicalRecords) == true
        return RowWrite(table.table, syncId, values, isNew = false) to (if (merged.differsFromRemote && mayUpload) writer.mark(table, syncId) else null)
    }

    private fun referenceId(row: Row, column: String): String? =
        row.text(column)?.lowercase(java.util.Locale.ROOT)?.takeIf { it.isNotEmpty() && it != ValueCodec.ZERO_GUID }

    private fun tombstoneOf(row: Row) = TombstoneRow(
        entityType = row.text("EntityType").orEmpty(),
        syncId = row.text("SyncId").orEmpty().lowercase(java.util.Locale.ROOT),
        deletedAtUtc = ProtocolDate.canonical(row.text("DeletedAtUtc")),
        syncedAtUtc = row.text("SyncedAtUtc").orEmpty(),
        originDeviceId = row.text("OriginDeviceId"),
    )

    /**
     * `ApplyTombstonesAsync`: se borra salvo que la local sea estrictamente más nueva, con lo que haría cada llave foránea de la PC
     * (cascada, a nulo). Una `restrict` con dependientes aparta la lápida hasta que se vayan.
     */
    private fun applyTombstones(run: Run, tombstones: List<TombstoneRow>, meta: Map<String, String>) = store.transaction {
        val parked = store.parkedRows().filter { it.direction == ParkDirection.TOMBSTONE }.map { it.entityType to it.syncId }.toSet()
        val records = ArrayList<TombstoneRow>()
        val unpark = ArrayList<Triple<ParkDirection, String, String>>()
        val park = ArrayList<ParkedRow>()
        for (tombstone in tombstones) {
            val table = schema.tableOf(tombstone.entityType) ?: continue
            records += tombstone
            val key = Triple(ParkDirection.TOMBSTONE, tombstone.entityType, tombstone.syncId)
            val local = store.stamps(table.table, listOf(tombstone.syncId))[tombstone.syncId]
            if (!SyncRules.remoteTombstoneDeletes(local, tombstone.deletedAtUtc)) {
                if (tombstone.entityType to tombstone.syncId in parked) unpark += key
                continue
            }
            val plan = DeletePlan()
            if (!writer.planDelete(table, setOf(tombstone.syncId), plan)) {
                park += ParkedRow(ParkDirection.TOMBSTONE, tombstone.entityType, tombstone.syncId, tombstone.deletedAtUtc, "Todavía tiene registros que dependen de él.")
                continue
            }
            run.touchDeleted(store, plan.deletes)
            store.apply(
                StoreChanges(
                    deletes = plan.deletes,
                    nullify = plan.nullify,
                    unpark = if (tombstone.entityType to tombstone.syncId in parked) listOf(key) else emptyList(),
                    // Su propia lápida va con el borrado, para no perderla si se corta aquí
                    tombstones = listOf(tombstone),
                ),
            )
            run.applied++
        }
        store.apply(StoreChanges(tombstones = records, park = park, unpark = unpark, meta = meta))
    }

    /**
     * `RetryParkedPullAsync`: lo apartado se vuelve a intentar con lo que ya llegó. Qué principales se borraron lo dicen las lápidas que
     * ya bajaron aquí; una lápida más nueva que la marca de agua llega en la siguiente bajada, y la fila sigue apartada mientras tanto.
     */
    private fun retryParked(run: Run) {
        val parked = store.parkedRows()
        if (parked.isEmpty()) return
        val rowsByType = parked.filter { it.direction == ParkDirection.PULL }
            .groupBy({ it.entityType }, { RowMapper.deserialize(it.payload) })
            .mapValues { (entityType, rows) -> dropDeletedWhileParked(entityType, rows) }

        val dead = HashSet<Pair<String, String>>()
        for (table in schema.tables) {
            val rows = rowsByType[table.entityType] ?: continue
            for (reference in table.references) {
                val principalTable = schema.tableOf(reference.principal) ?: continue
                val wanted = rows.mapNotNull { referenceId(it, reference.column) }.toSet()
                val missing = wanted - store.existing(principalTable.table, wanted)
                if (missing.isEmpty()) continue
                store.tombstoned(reference.principal, missing).forEach { dead += reference.principal to it }
            }
        }
        // Filas en el orden de dependencias: un principal apartado se aplica antes que sus dependientes
        for (table in schema.tables) {
            val rows = rowsByType[table.entityType] ?: continue
            applyRows(run, table, rows, emptyMap(), dead)
        }
        val tombstones = parked.filter { it.direction == ParkDirection.TOMBSTONE }
            .map { TombstoneRow(it.entityType, it.syncId, it.payload, "", null) }
        if (tombstones.isNotEmpty()) applyTombstones(run, tombstones, emptyMap())
    }

    /** Una fila apartada que se borró mientras esperaba, con una lápida igual o más nueva, no revive: sale de las apartadas. */
    private fun dropDeletedWhileParked(entityType: String, rows: List<Row>): List<Row> {
        val ids = rows.mapNotNull { it.text("SyncId")?.lowercase(java.util.Locale.ROOT) }
        val deleted = store.select(tombstonesTable, listOf("EntityType", "SyncId", "DeletedAtUtc"), "SyncId", ids)
            .filter { it["EntityType"] == entityType }
            .associate { it["SyncId"] as String to it["DeletedAtUtc"] as String }
        val (gone, kept) = rows.partition { row ->
            val deletedAt = deleted[row.text("SyncId")?.lowercase(java.util.Locale.ROOT)]
            deletedAt != null && deletedAt >= ProtocolDate.canonical(row.text("UpdatedAtUtc"))
        }
        if (gone.isNotEmpty()) {
            val keys = gone.map { Triple(ParkDirection.PULL, entityType, it.text("SyncId")!!.lowercase(java.util.Locale.ROOT)) }
            store.apply(StoreChanges(unpark = keys))
        }
        return kept
    }

    // ---------------------------------------------------------------------------------------------------------------------------
    // Subir (K5)
    // ---------------------------------------------------------------------------------------------------------------------------

    private fun hasPendingPush(): Boolean = store.pendingChanges() > 0

    /**
     * Antes de subir, en una petición: su fila y la hora con las claves. Regresa un resultado si hay que detenerse aquí (otra generación ya lista: esa recarga termina la sincronización).
     * En solo lectura (el manifiesto no es el de la app) la cola se conserva y no sube nada.
     */
    private fun pushFirst(run: Run, own: Statement): SyncOutcome? {
        val before = clock.instant()
        val results = run.query(listOf(own, Statements.handshake, Statements.metaSchema))
        val handshake = Handshake.parse(results[1])
        checkClinic(handshake.clinicId)
        checkRegistry(results[0])
        recheckManifest(run, results[2])
        checkCloud(run, handshake, before)
        when (generationState(handshake)) {
            GenerationState.Same -> Unit
            GenerationState.Pending -> {
                stashPending(store.meta(GENERATION) ?: normalizeGeneration(handshake.generationPrevious))
                throw Stop(SyncOutcome.GenerationPending)
            }
            GenerationState.Replaced -> return reloadGeneration(run, handshake)
        }
        adoptGeneration(run, handshake)
        if (store.meta(READ_ONLY) == null) push(run)
        return null
    }

    /** [table] es `null` en una lápida y en su `DELETE`. */
    private class PushStatement(val statement: Statement, val table: SyncTable?, val entityType: String, val syncId: String)

    /** Lo que sube en esta vuelta. [toSeq]: lo que sale de la cola si todo sube. */
    private class PushPlan(
        val toSeq: Long,
        val groups: List<List<PushStatement>>,
        val parkNow: MutableMap<Pair<String, String>, String>,
        val included: Set<Pair<String, String>>,
        val parkedBefore: List<ParkedRow>,
    )

    /**
     * Una sola foto de la cola, sus filas y sus lápidas: tabla por tabla en el orden del contrato, cada fila que todavía existe aquí completa y la que ya no, como su lápida más reciente. Una fila con
     * referencia obligatoria y borrado en cascada a otra que también sube va en su grupo (la venta con sus renglones): un corte no parte una operación.
     */
    private fun planPush(generation: String?): PushPlan = store.transaction {
        val queue = store.outbox()
        val parkedBefore = store.parkedRows().filter { it.direction == ParkDirection.PUSH }
        val keys = queue.map { it.entityType to it.syncId }.toMutableSet()
        parkedBefore.forEach { keys += it.entityType to it.syncId }
        val generationValue = generation?.let<String, HranaValue>(HranaValue::Text) ?: HranaValue.Null
        val groups = ArrayList<MutableList<PushStatement>>()
        val groupOf = HashMap<Pair<String, String>, MutableList<PushStatement>>()
        val parkNow = LinkedHashMap<Pair<String, String>, String>()
        val included = LinkedHashSet<Pair<String, String>>()
        val found = HashSet<Pair<String, String>>()
        val device = HranaValue.Text(config.deviceId)

        for (table in schema.tables) {
            val ids = keys.filter { it.first == table.entityType }.map { it.second }
            if (ids.isEmpty()) continue
            val rows = store.select(table.table, table.columns.map { it.name }, "SyncId", ids)
            val sql = Statements.upsertSql(table)
            val columns = Statements.dataColumns(table)
            val existing = table.references.associateWith { reference ->
                val principal = schema.tableOf(reference.principal)
                val wanted = rows.mapNotNull { it[reference.column] as String? }.toSet()
                if (principal == null || wanted.isEmpty()) emptySet() else store.existing(principal.table, wanted)
            }
            for (row in rows) {
                val syncId = row["SyncId"] as String
                val key = table.entityType to syncId
                found += key
                var missing: String? = null
                var owner: MutableList<PushStatement>? = null
                for (reference in table.references) {
                    val principalId = row[reference.column] as String?
                    if (reference.required && (principalId == null || principalId !in existing.getValue(reference))) {
                        missing = "le falta ${reference.principal}"
                        break
                    }
                    if (owner == null && principalId != null && reference.required && reference.onDelete == "cascade") owner = groupOf[reference.principal to principalId]
                }
                if (missing != null) {
                    parkNow[key] = "No se pudo subir: $missing."
                    continue
                }
                included += key
                val stamp = HranaValue.Text(row["UpdatedAtUtc"] as String)
                val id = HranaValue.Text(syncId)
                val args = buildList {
                    add(id)
                    add(stamp)
                    add(device)
                    columns.forEach { add(RowMapper.remoteValue(row[it.name])) }
                    add(HranaValue.Text(table.entityType))
                    add(id)
                    add(stamp)
                    add(generationValue)
                }
                val group = owner ?: ArrayList<PushStatement>().also { groups += it }
                group += PushStatement(Statement(sql, args), table, table.entityType, syncId)
                groupOf[key] = group
            }
        }

        // Lo anotado que ya no existe aquí sube como su lápida más reciente
        val gone = (keys - found).groupBy({ it.first }, { it.second })
        for ((entityType, ids) in gone) {
            val table = schema.tableOf(entityType)
            val tombstones = store.select(tombstonesTable, listOf("EntityType", "SyncId", "DeletedAtUtc"), "SyncId", ids).filter { it["EntityType"] == entityType }
            for (tombstone in tombstones) {
                val syncId = tombstone["SyncId"] as String
                val deletedAt = HranaValue.Text(tombstone["DeletedAtUtc"] as String)
                val id = HranaValue.Text(syncId)
                val group = arrayListOf(PushStatement(Statement(Statements.TOMBSTONE_UPSERT, listOf(HranaValue.Text(entityType), id, deletedAt, device, generationValue)), null, entityType, syncId))
                if (table != null) group += PushStatement(Statement(Statements.deleteTombstonedSql(table.table), listOf(id, deletedAt, generationValue)), null, entityType, syncId)
                groups += group
            }
        }
        PushPlan(queue.maxOfOrNull { it.seq } ?: 0L, groups, parkNow, included, parkedBefore)
    }

    /**
     * Cada lote en una transacción de la nube, terminando con la generación: si otra PC reemplazó la nube, la cerca no dejó escribir nada y se detiene **sin tocar la cola**.
     *
     * **La cola se vacía al final, en una sola transacción local con el desempate:** en la red va subir, latido y releer (el orden de `tie-conflict.jsonl`), y hasta tener lo releído nada sale de la cola. Un 401 o
     * que el proceso muera en medio solo vuelve a mandar todo; la nube descarta lo que ya tenía. Lo que se editó mientras tanto tiene un número mayor y se queda.
     */
    private fun push(run: Run) {
        val plan = planPush(run.generation)
        val rejected = ArrayList<Pair<SyncTable, String>>()
        val statements = sendInBatches(run, plan, rejected)

        if (statements > 0) writePushHeartbeat(run, plan)
        val reread = if (rejected.isEmpty()) null else readRejected(run, rejected)

        val keepParked = plan.parkNow.map { (key, reason) -> ParkedRow(ParkDirection.PUSH, key.first, key.second, "", reason) }
        val unpark = plan.parkedBefore.filter { (it.entityType to it.syncId) !in plan.parkNow }.map { Triple(ParkDirection.PUSH, it.entityType, it.syncId) }
        store.transaction {
            store.apply(StoreChanges(outboxClearedUpTo = plan.toSeq, park = keepParked, unpark = unpark))
            reread?.let { applyRejected(run, it) }
        }
    }

    /**
     * Lotes de [SyncEngineConfig.pushChunkSize] instrucciones. Ante `RESPONSE_TOO_LARGE`, `TIMEOUT` o HTTP 413, como al bajar: la mitad, hasta un grupo por petición; un grupo que no pasa solo se manda fila por fila
     * (la operación deja de ir junta, pero en la nube no hay llaves foráneas y es lo único que deja avanzar). Una fila que no pasa sola **se aparta y sigue pendiente**: no se tira y no detiene la bajada. Se vuelve a
     * intentar en cada subida, ya de una en una mientras haya algo apartado por esto.
     */
    private fun sendInBatches(run: Run, plan: PushPlan, rejected: MutableList<Pair<SyncTable, String>>): Int {
        var size = if (plan.parkedBefore.any { it.reason == TOO_BIG_TO_PUSH }) 1 else config.pushChunkSize
        val queue = ArrayDeque(plan.groups)
        var sent = 0
        while (queue.isNotEmpty()) {
            var taken = 1
            var count = queue.first().size
            while (taken < queue.size && count + queue[taken].size <= size) count += queue[taken++].size
            val chunk = queue.take(taken).flatten()
            try {
                val results = connection.snapshot(emptyList(), chunk.map { it.statement } + Statements.generation).statements
                run.rowsRead += 1
                if (normalizeGeneration(results.last().rows.firstOrNull()?.text("generation")) != run.generation) throw Stop(SyncOutcome.GenerationChanged)
                chunk.forEachIndexed { index, push ->
                    run.rowsWritten += results[index].affectedRows
                    run.pushed += results[index].affectedRows.toInt()
                    if (push.table != null && results[index].affectedRows == 0L) rejected += push.table to push.syncId
                }
                sent += chunk.size
                repeat(taken) { queue.removeFirst() }
            } catch (e: HranaException) {
                if (!e.isTooLargeOrSlow) throw e
                when {
                    taken > 1 -> size = maxOf(1, minOf(size, count) / 2)
                    // Una lápida con su `DELETE` no se parte
                    chunk.count { it.table != null } > 1 -> {
                        queue.removeFirst()
                        chunk.asReversed().forEach { queue.addFirst(listOf(it)) }
                    }
                    else -> {
                        queue.removeFirst()
                        val only = chunk.first()
                        plan.parkNow[only.entityType to only.syncId] = TOO_BIG_TO_PUSH
                    }
                }
            }
        }
        return sent
    }

    /** Un latido perdido no detiene nada: `LastPushUtc` se vuelve a escribir en la siguiente subida. Dice lo que quedará pendiente, aunque la cola se vacíe después. */
    private fun writePushHeartbeat(run: Run, plan: PushPlan) {
        val heartbeat = pushHeartbeat ?: return
        val remaining = store.outbox().filter { it.seq > plan.toSeq }
        val parked = plan.parkNow.keys + remaining.map { it.entityType to it.syncId }
        try {
            val results = run.query(heartbeat.statements(parked.size, remaining.minOfOrNull { it.enqueuedAtUtc }))
            heartbeat.onWritten(results.first())
        } catch (e: HranaException) {
            if (e.code == HranaException.UNAUTHORIZED || e.code == HranaException.BLOCKED) throw e
        }
    }

    private class RejectedRead(val table: SyncTable, val ids: List<String>, val rows: List<Row>, val tombstones: List<Row>)

    /** Lo que la nube no aceptó se relee, fila y lápida, en una sola petición. */
    private fun readRejected(run: Run, rejected: List<Pair<SyncTable, String>>): List<RejectedRead> {
        val batches = rejected.groupBy({ it.first }, { it.second }).flatMap { (table, ids) -> ids.distinct().chunked(REJECTED_PER_STATEMENT).map { table to it } }
        val statements = batches.flatMap { (table, ids) ->
            val args = ids.map<String, HranaValue>(HranaValue::Text)
            listOf(Statement(Statements.rereadRowsSql(table.table, ids.size), args), Statement(Statements.rereadTombstonesSql(ids.size), listOf<HranaValue>(HranaValue.Text(table.entityType)) + args))
        }
        val results = run.query(statements)
        return batches.mapIndexed { index, (table, ids) -> RejectedRead(table, ids, results[2 * index].rows, results[2 * index + 1].rows) }
    }

    /**
     * Desempate (`ApplyRejectedUpsertsAsync`, `golden/lww.json`): se aplica aquí aunque las marcas empaten; si ya no la tiene, su lápida. Si no tiene ninguna de las dos, **se vuelve a anotar**: esa fila no está en la
     * nube y no se pierde.
     */
    private fun applyRejected(run: Run, reads: List<RejectedRead>) {
        for (read in reads) {
            val present = read.rows.mapNotNull { it.text("SyncId")?.lowercase(java.util.Locale.ROOT) }.toSet()
            if (read.rows.isNotEmpty()) applyRows(run, read.table, read.rows, emptyMap(), emptySet(), afterReject = true)
            val deleted = read.tombstones.map(::tombstoneOf).filter { it.syncId !in present }
            if (deleted.isNotEmpty()) applyTombstones(run, deleted, emptyMap())
            val known = present + deleted.map { it.syncId }
            val lost = read.ids.filter { it !in known }
            if (lost.isNotEmpty()) store.apply(StoreChanges(outbox = lost.map { writer.mark(read.table, it) }))
        }
    }

    /**
     * Otra generación (8.9): lo que no subió se guarda (`SyncSavedChanges` de la PC), cada fila con su protocolo y su revisión, en la misma transacción que la saca de la cola. Se puede llamar varias veces.
     * Lo guardado no se borra al vaciar: «Volver a aplicar» lo escribe otra vez con marca nueva.
     */
    private fun stashPending(generation: String?) = PendingChanges.stash(store, schema, clock, generation)

    /** Guarda lo que hay en la cola antes de vaciar: la identidad nueva (regla «Latido») lo usa. Regresa cuántos hay guardados. */
    fun stashBeforeClearing(): Int {
        stashPending(store.meta(GENERATION))
        return store.savedChanges().size
    }

    /** Lo que hizo «Volver a aplicar»: [kept] sigue guardado para volver a intentarlo. */
    data class Reapplied(val rows: Int, val deletes: Int, val kept: Int)

    /**
     * «Volver a aplicar N cambios» (`ReapplySavedChangesAsync`): cada fila otra vez con marca nueva, lista para subir; cada borrado, como un borrado de aquí. Con los mismos permisos que cualquier escritura.
     *
     * **Una copia guardada se quita solo si esa fila de verdad quedó aplicada y anotada, o el borrado hecho.** Se queda, con un aviso, la fila sin su principal (sin apartarla: `RetryParked` la aplicaría sin
     * anotarla) y el borrado que impide una `restrict`. Se queda, sin aviso, lo que este rol no puede escribir. El médico lo vuelve a intentar cuando quiera.
     */
    fun reapplySaved(): Reapplied = store.transaction {
        val role = DeviceRole.fromName(store.meta(DEVICE_ROLE)) ?: throw WriteRefused(WriteRefused.Reason.NOT_APPROVED, "La PC todavía no aprueba este equipo.")
        if (store.meta(REVOKED_REASON) != null) throw WriteRefused(WriteRefused.Reason.REVOKED, "Este equipo se quitó de los equipos vinculados.")
        if (store.meta(READ_ONLY) != null) throw WriteRefused(WriteRefused.Reason.READ_ONLY, "Actualice la app: por ahora solo se puede consultar.")
        val saved = store.savedChanges()
        if (saved.isEmpty()) return@transaction Reapplied(0, 0, 0)
        val run = Run()
        val done = ArrayList<Long>()
        val notices = LinkedHashSet<SyncNotice>()
        var rows = 0
        var deletes = 0
        for (table in schema.tables) {
            if (!WritePolicy.canWrite(role, table.table, creating = false)) continue
            val copies = saved.filter { it.entityType == table.entityType && it.kind == SavedChange.KIND_ROW }
            val group = copies.map { RowMapper.deserialize(it.payload) }
                .groupBy { it.text("SyncId")?.lowercase(java.util.Locale.ROOT) }
                .mapNotNull { (id, versions) -> if (id == null) null else versions.maxByOrNull { ProtocolDate.canonical(it.text("UpdatedAtUtc")) } }
            if (group.isEmpty()) continue
            applyRows(run, table, group, emptyMap(), emptySet(), force = true)
            for (copy in copies) {
                if (copy.syncId !in run.forceApplied) {
                    notices += SyncNotice(SyncNotices.REAPPLY_NO_PRINCIPAL, table.entityType, copy.syncId)
                    continue
                }
                done += copy.id
            }
            for (id in group.mapNotNull { it.text("SyncId")?.lowercase(java.util.Locale.ROOT) }.filter { it in run.forceApplied }) {
                writer.restamp(table, id)
                rows++
            }
        }
        for (table in schema.tables.asReversed()) {
            if (!WritePolicy.canWrite(role, table.table, creating = false)) continue
            val copies = saved.filter { it.entityType == table.entityType && it.kind == SavedChange.KIND_TOMBSTONE }
            for ((syncId, sameRow) in copies.groupBy { it.syncId }) {
                if (writer.delete(table.table, syncId)) {
                    deletes++
                    done += sameRow.map { it.id }
                } else {
                    notices += SyncNotice(SyncNotices.REAPPLY_DELETE_BLOCKED, table.entityType, syncId)
                }
            }
        }
        val allNotices = SyncNotices.read(store.meta(NOTICES)) + notices
        store.apply(StoreChanges(savedRemoved = done, meta = if (notices.isEmpty()) emptyMap() else mapOf(NOTICES to SyncNotices.write(allNotices))))
        Reapplied(rows, deletes, saved.size - done.size)
    }

    /** Los empalmes y posibles duplicados de lo que acaba de llegar: un aviso de este equipo que no viaja y se queda hasta descartarlo (regla «Empalmes» y «Duplicados»). */
    private fun noteArrivals(run: Run) {
        if (run.arrivedPatients.isEmpty() && run.arrivedAppointments.isEmpty()) return
        val notices = SyncNotices.read(store.meta(NOTICES))
        val next = notices + SyncNotices.check(store, run.arrivedPatients, run.arrivedAppointments)
        if (next != notices) store.apply(StoreChanges(meta = mapOf(NOTICES to SyncNotices.write(next))))
    }

    // ---------------------------------------------------------------------------------------------------------------------------
    // Perfil, membrete y recálculo
    // ---------------------------------------------------------------------------------------------------------------------------

    private fun finish(run: Run, profile: Row?) {
        if (profile != null) applyProfile(run, profile)
        recalculate(run)
    }

    /** El perfil: gana el `UpdatedAtUtc` más nuevo. La imagen se baja si su hash todavía no está aquí. */
    private fun applyProfile(run: Run, profile: Row) {
        val hasData = !profile.text("DoctorName").isNullOrBlank() || !profile.text("ClinicName").isNullOrBlank()
        val stamp = ProtocolDate.canonical(profile.text("UpdatedAtUtc"))
        val local = store.profileStamp()
        if (hasData && (local == null || stamp > local)) {
            store.saveProfile(
                profile.mapValues { (column, value) ->
                    when (column) {
                        "Id" -> ValueCodec.integerText(value).toLong()
                        "UpdatedAtUtc" -> stamp
                        else -> value.text
                    }
                },
            )
        }
        downloadAsset(run, profile.text("CrestImageHash"), "crest")
        downloadAsset(run, profile.text("HeaderImageHash"), "header")
    }

    /** Su fila de `ClinicAssets` y sus trozos, cuatro por petición; se revisan el tamaño y el SHA-256. Si no está completa, después. */
    private fun downloadAsset(run: Run, hash: String?, kind: String) {
        if (hash.isNullOrEmpty() || hash in failedAssets || store.hasAsset(hash)) return
        val meta = run.query(listOf(Statements.assetMeta(hash))).single().rows.firstOrNull() ?: return
        val byteCount = meta.text("ByteCount")?.toLongOrNull() ?: 0L
        val chunkCount = meta.text("ChunkCount")?.toIntOrNull() ?: 0
        val bytes = java.io.ByteArrayOutputStream()
        for (first in 0 until chunkCount step ASSET_CHUNKS_PER_REQUEST) {
            val parts = (first until minOf(first + ASSET_CHUNKS_PER_REQUEST, chunkCount)).map { Statements.assetChunk(hash, it) }
            for (result in run.query(parts)) {
                val content = result.rows.firstOrNull()?.get("Content")?.let(ValueCodec::bytesOf)
                if (content == null) {
                    failedAssets += hash
                    return
                }
                bytes.write(content)
            }
        }
        val content = bytes.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(java.util.Locale.ROOT, it) }
        if (content.size.toLong() != byteCount || !digest.equals(hash, ignoreCase = true)) {
            failedAssets += hash
            return
        }
        store.saveAsset(hash.lowercase(java.util.Locale.ROOT), kind, content)
    }

    /** Después de cada bajada; también completo si cambió el día, porque "la próxima cita" depende de hoy. */
    private fun recalculate(run: Run) {
        val today = Clinic.today(clock.instant())
        val dayChanged = store.meta(SUMMARY_DAY) != today.toString()
        val all = run.allPatients || dayChanged
        PatientSummaryRecalculator(store).recalculate(if (all) null else run.touchedPatients, today)
        if (dayChanged) store.apply(StoreChanges(meta = mapOf(SUMMARY_DAY to today.toString())))
    }

    private fun completed(run: Run, fullDownload: Boolean) =
        SyncOutcome.Completed(fullDownload, run.applied, run.rowsRead, run.manifest?.takeUnless { it.isEmpty }, run.clockWarning, run.pushed)

    // ---------------------------------------------------------------------------------------------------------------------------

    /** Lo de una sola corrida: las páginas que se redujeron, lo leído y los expedientes que hay que recalcular. */
    private inner class Run {
        val pageSizes = HashMap<String, Int>()
        var rowsRead = 0L
        var rowsWritten = 0L
        var pushed = 0
        var mergedForUpload = 0
        val arrivedPatients = LinkedHashSet<String>()
        val arrivedAppointments = LinkedHashSet<String>()
        val forceApplied = HashSet<String>()
        var applied = 0
        var generation: String? = null
        var manifest: ManifestDiff? = null
        var clockWarning: String? = null
        var allPatients = false
        val touchedPatients = HashSet<String>()

        /** Cada instrucción cuenta al menos 1 lectura (el estimador de la PC). */
        private fun count(results: List<ResultSet>) {
            rowsRead += results.sumOf { maxOf(1, it.rows.size).toLong() }
        }

        fun query(statements: List<Statement>): List<ResultSet> = connection.query(statements).also(::count)

        fun snapshot(before: List<Statement>, statements: List<Statement>) =
            connection.snapshot(before, statements).also {
                count(it.before)
                count(it.statements)
            }

        fun touch(table: String, syncId: String, row: Row) {
            when (table) {
                PatientSummaryRecalculator.PATIENTS -> touchedPatients += syncId
                PatientSummaryRecalculator.APPOINTMENTS, PatientSummaryRecalculator.CONSULTATIONS ->
                    row.text(PatientSummaryRecalculator.REFERENCE)?.lowercase(java.util.Locale.ROOT)?.let { touchedPatients += it }
            }
        }

        /** Antes de borrarlas: una cita o consulta borrada cambia el resumen de su expediente. */
        fun touchDeleted(store: SyncStore, deletes: Map<String, Set<String>>) {
            for (table in listOf(PatientSummaryRecalculator.APPOINTMENTS, PatientSummaryRecalculator.CONSULTATIONS)) {
                val ids = deletes[table] ?: continue
                store.select(table, listOf(PatientSummaryRecalculator.REFERENCE), "SyncId", ids)
                    .mapNotNullTo(touchedPatients) { it[PatientSummaryRecalculator.REFERENCE] as String? }
            }
        }
    }

    private class Stop(val outcome: SyncOutcome) : Exception(outcome.message)

    private data class Handshake(
        val now: String?,
        val watermark: String,
        val clinicId: String?,
        val generation: String?,
        val generationReady: String?,
        val generationPrevious: String?,
        val minClientProtocol: Int?,
    ) {
        companion object {
            fun parse(result: ResultSet): Handshake {
                val row = result.rows.firstOrNull() ?: throw HranaException(HranaException.BAD_RESPONSE, "Turso no regresó su hora.")
                return Handshake(
                    now = row.text("now"),
                    // Se guarda tal cual, sin interpretarla
                    watermark = row.text("wm").orEmpty(),
                    clinicId = row.text("clinicId"),
                    generation = row.text("generation"),
                    generationReady = row.text("generationReady"),
                    generationPrevious = row.text("generationPrevious"),
                    minClientProtocol = row.text("minClientProtocol")?.toIntOrNull(),
                )
            }
        }
    }

    companion object {
        const val MIN_PAGE_SIZE = 10
        private const val REJECTED_PER_STATEMENT = 200

        /** El motivo de una fila apartada porque no pasa ni sola. */
        const val TOO_BIG_TO_PUSH = "No se pudo subir: es demasiado grande o tarda demasiado. Se vuelve a intentar en cada subida."
        private const val CLOCK_OFFSET_TOLERANCE_MS = 250L
        const val ASSET_CHUNKS_PER_REQUEST = 4
        val CLOCK_WARNING: Duration = Duration.ofMinutes(2)
        val QUOTA_RETRY_INTERVAL: Duration = Duration.ofHours(1)
        val TOKEN_WIPE_AFTER: Duration = Duration.ofMinutes(10)

        /** 100 en las tablas con columnas grandes, 500 en las demás (8.6). */
        fun defaultPageSize(table: String): Int = if (table == "Patients" || table == "PatientConsultations") 100 else 500

        const val CURSOR_PREFIX = "PullCursor:"
        const val CLINIC_ID = "Cloud:ClinicId"
        const val GENERATION = "Cloud:Generation"
        const val DEVICE_CODE = "Cloud:DeviceCode"
        const val DEVICE_ROLE = "Cloud:DeviceRole"
        const val MANIFEST_CONTRACT = "Cloud:ManifestContract"
        const val READ_ONLY = "Cloud:ReadOnly"

        /** La huella de la nube con que se leyó el manifiesto. */
        const val CLOUD_SCHEMA_SEEN = "Cloud:SchemaSeen"

        /** Otra instalación usaba su `DeviceId`: el nuevo mientras se termina de cambiar. Sobrevive a que el proceso muera. */
        const val IDENTITY_CHANGE = "Cloud:IdentityChange"

        /** Revocado sin borrar: hay algo pendiente. Sobrevive a reiniciar sin red: al abrir, `LinkFlow` lo dice sin preguntarle a la nube. */
        const val REVOKED_REASON = "Cloud:RevokedReason"

        /** Los avisos de empalmes y duplicados de este equipo. No viajan. */
        const val NOTICES = "Status:Notices"
        const val CLOCK_OFFSET_MS = "Cloud:ClockOffsetMs"
        const val QUOTA_RETRY_AT = "Pause:QuotaRetryAtUtc"
        const val AUTH_FIRST_401 = "Pause:First401Utc"
        const val AUTH_FIRST_401_ELAPSED = "Pause:First401Elapsed"
        const val AUTH_MALFORMED = "Pause:TokenMalformedUtc"
        const val LAST_ERROR = "Status:LastError"
        const val LAST_SUCCESS = "Status:LastSuccessUtc"
        const val FIRST_DOWNLOAD_DONE = "Sync:FirstDownloadDoneUtc"
        const val FULL_IN_PROGRESS = "Sync:FullPullInProgress"
        const val SUMMARY_DAY = "Sync:SummaryDay"

        private const val NO_GENERATION = "none"
        private const val REJECTED_CODE_PREFIX = "X"

        /** Vaciar por otra generación no toca el freno, la identidad ni las pausas. */
        val KEPT_META_ON_CLEAR = listOf(ConsumptionBrake.META_PREFIX, "Cloud:", "Pause:", "Status:")
    }
}
