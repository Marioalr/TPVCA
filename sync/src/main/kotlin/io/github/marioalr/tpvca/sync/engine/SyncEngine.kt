package io.github.marioalr.tpvca.sync.engine

import io.github.marioalr.tpvca.domain.Clinic
import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.ProtocolDate
import io.github.marioalr.tpvca.sync.Statements
import io.github.marioalr.tpvca.sync.SyncContract
import io.github.marioalr.tpvca.sync.SyncRules
import io.github.marioalr.tpvca.sync.SyncSchema
import io.github.marioalr.tpvca.sync.SyncTable
import io.github.marioalr.tpvca.sync.hrana.HranaConnection
import io.github.marioalr.tpvca.sync.hrana.HranaException
import io.github.marioalr.tpvca.sync.hrana.ResultSet
import io.github.marioalr.tpvca.sync.hrana.Row
import io.github.marioalr.tpvca.sync.hrana.Statement
import io.github.marioalr.tpvca.sync.hrana.ValueCodec
import io.github.marioalr.tpvca.sync.hrana.text
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
)

/**
 * El motor de lectura (plan §7.3, pasos 8.6, 8.7, 8.9): primer arranque, descarga completa, bajada incremental, LWW, lápidas, filas
 * apartadas, generación, freno, revocación y 401. **Solo lee de Turso**: ninguna instrucción escribe y ninguna es DDL
 * (`Statements`). Es la misma lógica que `TursoSyncService` de la PC, sin Android: corre en la JVM con un [SyncStore] de prueba.
 */
