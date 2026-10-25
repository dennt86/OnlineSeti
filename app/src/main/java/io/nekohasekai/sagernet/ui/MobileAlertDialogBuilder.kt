package app.qthrone.mobile.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.ContextThemeWrapper
import app.qthrone.mobile.utils.Theme
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Use the same surface and shape for editors, confirmations and preference dialogs. */
class MobileAlertDialogBuilder(context: Context) : MaterialAlertDialogBuilder(
    ContextThemeWrapper(context, Theme.getTheme()).apply { Theme.apply(this) }
) {
    override fun create(): androidx.appcompat.app.AlertDialog = super.create().apply {
        val density = context.resources.displayMetrics.density
        val surface = GradientDrawable().apply {
            setColor(MobilePalette.surface)
            cornerRadius = 20 * density
        }
        window?.setBackgroundDrawable(InsetDrawable(surface, (24 * density).toInt()))
    }
}
