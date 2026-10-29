package app.qthrone.mobile.bg.carrier

import org.junit.Assert.*
import org.junit.Test

class NativeSessionPolicyTest {
    @Test fun nineWorkerProbeNeverChangesTheProfileOrAnInteractiveConnection() {
        val source="""{"peer":"192.0.2.1:56003","device_id":"phone","workers":108,"mode":"raw","hashes":["one","two","three"]}"""
        val probe=app.qthrone.mobile.outbound.json.JsonInput.parseObject(NativeSessionPolicy.probeConfig(CarrierKind.QWDTT,source,false))
        assertEquals(9,probe.int("workers"))
        assertEquals("raw",probe.string("mode"))
        assertTrue(probe.bool("probe_only"))
        assertTrue(probe.bool("interactive_captcha"))
        assertEquals(source,NativeSessionPolicy.probeConfig(CarrierKind.QWDTT,source,true))
        val speed=app.qthrone.mobile.outbound.json.JsonInput.parseObject(NativeSessionPolicy.probeConfig(CarrierKind.QWDTT,source,false,latencyProbe=false))
        assertEquals(108,speed.int("workers"))
        assertTrue(speed.bool("probe_only"))
    }

    @Test fun vkPreparationHasRecoveryBudgetForQwdttAndWdttPlus() {
        assertEquals(300_000L,NativeSessionPolicy.startupTimeoutMs(CarrierKind.QWDTT,60_000))
        assertEquals(400_000L,NativeSessionPolicy.startupTimeoutMs(CarrierKind.QWDTT,400_000))
        assertEquals(300_000L,NativeSessionPolicy.startupTimeoutMs(CarrierKind.WDTTPLUS,60_000))
        assertEquals(400_000L,NativeSessionPolicy.startupTimeoutMs(CarrierKind.WDTTPLUS,400_000))
        assertEquals(60_000L,NativeSessionPolicy.startupTimeoutMs(CarrierKind.OPENFLUX,60_000))
    }
    @Test fun unavailableFirstCallCanTryEveryHashWithoutChangingTheStoredConfig() {
        val source="""{"peer":"192.0.2.1:56003","device_id":"phone","hashes":["closed","second","third"],"password":"fixture"}"""
        assertTrue(NativeSessionPolicy.unavailableCall("step2: Call not found (error_code=951)"))
        assertTrue(NativeSessionPolicy.unavailableCall("VK returns error: invalid join link (error_code=954)"))
        assertTrue(NativeSessionPolicy.unavailableCall("VK error_code=9001"))
        assertFalse(NativeSessionPolicy.unavailableCall("VK error_code=29"))
        assertFalse(NativeSessionPolicy.unavailableCall("VK error_code=14 captcha"))
        assertFalse(NativeSessionPolicy.unavailableCall("network timeout"))
        assertFalse(NativeSessionPolicy.unavailableCall("FATAL_AUTH: wrong password"))
        for(attempt in 0..2) {
            val candidate=app.qthrone.mobile.outbound.json.JsonInput.parseObject(NativeSessionPolicy.rotatedHashes(source,attempt))
            assertEquals(listOf("closed","second","third")[attempt],candidate.array("hashes").strings().first())
            assertEquals(setOf("closed","second","third"),candidate.array("hashes").strings().toSet())
            assertEquals("fixture",candidate.string("password"))
            assertEquals("phone",candidate.string("device_id"))
        }
        assertEquals(source,NativeSessionPolicy.rotatedHashes(source,0))
    }

    @Test fun differentHashesMustNotCreateAnotherSessionForAnActiveDevice() {
        val active="""{"peer":"192.0.2.1:46000","device_id":"phone","hashes":["a"]}"""
        val changed="""{"peer":"192.0.2.1:46000","device_id":"phone","hashes":["b"],"workers":27}"""
        assertTrue(NativeSessionPolicy.sharesRemoteSession(CarrierKind.QWDTT,active,CarrierKind.QWDTT,changed))
        assertFalse(NativeSessionPolicy.sharesRemoteSession(CarrierKind.QWDTT,active,CarrierKind.QWDTT,changed.replace("192.0.2.1","192.0.2.2")))
        assertFalse(NativeSessionPolicy.sharesRemoteSession(CarrierKind.QWDTT,active,CarrierKind.QWDTT,changed.replace("phone","another")))
    }

    @Test fun openFluxExitServesOneClientSoSharedCarriersAreOneSession() {
        val active="""{"negotiate":true,"transports":[{"type":"vyandex","url":"https://docs.yandex.ru/d/A"},{"type":"direct","dial":"203.0.113.7:9443"}]}"""
        val sameNode="""{"negotiate":true,"transports":[{"type":"direct","dial":"203.0.113.7:9443"}]}"""
        val otherNode="""{"negotiate":true,"transports":[{"type":"vyandex","url":"https://docs.yandex.ru/d/B"}]}"""
        assertTrue(NativeSessionPolicy.sharesRemoteSession(CarrierKind.OPENFLUX,active,CarrierKind.OPENFLUX,sameNode))
        assertFalse(NativeSessionPolicy.sharesRemoteSession(CarrierKind.OPENFLUX,active,CarrierKind.OPENFLUX,otherNode))
        // olcRTC rooms carry a session per client.
        assertFalse(NativeSessionPolicy.sharesRemoteSession(CarrierKind.OLCRTC,"""{"room":"r"}""",CarrierKind.OLCRTC,"""{"room":"r"}"""))
    }
    @Test fun serverRejectionsAreReportedWithoutEchoingSecretsOrGuessingOneCause() {
        val message=NativeSessionPolicy.startupFailure("[worker] FATAL_AUTH: private-password private-device")!!
        assertFalse(message.contains("private"))
        assertTrue(message.contains("пароль"))
        assertTrue(message.contains("привязку"))
        assertNotNull(NativeSessionPolicy.startupFailure("FATAL_PROTOCOL: other version"))
        assertNull(NativeSessionPolicy.startupFailure("[READY] worker is up"))
    }
}
