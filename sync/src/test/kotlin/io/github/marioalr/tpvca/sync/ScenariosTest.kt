package io.github.marioalr.tpvca.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `contracts/scenarios/`: B es el motor de Kotlin con una base en memoria (`:data` los corre también con Room) y A, por ahora, la PC simulada (`ScenarioHarness`). Lo esperado es lo que grabó la PC.
 */
class ScenariosTest {
    private fun check(name: String) = assertEquals(name, emptyList<String>(), ScenarioHarness(Contracts.file("scenarios/$name.json")).run())

    @Test fun lwwAndTies() = check("lww-and-ties")

    @Test fun tombstones() = check("tombstones")

    @Test fun parkedRows() = check("parked-rows")

    @Test fun clockSkew() = check("clock-skew")

    @Test fun uploadCut() = check("upload-cut")

    @Test fun generationFence() = check("generation-fence")

    @Test fun folios() = check("folios")

    @Test fun stockLedger() = check("stock-ledger")

    @Test fun patientMerge() = check("patient-merge")
}

/**
 * Los mismos escenarios con **A, el motor real de la PC**, y B hablándole a `FakeHranaServer` por HTTP: `-Ptpvc.control=http://127.0.0.1:8799`. Se saltan sin la propiedad, porque las rutas de control viven en TPVC y la
 * CI de esta app no la levanta. Es la comprobación que `ScenariosTest` no puede dar.
 */
class RealPcScenariosTest {
    private val control = System.getProperty("tpvc.control").orEmpty()

    private fun check(name: String) {
        org.junit.Assume.assumeTrue("Sin -Ptpvc.control", control.isNotEmpty())
        assertEquals(name, emptyList<String>(), ScenarioHarness(Contracts.file("scenarios/$name.json"), RealPcControl(control)).run())
    }

    @Test fun lwwAndTies() = check("lww-and-ties")

    @Test fun tombstones() = check("tombstones")

    @Test fun parkedRows() = check("parked-rows")

    @Test fun clockSkew() = check("clock-skew")

    @Test fun uploadCut() = check("upload-cut")

    @Test fun generationFence() = check("generation-fence")

    @Test fun folios() = check("folios")

    @Test fun stockLedger() = check("stock-ledger")

    @Test fun patientMerge() = check("patient-merge")
}
