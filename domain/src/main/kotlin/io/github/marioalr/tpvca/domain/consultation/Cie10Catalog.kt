package io.github.marioalr.tpvca.domain.consultation

import io.github.marioalr.tpvca.domain.search.TextSearch

/** Un diagnóstico de la lista. [displayText] es lo que se escribe en el diagnóstico al elegirlo. */
data class Cie10Diagnosis(val code: String, val name: String, val category: String) {
    val displayText: String get() = "$code - $name"
}

/**
 * Los diagnósticos que sugiere la consulta, en el orden en que se buscan (`Models/Cie10Diagnosis.cs`, copiada tal cual). Vectores:
 * `contracts/golden/cie10-catalog.json` y `cie10-search.json`.
 */
object Cie10Catalog {
    /** Cuántos da [search] si no se pide otro tope. */
    const val DEFAULT_LIMIT = 15

    val all: List<Cie10Diagnosis> = listOf(
        Cie10Diagnosis("A09", "Diarrea y gastroenteritis de presunto origen infeccioso", "Infecciosas"),
        Cie10Diagnosis("A08.4", "Gastroenteritis viral, no especificada", "Infecciosas"),
        Cie10Diagnosis("A06.0", "Disentería amebiana aguda (Amebiasis)", "Infecciosas"),
        Cie10Diagnosis("A04.9", "Infección bacteriana intestinal, no especificada", "Infecciosas"),
        Cie10Diagnosis("B82.9", "Parasitosis intestinal, sin otra especificación", "Infecciosas"),
        Cie10Diagnosis("B01.9", "Varicela sin complicaciones", "Infecciosas"),
        Cie10Diagnosis("B02.9", "Herpes zóster sin complicaciones", "Infecciosas"),
        Cie10Diagnosis("B00.9", "Infección por virus del herpes, no especificada (Herpes simple)", "Infecciosas"),
        Cie10Diagnosis("B34.9", "Infección viral, no especificada", "Infecciosas"),
        Cie10Diagnosis("B35.9", "Dermatofitosis (Tiña / Micosis), no especificada", "Infecciosas"),
        Cie10Diagnosis("B37.9", "Candidiasis, no especificada", "Infecciosas"),
        Cie10Diagnosis("B37.3", "Candidiasis de la vulva y de la vagina", "Infecciosas"),
        Cie10Diagnosis("J00", "Rinofaringitis aguda (Resfriado común)", "Respiratorio"),
        Cie10Diagnosis("J01.9", "Sinusitis aguda, no especificada", "Respiratorio"),
        Cie10Diagnosis("J02.9", "Faringitis aguda, no especificada", "Respiratorio"),
        Cie10Diagnosis("J03.9", "Amigdalitis aguda, no especificada", "Respiratorio"),
        Cie10Diagnosis("J04.0", "Laringitis aguda", "Respiratorio"),
        Cie10Diagnosis("J06.9", "Infección respiratoria aguda de vías superiores (IRAS), no especificada", "Respiratorio"),
        Cie10Diagnosis("J11.1", "Influenza con otras manifestaciones respiratorias", "Respiratorio"),
        Cie10Diagnosis("J12.8", "Neumonía por otros virus (incluye COVID-19 confirmado)", "Respiratorio"),
        Cie10Diagnosis("J18.9", "Neumonía, no especificada", "Respiratorio"),
        Cie10Diagnosis("J20.9", "Bronquitis aguda, no especificada", "Respiratorio"),
        Cie10Diagnosis("J45.9", "Asma, no especificada (Crisis asmática / Broncoespasmo)", "Respiratorio"),
        Cie10Diagnosis("J44.9", "Enfermedad pulmonar obstructiva crónica (EPOC), no especificada", "Respiratorio"),
        Cie10Diagnosis("J30.4", "Rinitis alérgica, no especificada", "Respiratorio"),
        Cie10Diagnosis("R05", "Tos persistente en estudio", "Respiratorio"),
        Cie10Diagnosis("I10", "Hipertensión arterial esencial (primaria)", "Cardiovascular"),
        Cie10Diagnosis("I11.9", "Enfermedad cardíaca hipertensiva sin insuficiencia cardíaca", "Cardiovascular"),
        Cie10Diagnosis("I20.9", "Angina de pecho, no especificada", "Cardiovascular"),
        Cie10Diagnosis("I25.9", "Cardiopatía isquémica crónica, no especificada", "Cardiovascular"),
        Cie10Diagnosis("I50.9", "Insuficiencia cardíaca, no especificada", "Cardiovascular"),
        Cie10Diagnosis("I83.9", "Venas varicosas de los miembros inferiores (Várices)", "Cardiovascular"),
        Cie10Diagnosis("I84.9", "Hemorroides, no especificadas", "Cardiovascular"),
        Cie10Diagnosis("I95.9", "Hipotensión arterial, no especificada", "Cardiovascular"),
        Cie10Diagnosis("E11.9", "Diabetes mellitus tipo 2 sin mención de complicación", "Endocrino"),
        Cie10Diagnosis("E11.6", "Diabetes mellitus tipo 2 con otras complicaciones especificadas", "Endocrino"),
        Cie10Diagnosis("E10.9", "Diabetes mellitus tipo 1 sin complicaciones", "Endocrino"),
        Cie10Diagnosis("E66.9", "Obesidad, no especificada", "Endocrino"),
        Cie10Diagnosis("E66.0", "Obesidad debida a exceso de calorías", "Endocrino"),
        Cie10Diagnosis("E78.5", "Hiperlipidemia, no especificada (Dislipidemia)", "Endocrino"),
        Cie10Diagnosis("E78.0", "Hipercolesterolemia pura", "Endocrino"),
        Cie10Diagnosis("E78.1", "Hipertrigliceridemia pura", "Endocrino"),
        Cie10Diagnosis("E03.9", "Hipotiroidismo, no especificado", "Endocrino"),
        Cie10Diagnosis("E05.9", "Tirotoxicosis / Hipertiroidismo, no especificado", "Endocrino"),
        Cie10Diagnosis("E79.0", "Hiperuricemia sin signos de artritis / Gota", "Endocrino"),
        Cie10Diagnosis("M10.9", "Gota, no especificada", "Endocrino"),
        Cie10Diagnosis("K21.9", "Enfermedad por reflujo gastroesofágico (ERGE) sin esofagitis", "Digestivo"),
        Cie10Diagnosis("K29.7", "Gastritis, no especificada", "Digestivo"),
        Cie10Diagnosis("K29.1", "Otras gastritis agudas (Gastropatía erosiva)", "Digestivo"),
        Cie10Diagnosis("K25.9", "Úlcera gástrica, no especificada como aguda ni crónica", "Digestivo"),
        Cie10Diagnosis("K30", "Dispepsia funcional", "Digestivo"),
        Cie10Diagnosis("K58.9", "Síndrome del colon irritable (Colitis) sin diarrea", "Digestivo"),
        Cie10Diagnosis("K58.0", "Síndrome del colon irritable con diarrea", "Digestivo"),
        Cie10Diagnosis("K59.0", "Constipación / Estreñimiento crónico", "Digestivo"),
        Cie10Diagnosis("K80.2", "Cálculo de la vesícula biliar sin colecistitis (Colelitiasis)", "Digestivo"),
        Cie10Diagnosis("K81.9", "Colecistitis, no especificada", "Digestivo"),
        Cie10Diagnosis("K35.8", "Apendicitis aguda, otra y la no especificada", "Digestivo"),
        Cie10Diagnosis("K02.9", "Caries dental, no especificada / Odontalgia", "Digestivo"),
        Cie10Diagnosis("N39.0", "Infección del tracto urinario (IVU), sitio no especificado", "Genitourinario"),
        Cie10Diagnosis("N30.9", "Cistitis, no especificada", "Genitourinario"),
        Cie10Diagnosis("N20.9", "Cálculo urinario, no especificado (Urolitiasis / Litiasis renal)", "Genitourinario"),
        Cie10Diagnosis("N23", "Cólico renal, no especificado", "Genitourinario"),
        Cie10Diagnosis("N40", "Hiperplasia prostática benigna (HPB)", "Genitourinario"),
        Cie10Diagnosis("N76.0", "Vaginitis aguda / Cervicovaginitis", "Genitourinario"),
        Cie10Diagnosis("N94.6", "Dismenorrea, no especificada", "Genitourinario"),
        Cie10Diagnosis("N92.6", "Menstruación irregular, no especificada", "Genitourinario"),
        Cie10Diagnosis("N95.1", "Estados menopáusicos y climatéricos", "Genitourinario"),
        Cie10Diagnosis("M54.5", "Lumbago no especificado (Lumbalgia mecánica)", "Músculo-esquelético"),
        Cie10Diagnosis("M54.2", "Cervicalgia", "Músculo-esquelético"),
        Cie10Diagnosis("M79.1", "Mialgia / Contractura muscular", "Músculo-esquelético"),
        Cie10Diagnosis("M19.9", "Osteoartrosis (Artrosis), sitio no especificado", "Músculo-esquelético"),
        Cie10Diagnosis("M17.9", "Gonartrosis (Artrosis de rodilla), no especificada", "Músculo-esquelético"),
        Cie10Diagnosis("M06.9", "Artritis reumatoide, no especificada", "Músculo-esquelético"),
        Cie10Diagnosis("M77.9", "Entesopatía / Tendinitis, no especificada", "Músculo-esquelético"),
        Cie10Diagnosis("S93.4", "Esguince y desgarro del tobillo", "Músculo-esquelético"),
        Cie10Diagnosis("S63.5", "Esguince de la muñeca", "Músculo-esquelético"),
        Cie10Diagnosis("T14.0", "Traumatismo superficial / Contusión, no especificada", "Músculo-esquelético"),
        Cie10Diagnosis("L20.9", "Dermatitis atópica, no especificada", "Dermatología"),
        Cie10Diagnosis("L23.9", "Dermatitis alérgica de contacto, causa no especificada", "Dermatología"),
        Cie10Diagnosis("L50.9", "Urticaria, no especificada", "Dermatología"),
        Cie10Diagnosis("L70.9", "Acné, no especificado", "Dermatología"),
        Cie10Diagnosis("L03.9", "Celulitis de sitio no especificado (Infección de tejidos blandos)", "Dermatología"),
        Cie10Diagnosis("L02.9", "Absceso cutáneo, forúnculo y ántrax", "Dermatología"),
        Cie10Diagnosis("L01.0", "Impétigo [cualquier sitio]", "Dermatología"),
        Cie10Diagnosis("L60.0", "Uña encarnada (Onicocriptosis)", "Dermatología"),
        Cie10Diagnosis("B35.1", "Tiña de las uñas (Onicomicosis)", "Dermatología"),
        Cie10Diagnosis("G43.9", "Migraña, no especificada", "Neurología"),
        Cie10Diagnosis("G44.2", "Cefalea tensional", "Neurología"),
        Cie10Diagnosis("R51", "Cefalea (Dolor de cabeza no especificado)", "Neurología"),
        Cie10Diagnosis("R42", "Mareo y desvanecimiento (Vértigo periférico)", "Neurología"),
        Cie10Diagnosis("F41.9", "Trastorno de ansiedad, no especificado (Crisis de ansiedad)", "Salud Mental"),
        Cie10Diagnosis("F41.1", "Trastorno de ansiedad generalizada", "Salud Mental"),
        Cie10Diagnosis("F32.9", "Episodio depresivo, no especificado", "Salud Mental"),
        Cie10Diagnosis("G47.0", "Trastornos del inicio y del mantenimiento del sueño (Insomnio)", "Salud Mental"),
        Cie10Diagnosis("R50.9", "Fiebre, no especificada (Síndrome febril)", "Síntomas Generales"),
        Cie10Diagnosis("R53", "Malestar y fatiga / Astenia y adinamia", "Síntomas Generales"),
        Cie10Diagnosis("R10.4", "Otros dolores abdominales y los no especificados", "Síntomas Generales"),
        Cie10Diagnosis("R11", "Náusea y vómito", "Síntomas Generales"),
        Cie10Diagnosis("Z00.0", "Examen médico general (Check-up / Control de salud)", "Control Preventivo"),
        Cie10Diagnosis("Z01.0", "Examen de ojos y de la visión", "Control Preventivo"),
        Cie10Diagnosis("Z02.1", "Examen médico para admisión preempleo / Certificado médico", "Control Preventivo"),
        Cie10Diagnosis("Z71.3", "Consulta para asesoramiento y supervisión dietética", "Control Preventivo"),
        Cie10Diagnosis("Z34.9", "Supervisión de embarazo normal, no especificado (Control prenatal)", "Control Preventivo"),
    )

    /** Sin texto, los primeros; si no, los que contienen lo escrito en código, nombre o categoría (sin acentos ni mayúsculas), en el orden de la lista. */
    fun search(query: String?, maxResults: Int = DEFAULT_LIMIT): List<Cie10Diagnosis> {
        if (query.isNullOrBlank()) return all.take(maxResults)
        val normalized = TextSearch.normalize(query)
        return all.filter { TextSearch.matches(normalized, it.code, it.name, it.category) }.take(maxResults)
    }
}
