package io.github.marioalr.tpvca.domain.patients

import io.github.marioalr.tpvca.domain.model.Consultation
import io.github.marioalr.tpvca.domain.model.Vitals
import io.github.marioalr.tpvca.domain.text.NetDecimal
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import io.github.marioalr.tpvca.domain.text.NetRegex

/**
 * Los signos vitales: el IMC y el aviso mientras se capturan, el texto de la nota (`Services/Rules/VitalSignsRules.cs`,
 * `contracts/golden/vitals.json`) y cómo se lee de vuelta ese texto (`PatientConsultation.ParseAndSetVitalSigns`,
 * `contracts/golden/vitals-evolution.json`).
 */
object VitalSignsRules {
    const val BMI_UNAVAILABLE = "Valores no disponibles"

    private val HUNDRED = BigDecimal(100)
    private val THREE = BigDecimal(3)

    // Lo que acepta `decimal.TryParse`/`double.TryParse` invariante con NumberStyles.Any en estos campos: espacios a los lados,
    // signo, dígitos ASCII con punto decimal y exponente. (.NET también acepta paréntesis, el símbolo de moneda invariante "¤" e
    // "Infinity"/"NaN" en double; nadie los escribe en un signo vital.)
    private val INVARIANT_NUMBER = Regex("^[+-]?(?:[0-9]+\\.?[0-9]*|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?$")

    /** Un número escrito como lo lee la PC (la coma vale punto); `null` si no es número. */
    fun parseDecimal(text: String?): BigDecimal? {
        val t = text.orEmpty().replace(',', '.').trim()
        if (!INVARIANT_NUMBER.matches(t)) return null
        return NetDecimal.parseOrNull(t)
    }

    private fun parseDouble(text: String?): Double? {
        val t = text.orEmpty().replace(',', '.').trim()
        return if (INVARIANT_NUMBER.matches(t)) t.toDoubleOrNull() else null
    }

    /** La talla en metros: más de 3 se escribió en centímetros (170 → 1.7, 95 → 0.95). */
    fun heightInMeters(height: BigDecimal): BigDecimal = if (height > THREE) NetDecimal.divide(height, HUNDRED) else height

    /** "24.8 kg/m² (Peso normal)" con la talla en metros o centímetros; [BMI_UNAVAILABLE] si falta alguno. */
    fun bmiText(weight: String?, height: String?): String {
        val kg = parseDecimal(weight) ?: return BMI_UNAVAILABLE
        val typed = parseDecimal(height) ?: return BMI_UNAVAILABLE
        if (typed.signum() <= 0) return BMI_UNAVAILABLE
        val meters = heightInMeters(typed)
        val imc = NetDecimal.divide(kg, meters.multiply(meters))
        return "${NetDecimal.fixed(imc, 1)} kg/m² (${bmiCategory(imc)})"
    }

    /** La clasificación del IMC de la OMS. */
    fun bmiCategory(imc: BigDecimal): String = when {
        imc < BigDecimal("18.5") -> "Bajo peso"
        imc < BigDecimal("25.0") -> "Peso normal"
        imc < BigDecimal("30.0") -> "Sobrepeso"
        imc < BigDecimal("35.0") -> "Obesidad Grado I"
        else -> "Obesidad Grado II+"
    }

