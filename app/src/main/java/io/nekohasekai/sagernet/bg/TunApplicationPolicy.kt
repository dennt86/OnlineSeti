package app.qthrone.mobile.bg

/** Native probe subprocesses cannot protect their sockets; exclude the app UID from every TUN. */
internal object TunApplicationPolicy {
    data class Packages(val included: List<String>, val excluded: List<String>)
    fun resolve(app: String, included: List<String>, excluded: List<String>, onlySelected: Boolean = false, installed: List<String> = emptyList()): Packages {
        // Android treats an empty addAllowedApplication list as "all apps". In whitelist mode
        // with no selection, explicitly exclude every known package so the result is "none".
        if(onlySelected && included.isEmpty()) return Packages(emptyList(), (installed+excluded+app).distinct())
        if(included.isEmpty()) return Packages(emptyList(), (excluded+app).distinct())
        val effective=included.filter { it!=app }.distinct()
        require(effective.isNotEmpty()) { "Select at least one application other than OnlineSeti VPN" }
        return Packages(effective,emptyList())
    }
}
