package app.qthrone.mobile.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import com.google.android.material.bottomsheet.BottomSheetDialog

class MobileSheetDialog(context: Context) : BottomSheetDialog(context) {
    override fun onStart() {
        super.onStart()
        findViewById<android.view.View>(com.google.android.material.R.id.design_bottom_sheet)?.apply {
            background=GradientDrawable().apply {
                setColor(MobilePalette.surface);cornerRadius=24*resources.displayMetrics.density
            }
            clipToOutline=true
        }
    }
}