class SyncEngine(
    private val connection: HranaConnection,
    private val store: SyncStore,
    private val config: SyncEngineConfig,
    private val schema: SyncSchema = SyncContract.bundledSchema,
    private val clock: Clock = Clock.systemUTC(),
    /** Un reloj que no cambia con la hora del equipo (`SystemClock.elapsedRealtime`), para los 10 min del 401. Vuelve a cero al reiniciar. */
    private val elapsedMillis: () -> Long = { clock.millis() },
) {
    private val brake = ConsumptionBrake(store, clock)
    private val tombstonesTable = schema.tombstones.table
    private val allPages = schema.tables.map { it.table } + tombstonesTable
    private val failedAssets = HashSet<String>()

    /** *Reanudar* después del freno. */
    fun resumeAfterBrake() = brake.resume()

    /** Se pegó otro token: vuelve a intentar sin esperar y sin contar los 401 anteriores. */
    fun tokenReplaced() = store.apply(StoreChanges(metaRemoved = setOf(AUTH_FIRST_401, AUTH_FIRST_401_ELAPSED, AUTH_MALFORMED)))

    /** Corre una sincronización completa de principio a fin. Nunca lanza un error de Turso: lo regresa como resultado. */
    fun sync(trigger: SyncTrigger = SyncTrigger.AUTOMATIC): SyncOutcome {
        pausedOutcome(trigger)?.let { return it }
        val run = Run()
        val outcome = try {
            if (store.meta(FIRST_DOWNLOAD_DONE) == null) firstStart(run) else incremental(run)
        } catch (stop: Stop) {
            stop.outcome
        } catch (e: HranaException) {
            outcomeOf(e)
        }
        if (run.rowsRead > 0) brake.recordUsage(run.rowsRead)
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
        val confirming = store.pendingChanges() == 0 && first401IsOldEnough()
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
        if (store.pendingChanges() == 0 && first401IsOldEnough()) {
            store.wipeEverything()
            return SyncOutcome.TokenRejected(wiped = true)
        }
        return SyncOutcome.TokenRejected(wiped = false)
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
                    if (!diff.isEmpty) put(READ_ONLY, diff.describe())
                },
                metaRemoved = if (diff.isEmpty) setOf(READ_ONLY) else emptySet(),
            ),
        )
        run.manifest = diff
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
        val row = result.rows.firstOrNull() ?: throw Stop(SyncOutcome.NotApproved)
        val code = row.text("DeviceCode")
        // Rechazar deja una fila con código X y ya revocada: basta con cualquiera de las dos para no bajar nada
        val rejected = code?.startsWith(REJECTED_CODE_PREFIX) == true
        if (!row.text("RevokedAtUtc").isNullOrEmpty() || rejected) {
            val reason = row.text("RevokedReason") ?: if (rejected) SyncOutcome.Revoked.REASON_REJECTED else null
            // "Quitado" sube antes lo pendiente (K5); "perdido" borra sin subir nada
            val mustKeep = store.pendingChanges() > 0 && reason != SyncOutcome.Revoked.REASON_LOST
            if (!mustKeep) store.wipeEverything()
            throw Stop(SyncOutcome.Revoked(reason, wiped = !mustKeep))
        }
        // §7.3: el rol sale de su fila, como el nombre del enum de la PC. Es la autoridad: la PC puede aprobar con el rol que no se pidió
        val roleName = row.text("Role")
        val role = DeviceRole.fromName(roleName)
        if (role !in ANDROID_ROLES) throw Stop(SyncOutcome.WrongRole(roleName))
        store.apply(StoreChanges(meta = buildMap {
            put(DEVICE_ROLE, role!!.name)
            if (code != null) put(DEVICE_CODE, code)
        }))
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
            GenerationState.Pending -> throw Stop(SyncOutcome.GenerationPending)
            GenerationState.Replaced -> {
                // Lo que había bajado era de la nube anterior. Otra generación no se mezcla con esto
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

        val cursors = loadCursors()
        val pages = allPages.map { pageStatement(it, cursors.getValue(it), skipOwnRows = true, pageSize(run, it)) }
        val own = Statements.registryOwn(config.deviceId)
        val before = clock.instant()

        var firstPages: Map<String, List<Row>>? = null
        val registry: ResultSet
        val handshake: Handshake
        var profile: Row?
        try {
            val snapshot = run.snapshot(listOf(own), listOf(Statements.handshake) + pages + Statements.profile)
            registry = snapshot.before.single()
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
        checkCloud(run, handshake, before)
        when (generationState(handshake)) {
            GenerationState.Same -> Unit
            GenerationState.Pending -> throw Stop(SyncOutcome.GenerationPending)
            GenerationState.Replaced -> return reloadGeneration(run, handshake)
        }
        adoptGeneration(run, handshake)
        pullEverything(run, handshake.watermark, firstPages, skipOwnRows = true)
        finish(run, profile)
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
            GenerationState.Pending -> throw Stop(SyncOutcome.GenerationPending)
            GenerationState.Replaced -> return reloadGeneration(run, handshake)
        }
        adoptGeneration(run, handshake)
        pullEverything(run, handshake.watermark, firstPages = null, skipOwnRows = true)
        finish(run, results[2].rows.firstOrNull())
        return completed(run, fullDownload = false)
    }

    /**
     * Otra PC reemplazó la nube y ya terminó (8.9): se vacía y baja todo una sola vez, en esta misma sincronización. Lo que no se había
     * subido se guardaría antes para «Volver a aplicar» (K5); aquí no hay nada pendiente.
     */
    private fun reloadGeneration(run: Run, handshake: Handshake): SyncOutcome {
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
        store.repairFutureStamps(schema.tables.map { it.table }, limit, ProtocolDate.format(server))
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
     * Los expedientes también siguen el LWW por fila: este equipo no los edita, y la combinación por campo llega con K5.
     */
    private fun applyRows(run: Run, table: SyncTable, rows: List<Row>, meta: Map<String, String>, dead: Set<Pair<String, String>>) {
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
        for ((syncId, row) in incoming) {
            val remoteStamp = ProtocolDate.canonical(row.text("UpdatedAtUtc"))
            val local = stamps[syncId]
            val parkedHere = syncId in parked
            // Una versión apartada con la misma marca es la misma versión: se aplica al reintentar
            val wins = SyncRules.remoteRowWins(local, remoteStamp) || (parkedHere && local == remoteStamp)
            val key = Triple(ParkDirection.PULL, table.entityType, syncId)
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
                park += ParkedRow(ParkDirection.PULL, table.entityType, syncId, RowMapper.serialize(row), parkReason)
                continue
            }
            if (parkedHere) unpark += key
            if (drop) continue

            val isNew = local == null
            val values = if (isNew) RowMapper.newRow(table, row) else RowMapper.update(table, row)
            writes += RowWrite(table.table, syncId, values + nulled.associateWith { null } + ("UpdatedAtUtc" to remoteStamp), isNew)
            run.touch(table.table, syncId, row)
        }
        store.apply(StoreChanges(writes = writes, park = park, unpark = unpark, meta = meta))
        run.applied += writes.size
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
    private fun applyTombstones(run: Run, tombstones: List<TombstoneRow>, meta: Map<String, String>) {
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
            if (!planDelete(table, setOf(tombstone.syncId), plan)) {
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

    private class DeletePlan {
        val deletes = HashMap<String, Set<String>>()
        val nullify = ArrayList<Nullify>()
    }

    /** `false` si una `restrict` lo impide. Recorre las referencias de `schema.json`: la base local no tiene llaves foráneas. */
    private fun planDelete(table: SyncTable, ids: Set<String>, plan: DeletePlan): Boolean {
        val known = plan.deletes[table.table].orEmpty()
        val fresh = ids - known
        if (fresh.isEmpty()) return true
        plan.deletes[table.table] = known + fresh
        for (dependent in schema.tables) {
            for (reference in dependent.references.filter { it.principal == table.entityType }) {
                val rows = store.referencing(dependent.table, reference.column, fresh)
                if (rows.isEmpty()) continue
                when (reference.onDelete) {
                    "restrict" -> return false
                    "cascade" -> if (!planDelete(dependent, rows, plan)) return false
                    "setNull" -> plan.nullify += Nullify(dependent.table, reference.column, rows)
                }
            }
        }
        return true
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
        SyncOutcome.Completed(fullDownload, run.applied, run.rowsRead, run.manifest?.takeUnless { it.isEmpty }, run.clockWarning)

    // ---------------------------------------------------------------------------------------------------------------------------

    /** Lo de una sola corrida: las páginas que se redujeron, lo leído y los expedientes que hay que recalcular. */
    private inner class Run {
        val pageSizes = HashMap<String, Int>()
        var rowsRead = 0L
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

        private val ANDROID_ROLES = setOf(DeviceRole.AndroidTablet, DeviceRole.AndroidMobileAdmin)
        private const val NO_GENERATION = "none"
        private const val REJECTED_CODE_PREFIX = "X"

        /** Vaciar por otra generación no toca el freno, la identidad ni las pausas. */
        val KEPT_META_ON_CLEAR = listOf(ConsumptionBrake.META_PREFIX, "Cloud:", "Pause:", "Status:")
    }
}
