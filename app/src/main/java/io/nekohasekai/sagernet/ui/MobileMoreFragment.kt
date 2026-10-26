package app.qthrone.mobile.ui

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.res.Configuration
import android.os.Bundle
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
import app.qthrone.mobile.BuildConfig
import app.qthrone.mobile.R
import app.qthrone.mobile.update.UpdateActivity

/** A grouped, easy-to-scan home for secondary app destinations. */
class MobileMoreFragment : ToolbarFragment() {
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val context = requireContext()
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(18))
            setBackgroundColor(MobilePalette.background)
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
            val leftInset = if (landscape) bars.left else 0
            val rightInset = if (landscape) bars.right else 0
            view.setPadding(leftInset + dp(20), bars.top + dp(12), rightInset + dp(20), dp(18))
            insets
        }

        root.addView(TextView(context).apply {
            text = "Ещё"
            textSize = 27f
            setTextColor(MobilePalette.text)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(2), 0, 0, dp(3))
        })
        root.addView(TextView(context).apply {
            text = "Инструменты и параметры OnlineSeti VPN"
            textSize = 14f
            setTextColor(MobilePalette.muted)
            setPadding(dp(2), 0, 0, dp(17))
        })

        val scroll = ScrollView(context).apply { clipToPadding = false }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(10))
        }
        // ScrollView defaults to measuring its child at wrap-content width. Keep the cards
        // stretched across the available content area on narrow phones as well.
        scroll.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        ))

        fun section(title: String, detail: String? = null) {
            val heading = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(3), dp(8), 0, dp(9))
            }
            heading.addView(TextView(context).apply {
                text = title.uppercase()
                textSize = 11f
                letterSpacing = .1f
                setTextColor(MobilePalette.accent(context))
                setTypeface(typeface, Typeface.BOLD)
            })
            if (detail != null) heading.addView(TextView(context).apply {
                text = detail
                textSize = 12f
                setTextColor(MobilePalette.muted)
                setPadding(0, dp(3), 0, 0)
            })
            content.addView(heading)
        }

        fun entry(icon: Int, title: String, description: String, callback: () -> Unit) {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(12), dp(12), dp(12))
                background = GradientDrawable().apply {
                    setColor(MobilePalette.surface)
                    cornerRadius = dp(17).toFloat()
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { callback() }

                val iconTile = FrameLayout(context).apply {
                    background = GradientDrawable().apply {
                        setColor(if (MobilePalette.light) android.graphics.Color.rgb(224, 239, 247)
                        else android.graphics.Color.rgb(21, 52, 84))
                        cornerRadius = dp(14).toFloat()
                    }
                    addView(ImageView(context).apply {
                        setImageResource(icon)
                        imageTintList = android.content.res.ColorStateList.valueOf(MobilePalette.accent(context))
                        contentDescription = null
                    }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                }
                addView(iconTile, LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(13) })

                val copy = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                copy.addView(TextView(context).apply {
                    text = title
                    textSize = 15f
                    setTextColor(MobilePalette.text)
                    setTypeface(typeface, Typeface.BOLD)
                })
                copy.addView(TextView(context).apply {
                    text = description
                    textSize = 12f
                    setTextColor(MobilePalette.muted)
                    setPadding(0, dp(4), 0, 0)
                })
                addView(copy, LinearLayout.LayoutParams(0, -2, 1f))
                addView(TextView(context).apply {
                    text = "›"
                    textSize = 23f
                    gravity = Gravity.CENTER
                    setTextColor(MobilePalette.muted)
                    minWidth = dp(23)
                })
            }
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }

        val host = requireActivity() as MainActivity
        section(
            if (BuildConfig.BUILD_TYPE == "client") "ПРИЛОЖЕНИЕ" else "ПОДКЛЮЧЕНИЕ",
            if (BuildConfig.BUILD_TYPE == "client") "Основные параметры приложения" else "Параметры сети и правила маршрутов",
        )
        entry(R.drawable.ic_mobile_settings, "Настройки", "DNS, оформление и обновления") {
            host.displayFragmentWithId(R.id.nav_settings)
        }
        if (BuildConfig.BUILD_TYPE != "client") {
            entry(R.drawable.ic_mobile_route, "Маршрутизация", "Правила для сайтов, IP и приложений") {
                host.selectPrimary(R.id.nav_route)
            }
        }

        section(
            if (BuildConfig.BUILD_TYPE == "client") "ДАННЫЕ" else "ДАННЫЕ И ДИАГНОСТИКА",
            if (BuildConfig.BUILD_TYPE == "client") "Сохранение настроек и профилей" else "Резервные копии и сведения о работе",
        )
        entry(R.drawable.ic_mobile_backup, "Резервная копия", "Сохранить или восстановить профили") {
            host.displayFragmentWithId(R.id.nav_tools)
        }
        if (BuildConfig.BUILD_TYPE != "client") {
            entry(R.drawable.ic_mobile_logs, "Журнал событий", "Посмотреть ошибки и ход подключения") {
                host.displayFragmentWithId(R.id.nav_logcat)
            }
        }

        section("ПРИЛОЖЕНИЕ")
        if (BuildConfig.IN_APP_UPDATER) {
            entry(
                R.drawable.ic_mobile_refresh,
                "Обновление приложения",
                "Версия ${BuildConfig.VERSION_NAME} · проверить и установить",
            ) {
                UpdateActivity.start(requireContext())
            }
        }
        entry(R.drawable.ic_mobile_info, "О приложении", "Версия, лицензии и информация") {
            host.displayFragmentWithId(R.id.nav_about)
        }

        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        return root
    }
}
