package app.qthrone.mobile.ui

import android.os.Bundle
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.GravityCompat
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import app.qthrone.mobile.R
import app.qthrone.mobile.ui.settings.SettingsScreenFragment
import app.qthrone.mobile.utils.Theme
import app.qthrone.mobile.widget.applyListInsets

/** Hosts the settings root and its sub-screens; back and the toolbar arrow return to the previous screen. */
class SettingsFragment : ToolbarFragment(R.layout.layout_config_settings),
    PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            childFragmentManager.popBackStack()
        }
    }

    private val backStackListener = FragmentManager.OnBackStackChangedListener { syncToolbar() }

    /** Every settings screen's list scrolls under the navigation bar and clears side bars. */
    private val listInsets = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentStarted(fm: FragmentManager, f: Fragment) {
            val dialog=(f as? androidx.fragment.app.DialogFragment)?.dialog as? androidx.appcompat.app.AlertDialog ?: return
            for(id in listOf(-1,-2,-3)) dialog.getButton(id)?.setTextColor(MobilePalette.accent(requireContext()))
        }
        override fun onFragmentViewCreated(fm: FragmentManager, f: Fragment, v: View, savedInstanceState: Bundle?) {
            (f as? PreferenceFragmentCompat)?.listView?.applyListInsets()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        childFragmentManager.registerFragmentLifecycleCallbacks(listInsets, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<View>(R.id.appbar)?.setBackgroundColor(MobilePalette.toolbar)

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        childFragmentManager.addOnBackStackChangedListener(backStackListener)

        // A recreated activity (theme switch) restores the open sub-screen and its back stack.
        if (childFragmentManager.findFragmentById(R.id.settings) == null) {
            val initialScreen = arguments?.getString(ARG_SCREEN)
            if (initialScreen != null && arguments?.getBoolean(ARG_SCREEN_ROOT) == true) {
                val fragment = childFragmentManager.fragmentFactory.instantiate(requireContext().classLoader, initialScreen)
                fragment.arguments = Bundle().apply {
                    putCharSequence(SettingsScreenFragment.ARG_TITLE, arguments?.getCharSequence(ARG_SCREEN_TITLE))
                }
                childFragmentManager.beginTransaction()
                    .replace(R.id.settings, fragment)
                    .commitNowAllowingStateLoss()
            } else {
                childFragmentManager.beginTransaction()
                    .replace(R.id.settings, SettingsPreferenceFragment())
                    .commitNowAllowingStateLoss()
                initialScreen?.let { name ->
                    openScreen(name, arguments?.getCharSequence(ARG_SCREEN_TITLE), Bundle())
                }
            }
        }
        syncToolbar()
    }

    override fun onDestroyView() {
        childFragmentManager.removeOnBackStackChangedListener(backStackListener)
        super.onDestroyView()
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val name = pref.fragment ?: return false
        openScreen(name, pref.title, pref.extras)
        return true
    }

    private fun openScreen(name: String, title: CharSequence?, extras: Bundle) {
        if (childFragmentManager.isStateSaved) return
        val fragment = childFragmentManager.fragmentFactory.instantiate(requireContext().classLoader, name)
        fragment.arguments = Bundle(extras).apply {
            putCharSequence(SettingsScreenFragment.ARG_TITLE, title)
        }
        val current = childFragmentManager.findFragmentById(R.id.settings)
        val relayTransition = fragment is VkRelayActivity.Screen || fragment is DeployListFragment ||
            current is VkRelayActivity.Screen || current is DeployListFragment
        childFragmentManager.beginTransaction()
            .setReorderingAllowed(true)
            .setCustomAnimations(
                if (relayTransition) 0 else R.anim.settings_enter,
                if (relayTransition) 0 else R.anim.settings_exit,
                if (relayTransition) 0 else R.anim.settings_pop_enter,
                if (relayTransition) 0 else R.anim.settings_pop_exit)
            .replace(R.id.settings, fragment)
            .addToBackStack(null)
            .commit()
    }

    companion object {
        private const val ARG_SCREEN = "screen"
        private const val ARG_SCREEN_TITLE = "screenTitle"
        private const val ARG_SCREEN_ROOT = "screenRoot"

        /** The settings with the sub-screen [fragmentClass] open on top of the root. */
        fun forScreen(fragmentClass: String, title: CharSequence) = SettingsFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_SCREEN, fragmentClass)
                putCharSequence(ARG_SCREEN_TITLE, title)
            }
        }

        /** Opens a settings sub-screen as this host's root so Back returns to the previous app section. */
        fun forScreenRoot(fragmentClass: String, title: CharSequence) = SettingsFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_SCREEN, fragmentClass)
                putCharSequence(ARG_SCREEN_TITLE, title)
                putBoolean(ARG_SCREEN_ROOT, true)
            }
        }
    }

    private fun syncToolbar() {
        val toolbar = toolbar ?: return
        toolbar.setBackgroundColor(MobilePalette.toolbar)
        toolbar.setTitleTextColor(MobilePalette.text)
        toolbar.navigationIcon?.setTint(MobilePalette.text)
        val nested = childFragmentManager.backStackEntryCount > 0
        backCallback.isEnabled = nested
        val current = childFragmentManager.findFragmentById(R.id.settings)
        val rootScreen = arguments?.getBoolean(ARG_SCREEN_ROOT) == true
        toolbar.title = current?.arguments?.getCharSequence(SettingsScreenFragment.ARG_TITLE)
            ?.takeIf { nested || rootScreen } ?: getString(R.string.settings)
        if (nested) {
            toolbar.setNavigationIcon(R.drawable.baseline_arrow_back_24)
            toolbar.setNavigationContentDescription(R.string.navigation_back)
            toolbar.setNavigationOnClickListener { childFragmentManager.popBackStack() }
        } else {
            toolbar.setNavigationIcon(R.drawable.baseline_arrow_back_24)
            toolbar.setNavigationContentDescription(R.string.navigation_back)
            toolbar.setNavigationOnClickListener {
                requireActivity().onBackPressedDispatcher.onBackPressed()
            }
        }
        toolbar.navigationIcon?.setTint(MobilePalette.text)
        // White theme: the toolbar is white, so the navigation icon turns dark
        if (MobilePalette.light) {
            toolbar.navigationIcon?.setTint(ContextCompat.getColor(requireContext(), R.color.black))
        }
    }

}
