package app.qthrone.mobile.ui

import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.view.View
import androidx.fragment.app.Fragment
import app.qthrone.mobile.R

/** A short incoming slide; outgoing screens fade in place instead of exposing clipped edges. */
object MobileScreenMotion {
    fun create(fragment: Fragment, nextAnim: Int): Animator? {
        val view = fragment.view ?: return null
        val offset = 24 * fragment.resources.displayMetrics.density
        val incoming = nextAnim == R.anim.settings_enter || nextAnim == R.anim.settings_pop_enter
        if (nextAnim !in listOf(R.anim.settings_enter, R.anim.settings_exit, R.anim.settings_pop_enter, R.anim.settings_pop_exit)) return null
        val from = if (incoming) (if (nextAnim == R.anim.settings_pop_enter) -offset else offset) else 0f
        view.translationX = from
        view.alpha = if (incoming) 0f else 1f
        return AnimatorSet().apply {
            playTogether(
                ObjectAnimator.ofFloat(view, View.TRANSLATION_X, from, 0f),
                ObjectAnimator.ofFloat(view, View.ALPHA, if (incoming) 0f else 1f, if (incoming) 1f else 0f))
            duration = if (incoming) 180 else 90
            interpolator = android.view.animation.DecelerateInterpolator(1.5f)
        }
    }
}
