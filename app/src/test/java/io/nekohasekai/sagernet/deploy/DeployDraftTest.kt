package app.qthrone.mobile.deploy

import org.junit.Assert.*
import org.junit.Test

class DeployDraftTest {
    @Test fun dirtyStateDisappearsWhenTheUserRevertsChanges() {
        val server=DeployServer.create("qwdtt")
        val original=server.editSignature()
        server["host"]="vps.example.org"
        assertNotEquals(original,server.editSignature())
        server["host"]=""
        assertEquals(original,server.editSignature())
        server["manualPorts"]="true"
        assertNotEquals(original,server.editSignature())
        server["manualPorts"]="false"
        assertEquals(original,server.editSignature())
        server.recordInstallation("generated config")
        assertEquals(original,server.editSignature())
    }

    @Test fun configurationPersistsPerServerAndSuccessfulUninstallClearsIt() {
        val first=DeployServer.create("qwdtt")
        val second=DeployServer.create("wdttplus")
        first.recordInstallation("qwdtt config")
        second.recordInstallation("wdttplus config")
        assertEquals("install", first["lastOperation"])
        assertTrue(first["lastOperationAt"].toLong() > 0L)
        val restored=DeployServer(org.json.JSONObject(first.json.toString()))
        assertEquals("qwdtt config",restored["config"])
        restored.recordUninstall()
        assertEquals("",restored["config"])
        assertEquals("false",restored["installed"])
        assertEquals("uninstall", restored["lastOperation"])
        assertTrue(restored["lastOperationAt"].toLong() > 0L)
        assertEquals("wdttplus config",second["config"])
    }
    @Test fun savingDoesNotRequireDeploymentSecrets() {
        for(kind in listOf("qwdtt","wdttplus")) {
            val server=DeployServer.create(kind)
            server["host"]="vps.example.org"
            server.validateAddress()
            assertFalse(server.isEmptyDraft())
        }
    }

    @Test(expected=IllegalArgumentException::class)
    fun blankServerCannotBeExplicitlySaved() {
        DeployServer.create("qwdtt").validateAddress()
    }
    @Test fun untouchedRecordsCanBeReusedForBothDeployers() {
        for(kind in listOf("qwdtt","wdttplus")) {
            assertTrue(DeployServer.create(kind).isEmptyDraft())
        }
    }

    @Test fun neverCollapseRecordsWithCredentialsOrCustomSettings() {
        for(kind in listOf("qwdtt","wdttplus")) {
            for(key in listOf("host","name","sshPassword","key","password","botToken","peerPort","hostKey")) {
                val server=DeployServer.create(kind)
                server[key]="custom-value"
                assertFalse("$kind: $key",server.isEmptyDraft())
            }
        }
    }
}
