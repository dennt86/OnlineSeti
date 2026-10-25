package app.qthrone.mobile.bg.carrier

import app.qthrone.mobile.outbound.json.JsonInput
import app.qthrone.mobile.outbound.json.JsonObject

/** Custom full configurations must not reopen the per-app VPN bypass through local listeners. */
object VpnListenerPolicy {
    fun check(config:String, allowSharing: Boolean = false) {
        val json=JsonInput.parseObject(config)
        for(value in json.array("inbounds")) {
            val inbound=value as? JsonObject ?: error("Invalid VPN inbound configuration")
            if(inbound.string("type")=="tun") continue
            check(inbound.string("type") in listOf("socks","http","mixed")) {
                "VPN custom configuration contains an unsupported listening inbound; use the TUN inbound"
            }
            val explicitSharing = allowSharing &&
                (inbound.string("tag") == "mixed-in" && inbound.string("type") == "socks" ||
                    inbound.string("tag") == "http-in" && inbound.string("type") == "http") &&
                inbound.string("listen") == "0.0.0.0"
            check(explicitSharing || inbound.string("listen") in listOf("127.0.0.1","::1")) {
                "VPN proxy listeners must bind to loopback and require authentication"
            }
            val users=inbound.array("users")
            check(users.isNotEmpty()) { "VPN proxy listener requires authentication to prevent routing bypass" }
            for(user in users) {
                val account=user as? JsonObject ?: error("Invalid VPN proxy credentials")
                check(account.string("username").isNotEmpty() && account.string("password").length>=32) {
                    "VPN proxy listener requires a strong password of at least 32 characters"
                }
            }
        }
        val api=json.obj("experimental").obj("clash_api")
        if(api.string("external_controller").isNotEmpty()) {
            check(api.string("secret").length>=32) { "VPN dashboard API requires a strong secret of at least 32 characters" }
        }
    }
}
