package io.github.marioalr.tpvca.domain.golden

import io.github.marioalr.tpvca.domain.model.Appointment
import io.github.marioalr.tpvca.domain.model.Consultation
import io.github.marioalr.tpvca.domain.model.PrescriptionItem
import io.github.marioalr.tpvca.domain.model.Product
import io.github.marioalr.tpvca.domain.model.Vitals
import io.github.marioalr.tpvca.domain.text.NetDecimal
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

// Cómo se leen las entradas de los vectores: lo mismo que S, Str, Int, Dec, ParseLocal… de GoldenVectorTests.BusinessRules.cs
// en la PC (un dato que falta o es null toma el valor por omisión de la PC).

fun JsonElement?.obj(key: String): JsonObject? = (this as? JsonObject)?.get(key) as? JsonObject

fun JsonElement?.arr(key: String): JsonArray? = (this as? JsonObject)?.get(key) as? JsonArray

fun JsonElement?.s(key: String): String? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.contentOrNull

fun JsonElement?.str(key: String): String = s(key) ?: ""

fun JsonElement?.intOrNull(key: String): Int? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.intOrNull

fun JsonElement?.int(key: String, fallback: Int = 0): Int = intOrNull(key) ?: fallback

fun JsonElement?.boolOrNull(key: String): Boolean? = ((this as? JsonObject)?.get(key) as? JsonPrimitive)?.booleanOrNull

fun JsonElement?.bool(key: String, fallback: Boolean = false): Boolean = boolOrNull(key) ?: fallback

fun JsonElement?.decOrNull(key: String): BigDecimal? = s(key)?.let(NetDecimal::parse)

fun JsonElement?.dec(key: String): BigDecimal = decOrNull(key) ?: NetDecimal.ZERO

fun JsonElement?.items(key: String): List<JsonElement> = ((this as? JsonObject)?.get(key) as? JsonArray).orEmpty()

fun JsonElement?.strings(key: String): List<String> = items(key).map { (it as JsonPrimitive).content }

fun JsonElement?.has(key: String): Boolean = (this as? JsonObject)?.containsKey(key) == true

private val LOCAL_MINUTES = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val LOCAL_TICKS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSS")

/** "yyyy-MM-dd" o "yyyy-MM-dd HH:mm" (hora del consultorio, sin zona), como `ParseLocal` de la PC. */
fun parseLocal(text: String): LocalDateTime =
    if (text.length > 10) LocalDateTime.parse(text, LOCAL_MINUTES) else LocalDate.parse(text).atStartOfDay()

fun parseDay(text: String): LocalDate = LocalDate.parse(text.substring(0, 10))

/** "yyyy-MM-dd" a medianoche, si no "yyyy-MM-dd HH:mm" (`LocalText` de la PC). */
fun localText(date: LocalDateTime): String =
    if (date.toLocalTime() == java.time.LocalTime.MIDNIGHT) date.toLocalDate().toString() else date.format(LOCAL_MINUTES)

fun localText(date: LocalDate): String = date.toString()

/** El formato de fechas del contrato ("2026-10-15 00:00:00.0000000", `TursoSyncService.FormatDate`). */
fun contractDate(date: LocalDateTime): String = date.format(LOCAL_TICKS)

fun parseContractDate(text: String): LocalDateTime = LocalDateTime.parse(text, LOCAL_TICKS)

fun parseUtc(text: String?): Instant? = text?.let { parseContractDate(it).toInstant(ZoneOffset.UTC) }

fun parseSyncId(text: String?): UUID = text?.let(UUID::fromString) ?: io.github.marioalr.tpvca.domain.model.EMPTY_SYNC_ID

fun json(value: String?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

fun json(value: Int?): JsonElement = if (value == null) JsonNull else JsonPrimitive(value)

fun json(value: Boolean): JsonElement = JsonPrimitive(value)

fun jsonStrings(values: Iterable<String>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

fun jsonInts(values: Iterable<Int>): JsonArray = JsonArray(values.map { JsonPrimitive(it) })

fun jsonObject(vararg pairs: Pair<String, JsonElement>): JsonObject = JsonObject(mapOf(*pairs))

/** Un producto de entrada (`ToProduct` de la PC): solo lo que trae; lo demás, el valor por omisión. */
fun toProduct(n: JsonElement): Product = Product(
    id = n.int("id"),
    code = n.str("code"),
    name = n.str("name"),
    genericName = n.s("genericName"),
    presentation = n.s("presentation"),
    location = n.s("location"),
    currentStock = n.int("stock"),
    unitsPerPackage = n.intOrNull("unitsPerPackage") ?: 1,
    openedUnitsRemaining = n.intOrNull("openedUnitsRemaining") ?: 0,
    price = n.dec("price"),
    unitCost = n.dec("unitCost"),
    costExcludesVat = n.bool("costExcludesVat"),
    unit = n.s("unit") ?: "Piezas",
    isActive = n.bool("active", true),
    categoryId = n.int("categoryId"),
    categoryName = n.s("category"),
)

fun productsIn(input: JsonElement, key: String = "products"): List<Product> = input.items(key).map(::toProduct)

fun ids(products: Iterable<Product>): JsonArray = jsonInts(products.map { it.id })

/** Una consulta de entrada (`ToConsultation` de GoldenVectorTests.Chart.cs). */
fun toConsultation(n: JsonElement): Consultation = Consultation(
    date = parseLocal(n.str("date")),
    folio = n.str("folio"),
    diagnosis = n.str("diagnosis"),
    chiefComplaint = n.str("chiefComplaint"),
    vitalSigns = n.str("vitalSigns"),
    treatmentPlan = n.str("treatmentPlan"),
    notes = n.str("notes"),
    systemsReview = n.str("systemsReview"),
    physicalExamination = n.str("physicalExamination"),
    prescriptions = n.items("prescriptions").map {
        PrescriptionItem(medicineName = it.str("medicineName"), presentation = it.s("presentation"), dosage = it.str("dosage"))
    },
    labStudies = n.strings("labStudies"),
    labIndications = n.str("labIndications"),
    vitals = Vitals(
        bloodPressureSystolic = n.decOrNull("systolic"),
        bloodPressureDiastolic = n.decOrNull("diastolic"),
        heartRate = n.decOrNull("heartRate"),
        temperature = n.decOrNull("temperature"),
        weight = n.decOrNull("weight"),
        height = n.decOrNull("height"),
        bmi = n.decOrNull("bmi"),
        glucose = n.decOrNull("glucose"),
        oxygenSaturation = n.decOrNull("oxygenSaturation"),
    ),
)

/** Una cita de entrada (`ToAppointment` de GoldenVectorTests.Chart.cs). */
fun toAppointment(n: JsonElement): Appointment = Appointment(
    syncId = parseSyncId(n.s("syncId")),
    patientName = n.str("patientName"),
    date = parseLocal(n.str("date")).toLocalDate(),
    time = n.str("time"),
    durationMinutes = n.int("durationMinutes", 30),
    reason = n.str("reason"),
    status = n.s("status") ?: "Programada",
)
