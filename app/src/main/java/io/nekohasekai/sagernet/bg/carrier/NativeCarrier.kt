package app.qthrone.mobile.bg.carrier
import app.qthrone.mobile.outbound.json.JsonObject
interface NativeCarrier {
    val carrierKind:CarrierKind
    fun privateConfig(port:Int,udpPort:Int,credentials:CarrierBridgeCredentials):JsonObject
}
