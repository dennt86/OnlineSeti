package app.qthrone.mobile.ui

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import app.qthrone.mobile.ui.MobileAlertDialogBuilder as MaterialAlertDialogBuilder
import app.qthrone.mobile.R
import app.qthrone.mobile.database.RouteManager
import app.qthrone.mobile.database.SagerDatabase
import app.qthrone.mobile.ktx.needReload
import app.qthrone.mobile.route.MobileRouteInput
import app.qthrone.mobile.route.OutboundIds
import app.qthrone.mobile.route.RouteProfile
import app.qthrone.mobile.route.RouteRule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Compact routing overview with editable default route and rule cards. */
class MobileRoutingFragment : ToolbarFragment() {
    private var column: LinearLayout? = null
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private val cardColor get() = if (MobilePalette.light) MobilePalette.surface else Color.rgb(12, 34, 64)
    private val strokeColor get() = if (MobilePalette.light) Color.rgb(210, 221, 230) else Color.rgb(32, 63, 99)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(24))
            setBackgroundColor(MobilePalette.background)
        }
        return ScrollView(context).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(MobilePalette.background)
            addView(column, FrameLayout.LayoutParams(-1, -2))
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
    }

    override fun onViewCreated(view: View, state: Bundle?) { super.onViewCreated(view, state); render() }
    override fun onResume() { super.onResume(); render() }
    override fun onDestroyView() { column = null; super.onDestroyView() }

    private fun render() {
        viewLifecycleOwner.lifecycleScope.launch {
            val route = withContext(Dispatchers.IO) { RouteManager.current().copy() }
            val profiles = withContext(Dispatchers.IO) { SagerDatabase.proxyDao.getAll() }
            val root = column ?: return@launch
            root.removeAllViews()
            root.addView(TextView(requireContext()).apply {
                text = "Маршруты"; textSize = 26f; setTextColor(MobilePalette.text)
                setTypeface(typeface, Typeface.BOLD); setPadding(0, 0, 0, dp(4))
            })
            root.addView(TextView(requireContext()).apply {
                text = "Куда направлять интернет-трафик"; textSize = 14f
                setTextColor(MobilePalette.muted); setPadding(0, 0, 0, dp(19))
            })

            section("ОСНОВНОЕ СОЕДИНЕНИЕ")
            val defaultCard = cardRow(R.drawable.ic_mobile_globe, "Основной маршрут",
                "По умолчанию весь трафик направляется сюда", target(route.default_outbound_id, profiles), true)
            defaultCard.setOnClickListener {
                chooseTarget(route.default_outbound_id) { id -> save(route) { it.default_outbound_id = id } }
            }
            root.addView(defaultCard, cardParams(bottom = 10))

            val actions = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
            actions.addView(TextView(requireContext()).apply {
                text = "Правила"; textSize = 18f; setTextColor(MobilePalette.text)
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
            val add = TextView(requireContext()).apply {
                text = "+  Добавить"; textSize = 13f; setTextColor(MobilePalette.accent(requireContext()))
                gravity = Gravity.CENTER; minHeight = dp(44); minWidth = dp(106)
                background = pillBackground(); isFocusable = true
                setOnClickListener { newRule(route) }
            }
            actions.addView(add)
            root.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6); bottomMargin = dp(4) })
            root.addView(TextView(requireContext()).apply {
                text = "Правила применяются сверху вниз. Нажмите карточку, чтобы изменить маршрут или удалить правило."
                textSize = 12f; setTextColor(MobilePalette.muted); setPadding(0, 0, 0, dp(9))
            })

            if (route.rules.isEmpty()) {
                val empty = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                    background = cardBackground(); setPadding(dp(20), dp(24), dp(20), dp(24))
                    addView(TextView(requireContext()).apply {
                        text = "Пока нет отдельных правил"; textSize = 15f
                        setTextColor(MobilePalette.text); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
                    })
                    addView(TextView(requireContext()).apply {
                        text = "Добавьте домен, IP-сеть или приложение, чтобы направить его напрямую или через VPN."
                        textSize = 12f; setTextColor(MobilePalette.muted); gravity = Gravity.CENTER
                        setPadding(0, dp(7), 0, 0)
                    })
                }
                root.addView(empty, cardParams(top = 2))
            } else {
                for ((index, rule) in route.rules.withIndex()) {
                    val match = rule.domain_suffix + rule.domain + rule.ip_cidr + rule.package_name
                    val title = if (rule.action == "hijack-dns" && rule.protocol == "dns" && rule.name == "Route DNS") {
                        getString(R.string.qmobile_route_dns_rule)
                    } else {
                        rule.name.ifBlank { match.joinToString(", ").ifBlank { getString(R.string.qmobile_route_advanced) } }
                    }
                    val detail = match.joinToString(", ").ifBlank { getString(R.string.qmobile_route_advanced) }
                    val icon = when {
                        rule.package_name.isNotEmpty() -> R.drawable.ic_mobile_core
                        rule.ip_cidr.isNotEmpty() -> R.drawable.ic_mobile_globe
                        else -> R.drawable.ic_mobile_link
                    }
                    val row = cardRow(icon, title, detail, target(rule.outbound_id, profiles), false)
                    row.setOnClickListener { ruleMenu(route, rule, index, title) }
                    root.addView(row, cardParams(bottom = 8))
                }
            }
            val advanced = cardRow(R.drawable.ic_mobile_settings, "Дополнительные параметры",
                "Профили, геоданные и стратегии выбора IP", "›", false)
            advanced.setOnClickListener {
                (requireActivity() as MainActivity).displayFragment(
                    SettingsFragment.forScreenRoot("app.qthrone.mobile.ui.settings.RoutingSettingsFragment", getString(R.string.qmobile_route_advanced))
                )
            }
            root.addView(advanced, cardParams(top = 13))
        }
    }

    private fun target(id: Long, profiles: List<app.qthrone.mobile.database.ProxyEntity>): String = when (id) {
        OutboundIds.PROXY -> getString(R.string.qmobile_route_main)
        OutboundIds.DIRECT -> getString(R.string.qmobile_route_direct)
        else -> profiles.firstOrNull { it.id == id }?.displayName() ?: "Профиль $id"
    }

    private fun section(title: String) {
        column?.addView(TextView(requireContext()).apply {
            text = title; textSize = 10f; letterSpacing = .11f; setTextColor(MobilePalette.accent(requireContext()))
            setTypeface(typeface, Typeface.BOLD); setPadding(dp(2), 0, 0, dp(8))
        })
    }

    private fun cardRow(icon: Int, title: String, detail: String, value: String, primary: Boolean): LinearLayout {
        val root = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(12), dp(12), dp(12)); background = cardBackground()
            isFocusable = true; isClickable = true
        }
        val iconBox = FrameLayout(requireContext()).apply { background = iconBackground() }
        iconBox.addView(ImageView(requireContext()).apply {
            setImageResource(icon); imageTintList = android.content.res.ColorStateList.valueOf(MobilePalette.accent(requireContext()))
            contentDescription = null
        }, FrameLayout.LayoutParams(dp(21), dp(21), Gravity.CENTER))
        root.addView(iconBox, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(12) })
        val copy = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
        copy.addView(TextView(requireContext()).apply {
            text = title; textSize = 14f; setTextColor(MobilePalette.text)
            setTypeface(typeface, Typeface.BOLD); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        copy.addView(TextView(requireContext()).apply {
            text = detail; textSize = 11f; setTextColor(MobilePalette.muted)
            maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0)
        })
        root.addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(TextView(requireContext()).apply {
            text = value; textSize = if (primary) 12f else 11f
            setTextColor(if (primary) MobilePalette.accent(requireContext()) else MobilePalette.muted)
            setTypeface(typeface, if (primary) Typeface.BOLD else Typeface.NORMAL)
            maxWidth = dp(110); maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.END or Gravity.CENTER_VERTICAL; setPadding(dp(6), 0, 0, 0)
        })
        return root
    }

    private fun cardParams(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = dp(top); bottomMargin = dp(bottom)
    }
    private fun cardBackground() = GradientDrawable().apply {
        setColor(cardColor); cornerRadius = dp(17).toFloat(); setStroke(dp(1), strokeColor)
    }
    private fun iconBackground() = GradientDrawable().apply {
        setColor(if (MobilePalette.light) Color.rgb(224, 239, 247) else Color.rgb(21, 52, 84))
        cornerRadius = dp(13).toFloat()
    }
    private fun pillBackground() = GradientDrawable().apply {
        setColor(if (MobilePalette.light) Color.rgb(228, 243, 250) else Color.rgb(21, 62, 98))
        cornerRadius = dp(14).toFloat(); setStroke(dp(1), strokeColor)
    }

    private fun ruleMenu(route: RouteProfile, rule: RouteRule, index: Int, title: String) {
        MaterialAlertDialogBuilder(requireContext()).setTitle(title)
            .setItems(arrayOf(getString(R.string.qmobile_route_target), getString(R.string.qmobile_route_remove))) { _, action ->
                if (action == 0) chooseTarget(rule.outbound_id) { id -> save(route) { it.rules[index].outbound_id = id } }
                else MaterialAlertDialogBuilder(requireContext()).setMessage(R.string.qmobile_route_remove)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ -> save(route) { it.rules.removeAt(index) } }.show()
            }.show()
    }

    private fun save(snapshot: RouteProfile, change: (RouteProfile) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            val saved = withContext(Dispatchers.IO) {
                val current = RouteManager.get(snapshot.id) ?: return@withContext false
                if (current.is_remote || current.prevent_modifications) return@withContext false
                if (current.rules.map { it.toRuleJson(false).toString() } != snapshot.rules.map { it.toRuleJson(false).toString() } ||
                    current.default_outbound_id != snapshot.default_outbound_id) return@withContext false
                change(current); RouteManager.save(current); true
            }
            if (saved) needReload()
            render()
        }
    }

    private fun chooseTarget(selected: Long, chosen: (Long) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            val profiles = withContext(Dispatchers.IO) { SagerDatabase.proxyDao.getAll() }
            val ids = listOf(OutboundIds.PROXY, OutboundIds.DIRECT) + profiles.map { it.id }
            val labels = listOf(getString(R.string.qmobile_route_main), getString(R.string.qmobile_route_direct)) + profiles.map { it.displayName() }
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.qmobile_route_target)
                .setSingleChoiceItems(labels.toTypedArray(), ids.indexOf(selected)) { dialog, index -> dialog.dismiss(); chosen(ids[index]) }
                .setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    private fun newRule(route: RouteProfile) {
        if (route.is_remote || route.prevent_modifications) return
        val kinds = arrayOf(getString(R.string.qmobile_route_domains), getString(R.string.qmobile_route_ips), getString(R.string.qmobile_route_apps))
        MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.qmobile_route_add).setItems(kinds) { _, kind ->
            if (kind == 2) { chooseApps(route); return@setItems }
            val field = EditText(requireContext()).apply { hint = kinds[kind]; minLines = 3; maxLines = 8 }
            val dialog = MaterialAlertDialogBuilder(requireContext()).setTitle(kinds[kind]).setView(field)
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok, null).create()
            dialog.setOnShowListener {
                dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val values = runCatching {
                        if (kind == 0) MobileRouteInput.domains(field.text.toString()) else MobileRouteInput.networks(field.text.toString())
                    }.getOrElse { field.error = getString(R.string.qmobile_route_invalid); return@setOnClickListener }
                    dialog.dismiss()
                    chooseTarget(OutboundIds.DIRECT) { target ->
                        val rule = RouteRule().apply {
                            name = values.joinToString(", "); outbound_id = target
                            if (kind == 0) domain_suffix.addAll(values) else ip_cidr.addAll(values)
                        }
                        save(route) { it.rules.add(rule) }
                    }
                }
            }
            dialog.show()
        }.show()
    }

    private fun chooseApps(route: RouteProfile) {
        viewLifecycleOwner.lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                val pm = requireContext().packageManager
                @Suppress("DEPRECATION")
                pm.getInstalledApplications(0).filter { it.packageName != requireContext().packageName }
                    .map { it.packageName to pm.getApplicationLabel(it).toString() }.sortedBy { it.second.lowercase() }
            }
            val checked = BooleanArray(apps.size)
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.qmobile_route_apps)
                .setMultiChoiceItems(apps.map { "${it.second}\n${it.first}" }.toTypedArray(), checked) { _, index, value -> checked[index] = value }
                .setNegativeButton(android.R.string.cancel, null).setPositiveButton(android.R.string.ok) { _, _ ->
                    val selected = apps.filterIndexed { index, _ -> checked[index] }
                    if (selected.isNotEmpty()) chooseTarget(OutboundIds.DIRECT) { target ->
                        save(route) {
                            it.rules.add(RouteRule().apply {
                                name = selected.joinToString(", ") { app -> app.second }
                                package_name.addAll(selected.map { app -> app.first }); outbound_id = target
                            })
                        }
                    }
                }.show()
        }
    }
}