    /**
     * Los valores fuera de rango para adulto o niño (menos de 12 años), unidos por " · "; vacío si todo es normal. Los números se
     * leen como `double` y se escriben como .NET (`0.#` y `0`: el valor más corto, con el punto medio hacia arriba).
     */
    fun warning(temperature: String?, bloodPressure: String?, oxygenSaturation: String?, glucose: String?, heartRate: String?, patientAge: Int?): String {
        fun d1(v: Double) = NetDecimal.customDouble(v, 0, 1)
        fun d0(v: Double) = NetDecimal.customDouble(v, 0, 0)
        val warnings = mutableListOf<String>()
        parseDouble(temperature)?.let { temp ->
            if (temp >= 38) warnings.add("Temp ${d1(temp)} °C: fiebre") else if (temp < 35.5 && temp > 0) warnings.add("Temp ${d1(temp)} °C: hipotermia")
        }

        val ta = bloodPressure.orEmpty().split('/')
        if (ta.size == 2) {
            val sys = parseDouble(ta[0])
            val dia = parseDouble(ta[1])
            if (sys != null && dia != null) {
                if (sys >= 140 || dia >= 90) {
                    warnings.add("T/A ${d0(sys)}/${d0(dia)}: elevada")
                } else if (sys > 0 && sys < 90) {
                    warnings.add("T/A ${d0(sys)}/${d0(dia)}: baja")
                }
            }
        }

        parseDouble(oxygenSaturation)?.takeIf { it > 0 }?.let {
            val sat = if (it <= 1) it * 100 else it
            if (sat < 92) warnings.add("SpO₂ ${d0(sat)} %: baja")
        }

        parseDouble(glucose)?.takeIf { it > 0 }?.let { glu ->
            if (glu >= 200) warnings.add("Glucosa ${d0(glu)} mg/dL: elevada") else if (glu < 70) warnings.add("Glucosa ${d0(glu)} mg/dL: hipoglucemia")
        }

        parseDouble(heartRate)?.takeIf { it > 0 }?.let { fc ->
            val child = patientAge != null && patientAge < 12
            when {
                !child && fc > 100 -> warnings.add("FC ${d0(fc)}: taquicardia")
                child && fc > 140 -> warnings.add("FC ${d0(fc)}: alta para la edad")
                fc < 50 -> warnings.add("FC ${d0(fc)}: bradicardia")
            }
        }
        return warnings.joinToString(" · ")
    }

    /** El texto de la nota: "TA: 120/80 mmHg | FC: 76 lpm | …", con lo capturado tal cual y sin lo vacío. */
    fun format(
        bloodPressure: String?,
        heartRate: String?,
        respiratoryRate: String?,
        temperature: String?,
        weight: String?,
        height: String?,
        glucose: String?,
        oxygenSaturation: String?,
        bmiText: String?,
    ): String {
        val cleanBloodPressure = bloodPressure.orEmpty().trim().trimEnd('/')
        val parts = mutableListOf<String>()
        if (cleanBloodPressure.isNotBlank()) parts.add("TA: $cleanBloodPressure mmHg")
        if (!heartRate.isNullOrBlank()) parts.add("FC: ${heartRate.trim()} lpm")
        if (!respiratoryRate.isNullOrBlank()) parts.add("FR: ${respiratoryRate.trim()} rpm")
        if (!temperature.isNullOrBlank()) parts.add("Temp: ${temperature.trim()} °C")
        if (!weight.isNullOrBlank()) parts.add("Peso: ${weight.trim()} kg")
        if (!height.isNullOrBlank()) parts.add("Talla: ${height.trim()} m")
        if (!glucose.isNullOrBlank()) parts.add("Glucosa: ${glucose.trim()} mg/dL")
        if (!oxygenSaturation.isNullOrBlank()) parts.add("SpO2: ${oxygenSaturation.trim().trimEnd('%')}%")
        if (!bmiText.isNullOrBlank() && !bmiText.contains("no disponibles")) parts.add("IMC: ${bmiText.trim()}")
        return parts.joinToString(" | ")
    }

    // ================================================================== lectura de la nota

    // Sin distinguir mayúsculas y con \d y \s de Unicode, como .NET. Un número con dígitos que no son ASCII coincide pero no se
    // lee (decimal.TryParse solo lee 0-9): queda sin dato, igual que en la PC.
    private fun vital(pattern: String) = NetRegex.of(pattern, "i")

    private val TA = vital("(?:T/?A|TA|PA|Presi[oó]n):\\s*(\\d{2,3})\\s*/\\s*(\\d{2,3})")
    private val FC = vital("FC:\\s*(\\d{2,3})")
    private val FR = vital("FR:\\s*(\\d{1,2})")
    private val TEMP = vital("(?:Temp|Temperatura):\\s*([\\d\\.\\,]+)")
    private val PESO = vital("(?:Peso|Weight):\\s*([\\d\\.\\,]+)")
    private val TALLA = vital("(?:Talla|Height|Estatura):\\s*([\\d\\.\\,]+)")
    private val SPO2 = vital("(?:SpO2%?|SatO2|SPO2)\\s*[:=]?\\s*([\\d\\.\\,]+)")
    private val IMC = vital("IMC:\\s*([\\d\\.\\,]+)")
    private val GLUCOSA = vital("Glucosa:\\s*([\\d\\.\\,]+)")

