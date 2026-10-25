package app.qthrone.mobile.deploy

object DeployProtocol {
    fun stagedFiles(kind: String): List<String> = when (kind) {
        "qwdtt" -> listOf("/tmp/deploy.sh", "/tmp/wdtt-server", "/tmp/wdtt-main.password", "/tmp/wdtt-admin.token", "/tmp/wdtt-bot.token")
        else -> emptyList()
    }

    fun safeQwdttServer(bytes: ByteArray): Boolean {
        val text=String(bytes,Charsets.ISO_8859_1)
        return !text.contains("/etc/wireguard") && (!text.contains("wg0") || text.contains("wdtt0"))
    }
    fun progress(kind: String,line: String): Pair<Int,String>? {
        val prefix=if(kind=="qwdtt" || kind=="wdttplus") "WDTT_PROGRESS" else return null
        val parts=line.trim().split('|',limit=3)
        if(parts.size!=3 || parts[0]!=prefix) return null
        val value=parts[1].toDoubleOrNull() ?: return null
        if(!value.isFinite() || value !in 0.0..1.0) return null
        return (value*100).toInt().coerceIn(0,99) to parts[2]
    }
    fun quote(value: String) = "'"+value.replace("'","'\"'\"'")+"'"

    /** What a VPS holds before a WDTT Plus deploy; `deploy.sh uninstall` keeps /etc/wdtt, leaving [DataOnly]. */
    enum class WdttPlusData { None, Installed, DataOnly, Partial }
    const val WDTT_PLUS_PROBE = "[ -s /etc/wdtt/passwords.json ] && echo WDTT_DB=1 || echo WDTT_DB=0; " +
        "[ -e /etc/systemd/system/wdtt.service ] && echo WDTT_UNIT=1 || echo WDTT_UNIT=0; " +
        "[ -e /usr/local/bin/wdtt-server ] && echo WDTT_BIN=1 || echo WDTT_BIN=0"
    fun wdttPlusData(probe: String): WdttPlusData {
        fun has(name: String) = Regex("""(?m)^$name=1\s*$""").containsMatchIn(probe)
        val unit=has("WDTT_UNIT");val bin=has("WDTT_BIN")
        return when {
            !has("WDTT_DB") -> WdttPlusData.None
            unit && bin -> WdttPlusData.Installed
            !unit && !bin -> WdttPlusData.DataOnly
            else -> WdttPlusData.Partial
        }
    }
    /**
     * WDTT Plus's markPreservedAndroidData: lets deploy.sh preserve data that has no service or server beside it.
     * Each check is wrapped: set -e ignores a failure left of &&, so the original marks even without wg-keys.dat.
     */
    const val WDTT_PLUS_MARK_PRESERVED = "set -e; " +
        "{ [ -d /etc/wdtt ] && [ ! -L /etc/wdtt ]; } || exit 1; " +
        "{ [ -f /etc/wdtt/passwords.json ] && [ ! -L /etc/wdtt/passwords.json ]; } || exit 1; " +
        "{ [ -f /etc/wdtt/wg-keys.dat ] && [ ! -L /etc/wdtt/wg-keys.dat ]; } || exit 1; " +
        "printf '%s\\n' 'Preserved by WDTT Plus Android deploy' > /etc/wdtt/.android-deploy-preserved.new; " +
        "chmod 600 /etc/wdtt/.android-deploy-preserved.new; " +
        "mv -f /etc/wdtt/.android-deploy-preserved.new /etc/wdtt/.android-deploy-preserved; " +
        "echo WDTT_PRESERVED_MARKER=ready"
    fun wdttPlusMarked(output: String) = Regex("""(?m)^WDTT_PRESERVED_MARKER=ready\s*$""").containsMatchIn(output)
    fun successful(kind: String,exit: Int,output: String): Boolean = exit == 0 && when(kind) {
        "qwdtt" -> output.contains("WDTT_DEPLOY_OK") && Regex("WDTT_ADMIN_PIN\\|(sha256/[A-Za-z0-9+/=]+)").containsMatchIn(output)
        "wdttplus" -> Regex("""(?m)^WDTT_INSTALL_RESULT=success\s*$""").containsMatchIn(output)
        else -> false
    }
}
