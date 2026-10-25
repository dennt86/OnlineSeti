package app.qthrone.mobile.ui

import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import app.qthrone.mobile.R
import app.qthrone.mobile.deploy.DeployServer
import app.qthrone.mobile.deploy.DeployStore
import app.qthrone.mobile.deploy.QwdttAdmin
import app.qthrone.mobile.deploy.WdttPlusAdmin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A deployed server, as the original apps open it: the address, then its actions. qWDTT manages
 * accesses through its admin API; "Управление сервером" is the deploy editor.
 */
class ServerActivity : ServerScreenActivity() {
    private lateinit var body: LinearLayout
    private lateinit var toolbar: Toolbar
    private var server: DeployServer? = null
    private val ui by lazy { ServerUi(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(MobilePalette.background))
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(MobilePalette.background) }
        toolbar = Toolbar(this).apply {
            setTitleTextColor(MobilePalette.text); setSubtitleTextColor(MobilePalette.text)
            setNavigationIcon(R.drawable.baseline_arrow_back_24); setNavigationOnClickListener { finish() }
            title = "Управление сервером"
        }
        body = ui.column().apply { setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(24)) }
        root.addView(toolbar)
        root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()); view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
    }

    override fun onResume() {
        super.onResume()
        val id = intent.getStringExtra("server") ?: return finish()
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { DeployStore.all().firstOrNull { it.id == id } }.getOrNull() }
            if (loaded == null) { Toast.makeText(this@ServerActivity, "Не удалось открыть VPS", Toast.LENGTH_LONG).show(); finish(); return@launch }
            server = loaded; render(loaded)
        }
    }

    private fun render(server: DeployServer) {
        toolbar.subtitle = server["host"]
        body.removeAllViews()
        body.addView(LinearLayout(this).apply {
            gravity = android.view.Gravity.CENTER_VERTICAL; setPadding(ui.dp(18), ui.dp(16), ui.dp(18), ui.dp(16))
            background = ui.card().background
            addView(ui.badge(R.drawable.ic_action_dns), LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)).apply { marginEnd = ui.dp(16) })
            addView(ui.column().apply {
                addView(ui.text(server["name"].ifBlank { server["host"] }, 20f))
                addView(ui.text(buildString {
                    if (server["name"].isNotBlank()) append(server["host"]).append(" · ")
                    append("SSH ").append(server["sshPort"])
                    append(" · ").append(deploymentStatus(server))
                }, 14f, MobilePalette.muted))
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        body.addView(ui.section("Действия"))
        when (server.kind) {
            "qwdtt" -> {
                val admin = QwdttAdmin(server)
                body.addView(ui.tile("Пользователи и подписки", "Доступы, лимиты устройств и сроки действия", R.drawable.ic_mobile_people) {
                    if (admin.available) startActivity(Intent(this, QwdttAccessActivity::class.java).putExtra("server", server.id))
                    else Toast.makeText(this, "Доступно после установки сервера из приложения", Toast.LENGTH_LONG).show()
                })
            }
            "wdttplus" -> listOf(
                Triple("Клиенты и сервер", "Пароли, хеши, устройства, сроки и настройки сервера", R.drawable.ic_mobile_people) to WdttPlusClientsActivity::class.java,
                Triple("Выходной IP и прокси", "Прокси, WARP, WireGuard или второй сервер для выхода", R.drawable.ic_hardware_router) to WdttPlusExitActivity::class.java,
                Triple("Резервные копии и перенос", "Расписание, проверка и выгрузка копий", R.drawable.ic_mobile_backup) to WdttPlusBackupsActivity::class.java,
            ).forEach { (tile, screen) ->
                body.addView(ui.tile(tile.first, tile.second, tile.third) {
                    if (WdttPlusAdmin(server).available) startActivity(Intent(this, screen).putExtra("server", server.id))
                    else Toast.makeText(this, "Доступно после установки сервера из приложения", Toast.LENGTH_LONG).show()
                }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
            }
        }
        body.addView(ui.gap(10))
        body.addView(ui.tile("Управление сервером", "SSH, порты, обновление и переустановка", R.drawable.ic_mobile_settings) {
            startActivity(Intent(this, DeployActivity::class.java).putExtra("kind", server.kind).putExtra("editServer", server.id)
                .putExtra("directEditor", true))
        })
    }

    private fun deploymentStatus(server: DeployServer): String {
        val operation = server["lastOperation"]
        val timestamp = server["lastOperationAt"].toLongOrNull() ?: 0L
        if (operation.isBlank() || timestamp <= 0L) return "состояние VPS не проверено"
        val result = if (operation == "install" && server["installed"] == "true") "установлен по последнему деплою"
            else if (operation == "uninstall" && server["installed"] == "false") "удалён по последней операции"
            else "результат последней операции неизвестен"
        val time = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))
        return "$result · $time"
    }
}
