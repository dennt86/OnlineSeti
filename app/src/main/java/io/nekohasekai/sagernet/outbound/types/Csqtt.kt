package app.qthrone.mobile.outbound.types

import app.qthrone.mobile.outbound.json.*
import app.qthrone.mobile.outbound.link.*
import app.qthrone.mobile.bg.carrier.CarrierBridgeCredentials

/** Shares the authenticated qWDTT bridge; the external backend is the CSQTT Rust client. */
class Csqtt:Qwdtt() {
    override val carrierKind get() = app.qthrone.mobile.bg.carrier.CarrierKind.CSQTT
    init { type="csqtt";serverPort=46000;workers=18;turnTcp=false }
    override fun displayType()=if(turnTcp) "CSQTT (TCP)" else "CSQTT (UDP)"
    override fun parseFromJson(obj:JsonObject):Boolean {
        if(obj.string("type")!="csqtt") return false
        val copy=obj.copy();copy["type"]="qwdtt"
        if(!copy.contains("turn_tcp")) copy["turn_tcp"]=false
        if(!super.parseFromJson(copy)) return false
        // "auto" takes call hashes from the qWDTT account binding; CSQTT still joins those calls
        // anonymously. CSQTT's own account login is never used: it gets VK accounts banned.
        hashes=obj.array("hashes").strings().take(6).map(VkCallHash::strip).filter { it.isNotEmpty() }
        workers=(if(obj.contains("workers")) obj.int("workers") else 18).coerceIn(9,((if(hashSource == "auto") 6 else hashes.size.coerceIn(1,6))*27).coerceAtMost(126))/9*9
        return obfs in listOf("audio","video") && vkAnonPath in listOf("vkcalls","legacy") && captchaMode in listOf("auto","wv","rjs")
    }
    override fun parseFromLink(link:String):Boolean {
        val url=LinkParser.parse(link.trim());if(!url.isValid || url.scheme!="csqtt") return false
        val obj=jsonObjectOf("type" to "csqtt","tag" to (url.fragment ?: ""))
        if(url.host=="connect") {
            val q=url.query
            if(q.valueFully("v")!="2" || url.port!=-1 || url.userName.isNotEmpty() || url.path.isNotEmpty()) return false
            obj["server"]=q.valueFully("host");obj["server_port"]=q.valueFully("peer").toIntOrNull() ?: return false
            obj["password"]=q.valueFully("password")
            if(q.has("name")) obj["tag"]=q.valueFully("name") else if(q.has("remark")) obj["tag"]=q.valueFully("remark")
            if(q.has("device_id")) obj["device_id"]=q.valueFully("device_id")
            if(q.has("hashes")) {
                val hashes=q.value("hashes").split('+').map { LinkCodec.decodeFully(it) }.filter { it.isNotBlank() }.distinct().take(6)
                if(hashes.size !in 1..6 || hashes.any { it.length<16 || it.any(Char::isWhitespace) } || hashes.distinct().size!=hashes.size) return false
                obj["hashes"]=JsonArray.of(*hashes.toTypedArray())
            }
        } else {
            obj["server"]=url.host;obj["server_port"]=url.port
            obj["password"]=url.userName + if(url.password.isNotEmpty()) ":"+url.password else ""
        }
        if((obj.string("server")+obj.string("password")).any(Char::isWhitespace) || obj.string("password").isEmpty()) return false
        return parseFromJson(obj)
    }
    override fun exportToJson()=super.exportToJson().also {
        for(key in listOf("mode","raw_port","go_dns")) it.remove(key)
    }
    override fun exportToLink():String {
        val url=LinkBuilder("csqtt");url.host="connect"
        url.addQueryItem("v","2");url.addQueryItem("host",server);url.addQueryItem("peer",serverPort.toString())
        url.addQueryItem("password",password)
        url.addQueryItemPercentEncoded("hashes",hashes.joinToString("+") { LinkCodec.percentEncodeAll(it) });url.addQueryItem("name",name)
        return url.build()
    }
    override fun privateConfig(port:Int,udpPort:Int,credentials:CarrierBridgeCredentials):JsonObject {
        require(server.isNotBlank() && serverPort in 1..65535)
        require(password.isNotEmpty() && (password+deviceId).none { it=='|' || it=='\n' || it=='\r' })
        require(deviceId.isNotEmpty() && (hashSource == "auto" || hashes.size in 1..6) && hashes.distinct().size==hashes.size)
        require(hashes.all { h -> h.length>=16 && h.none { it==',' || it=='|' || it.isWhitespace() } })
        require(workers in 9..126 && obfs in listOf("audio","video"))
        return exportToJson().also {
            it["backend"]="csqtt";it["peer"]=Hosts.displayAddress(server,serverPort)
            it["listen"]=Hosts.displayAddress("127.0.0.1",udpPort);it["socks"]=Hosts.displayAddress("127.0.0.1",port)
            it["socks_user"]=credentials.username;it["socks_pass"]=credentials.password
        }
    }
}
