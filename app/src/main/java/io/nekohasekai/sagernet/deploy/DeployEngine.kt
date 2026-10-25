package app.qthrone.mobile.deploy

import app.qthrone.mobile.bg.carrier.NativeHashPolicy
import app.qthrone.mobile.group.DeviceDetails
import app.qthrone.mobile.ktx.app
import app.qthrone.mobile.bg.carrier.VkRelayAccount
import app.qthrone.mobile.outbound.types.WdttPlus
import com.jcraft.jsch.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

data class DeployState(val serverId: String = "",val running: Boolean = false,val percent: Int = 0,val step: String = "",val logs: String = "",val config: String = "",val trust: String = "",val installerProgress: Boolean = false,val configCleared: Boolean = false,val stopping: Boolean = false)

object DeployEngine {
    val state = MutableStateFlow(DeployState())
    private val scope = CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var session: Session? = null
    // Session.disconnect() is a no-op until the handshake ends, so Stop closes the socket itself.
    @Volatile private var socket: java.net.Socket? = null
    @Volatile private var download: okhttp3.Call? = null
    @Volatile private var trustReply: CompletableDeferred<Boolean>? = null
    fun approveHost(approved: Boolean) { trustReply?.complete(approved) }
    /** Blocking SSH, SFTP and download calls ignore coroutine cancellation, so each is broken off here. */
    fun cancel() {
        if(!state.value.running || state.value.stopping) return
        state.update { it.copy(stopping=true,step="Остановка…") }
        log("Остановка по запросу…")
        job?.cancel();trustReply?.complete(false);download?.cancel()
        scope.launch { session?.disconnect();runCatching { socket?.close() } }
    }
    fun start(server: DeployServer, uninstall: Boolean) {
        if(state.value.running) return
        val copy = DeployServer(JSONObject(server.json.toString()))
        if(copy["manualPorts"] != "true") {
            copy["peerPort"]="56000";copy["localPort"]="9000"
            copy["wgPort"]="56001";copy["directPort"]="56002";copy["rawPort"]="56003";copy["adminPort"]="56002";copy["webPort"]="46002"
        }
        state.value = DeployState(server.id,true,0,"Подключение",config=server["config"])
        job = scope.launch {
            try { run(copy,uninstall) }
            catch(e: Exception) {
                // Breaking off a blocking call surfaces as its own error; after Stop it is the stop, not a failure.
                if(e is CancellationException || state.value.stopping) {
                    log("Операция остановлена. Уже выполненные на VPS шаги не откатываются: при необходимости запустите установку или удаление заново.")
                    update(state.value.percent,"Операция остановлена")
                } else { log("Ошибка: "+redact(copy,e.message ?: e.javaClass.simpleName));update(state.value.percent,"Ошибка установки или SSH") }
                if(e is CancellationException) throw e
            }
            finally { session?.disconnect();session = null;runCatching { socket?.close() };socket = null;download = null;state.update { it.copy(running=false,stopping=false,trust="") } }
        }
    }
    // Stop writes from the UI thread while the job logs, so these changes are atomic.
    private fun update(percent: Int,step: String) { state.update { it.copy(percent=percent,step=step) } }
    private fun log(line: String) { state.update { it.copy(logs=(it.logs+line+"\n").takeLast(200_000)) } }
    private fun redact(server: DeployServer,text: String): String {
        var result = text
        for(key in listOf("sshPassword","key","keyPassword","sudoPassword","password","webPassword","botToken","adminToken")) {
            val secret = server[key]
            if(secret.isNotBlank()) result = result.replace(secret,"[скрыто]")
        }
        return result.replace(Regex("\\u001B\\[[0-9;]*[A-Za-z]"),"")
    }
    fun quote(value: String) = DeployProtocol.quote(value)
    private fun root(command: String): String = "if [ \"\$(id -u)\" = 0 ]; then bash -c ${quote(command)}; else sudo -S -p '' bash -c ${quote(command)}; fi"
    private suspend fun run(server: DeployServer,uninstall: Boolean) {
        server.validate(!uninstall)
        val jsch = JSch()
        val key = server["key"].toByteArray()
        if(server["auth"] == "key") jsch.addIdentity("qThrone",key,null,server["keyPassword"].takeIf { it.isNotEmpty() }?.toByteArray())
        // Host verification precedes sending a password or authenticating with the private key.
        jsch.hostKeyRepository = object : HostKeyRepository {
            override fun check(host: String, bytes: ByteArray): Int {
                val fingerprint = "SHA256:"+android.util.Base64.encodeToString(java.security.MessageDigest.getInstance("SHA-256").digest(bytes),android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
                val expected = server["hostKey"]
                if(expected.isNotBlank()) return if(expected == fingerprint) HostKeyRepository.OK else HostKeyRepository.CHANGED
                val reply = CompletableDeferred<Boolean>();trustReply = reply
                state.update { it.copy(trust=fingerprint) }
                val accepted = runBlocking { withTimeoutOrNull(120_000) { reply.await() } } == true
                state.update { it.copy(trust="") };trustReply = null
                if(!accepted) return HostKeyRepository.CHANGED
                server["hostKey"] = fingerprint;DeployStore.save(server)
                return HostKeyRepository.OK
            }
            override fun add(hostkey: HostKey?,userinfo: UserInfo?) {}
            override fun remove(host: String?,type: String?) {}
            override fun remove(host: String?,type: String?,key: ByteArray?) {}
            override fun getKnownHostsRepositoryID() = "qThrone encrypted servers"
            override fun getHostKey(): Array<HostKey> = emptyArray()
            override fun getHostKey(host: String?,type: String?): Array<HostKey> = emptyArray()
        }
        val ssh = jsch.getSession(server["user"],server["host"],server.port("sshPort"))
        session = ssh
        ssh.setSocketFactory(object : SocketFactory {
            override fun createSocket(host: String,port: Int) = java.net.Socket().also { socket = it;if(state.value.stopping) it.close() }.apply { connect(java.net.InetSocketAddress(host,port),30_000) }
            override fun getInputStream(socket: java.net.Socket) = socket.getInputStream()
            override fun getOutputStream(socket: java.net.Socket) = socket.getOutputStream()
        })
        ssh.setConfig("StrictHostKeyChecking","yes")
        ssh.setConfig("PreferredAuthentications",if(server["auth"] == "key") "publickey" else "password,keyboard-interactive")
        if(server["auth"] != "key") ssh.setPassword(server["sshPassword"])
        ssh.setServerAliveInterval(15000);ssh.setServerAliveCountMax(4)
        currentCoroutineContext().ensureActive()
        ssh.connect(30_000)
        currentCoroutineContext().ensureActive()
        val machine = exec(ssh,server,"uname -m",30_000).second.trim()
        if(server.kind == "wdttplus") { runWdttPlus(ssh,server,uninstall,machine);return }
        require(server.kind == "qwdtt") { "Тип сервера больше не поддерживается" }
        val source = Pair("https://github.com/makriq-org/qwdtt/releases/download/v1.4.4/qWDTT_v1.4.4.apk","ff5b2d1f265b0c4253cdf54ebf1f8a5d64afd1424086aa8b8be754b5c14df6a7")
        update(5,"Загрузка официального установщика")
        val apk = artifact(source.first,source.second)
        require(machine == "x86_64" || uninstall) { "Официальный qWDTT установщик содержит сервер только для x86_64" }
        val binaryName = "assets/server"
        try {
        val sftp = ssh.openChannel("sftp") as ChannelSftp
        sftp.connect(30_000)
        try {
            fun upload(path: String,bytes: ByteArray) { sftp.put(bytes.inputStream(),path);sftp.chmod(384,path) }
            ZipFile(apk).use { zip ->
                fun entry(name: String): ByteArray {
                    val item = zip.getEntry(name) ?: error("Нет файла установщика $name")
                    require(item.size in 1..30_000_000)
                    return zip.getInputStream(item).use { it.readBytes() }
                }
                upload("/tmp/deploy.sh",entry("assets/deploy.sh"))
                if(!uninstall) {
                    // As in the original apps a new server is bound to this phone's own identity.
                    if(server["installed"]!="true") server.set("deviceId",ownDeviceId())
                    update(30,"Передача файлов на VPS")
                    val binary=entry(binaryName)
                    if(server.kind == "qwdtt") require(DeployProtocol.safeQwdttServer(binary)) { "Устаревший сервер qWDTT: требуется сборка для wdtt0 и /etc/wdtt" }
                    upload("/tmp/wdtt-server",binary)
                    upload("/tmp/wdtt-main.password",server["password"].toByteArray())
                    upload("/tmp/wdtt-admin.token",server["adminToken"].toByteArray())
                    upload("/tmp/wdtt-bot.token",server["botToken"].toByteArray())
                }
            }
        } finally { sftp.disconnect() }
        val env = "WDTT_DTLS_PORT=${server.port("peerPort")} WDTT_WG_PORT=${server.port("wgPort")} WDTT_RAW_PORT=${server.port("rawPort")} WDTT_DIRECT_PORT=${server.port("directPort")} WDTT_ADMIN_PORT=${server.port("adminPort")} WDTT_SSH_PORT=${server.port("sshPort")} WDTT_ADMIN_ID=${quote(server["adminId"])} WDTT_DNS_SERVERS=${quote(server["dns1"]+","+server["dns2"])}"
        update(45,if(uninstall) "Удаление" else "Установка и запуск сервиса")
        val result = exec(ssh,server,root("env $env bash /tmp/deploy.sh ${if(uninstall) "uninstall" else "install"}"),30*60_000L)
        require(result.first == 0) { "Установщик завершился с кодом ${result.first}" }
        if(!uninstall) {
            require(DeployProtocol.successful(server.kind,result.first,result.second)) { "Установщик не подтвердил запуск сервиса" }
            if(server.kind == "qwdtt") {
                val pin = Regex("WDTT_ADMIN_PIN\\|(sha256/[A-Za-z0-9+/=]+)").find(result.second)?.groupValues?.get(1).orEmpty()
                require(pin.isNotBlank()) { "Не получен TLS-отпечаток панели" };server["adminPin"] = pin
            }
            val json = JSONObject().put("type",server.kind).put("name",server["name"]).put("server",server["host"]).put("server_port",server.port("peerPort"))
                .put("password",server["password"]).put("device_id",server["deviceId"]).put("hashes",org.json.JSONArray())
                .put("hash_source",if(server.kind == "qwdtt" && VkRelayAccount.linked() && !VkRelayAccount.needsRelink()) "auto" else "manual")
            if(server.kind == "qwdtt") json.put("raw_port",server.port("rawPort"))
            val config=DeployConfig.link(json,ownDeviceId())
            server.recordInstallation(config);DeployStore.save(server)
            state.update { it.copy(config=config) }
        } else {
            if(uninstall) { server.recordUninstall();DeployStore.save(server);state.update { it.copy(config="",configCleared=true) } }
        }
        update(100,if(uninstall) "Удаление завершено" else "Установка завершена")
        } finally {
            val paths = DeployProtocol.stagedFiles(server.kind).joinToString(" ") { quote(it) }
            if (paths.isNotBlank()) runCatching { exec(ssh,server,root("rm -f -- $paths"),15_000) }
        }
    }
    /**
     * WDTT Plus, as its app deploys: the pinned official APK's deploy.sh and server go to a private
     * staging folder; a new server gets passwords.json with the main password, an existing one is
     * updated in place keeping its data. WDTT_INSTALL_RESULT=success, the service and the admin
     * socket confirm the install.
     */
    private suspend fun runWdttPlus(ssh: Session,server: DeployServer,uninstall: Boolean,machine: String) {
        require(machine == "x86_64" || machine == "amd64" || uninstall) { "Официальный сервер WDTT Plus собран только для x86_64" }
        update(5,"Загрузка официального установщика")
        val apk = artifact("https://github.com/Ivan4537/WDTT-Plus/releases/download/v20/WDTT-Plus-v20-arm64-v8a-release.apk",
            "918cc7ab477247ebdaca030c13c0d689ca1a9f59152217f8edf0484a943da0e3")
        val staging = exec(ssh,server,"mktemp -d /tmp/wdtt-plus-deploy.XXXXXX",30_000).second.trim().lines().last()
        require(staging.matches(Regex("""/tmp/wdtt-plus-deploy\.[A-Za-z0-9]+"""))) { "Не удалось подготовить временный каталог" }
        try {
            exec(ssh,server,"chmod 700 ${quote(staging)}",30_000)
            val data = DeployProtocol.wdttPlusData(exec(ssh,server,root(DeployProtocol.WDTT_PLUS_PROBE),30_000).second)
            val existing = data != DeployProtocol.WdttPlusData.None
            require(uninstall || data != DeployProtocol.WdttPlusData.Partial) {
                "На VPS неполная установка WDTT Plus: есть только служба или только сервер. Удалите сервис с VPS и повторите установку"
            }
            val sftp = ssh.openChannel("sftp") as ChannelSftp
            sftp.connect(30_000)
            try {
                fun upload(path: String,bytes: ByteArray) { sftp.put(bytes.inputStream(),path);sftp.chmod(384,path) }
                ZipFile(apk).use { zip ->
                    fun entry(name: String): ByteArray {
                        val item = zip.getEntry(name) ?: error("Нет файла установщика $name")
                        require(item.size in 1..30_000_000)
                        return zip.getInputStream(item).use { it.readBytes() }
                    }
                    update(30,"Передача файлов на VPS")
                    upload("$staging/deploy.sh",entry("assets/deploy.sh"))
                    if(!uninstall) {
                        upload("$staging/server",entry("assets/server"))
                        if(!existing) upload("$staging/passwords.json",JSONObject().put("main_password",server["password"]).put("admin_id","")
                            .put("bot_token","").put("passwords",JSONObject()).put("devices",JSONObject())
                            .put("dns",server["dns"]).put("default_ports","${server.port("peerPort")},${server.port("wgPort")},${server.port("localPort")}")
                            .put("max_passwords",50).toString().toByteArray())
                    }
                }
            } finally { sftp.disconnect() }
            // As WDTT Plus does: data left without the service and server is marked, or deploy.sh refuses to preserve it.
            if(uninstall && existing) {
                if(!DeployProtocol.wdttPlusMarked(exec(ssh,server,root(DeployProtocol.WDTT_PLUS_MARK_PRESERVED),30_000).second))
                    log("Предупреждение: не удалось отметить данные /etc/wdtt как сохранённые; при повторной установке проверка повторится.")
            } else if(data == DeployProtocol.WdttPlusData.DataOnly) {
                update(40,"Защищаю сохранённые данные")
                require(DeployProtocol.wdttPlusMarked(exec(ssh,server,root(DeployProtocol.WDTT_PLUS_MARK_PRESERVED),30_000).second)) {
                    "Не удалось отметить сохранённые данные /etc/wdtt: нет passwords.json или wg-keys.dat, либо это символические ссылки. Установка не запущена"
                }
            } else if(data == DeployProtocol.WdttPlusData.Installed) {
                // A marker left by an uninstall that failed midway would make deploy.sh refuse the running install.
                exec(ssh,server,root("rm -f /etc/wdtt/.android-deploy-preserved"),30_000)
            }
            update(45,if(uninstall) "Удаление" else if(existing) "Обновление с сохранением данных" else "Установка и запуск сервиса")
            val command = if(uninstall) "bash ${quote("$staging/deploy.sh")} uninstall"
                else "env WDTT_DTLS_PORT=${server.port("peerPort")} WDTT_WG_PORT=${server.port("wgPort")} WDTT_SSH_PORT=${server.port("sshPort")} " +
                    "WDTT_INSTALL_MODE=${if(existing) "preserve" else "fresh"} WDTT_PRESERVE_DATA=${if(existing) 1 else 0} " +
                    (if(server["dns"].isNotBlank()) "WDTT_DNS=${quote(server["dns"])} " else "") +
                    "WDTT_STAGED_SERVER=${quote("$staging/server")} " +
                    (if(!existing) "WDTT_STAGED_DB=${quote("$staging/passwords.json")} " else "") +
                    "bash ${quote("$staging/deploy.sh")}"
            val result = exec(ssh,server,root(command),30*60_000L)
            require(result.first == 0) { "Установщик завершился с кодом ${result.first}" }
            if(uninstall) {
                server.recordUninstall();DeployStore.save(server)
                state.update { it.copy(config="",configCleared=true) }
                update(100,"Удаление завершено");return
            }
            require(DeployProtocol.successful("wdttplus",result.first,result.second)) { "Установщик не подтвердил запуск сервиса" }
            val verify = exec(ssh,server,root("sleep 2; printf 'SERVICE=%s\n' \"\$(systemctl is-active wdtt 2>/dev/null || true)\"; " +
                "printf 'ADMIN_SOCKET=%s\n' \"\$([ -S /run/wdtt/admin.sock ] && echo 1 || echo 0)\""),60_000).second
            require(verify.contains("SERVICE=active")) { "Служба wdtt не запустилась" }
            require(verify.contains("ADMIN_SOCKET=1")) { "Сервер не открыл канал управления" }
            val link = WdttPlus().apply {
                this.server = server["host"]; serverPort = server.port("peerPort"); wgPort = server.port("wgPort"); localPort = server.port("localPort")
                password = server["password"]; name = server["name"].ifBlank { "WDTT Plus" }
            }.exportToLink()
            server.recordInstallation(link);DeployStore.save(server)
            state.update { it.copy(config=link) }
            update(100,if(existing) "Обновление завершено" else "Установка завершена")
        } finally { runCatching { exec(ssh,server,"rm -rf ${quote(staging)}",30_000) } }
    }

    private suspend fun artifact(url: String,sha: String): File {
        val target = File(app.cacheDir,"deploy-$sha.apk")
        fun digest(file: File): String {
            val hash = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val buffer = ByteArray(65536);while(true) { val count=input.read(buffer);if(count<0) break;hash.update(buffer,0,count) } }
            return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        if(target.exists() && digest(target) == sha) return target
        target.delete()
        val http = OkHttpClient.Builder().callTimeout(5,TimeUnit.MINUTES).build()
        try {
            val call=http.newCall(Request.Builder().url(url).build());download=call
            currentCoroutineContext().ensureActive()
            call.execute().use { response ->
                check(response.isSuccessful) { "Не удалось загрузить установщик: HTTP ${response.code}" }
                val body=response.body ?: error("Пустой установщик");val total=body.contentLength()
                body.byteStream().use { input -> target.outputStream().use { output ->
                    val buffer=ByteArray(65536);var read=0L
                    while(true) { currentCoroutineContext().ensureActive();val count=input.read(buffer);if(count<0) break;read+=count;check(read<=80_000_000);output.write(buffer,0,count);update(if(total>0) 5+(read*20/total).toInt() else 5,"Загрузка установщика") }
                } }
            }
            check(digest(target) == sha) { "Контрольная сумма установщика не совпала" }
            return target
        } catch(e: Exception) { target.delete();throw e }
    }
    private suspend fun exec(ssh: Session,server: DeployServer,command: String,timeout: Long): Pair<Int,String> {
        val channel=ssh.openChannel("exec") as ChannelExec
        channel.setCommand("$command 2>&1")
        channel.setInputStream((server["sudoPassword"].ifBlank { server["sshPassword"] }+"\n").byteInputStream())
        val input=channel.inputStream
        val output=StringBuilder();var pending=""
        val deadline=System.currentTimeMillis()+timeout
        try {
            channel.connect(30_000)
            val buffer=ByteArray(8192)
            while(true) {
                currentCoroutineContext().ensureActive()
                check(System.currentTimeMillis()<deadline) { "Превышено время ожидания SSH-команды" }
                while(input.available()>0) {
                    val count=input.read(buffer);if(count<0) break
                    val part=String(buffer,0,count,Charsets.UTF_8);output.append(part);pending+=part
                    while(pending.contains('\n')) {
                        val line=redact(server,pending.substringBefore('\n'));pending=pending.substringAfter('\n')
                        val stage=DeployProtocol.progress(server.kind,line)
                        if(stage!=null) state.update { it.copy(percent=stage.first,step=stage.second,installerProgress=true) }
                        else log(line)
                    }
                    if(output.length>1_000_000) output.delete(0,output.length-1_000_000)
                }
                if(channel.isClosed && input.available()==0) break
                delay(100)
            }
            if(pending.isNotBlank()) log(redact(server,pending))
            return channel.exitStatus to output.toString()
        } finally { channel.disconnect() }
    }
    private fun ownDeviceId(): String =
        NativeHashPolicy.defaultDeviceId(DeviceDetails.get().hwid)
}
