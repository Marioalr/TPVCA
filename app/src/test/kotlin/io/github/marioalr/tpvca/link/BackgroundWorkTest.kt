package io.github.marioalr.tpvca.link

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.marioalr.tpvca.domain.devices.DeviceRole
import io.github.marioalr.tpvca.sync.link.LinkState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** §3.3: qué se agenda en cada estado y con qué restricciones. Ningún trabajo lleva datos. */
@RunWith(AndroidJUnit4::class)
class BackgroundWorkTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun init() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    private fun running(name: String) = WorkManager.getInstance(context).getWorkInfosForUniqueWork(name).get().filter { !it.state.isFinished }

    private val linkedTablet = LinkState.Linked(DeviceRole.AndroidTablet, "T1", "Consultorio")

    @Test
    fun lasRestriccionesSonLasDelPlan() {
        val tablet = BackgroundWork.periodicRequest(DeviceRole.AndroidTablet)
        assertEquals(TimeUnit.MINUTES.toMillis(60), tablet.workSpec.intervalDuration)
        assertEquals(NetworkType.CONNECTED, tablet.workSpec.constraints.requiredNetworkType)
        assertTrue(tablet.workSpec.constraints.requiresBatteryNotLow())

        val phone = BackgroundWork.periodicRequest(DeviceRole.AndroidMobileAdmin)
        assertEquals(TimeUnit.HOURS.toMillis(3), phone.workSpec.intervalDuration)
        assertFalse(phone.workSpec.constraints.requiresBatteryNotLow())

        val push = BackgroundWork.pushRequest()
        assertEquals(NetworkType.CONNECTED, push.workSpec.constraints.requiredNetworkType)
        assertEquals(TimeUnit.SECONDS.toMillis(30), push.workSpec.backoffDelayDuration)
        assertFalse("Normal, no expedito", push.workSpec.expedited)
    }

    @Test
    fun ningunTrabajoLlevaDatos() {
        val requests = listOf(
            BackgroundWork.periodicRequest(DeviceRole.AndroidTablet), BackgroundWork.pushRequest(), BackgroundWork.tokenCheckRequest(), BackgroundWork.sharedPurgeRequest(),
        )
        for (request in requests) {
            assertEquals(Data.EMPTY, request.workSpec.input)
            assertTrue(request.tags.none { it.contains("@") })
        }
    }

    @Test
    fun vinculadoAgendaYRevocadoCancelaTodo() {
        BackgroundWork.follow(context, linkedTablet, DeviceRole.AndroidTablet)
        BackgroundWork.schedulePush(context)
        assertEquals(1, running(BackgroundWork.PERIODIC).size)
        assertEquals(1, running(BackgroundWork.PUSH).size)
        BackgroundWork.follow(context, LinkState.Revoked("quitado", erased = false, pending = 2), DeviceRole.AndroidTablet)
        assertEquals(emptyList<WorkInfo>(), running(BackgroundWork.PERIODIC))
        assertEquals(emptyList<WorkInfo>(), running(BackgroundWork.PUSH))
    }

    @Test
    fun laSegundaRevisionDelTokenSoloSinNadaPendiente() {
        BackgroundWork.follow(context, LinkState.NeedsToken(malformed = false, erased = false, pending = 1), DeviceRole.AndroidTablet)
        assertEquals(emptyList<WorkInfo>(), running(BackgroundWork.TOKEN_CHECK))
        BackgroundWork.follow(context, LinkState.NeedsToken(malformed = true, erased = false, pending = 0), DeviceRole.AndroidTablet)
        assertEquals("Un token mal copiado no cuenta para borrarse", emptyList<WorkInfo>(), running(BackgroundWork.TOKEN_CHECK))
        BackgroundWork.follow(context, LinkState.NeedsToken(malformed = false, erased = false, pending = 0), DeviceRole.AndroidTablet)
        assertEquals(1, running(BackgroundWork.TOKEN_CHECK).size)
        assertTrue(BackgroundWork.tokenCheckRequest().workSpec.initialDelay >= TimeUnit.MINUTES.toMillis(10))
        BackgroundWork.follow(context, linkedTablet, DeviceRole.AndroidTablet)
        assertEquals(emptyList<WorkInfo>(), running(BackgroundWork.TOKEN_CHECK))
    }

    @Test
    fun loCompartidoSeBorraConLaAppCerrada() {
        BackgroundWork.scheduleSharedPurge(context)
        val info = running(BackgroundWork.SHARED_PURGE).single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertFalse(BackgroundWork.sharedPurgeRequest().workSpec.constraints.requiredNetworkType != NetworkType.NOT_REQUIRED)
        assertTrue(BackgroundWork.sharedPurgeRequest().workSpec.initialDelay > 15 * 60 * 1000L)
    }
}
