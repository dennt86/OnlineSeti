package app.qthrone.mobile.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.lifecycleScope
import app.qthrone.mobile.ui.MobileAlertDialogBuilder as MaterialAlertDialogBuilder
import app.qthrone.mobile.bg.test.TestSpec
import app.qthrone.mobile.ui.test.TestSessionClient
import androidx.room.InvalidationTracker
import app.qthrone.mobile.R
import app.qthrone.mobile.TrafficFormat
import app.qthrone.mobile.SagerNet
import app.qthrone.mobile.database.*
import app.qthrone.mobile.group.SubscriptionClient
import app.qthrone.mobile.ui.profile.profileSettingsIntent
import app.qthrone.mobile.bg.autoselector.AutoSelectorClient
import app.qthrone.mobile.bg.autoselector.AutoSelectorStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Mobile dashboard backed by the existing Room repositories and subscription queue. */
class MobileHomeFragment : ToolbarFragment() {
    companion object {
        private const val ARG_SERVERS_ONLY = "serversOnly"
        fun servers() = MobileHomeFragment().apply {
            arguments = Bundle().apply { putBoolean(ARG_SERVERS_ONLY, true) }
        }
    }
    private val serversOnly get() = arguments?.getBoolean(ARG_SERVERS_ONLY) == true
    private var nativeConnection:app.qthrone.mobile.bg.carrier.NativeConnectionStatus.Snapshot?=null
    private var nativeTest:app.qthrone.mobile.bg.carrier.NativeConnectionStatus.Snapshot?=null
    private var nativeReceiverRegistered=false
    private val nativeStatusReceiver=object:android.content.BroadcastReceiver() {
        override fun onReceive(context:Context,intent:Intent) {
            val stage=runCatching { app.qthrone.mobile.bg.carrier.NativeConnectionStage.valueOf(intent.getStringExtra("stage").orEmpty()) }.getOrNull() ?: return
            val snapshot=app.qthrone.mobile.bg.carrier.NativeConnectionStatus.Snapshot(intent.getLongExtra("profileId",0),stage)
            if(intent.getBooleanExtra("testing",false)) nativeTest=snapshot else nativeConnection=snapshot
            updateState()
        }
    }
    private val blue get() = MobilePalette.accent(requireContext())
    private val surface get() = MobilePalette.surface
    private val muted get() = MobilePalette.muted
    private var content: LinearLayout? = null
    private var power: TextView? = null
    private var currentCheck: TextView? = null
    private var autoSelectorCard: View? = null
    private var autoSelectorServer: TextView? = null
    private var autoSelectorDetail: TextView? = null
    private var geoBanner: LinearLayout? = null
    private var geoStatus: TextView? = null
    private var geoProgress: ProgressBar? = null
    private var geoCheck: ImageView? = null
    private fun updateGeoStatus() {
        val states = app.qthrone.mobile.route.ProviderGeoAssets.states.value.values
        val active = states.firstOrNull { !it.complete } ?: states.lastOrNull()
        geoBanner?.visibility = if (active == null) View.GONE else View.VISIBLE
        geoProgress?.visibility = if (active != null && !active.complete && active.error.isBlank()) View.VISIBLE else View.GONE
        geoCheck?.visibility = if (active?.complete == true) View.VISIBLE else View.GONE
        geoStatus?.apply {
            text = when {
                active == null -> ""
                active.error.isNotBlank() -> active.error
                active.complete -> getString(R.string.provider_geo_complete)
                else -> getString(R.string.provider_geo_loading) + " " + active.file + if (active.total > 0) "\n${active.bytes * 100 / active.total}% · " + android.text.format.Formatter.formatShortFileSize(requireContext(),active.bytes) + " / " + android.text.format.Formatter.formatShortFileSize(requireContext(),active.total) else ""
            }
        }
    }
    private var currentPing: String? = null
    private var currentPingConnectionAt = 0L
    private var currentTestArmed = false
    private var currentResultJob: Job? = null
    private var lastSubscriptionRefresh = 0L
    private var powerPulse: android.animation.ObjectAnimator?=null
    private var pulseTarget: View?=null
    private var load: Job? = null
    private var reordering=false
    private var groupReorder: MobileReorder?=null
    private val profileReorders=mutableMapOf<Long,MobileReorder>()
    private fun saveReorder(groupId: Long?, ids: List<Long>) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    if(groupId==null) GroupRepo.setDisplayOrder(ids) else ProfileManager.setOrder(groupId,ids)
                }
            } finally { reordering=false;refresh() }
        }
    }

    /** Move a profile dropped onto another group's card, header, or profile list. */
    private fun moveProfileToGroup(profileId: Long, targetGroupId: Long, targetGroupName: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val moved = runCatching {
                withContext(Dispatchers.IO) {
                    ProfileManager.moveToGroup(listOf(profileId), targetGroupId)
                    SagerDatabase.proxyDao.getById(profileId)?.groupId == targetGroupId
                }
            }.getOrDefault(false)
            reordering = false
            refresh()
            Toast.makeText(
                requireContext(),
                if (moved) "Профиль перемещён в «$targetGroupName»" else getString(R.string.profile_move_failed),
                Toast.LENGTH_SHORT,
            ).show()
        }
    }
    /**
     * An expanded subscription can be taller than the screen; dragging it then hides everything
     * and leaves no visible place to drop. Fold every group to its header while a group moves,
     * keeping the dragged header under the finger, and unfold to the saved state afterwards.
     */
    private fun setGroupRowsCompact(column: LinearLayout, scroll: ScrollView, item: View, compact: Boolean) {
        val before=item.top-scroll.scrollY
        groupViews.forEach { (id,entry) ->
            val box=entry.second as? LinearLayout ?: return@forEach
            if(box.childCount==0) return@forEach
            box.getChildAt(box.childCount-1).visibility=if(compact || id in collapsed) View.GONE else View.VISIBLE
        }
        column.doOnPreDraw { scroll.scrollTo(0,(item.top-before).coerceAtLeast(0)) }
    }
    private val latencyViews=mutableMapOf<Long,FrameLayout>()
    private val collapsed = mutableSetOf<Long>()
    private val knownGroups = mutableSetOf<Long>()
    private var serverScrollView: ScrollView? = null
    private var subscriptionTop: View? = null
    private var serverRows: LinearLayout? = null
    private var chromeKey: String? = null
    private val groupViews = mutableMapOf<Long, Pair<String, View>>()
    private val refreshButtons = mutableMapOf<Long, ImageButton>()
    private var renderedSelectedProxy = Long.MIN_VALUE
    private var observer: InvalidationTracker.Observer? = null
    private var openSwipe: MobileSwipeTile? = null
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun tile(selected: Boolean = false) = GradientDrawable().apply {
        setColor(if (selected) androidx.core.graphics.ColorUtils.blendARGB(surface,blue,.16f) else surface)
        cornerRadius = dp(5).toFloat()
        setStroke(dp(1), if (selected) blue else Color.argb(45, 103, 116, 128))
    }
    private fun text(value: String, size: Float = 16f, color: Int = MobilePalette.text) = TextView(requireContext()).apply {
        text = value; textSize = size; setTextColor(color)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }
    private fun action(label: String, callback: () -> Unit) = text(label, 16f, blue).apply {
        gravity = Gravity.CENTER; minHeight = dp(48)
        isFocusable = true; setOnClickListener { callback() }
    }
    private fun iconAction(icon: Int, description: String, color: Int = Color.TRANSPARENT, callback: () -> Unit) = ImageButton(requireContext()).apply {
        setImageResource(icon); imageTintList=android.content.res.ColorStateList.valueOf(MobilePalette.text); setBackgroundColor(color); setPadding(dp(12),dp(12),dp(12),dp(12))
        contentDescription=description; setOnClickListener { callback() }; MobileFeedback.apply(this, circle=true)
    }
    private fun View.showUpdating(updating: Boolean) {
        val previous = tag as? android.animation.ObjectAnimator
        if (!updating) { previous?.cancel(); tag=null; rotation=0f; return }
        if (previous != null) return
        tag=android.animation.ObjectAnimator.ofFloat(this,View.ROTATION,0f,360f).apply {
            duration=900;repeatCount=android.animation.ValueAnimator.INFINITE
            interpolator=android.view.animation.LinearInterpolator(); start()
        }
        addOnAttachStateChangeListener(object: View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {}
            override fun onViewDetachedFromWindow(view: View) { (view.tag as? android.animation.ObjectAnimator)?.cancel(); view.tag=null; view.rotation=0f }
        })
    }
    private fun updateSubscriptionButtons() {
        refreshButtons.forEach { (id, button) ->
            val updating=SubscriptionClient.states.value.containsKey(id)
            button.isEnabled=!updating; button.showUpdating(updating)
        }
    }
    private fun host() = requireActivity() as MainActivity
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        val scroll = FrameLayout(requireContext()).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            background = MobileHomeBackdrop()
        }
        content = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(20))
        }
        scroll.addView(content, FrameLayout.LayoutParams(-1,-1))
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        return scroll
    }
    override fun onViewCreated(view: View, state: Bundle?) {
        super.onViewCreated(view, state)
        androidx.core.content.ContextCompat.registerReceiver(requireContext(),nativeStatusReceiver,
            android.content.IntentFilter(app.qthrone.mobile.bg.carrier.NativeConnectionStatus.action()),requireContext().packageName+".SERVICE",null,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        nativeReceiverRegistered=true
        viewLifecycleOwner.lifecycleScope.launch {
            val snapshots=withContext(Dispatchers.IO) { app.qthrone.mobile.bg.carrier.NativeConnectionStatus.read() to app.qthrone.mobile.bg.carrier.NativeConnectionStatus.read(true) }
            nativeConnection=snapshots.first;nativeTest=snapshots.second;updateState()
        }
        collapsed.clear()
        collapsed.addAll(requireContext().getSharedPreferences("dashboard",Context.MODE_PRIVATE).getStringSet("collapsedGroups",emptySet()).orEmpty().mapNotNull { it.toLongOrNull() })
        state?.getLongArray("knownGroups")?.let { knownGroups.addAll(it.toList()) }
        observer = object : InvalidationTracker.Observer("groups", "profiles") {
            override fun onInvalidated(tables: Set<String>) { activity?.runOnUiThread { refresh() } }
        }.also { SagerDatabase.instance.invalidationTracker.addObserver(it) }
        viewLifecycleOwner.lifecycleScope.launch { SubscriptionClient.states.collect { updateSubscriptionButtons() } }
        viewLifecycleOwner.lifecycleScope.launch { AutoSelectorClient.status.collect { updateAutoSelectorCard(it) } }
        viewLifecycleOwner.lifecycleScope.launch { app.qthrone.mobile.route.ProviderGeoAssets.states.collect { updateGeoStatus() } }
        viewLifecycleOwner.lifecycleScope.launch { TestSessionClient.queuedProfiles.collect { updateLatencies() } }
        viewLifecycleOwner.lifecycleScope.launch { TestSessionClient.state.collect {
            updateLatencies()
            if(currentTestArmed && DataStore.serviceState.connected && it.testCurrent && !it.running) {
                val ping=it.rows.values.firstOrNull { row -> row.ok && row.latency>0 }?.latency
                currentPing=if(ping!=null && !it.cancelled) getString(R.string.qmobile_ping_success,ping) else getString(R.string.qmobile_unavailable)
                currentCheck?.text=currentPing
                currentTestArmed=false
                updateState()
                currentResultJob?.cancel()
                currentResultJob=viewLifecycleOwner.lifecycleScope.launch {
                    kotlinx.coroutines.delay(2500)
                    currentPing=null
                    currentCheck?.text=getString(R.string.qmobile_check_connection)
                }
            }
        } }
        viewLifecycleOwner.lifecycleScope.launch {
            while(true) { updateState(); kotlinx.coroutines.delay(1000) }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putLongArray("collapsedGroups", collapsed.toLongArray())
        outState.putLongArray("knownGroups", knownGroups.toLongArray())
        super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume(); refresh() }
    override fun onPause() {
        groupReorder?.cancel();profileReorders.values.forEach { it.cancel() }
        load?.cancel()
        powerPulse?.cancel(); powerPulse=null; pulseTarget=null
        power?.scaleX=1f; power?.scaleY=1f
        super.onPause()
    }
    override fun onDestroyView() {
        if(nativeReceiverRegistered) { requireContext().unregisterReceiver(nativeStatusReceiver);nativeReceiverRegistered=false }
        groupReorder?.cancel();groupReorder=null;profileReorders.values.forEach { it.cancel() };profileReorders.clear();reordering=false
        geoBanner = null; geoStatus = null; geoProgress = null; geoCheck = null
        observer?.let { SagerDatabase.instance.invalidationTracker.removeObserver(it) }
        observer = null; load?.cancel(); content = null; powerPulse?.cancel();powerPulse=null;pulseTarget=null; power = null
        currentResultJob?.cancel(); currentResultJob=null
        if(!currentTestArmed) currentPing=null
        openSwipe=null; renderedStructure=null
        autoSelectorCard=null; autoSelectorServer=null; autoSelectorDetail=null
        serverScrollView=null; serverRows=null; chromeKey=null; subscriptionTop=null
        groupViews.clear(); refreshButtons.clear(); latencyViews.clear(); profileRows.clear()
        super.onDestroyView()
    }
    private val profileRows=mutableMapOf<Long,View>()
    private var serverScrollPosition = 0
    private var renderedStructure: String? = null
    private fun ViewGroup.childrenList(): List<View> = (0 until childCount).map { getChildAt(it) }
    fun refresh() {
        if (content == null || !isAdded || !isResumed || isHidden || reordering) return
        load?.cancel()
        load = viewLifecycleOwner.lifecycleScope.launch {
            val groups = withContext(Dispatchers.IO) {
                GroupRepo.all().sortedBy { if(it.id in pinnedGroups()) 0 else 1 }.map { it to SagerDatabase.proxyDao.getByGroup(it.id) }
            }
            if(reordering) return@launch
            val column = content ?: return@launch
            groups.forEach { (group, _) -> knownGroups.add(group.id) }
            val structure = buildString {
                append(MobilePalette.surface); append(blue)
                for ((group, profiles) in groups) {
                    append(group.id); append(group.displayName()); append(group.isSubscription); append(group.subLastUpdate)
                    append(group.subInfo.toString())
                    append(group.subOptions.autoSortByPing)
                    for (profile in profiles) {
                        append(profile.id); append(profile.displayName()); append(profile.displayType())
                        append(profile.outboundJson)
                    }
                }
            }
            if (renderedStructure == structure) {
                groups.forEach { (_, profiles) -> profiles.forEach { profile ->
                    latencyViews[profile.id]?.tag = profile.latency
                } }
                updateLatencies()
                updateState()
                return@launch
            }
            renderedStructure = structure
            val hasServers = groups.any { it.second.isNotEmpty() || it.first.isSubscription }
            val hwid = app.qthrone.mobile.group.RequestIdentity.applyCustomHwidParams(app.qthrone.mobile.group.DeviceDetails.get(),DataStore.subCustomHwidParams).hwid
            val nextChromeKey="$surface:$blue:$hasServers:$hwid:$serversOnly"
            if(chromeKey != nextChromeKey) {
                chromeKey=nextChromeKey
                groupReorder=null;profileReorders.clear()
                column.removeAllViews(); latencyViews.clear(); profileRows.clear(); groupViews.clear(); refreshButtons.clear()
                openSwipe=null
                val header = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
                fun headerIcon(icon: Int, description: String, callback: (View) -> Unit) = ImageButton(requireContext()).apply {
                    setImageResource(icon); imageTintList=android.content.res.ColorStateList.valueOf(MobilePalette.text); background = null; setPadding(dp(10),dp(10),dp(10),dp(10))
                    contentDescription = description; setOnClickListener { callback(it) }; MobileFeedback.apply(this,circle=true)
                }
                header.addView(headerIcon(R.drawable.ic_mobile_settings, getString(R.string.qmobile_settings)) { host().displayFragmentWithId(R.id.nav_settings) }, LinearLayout.LayoutParams(dp(48), dp(48)))
                header.addView(View(requireContext()), LinearLayout.LayoutParams(0, dp(48), 1f))
                header.addView(headerIcon(R.drawable.ic_mobile_add, getString(R.string.qmobile_add)) { addMenu(it) }, LinearLayout.LayoutParams(dp(48), dp(48)))
                if (serversOnly) {
                    header.getChildAt(0).visibility = View.INVISIBLE
                    header.getChildAt(1).visibility = View.GONE
                    header.addView(text("Серверы", 20f).apply {
                        setTypeface(null, Typeface.BOLD); setPadding(dp(12), 0, 0, 0)
                    }, 0, LinearLayout.LayoutParams(0, dp(48), 1f))
                }
                column.addView(header)
                if (!hasServers) column.addView(ImageView(requireContext()).apply {
                    setImageResource(R.drawable.onlineseti_logo)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    contentDescription = "Onlineseti VPN"
                }, LinearLayout.LayoutParams(-1, dp(96)))
                column.addView(text("Onlineseti VPN", 22f).apply {
                    gravity = Gravity.CENTER; setTypeface(null, Typeface.BOLD)
                }.apply { if (serversOnly) visibility = View.GONE })
                column.addView(action(if (hasServers) "Подписка и поддержка" else "Получить подписку в Telegram") {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(OnlinesetiLinks.BOT))) }
                        .onFailure { android.widget.Toast.makeText(requireContext(), "Откройте @onlineseti_bot в Telegram", android.widget.Toast.LENGTH_LONG).show() }
                })
                val centre = FrameLayout(requireContext())
                power = action("") { host().toggleService() }.apply {
                    contentDescription = getString(R.string.qmobile_connect)
                    background = MobilePowerArtwork(blue)
                    var pressAnimator: android.animation.ValueAnimator? = null
                    setOnTouchListener { _, event ->
                        val artwork=background as? MobilePowerArtwork
                        val target=when(event.actionMasked) {
                            android.view.MotionEvent.ACTION_DOWN -> .94f
                            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> 1f
                            else -> null
                        }
                        if(artwork!=null && target!=null) {
                            pressAnimator?.cancel()
                            pressAnimator=android.animation.ValueAnimator.ofFloat(artwork.pressScale,target).apply {
                                duration=if(target<1f) 90 else 150
                                interpolator=android.view.animation.DecelerateInterpolator()
                                addUpdateListener { artwork.pressScale=it.animatedValue as Float }
                                start()
                            }
                        }
                        false
                    }
                }
                centre.addView(power, FrameLayout.LayoutParams(dp(224), dp(224), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin=dp(8) })
                if (serversOnly) centre.visibility = View.GONE
                column.addView(centre, LinearLayout.LayoutParams(-1, dp(246)))
                val autoCard = LinearLayout(requireContext()).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(10), dp(12), dp(10))
                    background = GradientDrawable().apply {
                        setColor(surface)
                        cornerRadius = dp(16).toFloat()
                        setStroke(dp(1), Color.argb(55, 100, 160, 205))
                    }
                    isFocusable = true
                    setOnClickListener {
                        val selectorId = AutoSelectorClient.status.value.selectorId
                        if (selectorId > 0) startActivity(AutoSelectorStatusActivity.intent(requireContext(), selectorId))
                    }
                    MobileFeedback.apply(this)
                }
                autoCard.addView(ImageView(requireContext()).apply {
                    setImageResource(R.drawable.ic_mobile_globe)
                    imageTintList = android.content.res.ColorStateList.valueOf(Color.rgb(91, 210, 170))
                    contentDescription = null
                }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(12) })
                val autoLabels = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL }
                autoLabels.addView(text(getString(R.string.qmobile_current_server), 11f, muted).apply {
                    setPadding(0, 0, 0, dp(2)); setTypeface(null, Typeface.BOLD)
                })
                autoSelectorServer = text(getString(R.string.qmobile_auto_server_checking), 16f).apply {
                    setPadding(0, 0, 0, dp(2)); setTypeface(null, Typeface.BOLD); maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }
                autoLabels.addView(autoSelectorServer)
                autoSelectorDetail = text(getString(R.string.qmobile_auto_switch_active), 12f, muted).apply {
                    setPadding(0, 0, 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                }
                autoLabels.addView(autoSelectorDetail)
                autoCard.addView(autoLabels, LinearLayout.LayoutParams(0, -2, 1f))
                autoCard.addView(ImageView(requireContext()).apply {
                    setImageResource(R.drawable.ic_mobile_chevron)
                    imageTintList = android.content.res.ColorStateList.valueOf(muted)
                    contentDescription = getString(R.string.qmobile_auto_server_details)
                }, LinearLayout.LayoutParams(dp(24), dp(24)))
                autoSelectorCard = autoCard
                autoCard.visibility = View.GONE
                column.addView(autoCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
                updateAutoSelectorCard(AutoSelectorClient.status.value)
                updateState()
                val imports = LinearLayout(requireContext())
                fun importButton(label: String, icon: Int, callback: () -> Unit) = LinearLayout(requireContext()).apply {
                    gravity=Gravity.CENTER; isFocusable=true; contentDescription=label
                    setOnClickListener { callback() }
                    background=GradientDrawable().apply { setColor(surface); cornerRadius=dp(16).toFloat() }
                    addView(ImageView(requireContext()).apply { setImageResource(icon); imageTintList=android.content.res.ColorStateList.valueOf(MobilePalette.text); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(24),dp(24)).apply { marginEnd=dp(10) })
                    addView(text(label,14f).apply { setPadding(0,0,0,0); importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO })
                }
                imports.addView(importButton(getString(R.string.qmobile_clipboard), R.drawable.ic_mobile_clipboard) { importClipboard() }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(8) })
                imports.addView(importButton(getString(R.string.qmobile_qr), R.drawable.ic_mobile_qr) { startActivity(Intent(requireContext(), ScannerActivity::class.java)) }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
                if (hasServers) {
                    val above=FrameLayout(requireContext())
                    currentCheck=text(currentPing ?: getString(R.string.qmobile_check_connection),12f,muted).apply {
                        maxLines=2;setPadding(dp(2),dp(2),dp(2),dp(2));gravity=Gravity.BOTTOM
                        isFocusable=true
                        MobileFeedback.apply(this,white=true)
                        setOnClickListener { checkCurrentConnection() }
                    }
                    above.addView(currentCheck,FrameLayout.LayoutParams(-2,-2,Gravity.START or Gravity.CENTER_VERTICAL))
                    subscriptionTop=above
                    above.layoutParams=LinearLayout.LayoutParams(-1,dp(40))
                    updateState()
                } else { currentCheck=null;subscriptionTop=null }
                geoBanner = LinearLayout(requireContext()).apply {
                    gravity = Gravity.CENTER_VERTICAL; setPadding(dp(16),dp(12),dp(16),dp(12))
                    background = GradientDrawable().apply { setColor(surface); cornerRadius=dp(16).toFloat() }
                    visibility = View.GONE
                }
                geoStatus = text("",14f).apply { setPadding(0,0,dp(12),0) }
                geoProgress = ProgressBar(requireContext()).apply { isIndeterminate = true; indeterminateTintList = android.content.res.ColorStateList.valueOf(blue); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                geoCheck = ImageView(requireContext()).apply { setImageResource(R.drawable.ic_baseline_check_circle_24); imageTintList = android.content.res.ColorStateList.valueOf(Color.rgb(76,175,80)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
                geoBanner!!.addView(geoStatus,LinearLayout.LayoutParams(0,-2,1f))
                geoBanner!!.addView(geoProgress,LinearLayout.LayoutParams(dp(28),dp(28)))
                geoBanner!!.addView(geoCheck,LinearLayout.LayoutParams(dp(28),dp(28)))
                column.addView(geoBanner,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
                updateGeoStatus()
                val serverColumn=LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL }
                subscriptionTop?.let { column.addView(it) }
                val serverScroll=ScrollView(requireContext()).apply {
                    isVerticalScrollBarEnabled=false; isHorizontalScrollBarEnabled=false
                    clipToPadding=false; addView(serverColumn,FrameLayout.LayoutParams(-1,-2))
                    setOnScrollChangeListener { _, _, y, _, _ -> serverScrollPosition=y }
                }
                column.addView(serverScroll,LinearLayout.LayoutParams(-1,0,1f))
                serverRows=serverColumn; serverScrollView=serverScroll
                if(!hasServers) {
                    column.addView(View(requireContext()), LinearLayout.LayoutParams(-1,0,1f))
                    val identity=LinearLayout(requireContext()).apply { gravity=Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL }
                    identity.addView(text("HWID: $hwid",14f,muted).apply { setPadding(dp(4),dp(4),dp(4),dp(4));maxLines=1;maxWidth=(resources.displayMetrics.widthPixels-dp(96)).coerceAtLeast(dp(120));ellipsize=android.text.TextUtils.TruncateAt.MIDDLE;contentDescription="HWID: $hwid" },LinearLayout.LayoutParams(-2,-2))
                    identity.addView(iconAction(R.drawable.ic_mobile_copy_outline,"Скопировать HWID") { SagerNet.trySetPrimaryClip(hwid) }.apply {
                        imageTintList=android.content.res.ColorStateList.valueOf(MobilePalette.accent(requireContext()))
                        setPadding(dp(13),dp(13),dp(13),dp(13))
                    },LinearLayout.LayoutParams(dp(40),dp(40)))
                    column.addView(identity,LinearLayout.LayoutParams(-1,-2))
                    column.addView(text(getString(R.string.qmobile_empty),14f).apply { gravity=Gravity.CENTER; setPadding(dp(16),dp(16),dp(16),dp(12)) })
                    column.addView(imports,LinearLayout.LayoutParams(-1,dp(48)))
                }
            }
            val serverColumn=serverRows ?: return@launch
            val serverScroll=serverScrollView ?: return@launch
            val anchor=serverColumn.childrenList().firstOrNull { it.bottom > serverScroll.scrollY }
            val anchorId=groupViews.entries.firstOrNull { it.value.second === anchor }?.key
            val anchorOffset=(anchor?.top ?: 0)-serverScroll.scrollY
            val groupDrag=groupReorder ?: MobileReorder(serverColumn,serverScroll,
                { reordering=it },{ saveReorder(null,it) },
                { first,second -> (first in pinnedGroups())==(second in pinnedGroups()) },
                { item -> setGroupRowsCompact(serverColumn,serverScroll,item,true) },
                { item -> setGroupRowsCompact(serverColumn,serverScroll,item,false) }).also { groupReorder=it }
            val desired=mutableListOf<View>()
            val liveIds=groups.flatMap { it.second }.map { it.id }.toSet()
            latencyViews.keys.retainAll(liveIds); profileRows.keys.retainAll(liveIds)
            val liveGroups=groups.map { it.first.id }.toSet()
            groupViews.keys.retainAll(liveGroups); refreshButtons.keys.retainAll(liveGroups)
            profileReorders.keys.retainAll(liveGroups)

            var firstGroup = true
            for ((group, profiles) in groups) {
                if (profiles.isEmpty() && !group.isSubscription) continue
                val signature=buildString {
                    append(surface); append(blue); append(group.displayName()); append(group.isSubscription); append(group.subLastUpdate); append(group.subInfo);append(group.subOptions.autoSortByPing)
                    profiles.sortedBy { it.id }.forEach { append(it.id); append(it.displayName()); append(it.displayType()); append(it.outboundJson) }
                }
                val retained=groupViews[group.id]?.takeIf { it.first==signature }?.second
                if(retained != null) {
                    val retainedBox=retained as LinearLayout
                    val retainedRows=retainedBox.getChildAt(retainedBox.childCount-1) as LinearLayout
                    profiles.forEachIndexed { index,profile ->
                        val swipe=profileRows[profile.id]?.parent as? MobileSwipeTile
                        if(swipe!=null && swipe.parent===retainedRows && retainedRows.getChildAt(index)!==swipe) {
                            retainedRows.removeView(swipe);retainedRows.addView(swipe,index)
                        }
                    }
                    desired.add(retained); firstGroup=false
                    profiles.forEach { latencyViews[it.id]?.tag=it.latency }
                    continue
                }
                val box = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; background = tile() }
                val rows = LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL }
                val profileDrag=MobileReorder(
                    rows,serverScroll,{ reordering=it },{ saveReorder(group.id,it) },
                    onCrossDrop = { profileId -> moveProfileToGroup(profileId,group.id,group.displayName()) },
                )
                profileReorders[group.id]=profileDrag
                val title = LinearLayout(requireContext()).apply { gravity = Gravity.CENTER_VERTICAL }
                val arrow = ImageView(requireContext()).apply {
                    setImageResource(R.drawable.ic_mobile_chevron); imageTintList=android.content.res.ColorStateList.valueOf(muted); rotation=if(group.id in collapsed) 0f else 90f
                }
                val heading = LinearLayout(requireContext()).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(12),0,0,0); isFocusable=true }
                heading.addView(arrow,LinearLayout.LayoutParams(dp(20),dp(20)))
                val names=LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(10),dp(8),dp(4),dp(8)) }
                names.addView(text(group.displayName(),17f).apply { setPadding(0,0,0,0); maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END })
                if(group.isSubscription && group.subLastUpdate>0) names.addView(text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT,java.text.DateFormat.SHORT).format(java.util.Date(group.subLastUpdate*1000)),10f,muted).apply { setPadding(0,dp(3),0,0) })
                heading.addView(names,LinearLayout.LayoutParams(0,-2,1f))
                profileDrag.addDropTarget(heading)
                profileDrag.addDropTarget(box)
                heading.setOnClickListener {
                    if (!collapsed.add(group.id)) collapsed.remove(group.id)
                    requireContext().getSharedPreferences("dashboard",Context.MODE_PRIVATE).edit().putStringSet("collapsedGroups",collapsed.map { it.toString() }.toSet()).apply()
                    rows.visibility=if(group.id in collapsed) View.GONE else View.VISIBLE
                    arrow.animate().rotation(if(group.id in collapsed) 0f else 90f).setDuration(180).start()
                }
                groupDrag.bind(heading,box,group.id,heading)
                title.addView(heading,LinearLayout.LayoutParams(0,-2,1f))
                if(!group.subOptions.autoSortByPing) title.addView(iconAction(R.drawable.ic_mobile_sort_ping,"Сортировать по пингу") {
                    viewLifecycleOwner.lifecycleScope.launch { withContext(Dispatchers.IO) {
                        app.qthrone.mobile.database.GroupSort.sortProfiles(group.id,app.qthrone.mobile.database.GroupSortAction(app.qthrone.mobile.database.GroupSortMethod.BY_LATENCY))
                    } }
                },LinearLayout.LayoutParams(dp(36),dp(48)))
                if (group.isSubscription) title.addView(iconAction(R.drawable.ic_mobile_refresh,getString(R.string.qmobile_refresh)) { SubscriptionClient.refreshGroup(group.id,false) }.apply { isEnabled=!SubscriptionClient.states.value.containsKey(group.id); alpha=1f;refreshButtons[group.id]=this },LinearLayout.LayoutParams(dp(40),dp(48)))
                title.addView(iconAction(R.drawable.ic_mobile_test,getString(R.string.qmobile_tests)) { testMenu(profiles.map { it.id }.toLongArray(),group.displayName(),group.id,true) },LinearLayout.LayoutParams(dp(40),dp(48)))
                lateinit var more: ImageButton
                more=iconAction(R.drawable.ic_mobile_more,getString(R.string.qmobile_more)) { groupMenu(group,more) }
                title.addView(more,LinearLayout.LayoutParams(dp(44),dp(48)))
                box.addView(title)
                if (group.isSubscription) {
                    val quota=group.subInfo
                    val traffic=LinearLayout(requireContext()).apply { gravity=Gravity.CENTER_VERTICAL; setPadding(dp(12),0,dp(12),dp(8)) }
                    traffic.addView(iconAction(R.drawable.ic_mobile_info,getString(R.string.qmobile_subscription_info)) {
                        val url = quota.webUrl.ifBlank { group.url }
                        val uri = android.net.Uri.parse(url)
                        if (uri.scheme == "https" || uri.scheme == "http") runCatching {
                            startActivity(Intent(Intent.ACTION_VIEW, uri))
                        }
                    },LinearLayout.LayoutParams(dp(32),dp(32)))
                    val bar=FrameLayout(requireContext()).apply { background=GradientDrawable().apply { setColor(MobilePalette.toolbar); cornerRadius=dp(3).toFloat(); setStroke(dp(1),muted) } }
                    var usage=getString(R.string.qmobile_quota_unknown)
                    if (quota.valid && quota.hasQuota) {
                        val upload=quota.upload.coerceAtLeast(0); val download=quota.download.coerceAtLeast(0)
                        val bytes=if(download>Long.MAX_VALUE-upload) Long.MAX_VALUE else upload+download
                        val used=TrafficFormat.format(bytes)
                        val total=if(quota.total>0) TrafficFormat.format(quota.total) else "\u221e"
                        usage="$used / $total"
                    }
                    bar.addView(text(usage,11f).apply {
                        gravity=Gravity.CENTER; setPadding(dp(6),dp(2),dp(6),dp(2))
                        setSingleLine(true)
                        androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this,9,11,1,android.util.TypedValue.COMPLEX_UNIT_SP)
                    },FrameLayout.LayoutParams(-1,-1))
                    traffic.addView(bar,LinearLayout.LayoutParams(0,dp(28),1f).apply { marginStart=dp(8) })
                    if (quota.supportUrl.isNotBlank()) traffic.addView(iconAction(R.drawable.ic_mobile_globe,getString(R.string.qmobile_subscription_info)) {
                        val uri = android.net.Uri.parse(quota.supportUrl)
                        if (uri.scheme == "https" || uri.scheme == "http") runCatching { startActivity(Intent(Intent.ACTION_VIEW,uri)) }
                    },LinearLayout.LayoutParams(dp(32),dp(32)))
                    box.addView(traffic)
                    if (quota.expire > 0) box.addView(text(getString(R.string.qmobile_expiry,java.text.DateFormat.getDateInstance().format(java.util.Date(quota.expire*1000))),11f,muted).apply { setPadding(dp(14),0,dp(14),dp(8)) })
                    if (quota.announce.isNotBlank()) box.addView(text(quota.announce,12f,muted).apply { setPadding(dp(14),0,dp(14),dp(10)) })
                }
                for (profile in profiles) {
                    val row = LinearLayout(requireContext()).apply {
                        gravity = Gravity.CENTER_VERTICAL; background = tile(profile.id == DataStore.selectedProxy)
                        isFocusable = true
                        setOnClickListener {
                            DataStore.selectedProxy = profile.id
                            if (DataStore.serviceState.connected) SagerNet.reloadService()
                            profileRows.forEach { (id,view) -> view.background=tile(id==profile.id); MobileFeedback.apply(view) }
                        }
                    }
                    profileRows[profile.id]=row
                    val displayName=profile.displayName().trim()
                    val first=if(displayName.isNotEmpty()) displayName.codePointAt(0) else 0
                    val firstLength=Character.charCount(first)
                    val second=if(displayName.length>firstLength) displayName.codePointAt(firstLength) else 0
                    val hasFlag=first in 0x1f1e6..0x1f1ff && second in 0x1f1e6..0x1f1ff
                    val flagLength=if(hasFlag) firstLength+Character.charCount(second) else 0
                    val badgeBackground=GradientDrawable().apply { setColor(MobilePalette.toolbar);cornerRadius=dp(7).toFloat() }
                    val countryCode=if(hasFlag) "${('a'.code+first-0x1f1e6).toChar()}${('a'.code+second-0x1f1e6).toChar()}" else ""
                    val flag=if(hasFlag) MobileCountryFlags.bitmap(requireContext(),countryCode) else null
                    val badge=ImageView(requireContext()).apply {
                        if(flag!=null) {
                            setImageBitmap(flag);scaleType=ImageView.ScaleType.FIT_XY
                            contentDescription="Country flag: ${countryCode.uppercase(java.util.Locale.ROOT)}"
                        } else {
                            setImageResource(R.drawable.ic_mobile_globe)
                            imageTintList=android.content.res.ColorStateList.valueOf(MobilePalette.muted)
                            setPadding(dp(5),dp(5),dp(5),dp(5))
                        }
                    }
                    badge.background=badgeBackground;badge.clipToOutline=true
                    row.addView(badge,LinearLayout.LayoutParams(dp(28),dp(28)).apply { marginStart=dp(10) })
                    val labels = LinearLayout(requireContext()).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(6), dp(6), dp(6)) }
                    labels.addView(text(if(hasFlag) displayName.substring(flagLength).trimStart() else displayName, 15f).apply { setTypeface(null,Typeface.BOLD); setPadding(0,0,0,dp(2)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    labels.addView(text(profile.displayType(), 12f, muted).apply { setPadding(0,0,0,0); maxLines=1 })
                    row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
                    val latency=FrameLayout(requireContext()).apply { tag=profile.latency }
                    latencyViews[profile.id]=latency
                    row.addView(latency,LinearLayout.LayoutParams(dp(62),dp(32)))
                    row.addView(ImageView(requireContext()).apply { setImageResource(R.drawable.ic_mobile_chevron); setPadding(dp(6),dp(6),dp(6),dp(6)); contentDescription=getString(R.string.qmobile_edit); setOnClickListener { startActivity(profile.profileSettingsIntent(requireContext(),group.isSubscription)) } },LinearLayout.LayoutParams(dp(32),dp(32)))
                    val swipe = MobileSwipeTile(requireContext(), row,
                        iconAction(R.drawable.ic_mobile_test,getString(R.string.qmobile_tests),Color.rgb(36,171,85)) { openSwipe?.closeActions(); testMenu(longArrayOf(profile.id),profile.displayName(),group.id) },
                        iconAction(R.drawable.ic_mobile_share,getString(R.string.qmobile_share),Color.rgb(60,132,190)) { shareProfile(profile) },
                        iconAction(R.drawable.ic_mobile_delete,getString(R.string.qmobile_delete),Color.rgb(220,66,61)) {
                            viewLifecycleOwner.lifecycleScope.launch {
                                val result=withContext(Dispatchers.IO) { ProfileManager.batchDeleteProfiles(listOf(profile.id),stopRunning=true) }
                                if(!result.ok) Toast.makeText(requireContext(),R.string.qmobile_delete_failed,Toast.LENGTH_LONG).show()
                            }
                        }).apply { onOpened={ opened -> openSwipe?.takeIf { it!==opened }?.closeActions(); openSwipe=opened } }
                    profileDrag.bind(row,swipe,profile.id)
                    rows.addView(swipe, LinearLayout.LayoutParams(-1,-2).apply { setMargins(dp(5),0,dp(5),dp(3)) })
                }
                rows.visibility=if(group.id in collapsed) View.GONE else View.VISIBLE
                box.addView(rows)

                box.layoutParams=LinearLayout.LayoutParams(-1,-2).apply { topMargin=if(firstGroup) 0 else dp(16) }
                groupViews[group.id]=signature to box
                desired.add(box)
                firstGroup = false
            }
            serverColumn.childrenList().filter { it !in desired }.forEach { serverColumn.removeView(it) }
            desired.forEachIndexed { index, box ->
                (box.layoutParams as LinearLayout.LayoutParams).topMargin=if(index==0) 0 else dp(16)
                if(serverColumn.getChildAt(index) !== box) {
                    (box.parent as? ViewGroup)?.removeView(box)
                    serverColumn.addView(box,index)
                }
            }
            groupDrag.prune()
            // Restore before drawing, after layout knows the new row heights.
            serverScroll.viewTreeObserver.addOnPreDrawListener(object: android.view.ViewTreeObserver.OnPreDrawListener {
                override fun onPreDraw(): Boolean {
                    serverScroll.viewTreeObserver.removeOnPreDrawListener(this)
                    val retained=anchorId?.let { groupViews[it]?.second }
                    if(retained != null) serverScroll.scrollTo(0,(retained.top-anchorOffset).coerceAtLeast(0))
                    return true
                }
            })
            updateSubscriptionButtons()
            updateLatencies()
            updateState()
        }
    }
    private fun checkCurrentConnection() {
        if(!DataStore.serviceState.connected) return
        val session=TestSessionClient.state.value
        val accepted=if(session.running) session.testCurrent && session.kind==TestSpec.KIND_URL
            else TestSessionClient.startCurrent(TestSpec.KIND_URL)
        if(!accepted) return
        currentResultJob?.cancel(); currentResultJob=null
        currentTestArmed=true
        currentPing=getString(R.string.qmobile_ping_checking)
        currentCheck?.text=currentPing
        updateState()
    }

    private fun updateAutoSelectorCard(status: AutoSelectorStatus = AutoSelectorClient.status.value) {
        val card = autoSelectorCard ?: return
        val visible = !serversOnly && DataStore.serviceState.connected && status.active && status.selectorId > 0
        card.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) return

        val selected = status.selectedName.trim()
        autoSelectorServer?.text = when {
            status.suspended || status.corePhase == "suspended" -> getString(R.string.qmobile_auto_server_waiting)
            selected.isNotEmpty() -> selected
            else -> getString(R.string.qmobile_auto_server_checking)
        }
        autoSelectorDetail?.text = when {
            status.suspended || status.corePhase == "suspended" -> getString(R.string.qmobile_auto_server_no_network)
            selected.isEmpty() -> getString(R.string.qmobile_auto_server_checking_detail)
            status.lastSwitchMs > 0 -> getString(
                R.string.qmobile_auto_server_switched,
                AutoSelectorTexts.ago(requireContext(), status.lastSwitchMs),
            )
            else -> getString(R.string.qmobile_auto_switch_active)
        }
        card.contentDescription = getString(
            R.string.qmobile_auto_server_accessibility,
            autoSelectorServer?.text?.toString().orEmpty(),
            autoSelectorDetail?.text?.toString().orEmpty(),
        )
    }

    fun updateState() {
        if(!isAdded || !isResumed || isHidden || power==null) return
        updateAutoSelectorCard(AutoSelectorClient.status.value)
        if(renderedSelectedProxy != DataStore.selectedProxy) {
            renderedSelectedProxy=DataStore.selectedProxy
            profileRows.forEach { (id, row) -> row.background=tile(id==renderedSelectedProxy); MobileFeedback.apply(row) }
        }
        val state = DataStore.serviceState
        if(!state.connected || currentPingConnectionAt != DataStore.mobileConnectedAt || lastSubscriptionRefresh != SubscriptionReportDialog.refreshEpoch) {
            lastSubscriptionRefresh = SubscriptionReportDialog.refreshEpoch
            currentPing=null; currentTestArmed=false
            currentResultJob?.cancel(); currentResultJob=null
            currentPingConnectionAt=if(state.connected) DataStore.mobileConnectedAt else 0L
            currentCheck?.text=getString(R.string.qmobile_check_connection)
        }
        power?.isEnabled = state != app.qthrone.mobile.bg.BaseService.State.Stopping
        val test=TestSessionClient.state.value
        val testing=test.running && test.kind==TestSpec.KIND_URL
        val snapshot=if(testing && !test.testCurrent) nativeTest?.takeIf {
            test.rows.containsKey(it.profileId) && test.rows[it.profileId]?.phase!=app.qthrone.mobile.ui.test.RowPhase.DONE
        } else nativeConnection?.takeIf {
            it.profileId==(if(state.connected) DataStore.currentProfile else DataStore.selectedProxy)
        }
        val stage=if(testing && test.testCurrent) null else snapshot?.stage
        val caption=if(testing || state.canStop || state.connected) getString(when(stage) {
            app.qthrone.mobile.bg.carrier.NativeConnectionStage.AUTHORIZING -> R.string.qmobile_vk_authorizing
            app.qthrone.mobile.bg.carrier.NativeConnectionStage.CAPTCHA -> R.string.qmobile_vk_captcha
            app.qthrone.mobile.bg.carrier.NativeConnectionStage.WAITING_VK -> R.string.qmobile_vk_waiting
            app.qthrone.mobile.bg.carrier.NativeConnectionStage.RECONNECTING -> R.string.qmobile_reconnecting
            app.qthrone.mobile.bg.carrier.NativeConnectionStage.PREPARING, app.qthrone.mobile.bg.carrier.NativeConnectionStage.CONNECTING -> R.string.qmobile_connecting
            else -> if(testing || currentTestArmed) R.string.qmobile_ping_checking else if(state.connected) R.string.qmobile_connected else R.string.qmobile_connecting
        }) else ""
        val description=getString(R.string.qmobile_connect) + ", " + caption.ifEmpty { getString(R.string.qmobile_disconnected) }
        if(power?.contentDescription != description) power?.contentDescription=description
        power?.setTextColor(if (state.connected) Color.rgb(128,192,139) else blue)
        (power?.background as? MobilePowerArtwork)?.connected=state.connected
        currentCheck?.visibility=if(state.connected) View.VISIBLE else View.GONE
        val button=power
        if(!state.connected || pulseTarget!==button) {
            powerPulse?.cancel();powerPulse=null;pulseTarget=null
            button?.scaleX=1f;button?.scaleY=1f
        }
        if(state.connected && button!=null && powerPulse==null) {
            pulseTarget=button
            powerPulse=android.animation.ObjectAnimator.ofPropertyValuesHolder(button,
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_X,.995f,1.015f),
                android.animation.PropertyValuesHolder.ofFloat(View.SCALE_Y,.995f,1.015f)).apply {
                duration=1800;repeatCount=android.animation.ValueAnimator.INFINITE;repeatMode=android.animation.ValueAnimator.REVERSE
                interpolator=android.view.animation.AccelerateDecelerateInterpolator();start()
            }
        }
        power?.apply {
            textSize=13f;gravity=Gravity.CENTER;setPadding(0,dp(75),0,0)
            val seconds=((android.os.SystemClock.elapsedRealtime()-DataStore.mobileConnectedAt)/1000).coerceAtLeast(0)
            val fontSize=textSize
            (background as? MobilePowerArtwork)?.apply {
                captionSize=fontSize
                statusText=caption
                elapsedText=String.format(java.util.Locale.ROOT,"%02d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60)
            }
        }
    }
    private fun updateLatencies() {
        val state=TestSessionClient.state.value
        latencyViews.forEach { (id, holder) ->
            val result=state.rows[id].takeUnless { state.testCurrent }
            val busy=id in TestSessionClient.queuedProfiles.value || (state.running && result != null && result.phase != app.qthrone.mobile.ui.test.RowPhase.DONE)
            val latency=result?.takeIf { it.ok }?.latency ?: (holder.tag as? Int ?: 0)
            val unavailable=result?.failed==true || (result?.error?.isNotEmpty()==true && !ProxyEntity.isTestAborted(result.error))
            val label=if(unavailable) getString(R.string.qmobile_unavailable) else if(latency>0) "$latency ms" else ""
            val marker=if(busy) "busy" else label
            val previous=holder.contentDescription?.toString()
            if(previous==marker) return@forEach
            holder.contentDescription=marker
            holder.removeAllViews()
            if(busy) holder.addView(ProgressBar(requireContext()).apply {
                indeterminateTintList=android.content.res.ColorStateList.valueOf(blue)
            },FrameLayout.LayoutParams(dp(20),dp(20),Gravity.CENTER))
            else holder.addView(text(label,12f,if(unavailable) muted else Color.rgb(128,192,139)).apply {
                gravity=Gravity.CENTER;setPadding(0,0,0,0)
                if(previous=="busy") { alpha=0f;animate().alpha(1f).setDuration(180).start() }
            },FrameLayout.LayoutParams(-1,-1))
        }
    }
    private fun testMenu(ids: LongArray, label: String, gid: Long, wholeGroup: Boolean=false) {
        TestSessionClient.start(TestSpec(TestSpec.KIND_URL, profileIds=ids,scopeLabel=label,sortGroupId=if(wholeGroup) gid else 0L),gid)
    }
    private val importFile = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) SubscribeFlows.importFile(requireActivity(), uri)
    }
    private fun importClipboard() {
        val clip = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val value = clip.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(requireContext())?.toString()
        if (!value.isNullOrBlank()) SubscribeFlows.importText(requireActivity(), value)
        else Toast.makeText(requireContext(), R.string.qmobile_clipboard_empty, Toast.LENGTH_SHORT).show()
    }
    private fun pinnedGroups() = DataStore.mobilePinnedGroups.split(',').mapNotNull { it.toLongOrNull() }.toSet()
    private fun shareProfile(profile:ProxyEntity) {
        val swipe=openSwipe ?: return
        fun option(label:String,icon:Int,callback:()->Unit):View {
            return LinearLayout(requireContext()).apply {
                orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER;isFocusable=true;contentDescription=label;setBackgroundColor(Color.rgb(105,93,176))
                setOnClickListener { swipe.closeActions();callback() }
                addView(ImageView(requireContext()).apply { setImageResource(icon);imageTintList=android.content.res.ColorStateList.valueOf(Color.WHITE);importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO },LinearLayout.LayoutParams(dp(23),dp(23)))
                addView(text(label,10f,Color.WHITE).apply { setPadding(0,dp(4),0,0);maxLines=1;includeFontPadding=false;gravity=Gravity.CENTER;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO })
            }
        }
        fun copy(value:String) { Toast.makeText(requireContext(),if(SagerNet.trySetPrimaryClip(value)) R.string.action_export_msg else R.string.action_export_err,Toast.LENGTH_SHORT).show() }
        val link=profile.exportLink().ifBlank { profile.exportJsonLink() }
        swipe.showShareChoices(
            option("URL",R.drawable.ic_mobile_link) { copy(link) },
            option(getString(R.string.qmobile_qr),R.drawable.ic_mobile_qr) { app.qthrone.mobile.widget.QRCodeDialog(link,profile.displayName()).show(parentFragmentManager,"profile-qr") },
            option("JSON",R.drawable.ic_mobile_json) { copy(org.json.JSONObject(profile.outbound.exportToJson().toCompact()).toString(2)) },
        )
    }
    private fun groupMenu(group: ProxyGroup, anchor: View) {
        val dialog=MobileSheetDialog(requireContext())
        val body=LinearLayout(requireContext()).apply {
            orientation=LinearLayout.VERTICAL; setPadding(dp(16),dp(12),dp(16),dp(24)); setBackgroundColor(MobilePalette.background)
        }
        body.addView(View(requireContext()).apply { background=GradientDrawable().apply { setColor(MobilePalette.muted); cornerRadius=dp(2).toFloat() } },LinearLayout.LayoutParams(dp(40),dp(4)).apply { gravity=Gravity.CENTER_HORIZONTAL; bottomMargin=dp(20) })
        body.addView(text(group.displayName(),18f).apply { setTypeface(null,Typeface.BOLD) })
        fun entry(label:String,icon:Int,action:()->Unit) {
            val row=LinearLayout(requireContext()).apply { gravity=Gravity.CENTER_VERTICAL; minimumHeight=dp(56);background=tile(); isFocusable=true; contentDescription=label; setOnClickListener { dialog.dismiss();action() } }
            row.addView(ImageView(requireContext()).apply { setImageResource(icon); imageTintList=android.content.res.ColorStateList.valueOf(MobilePalette.muted) },LinearLayout.LayoutParams(dp(26),dp(26)).apply { marginStart=dp(14) })
            row.addView(text(label,17f).apply { gravity=Gravity.CENTER_VERTICAL; setTypeface(null,Typeface.BOLD) },LinearLayout.LayoutParams(-1,-2))
            body.addView(row,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
        }
        if(group.isSubscription) entry(getString(R.string.qmobile_refresh),R.drawable.ic_mobile_refresh) { SubscriptionClient.refreshGroup(group.id,false) }
        if(group.subOptions.routingProvider.isNotBlank()) entry(getString(R.string.provider_routing_title),R.drawable.ic_mobile_route) {
            startActivity(Intent(requireContext(),ProviderRoutingActivity::class.java).putExtra("groupId",group.id))
        }
        entry(getString(R.string.qmobile_ping),R.drawable.ic_mobile_test) { viewLifecycleOwner.lifecycleScope.launch { val ids=withContext(Dispatchers.IO) { SagerDatabase.proxyDao.getByGroup(group.id).map { it.id }.toLongArray() };testMenu(ids,group.displayName(),group.id,true) } }
        val autoSort=androidx.appcompat.widget.SwitchCompat(requireContext()).apply {
            text="Автосортировка по пингу";setTextColor(MobilePalette.text);textSize=17f;setTypeface(null,Typeface.BOLD)
            gravity=Gravity.CENTER_VERTICAL;minHeight=dp(56);setPadding(dp(14),dp(12),dp(14),dp(12))
            isChecked=group.subOptions.autoSortByPing
            setOnCheckedChangeListener { _,checked ->
                isEnabled=false
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            val latest=GroupRepo.get(group.id) ?: return@withContext
                            latest.subOptions=latest.subOptions.copy(autoSortByPing=checked)
                            GroupRepo.save(latest)
                        }
                        group.subOptions=group.subOptions.copy(autoSortByPing=checked)
                        refresh()
                    } finally { isEnabled=true }
                }
            }
        }
        val autoSortRow=LinearLayout(requireContext()).apply { gravity=Gravity.CENTER_VERTICAL;minimumHeight=dp(56);background=tile() }
        autoSortRow.addView(ImageView(requireContext()).apply {
            setImageResource(R.drawable.ic_mobile_sort_ping)
            imageTintList=android.content.res.ColorStateList.valueOf(MobilePalette.muted)
        },LinearLayout.LayoutParams(dp(26),dp(26)).apply { marginStart=dp(14) })
        autoSortRow.addView(autoSort,LinearLayout.LayoutParams(0,-2,1f))
        autoSortRow.setOnClickListener { if(autoSort.isEnabled) autoSort.toggle() }
        body.addView(autoSortRow,LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(8) })
        entry(getString(R.string.qmobile_edit),R.drawable.ic_mobile_edit) { editGroup(group,false) }
        val pinned=group.id in pinnedGroups()
        entry(getString(if(pinned) R.string.qmobile_unpin else R.string.qmobile_pin),R.drawable.ic_mobile_pin) { val ids=pinnedGroups().toMutableSet();if(pinned) ids.remove(group.id) else ids.add(group.id);DataStore.mobilePinnedGroups=ids.joinToString(",");refresh() }
        entry(getString(R.string.qmobile_delete),R.drawable.ic_mobile_delete) {
            viewLifecycleOwner.lifecycleScope.launch {
                val deleted=withContext(Dispatchers.IO) { GroupRepo.delete(group.id) }
                if(!deleted) Toast.makeText(requireContext(),R.string.qmobile_delete_failed,Toast.LENGTH_LONG).show()
            }
        }
        dialog.setContentView(androidx.core.widget.NestedScrollView(requireContext()).apply { addView(body) });dialog.show()
    }
    private fun editGroup(group:ProxyGroup,settings:Boolean) {
        val dialog=MobileSheetDialog(requireContext())
        val body=LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(24),dp(24),dp(24),dp(32));setBackgroundColor(MobilePalette.surface) }
        body.addView(text(getString(if(settings) R.string.qmobile_settings else R.string.qmobile_edit),20f))
        val name=EditText(requireContext()).apply { setText(group.name);hint=getString(R.string.qmobile_subscription_name);setTextColor(MobilePalette.text);setHintTextColor(MobilePalette.muted);isSingleLine=true }
        val url=EditText(requireContext()).apply { setText(group.url);hint=getString(R.string.qmobile_subscription_url);setTextColor(MobilePalette.text);setHintTextColor(MobilePalette.muted);inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        body.addView(name);if(group.isSubscription) body.addView(url)
        val auto=androidx.appcompat.widget.SwitchCompat(requireContext()).apply { text=getString(R.string.qmobile_auto_update);isChecked=!group.skipAutoUpdate;setTextColor(MobilePalette.text) }
        if(group.isSubscription) body.addView(auto)
        body.addView(action(getString(R.string.qmobile_save)) {
            val nextUrl=if(group.isSubscription) url.text.toString().trim() else ""
            if(group.isSubscription && (!nextUrl.startsWith("https://") && !nextUrl.startsWith("http://"))) { url.error=getString(R.string.qmobile_subscription_url);return@action }
            group.name=name.text.toString().trim().ifBlank { if(group.isSubscription) android.net.Uri.parse(nextUrl).host.orEmpty() else getString(R.string.qmobile_server_list) };group.url=nextUrl;if(group.isSubscription) group.skipAutoUpdate=!auto.isChecked
            viewLifecycleOwner.lifecycleScope.launch { withContext(Dispatchers.IO) { GroupRepo.save(group) };dialog.dismiss();refresh() }
        })
        dialog.setContentView(body);dialog.show()
    }
    private fun addMenu(anchor: View) {
        val popup = androidx.appcompat.widget.PopupMenu(requireContext(), anchor)
        popup.menu.add(getString(R.string.grp_add_subscription)).setOnMenuItemClickListener { addSubscription();true }
        popup.menu.add(getString(R.string.qmobile_clipboard)).setOnMenuItemClickListener { importClipboard(); true }
        popup.menu.add(getString(R.string.qmobile_qr)).setOnMenuItemClickListener { startActivity(Intent(requireContext(), ScannerActivity::class.java)); true }
        popup.menu.add(getString(R.string.qmobile_file)).setOnMenuItemClickListener { importFile.launch("*/*");true }
        popup.menu.add(getString(R.string.qmobile_manual)).setOnMenuItemClickListener { manualProfile();true }
        popup.menu.add(getString(R.string.qmobile_paste_json)).setOnMenuItemClickListener { importClipboard();true }
        popup.menu.add(getString(R.string.qmobile_copy_json)).setOnMenuItemClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val json=withContext(Dispatchers.IO) {
                    org.json.JSONArray().apply { SagerDatabase.proxyDao.getAll().forEach { put(org.json.JSONObject(it.outbound.exportToJson().toCompact())) } }.toString(2)
                }
                SagerNet.trySetPrimaryClip(json)
            };true
        }
        popup.show()
    }
    private fun addSubscription() {
        val sheet=MobileSheetDialog(requireContext())
        val body=LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(20),dp(20),dp(28));setBackgroundColor(surface) }
        body.addView(text(getString(R.string.grp_add_subscription),20f).apply { setTypeface(null,Typeface.BOLD) })
        val name=EditText(requireContext()).apply { hint=getString(R.string.qmobile_subscription_name);setTextColor(MobilePalette.text);setHintTextColor(muted);isSingleLine=true }
        val url=EditText(requireContext()).apply { hint=getString(R.string.qmobile_subscription_url);setTextColor(MobilePalette.text);setHintTextColor(muted);isSingleLine=true;inputType=android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI }
        body.addView(name);body.addView(url)
        body.addView(action(getString(R.string.qmobile_save)) {
            val address=url.text.toString().trim()
            val parsed=android.net.Uri.parse(app.qthrone.mobile.group.ProviderLinks.subscription(address)?.data ?: address)
            if(parsed.scheme !in listOf("https","http") || parsed.host.isNullOrBlank()) { url.error=getString(R.string.qmobile_subscription_url);return@action }
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    SubscriptionClient.subscribeUrl(address,name.text.toString().trim().ifBlank { parsed.host.orEmpty() },true)
                    sheet.dismiss()
                } catch(error: kotlinx.coroutines.CancellationException) {
                    throw error
                } catch(error: Exception) {
                    url.error=error.localizedMessage ?: getString(R.string.qmobile_subscription_url)
                }
            }
        })
        sheet.setContentView(body);sheet.show()
    }
    private fun manualProfile() {
        val sheet=MobileSheetDialog(requireContext())
        val body=LinearLayout(requireContext()).apply {
            orientation=LinearLayout.VERTICAL;setPadding(dp(16),dp(12),dp(16),0)
            setBackgroundColor(MobilePalette.background)
        }
        body.addView(View(requireContext()).apply {
            background=GradientDrawable().apply { setColor(muted);cornerRadius=dp(2).toFloat() }
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(dp(40),dp(4)).apply {
            gravity=Gravity.CENTER_HORIZONTAL;bottomMargin=dp(20)
        })
        body.addView(text(getString(R.string.qmobile_manual),20f).apply {
            setTypeface(null,Typeface.BOLD)
        },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(12) })
        val protocols=LinearLayout(requireContext()).apply {
            orientation=LinearLayout.VERTICAL;setPadding(0,0,0,dp(24))
        }
        // Nested scrolling lets the sheet consume a downward drag only at the top of the list.
        // The handle and title stay outside the scrolling content.
        body.addView(androidx.core.widget.NestedScrollView(requireContext()).apply {
            isFillViewport=true;isVerticalScrollBarEnabled=false
            addView(protocols,ViewGroup.LayoutParams(-1,-2))
        },LinearLayout.LayoutParams(-1,0,1f))
        val nativeTypes = listOf("qwdtt" to "qWDTT", "wdttplus" to "WDTT Plus", "openflux" to "OpenFlux", "olcrtc" to "olcRTC",
            "masterdns" to "MasterDNS", "vaydns" to "VayDNS", "dnstt" to "DNSTT", "slipstream" to "SlipStream", "s3bypass" to "S3 Bypass")
        // Keep the same editors and localized labels as the original manual-entry menu.
        val originalTypes = listOf(
            "autoselector" to R.string.action_auto_selector,
            "socks" to R.string.action_socks,
            "http" to R.string.action_http,
            "shadowsocks" to R.string.action_shadowsocks,
            "vmess" to R.string.action_vmess,
            "vless" to R.string.action_vless,
            "xrayvless" to R.string.action_vless_xray,
            "trojan" to R.string.action_trojan,
            "mieru" to R.string.action_mieru,
            "naive" to R.string.action_naive,
            "hysteria" to R.string.action_hysteria,
            "tuic" to R.string.action_tuic,
            "juicity" to R.string.action_juicity,
            "shadowtls" to R.string.action_shadowtls,
            "anytls" to R.string.action_anytls,
            "trusttunnel" to R.string.action_trusttunnel,
            "snell" to R.string.action_snell,
            "ssh" to R.string.action_ssh,
            "wireguard" to R.string.action_wireguard,
            "masque" to R.string.action_masque,
            "openvpn" to R.string.action_openvpn,
            "openconnect" to R.string.action_openconnect,
            "direct" to R.string.action_direct,
            "custom" to R.string.custom_config,
            "chain" to R.string.proxy_chain,
        ).map { (type, title) -> type to getString(title) }
        for((type, title) in nativeTypes + originalTypes) {
            protocols.addView(androidx.appcompat.widget.AppCompatButton(requireContext()).apply {
                text=title;isAllCaps=false;textSize=18f;setTypeface(null,Typeface.BOLD)
                setTextColor(MobilePalette.text);background=tile();stateListAnimator=null
                supportBackgroundTintList=null
                gravity=Gravity.CENTER_VERTICAL or Gravity.START;includeFontPadding=false
                minimumHeight=dp(56);setPadding(dp(16),dp(14),dp(16),dp(14))
                MobileFeedback.apply(this,radius=5f)
                setOnClickListener {
                    isEnabled=false
                    sheet.dismiss()
                    startActivity(Intent(requireContext(),app.qthrone.mobile.ui.profile.settingsActivityFor(type)).putExtra("qmobileProfileType",type))
                }
            },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
        }
        sheet.setContentView(body,ViewGroup.LayoutParams(-1,(resources.displayMetrics.heightPixels*.9f).toInt()))
        sheet.setOnShowListener {
            val panel=sheet.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            val available=(panel?.parent as? View)?.height?.takeIf { it>0 }
                ?: resources.displayMetrics.heightPixels
            body.layoutParams=body.layoutParams.apply { height=(available*.9f).toInt() }
            sheet.behavior.apply {
                skipCollapsed=true
                state=com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
            }
        }
        sheet.show()
    }
}
