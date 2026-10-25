package app.qthrone.mobile.ui

import android.content.Context
import android.graphics.Color
import app.qthrone.mobile.database.DataStore
import app.qthrone.mobile.utils.Theme
import com.google.android.material.color.MaterialColors

object MobilePalette {
    val oled get() = DataStore.amoledTheme && Theme.usingNightMode()
    val light get() = !oled && !Theme.usingNightMode()
    val background get() = if (oled) Color.BLACK else if (light) Color.rgb(236,237,239) else Color.rgb(3,19,55)
    val surface get() = if (oled) Color.rgb(15,15,15) else if (light) Color.rgb(248,249,250) else Color.rgb(7,36,78)
    val toolbar get() = if (oled) Color.BLACK else if (light) Color.rgb(231,233,235) else Color.rgb(4,26,67)
    val text get() = if (light) Color.rgb(39,44,48) else Color.rgb(245,251,255)
    val muted get() = if (light) Color.rgb(100,110,119) else Color.rgb(162,201,229)
    fun accent(context: Context) = if (!DataStore.useSystemTheme && DataStore.appTheme == Theme.CYAN) Color.rgb(67,199,255)
        else MaterialColors.getColor(context, androidx.appcompat.R.attr.colorAccent, Color.rgb(67,199,255))
}
