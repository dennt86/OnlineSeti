package app.qthrone.mobile.bg.carrier

import app.qthrone.mobile.outbound.`import`.ProfileImport
import app.qthrone.mobile.outbound.types.Qwdtt
import org.junit.Assert.*
import org.junit.Test

class NativeSubscriptionImportTest {
    @Test fun importsAndroidWrapperWithoutType() {
        val result = ProfileImport.parse("""{"subscriptionName":"Demo","profiles":[{"name":"Demo node","peer":"203.0.113.5:56000","password":"example","hashes":["exampleHash123456789"],"workersPerHash":18,"listenPort":9999}]}""")
        assertEquals(1,result.outbounds.size)
        val node = result.outbounds.single() as Qwdtt
        assertEquals("203.0.113.5",node.server)
        assertEquals(56000,node.serverPort)
        assertEquals(18,node.workers)
        assertEquals("Demo node",node.name)
    }
}
