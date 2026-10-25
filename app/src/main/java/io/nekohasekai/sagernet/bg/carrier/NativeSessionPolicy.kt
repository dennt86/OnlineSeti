package app.qthrone.mobile.bg.carrier

import app.qthrone.mobile.outbound.json.JsonInput

internal object NativeSessionPolicy {
    fun probeConfig(kind:CarrierKind,privateJson:String,interactive:Boolean,latencyProbe:Boolean=!interactive):String {
        if((interactive && !latencyProbe) || kind != CarrierKind.QWDTT) return privateJson
        return JsonInput.parseObject(privateJson).also {
            // Native auto captcha is allowed, visible prompts are refused by the Android bridge.
            it["probe_only"]=true;it["interactive_captcha"]=true
            if(kind==CarrierKind.QWDTT && latencyProbe) it["workers"]=9
        }.toCompact()
    }

    fun startupTimeoutMs(kind:CarrierKind,requested:Long):Long = when(kind) {
        CarrierKind.QWDTT -> requested.coerceAtLeast(300_000L)
        // DNS tunnels measure MTU and handshake query by query; S3 Bypass waits for the bucket.
        CarrierKind.DNSTT,CarrierKind.VAYDNS,CarrierKind.SLIPSTREAM,CarrierKind.MASTERDNS,CarrierKind.S3BYPASS -> requested.coerceAtLeast(120_000L)
        else -> requested
    }
    private val callErrorCode=Regex("error_code[\\s]*[:=][\\s]*[\"']?([0-9]+)",RegexOption.IGNORE_CASE)
    fun unavailableCall(line:String):Boolean {
        val code=callErrorCode.find(line)?.groupValues?.get(1)?.toIntOrNull()
        return line.contains("Call not found",ignoreCase=true) || code==951 || code==954 || (code!=null && code in 9000..9999)
    }
    fun rotatedHashes(privateJson:String,attempt:Int):String {
        if(attempt==0) return privateJson
        val config=JsonInput.parseObject(privateJson)
        val hashes=config.array("hashes").strings()
        if(hashes.isEmpty()) return privateJson
        val offset=attempt%hashes.size
        config["hashes"]=app.qthrone.mobile.outbound.json.JsonArray().also { array ->
            (hashes.drop(offset)+hashes.take(offset)).forEach { array.add(it) }
        }
        return config.toCompact()
    }

    fun sharesRemoteSession(firstKind:CarrierKind,first:String,secondKind:CarrierKind,second:String):Boolean {
        if(firstKind==CarrierKind.OPENFLUX && secondKind==CarrierKind.OPENFLUX) {
            // An OpenFlux exit serves one client at a time: a second one on any shared carrier takes the session over.
            fun carriers(json:String)=JsonInput.parseObject(json).array("transports")
                .filterIsInstance<app.qthrone.mobile.outbound.json.JsonObject>()
                .map { it.string("type")+" "+it.string(if(it.string("type")=="direct") "dial" else "url").trim() }.toSet()
            return carriers(first).intersect(carriers(second)).isNotEmpty()
        }
        if(firstKind!=secondKind || firstKind != CarrierKind.QWDTT) return false
        val a=JsonInput.parseObject(first);val b=JsonInput.parseObject(second)
        return a.string("peer").isNotEmpty() && a.string("device_id").isNotEmpty() &&
            a.string("peer")==b.string("peer") && a.string("device_id")==b.string("device_id")
    }
    fun startupFailure(line:String):String? = when {
        line.contains("FATAL_AUTH:") -> "Сервер отклонил авторизацию: проверьте пароль, привязку устройства и срок доступа"
        line.contains("FATAL_PROTOCOL:") -> "Сервер отклонил несовместимый протокол подключения"
        else -> null
    }
}
