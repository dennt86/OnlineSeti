package app.qthrone.mobile.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import app.qthrone.mobile.BuildConfig
import app.qthrone.mobile.R
import app.qthrone.mobile.bg.BaseService
import app.qthrone.mobile.bg.autoselector.AutoSelectorClient
import app.qthrone.mobile.bg.autoselector.AutoSelectorStatus
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.database.SagerDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Native implementation of the focused home composition from the OnlineSeti interface preview. */
class MobileDashboardFragment : ToolbarFragment() {
    private var statePill: TextView? = null
    private var stateDot: View? = null
    private var powerIcon: ImageView? = null
    private var powerCaption: TextView? = null
    private var elapsed: TextView? = null
    private var selectedServer: TextView? = null
    private var selectedMeta: TextView? = null
    private var autoBadge: TextView? = null
    private var switchText: TextView? = null
    private var availableValue: TextView? = null
    private var routingValue: TextView? = null
    private var appRoutingValue: TextView? = null
    private var appRoutingCard: View? = null
    private var lastStatus: AutoSelectorStatus = AutoSelectorStatus.IDLE
    private var profileRefreshKey = Long.MIN_VALUE

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private val green get() = if (MobilePalette.light) Color.rgb(21, 133, 94) else Color.rgb(85, 213, 160)
    private val lineColor get() = if (MobilePalette.light) Color.rgb(205, 216, 225) else Color.rgb(36, 71, 109)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(19), dp(8), dp(19), dp(22))
            background = MobileHomeBackdrop()
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            addView(column, FrameLayout.LayoutParams(-1, -2))
        }
        // The redesigned home is edge-to-edge; keep its header below the status bar like the other tabs.
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, 0)
            insets
        }

        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val brandMark = FrameLayout(context).apply {
            background = rounded(if (MobilePalette.light) Color.rgb(224, 241, 249) else Color.rgb(18, 52, 91), 14)
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_mobile_globe)
                imageTintList = android.content.res.ColorStateList.valueOf(MobilePalette.accent(context))
                contentDescription = null
            }, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
        }
        header.addView(brandMark, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(11) })
        header.addView(TextView(context).apply {
            text = "OnlineSeti"; textSize = 18f; setTextColor(MobilePalette.text)
            setTypeface(typeface, Typeface.BOLD)
            append(android.text.SpannableString(context.getString(R.string.app_name).removePrefix("OnlineSeti")).apply {
                setSpan(android.text.style.ForegroundColorSpan(MobilePalette.muted), 0, length, 0)
                setSpan(android.text.style.StyleSpan(Typeface.NORMAL), 0, length, 0)
            })
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        column.addView(header)

        val heading = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(13))
        }
        heading.addView(TextView(context).apply {
            text = "ЗАЩИЩЁННОЕ СОЕДИНЕНИЕ"; textSize = 10f; letterSpacing = .11f
            setTextColor(MobilePalette.muted); setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val pill = LinearLayout(context).apply {
            gravity = Gravity.CENTER; orientation = LinearLayout.HORIZONTAL
            setPadding(dp(10), dp(6), dp(10), dp(6)); background = statusPill(false)
        }
        stateDot = View(context).apply { background = rounded(MobilePalette.muted, 8) }
        pill.addView(stateDot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(7) })
        statePill = TextView(context).apply {
            text = "ВЫКЛЮЧЕНО"; textSize = 10f; letterSpacing = .04f
            setTextColor(MobilePalette.muted); setTypeface(typeface, Typeface.BOLD)
        }
        pill.addView(statePill)
        heading.addView(pill)
        column.addView(heading)

        val connectCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(18), dp(15), dp(18), dp(15))
            background = connectSurface()
        }
        val powerFrame = FrameLayout(context).apply {
            background = powerRing()
            isClickable = true; isFocusable = true
            setOnClickListener { (requireActivity() as MainActivity).toggleService() }
            contentDescription = "Переключить VPN"
        }
        powerIcon = ImageView(context).apply {
            setImageResource(R.drawable.ic_mobile_power)
            imageTintList = android.content.res.ColorStateList.valueOf(MobilePalette.accent(context))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        powerFrame.addView(powerIcon, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
        connectCard.addView(powerFrame, LinearLayout.LayoutParams(dp(104), dp(104)).apply { bottomMargin = dp(10) })
        powerCaption = TextView(context).apply {
            text = "Нажмите, чтобы подключиться"; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(MobilePalette.text); setTypeface(typeface, Typeface.BOLD)
        }
        connectCard.addView(powerCaption)
        elapsed = TextView(context).apply {
            text = ""; textSize = 12f; gravity = Gravity.CENTER; setTextColor(MobilePalette.muted)
            setPadding(0, dp(4), 0, 0)
        }
        connectCard.addView(elapsed)
        column.addView(connectCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        val connection = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(14), dp(15), dp(13))
            background = connectionSurface()
            isClickable = true; isFocusable = true
            setOnClickListener {
                val selectorId = AutoSelectorClient.status.value.selectorId
                if (selectorId > 0) startActivity(AutoSelectorStatusActivity.intent(requireContext(), selectorId))
                else (requireActivity() as MainActivity).selectPrimary(R.id.nav_mobile_servers)
            }
        }
        val connectionTop = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        connectionTop.addView(TextView(context).apply {
            text = "ПОДКЛЮЧЕНО ЧЕРЕЗ"; textSize = 10f; letterSpacing = .1f
            setTextColor(MobilePalette.muted); setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        autoBadge = TextView(context).apply {
            text = "АВТО · ВКЛЮЧЕНО"; textSize = 9f; letterSpacing = .02f
            setTextColor(green); setPadding(dp(8), dp(5), dp(8), dp(5)); background = badgeSurface()
        }
        connectionTop.addView(autoBadge)
        connection.addView(connectionTop)
        selectedServer = TextView(context).apply {
            text = "Выберите сервер"; textSize = 17f; setTextColor(MobilePalette.text)
            setTypeface(typeface, Typeface.BOLD); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(7), 0, 0)
        }
        connection.addView(selectedServer)
        selectedMeta = TextView(context).apply {
            text = "Список серверов и проверка связи"; textSize = 12f; setTextColor(MobilePalette.muted)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0)
        }
        connection.addView(selectedMeta)
        val divider = View(context).apply { setBackgroundColor(Color.argb(24, 255, 255, 255)) }
        connection.addView(divider, LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(12); bottomMargin = dp(9) })
        val footer = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        switchText = TextView(context).apply {
            text = "Автопереключение ожидает запуск"; textSize = 11f; setTextColor(MobilePalette.muted)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        footer.addView(switchText, LinearLayout.LayoutParams(0, -2, 1f))
        footer.addView(TextView(context).apply {
            text = "›"; textSize = 21f; gravity = Gravity.CENTER
            setTextColor(MobilePalette.muted); minWidth = dp(24)
        })
        connection.addView(footer)
        column.addView(connection, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(12) })

        if (BuildConfig.BUILD_TYPE != "client") {
            val quick = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            quick.addView(summaryCard("УЗЛОВ ДОСТУПНО", "—") { (requireActivity() as MainActivity).selectPrimary(R.id.nav_mobile_servers) }
                .also { availableValue = it.tag as? TextView },
                LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginEnd = dp(5) })
            quick.addView(summaryCard("МАРШРУТИЗАЦИЯ", "Настроить") {
                (requireActivity() as MainActivity).selectPrimary(R.id.nav_route)
            }.also { routingValue = it.tag as? TextView },
                LinearLayout.LayoutParams(0, dp(72), 1f).apply { marginStart = dp(5) })
            column.addView(quick)

        }

        column.addView(summaryCard("МАРШРУТИЗАЦИЯ ПРИЛОЖЕНИЙ", "Настроить исключения") {
            startActivity(Intent(requireContext(), AppManagerActivity::class.java))
        }.also {
            appRoutingCard = it
            appRoutingValue = it.tag as? TextView
        }, LinearLayout.LayoutParams(-1, dp(62)).apply { topMargin = dp(10) })

        val support = LinearLayout(context).apply {
            gravity = Gravity.CENTER; background = roundedOutline(); setPadding(dp(12), dp(11), dp(12), dp(11))
            isClickable = true; isFocusable = true
            setOnClickListener { runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(OnlinesetiLinks.BOT))) } }
        }
        support.addView(TextView(context).apply {
            text = "ⓘ"; textSize = 17f; setTextColor(MobilePalette.accent(context))
        }, LinearLayout.LayoutParams(dp(28), -2))
        support.addView(TextView(context).apply {
            text = "Подписка и поддержка"; textSize = 13f; setTextColor(MobilePalette.text)
            setTypeface(typeface, Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        support.addView(TextView(context).apply { text = "›"; textSize = 22f; setTextColor(MobilePalette.muted) })
        column.addView(support, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(11) })
        return scroll
    }

    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) { updateConnection(); delay(700) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            AutoSelectorClient.status.collect { lastStatus = it; updateSelector(it) }
        }
        loadProfileCount()
    }

    override fun onResume() {
        super.onResume(); updateConnection(); updateSelector(AutoSelectorClient.status.value); loadProfileCount()
    }

    private fun updateConnection() {
        val state = DataStore.serviceState
        val connected = state.connected
        statePill?.text = when (state) {
            BaseService.State.Connected -> "ПОДКЛЮЧЕНО"
            BaseService.State.Connecting -> if (DataStore.waitingForPhysicalNetwork) "ОЖИДАНИЕ СЕТИ" else "ПОДКЛЮЧЕНИЕ"
            BaseService.State.Stopping -> "ОТКЛЮЧЕНИЕ"
            else -> "ВЫКЛЮЧЕНО"
        }
        val color = when (state) {
            BaseService.State.Connected -> green
            BaseService.State.Connecting, BaseService.State.Stopping -> Color.rgb(255, 201, 120)
            else -> MobilePalette.muted
        }
        statePill?.setTextColor(color)
        (statePill?.parent as? View)?.background = statusPill(connected || state == BaseService.State.Connecting)
        stateDot?.background = rounded(color, 8)
        powerCaption?.text = when (state) {
            BaseService.State.Connected -> "Подключено"
            BaseService.State.Connecting -> if (DataStore.waitingForPhysicalNetwork) "Ожидание сети…" else "Подключение…"
            BaseService.State.Stopping -> "Отключение…"
            else -> "Нажмите, чтобы подключиться"
        }
        elapsed?.text = if (connected && DataStore.mobileConnectedAt > 0) {
            "Защита включена · ${formatElapsed((SystemClock.elapsedRealtime() - DataStore.mobileConnectedAt).coerceAtLeast(0) / 1000)}"
        } else ""
        powerIcon?.imageTintList = android.content.res.ColorStateList.valueOf(if (connected) MobilePalette.accent(requireContext()) else MobilePalette.muted)
        updateRoutingCard()
        updateSelector(lastStatus)
    }

    private fun updateSelector(value: AutoSelectorStatus) {
        val connected = DataStore.serviceState.connected
        val auto = connected && value.active
        autoBadge?.text = if (auto) "АВТО · ВКЛЮЧЕНО" else if (connected) "РУЧНОЙ РЕЖИМ" else "ОЖИДАНИЕ"
        autoBadge?.setTextColor(if (auto) green else MobilePalette.muted)
        autoBadge?.background = badgeSurface(auto)
        if (auto) {
            selectedServer?.text = value.selectedName.ifBlank { "Выбор сервера…" }
            selectedMeta?.text = value.selectorName.ifBlank { "Автоматический выбор · ${value.membersAlive}/${value.membersTotal} доступно" }
            switchText?.text = when {
                value.suspended -> "Ожидание сети"
                value.lastSwitchMs > 0 -> "Последнее переключение · ${AutoSelectorTexts.ago(requireContext(), value.lastSwitchMs)}"
                else -> "При сбое выберем другой доступный узел"
            }
            if (value.membersTotal > 0) availableValue?.text = "${value.membersAlive} из ${value.membersTotal}"
        } else if (connected) {
            switchText?.text = "Автопереключение выключено"
            val key = DataStore.selectedProxy
            if (profileRefreshKey != key) {
                profileRefreshKey = key
                viewLifecycleOwner.lifecycleScope.launch {
                    val profile = withContext(Dispatchers.IO) { SagerDatabase.proxyDao.getById(key) }
                    if (DataStore.selectedProxy == key && !AutoSelectorClient.status.value.active) {
                        selectedServer?.text = profile?.displayName() ?: "Текущий профиль"
                        selectedMeta?.text = profile?.displayType() ?: "Подключение активно"
                    }
                }
            }
        } else {
            selectedServer?.text = "VPN не подключён"
            selectedMeta?.text = "После подключения здесь появится активный сервер"
            switchText?.text = "Автоматический выбор будет показан здесь"
        }
    }

    private fun loadProfileCount() {
        if (!isAdded || availableValue == null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) { SagerDatabase.proxyDao.getAll().size }
            val status = AutoSelectorClient.status.value
            availableValue?.text = if (status.active && status.membersTotal > 0) "${status.membersAlive} из ${status.membersTotal}" else "$count профилей"
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val route = withContext(Dispatchers.IO) {
                runCatching {
                    app.qthrone.mobile.database.RouteManager.current().let {
                        it.default_outbound_id to !it.isEmpty()
                    }
                }.getOrNull()
            }
            val routeText = when {
                route == null -> "Настроить"
                route.second -> "По правилам"
                route.first == app.qthrone.mobile.route.OutboundIds.DIRECT -> "Напрямую"
                route.first == app.qthrone.mobile.route.OutboundIds.BLOCK -> "Блокировка"
                route.first == app.qthrone.mobile.route.OutboundIds.WARP_BYPASS -> "Обход WARP"
                else -> "Через прокси"
            }
            if (!(DataStore.proxyApps && DataStore.bypass)) routingValue?.text = routeText
        }
    }

    private fun updateRoutingCard() {
        val bypassEnabled = DataStore.proxyApps && DataStore.bypass
        val selectedCount = DataStore.individual.lineSequence().count { it.isNotBlank() }
        if (bypassEnabled) {
            appRoutingValue?.text = if (selectedCount == 0) "Список пуст" else "Напрямую · $selectedCount"
            appRoutingCard?.contentDescription = if (selectedCount == 0) {
                "Список приложений для обхода VPN пуст. Нажмите, чтобы настроить."
            } else {
                "$selectedCount приложений направляются напрямую в обход туннеля. Они всё ещё могут определить, что VPN включён. Нажмите, чтобы настроить."
            }
        } else if (DataStore.proxyApps) {
            appRoutingValue?.text = if (selectedCount == 0) "Список VPN пуст" else "Через VPN · $selectedCount"
            appRoutingCard?.contentDescription = "Через VPN идут только приложения из списка. Нажмите, чтобы настроить."
        } else {
            appRoutingValue?.text = "Все приложения через VPN"
            appRoutingCard?.contentDescription = "Все приложения используют VPN. Нажмите, чтобы настроить исключения."
        }
    }

    private fun formatElapsed(seconds: Long): String {
        val hours = seconds / 3600
        val minutes = seconds / 60 % 60
        val remainder = seconds % 60
        return if (hours > 0) "%02d:%02d:%02d".format(hours, minutes, remainder) else "%02d:%02d".format(minutes, remainder)
    }

    private fun summaryCard(title: String, initial: String, callback: () -> Unit) = LinearLayout(requireContext()).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(9), dp(10), dp(8)); background = roundedOutline(17)
        isClickable = true; isFocusable = true; setOnClickListener { callback() }
        addView(TextView(requireContext()).apply {
            text = title; textSize = 9f; letterSpacing = .05f; setTextColor(MobilePalette.muted)
            setTypeface(typeface, Typeface.BOLD); maxLines = 1
        })
        val value = TextView(requireContext()).apply {
            text = initial; textSize = 13f; setTextColor(MobilePalette.text)
            setTypeface(typeface, Typeface.BOLD); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(5), 0, 0)
        }
        addView(value); tag = value
    }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun roundedOutline(radius: Int = 15) = GradientDrawable().apply {
        setColor(if (MobilePalette.light) MobilePalette.surface else Color.rgb(12, 34, 64))
        cornerRadius = dp(radius).toFloat(); setStroke(dp(1), lineColor)
    }
    private fun connectSurface() = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        if (MobilePalette.light) intArrayOf(MobilePalette.surface, MobilePalette.surface)
        else intArrayOf(Color.rgb(16, 45, 82), Color.rgb(10, 32, 64))).apply {
        cornerRadius = dp(26).toFloat(); setStroke(dp(1), lineColor)
    }
    private fun powerRing() = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (MobilePalette.light) Color.rgb(235, 247, 252) else Color.rgb(13, 39, 72))
        setStroke(dp(1), if (MobilePalette.light) MobilePalette.accent(requireContext()) else Color.rgb(56, 174, 226))
    }
    private fun connectionSurface() = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        if (MobilePalette.light) intArrayOf(Color.rgb(237, 248, 245), Color.rgb(230, 243, 241))
        else intArrayOf(Color.rgb(16, 47, 71), Color.rgb(13, 40, 66))).apply {
        cornerRadius = dp(21).toFloat(); setStroke(dp(1), if (MobilePalette.light) Color.rgb(133, 197, 173) else Color.rgb(42, 98, 71))
    }
    private fun badgeSurface(enabled: Boolean = true) = GradientDrawable().apply {
        setColor(if (enabled) if (MobilePalette.light) Color.rgb(221, 242, 233) else Color.rgb(18, 58, 51) else if (MobilePalette.light) Color.rgb(232, 236, 239) else Color.rgb(32, 48, 67))
        cornerRadius = dp(99).toFloat(); setStroke(dp(1), if (enabled) if (MobilePalette.light) Color.rgb(139, 202, 175) else Color.rgb(43, 116, 88) else lineColor)
    }
    private fun statusPill(enabled: Boolean) = GradientDrawable().apply {
        setColor(if (enabled) if (MobilePalette.light) Color.rgb(223, 243, 235) else Color.rgb(11, 55, 46) else if (MobilePalette.light) Color.rgb(232, 236, 239) else Color.rgb(24, 43, 66))
        cornerRadius = dp(99).toFloat(); setStroke(dp(1), if (enabled) if (MobilePalette.light) Color.rgb(126, 191, 164) else Color.rgb(40, 115, 85) else lineColor)
    }
}