    private fun Regex.number(text: String, group: Int = 1): BigDecimal? = find(text)?.groupValues?.get(group)?.let(::parseDecimal)

    /**
     * Los signos leídos del texto de la nota sobre los que ya hay ([current]): cada uno que se encuentra reemplaza al de antes.
     * Sin IMC escrito y con peso y talla, IMC = `Math.Round(peso / talla², 2)` al par.
     */
    fun parse(text: String?, current: Vitals = Vitals()): Vitals {
        if (text.isNullOrBlank()) return current
        var v = current
        TA.find(text)?.let { m ->
            parseDecimal(m.groupValues[1])?.let { v = v.copy(bloodPressureSystolic = it) }
            parseDecimal(m.groupValues[2])?.let { v = v.copy(bloodPressureDiastolic = it) }
        }
        FC.number(text)?.let { v = v.copy(heartRate = it) }
        FR.number(text)?.let { v = v.copy(respiratoryRate = it) }
        TEMP.number(text)?.let { v = v.copy(temperature = it) }
        PESO.number(text)?.let { v = v.copy(weight = it) }
        TALLA.number(text)?.let { v = v.copy(height = heightInMeters(it)) }
        SPO2.number(text)?.let { v = v.copy(oxygenSaturation = if (it.signum() > 0 && it <= BigDecimal.ONE) it.multiply(HUNDRED) else it) }
        IMC.number(text)?.let { v = v.copy(bmi = it) }
        GLUCOSA.number(text)?.let { v = v.copy(glucose = it) }

        val weight = v.weight
        val height = v.height
        if (v.bmi == null && weight != null && height != null && height.signum() > 0) {
            val h = heightInMeters(height)
            if (h.signum() > 0) v = v.copy(bmi = NetDecimal.round(NetDecimal.divide(weight, h.multiply(h)), 2))
        }
        return v
    }

    /** Los signos de la consulta: los de sus columnas, o leídos de la nota si no tiene sistólica, peso, glucosa ni SpO2. */
    fun ensureNumeric(consultation: Consultation): Vitals {
        val v = consultation.vitals
        if (v.bloodPressureSystolic != null || v.weight != null || v.glucose != null || v.oxygenSaturation != null) return v
        return parse(consultation.vitalSigns, v)
    }

    // ================================================================== la serie de la ficha

    private val SHORT_DATE = DateTimeFormatter.ofPattern("dd/MM/yy")

    /** Un punto de la gráfica de signos (`VitalSignsEvolutionPoint`). */
    data class EvolutionPoint(
        val date: LocalDateTime,
        val weight: BigDecimal? = null,
        val systolic: BigDecimal? = null,
        val diastolic: BigDecimal? = null,
        val glucose: BigDecimal? = null,
        val heartRate: BigDecimal? = null,
        val temperature: BigDecimal? = null,
        val bmi: BigDecimal? = null,
    ) {
        val formattedDate: String get() = date.format(SHORT_DATE)
        val weightText: String get() = weight?.let { "${NetDecimal.fixed(it, 1)} kg" } ?: "--"
        val bloodPressureText: String
            get() = if (systolic != null && diastolic != null) "${NetDecimal.fixed(systolic, 0)}/${NetDecimal.fixed(diastolic, 0)}" else "--"
        val glucoseText: String get() = glucose?.let { "${NetDecimal.fixed(it, 0)} mg/dL" } ?: "--"
        val temperatureText: String get() = temperature?.let { "${NetDecimal.fixed(it, 1)} °C" } ?: "--"
    }

    /**
     * La serie de signos de un expediente (`PatientRecord.GetVitalSignsEvolution`): por consulta, de la más vieja a la más nueva,
     * con sus signos ([ensureNumeric]); hay punto si tiene peso, sistólica, glucosa, temperatura o SpO2.
     */
    fun evolution(consultations: Iterable<Consultation>): List<EvolutionPoint> =
        consultations.sortedBy { it.date }.mapNotNull { c ->
            val v = ensureNumeric(c)
            if (v.weight != null || v.bloodPressureSystolic != null || v.glucose != null || v.temperature != null || v.oxygenSaturation != null) {
                EvolutionPoint(c.date, v.weight, v.bloodPressureSystolic, v.bloodPressureDiastolic, v.glucose, v.heartRate, v.temperature, v.bmi)
            } else {
                null
            }
        }
}
