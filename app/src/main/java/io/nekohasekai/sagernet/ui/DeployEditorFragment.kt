package app.qthrone.mobile.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import android.text.InputType
import android.text.TextWatcher
import android.text.Editable
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import app.qthrone.mobile.R
import app.qthrone.mobile.deploy.*
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText

/** Compact author-style deployment form with an inline, collapsible VPS console. */
open class DeployEditorFragment : Fragment() {
    private val host get()=requireActivity() as DeployActivity
    private lateinit var scroll: ScrollView
    private lateinit var body: LinearLayout
    private var consoleCard: View?=null
    private var details: View?=null
    private var toggle: ImageView?=null
    private var consoleHeader: View?=null
    private var configCard: View?=null
    private var configDetails: View?=null
    private var configArrow: ImageView?=null
    private var configBox: LinearLayout?=null
    private var configExpanded=true
    private var displayedConfig=""
    private var shownConfig: String?=null
    private var saveAction: android.view.MenuItem?=null
    private var openSwipe: MobileSwipeTile?=null
    private var listLoad: Job?=null
    private var output: TextView?=null
    private var logScroll: ScrollView?=null
    private var followLogs=true
    private var touchingLogs=false
    private var status: TextView?=null
    private var progress: ProgressBar?=null
    private var stop: MaterialButton?=null
    private var expanded=true
    private val operations=mutableListOf<View>()
    private fun dp(n: Int)=(n*resources.displayMetrics.density).toInt()
    private fun column()=LinearLayout(requireContext()).apply { orientation=LinearLayout.VERTICAL }
    override fun onCreateView(inflater: android.view.LayoutInflater,container: ViewGroup?,state: Bundle?): View {
        expanded=state?.getBoolean("consoleExpanded") ?: true
        configExpanded=state?.getBoolean("configExpanded") ?: true
        scroll=ScrollView(requireContext()).apply { isFillViewport=true;setBackgroundColor(MobilePalette.background) }
        body=column().apply { setPadding(dp(16),dp(8),dp(16),dp(24)) };scroll.addView(body);return scroll
    }
    override fun onViewCreated(view: View,state: Bundle?) { super.onViewCreated(view,state);build() }
    override fun onSaveInstanceState(out: Bundle) { out.putBoolean("consoleExpanded",expanded);out.putBoolean("configExpanded",configExpanded);super.onSaveInstanceState(out) }
    fun refreshSaveAction() { saveAction?.isVisible=host.hasChanges() }
    private fun disclosure(parent: LinearLayout,title: String,click: ()->Unit): ImageView {
        val header=LinearLayout(requireContext()).apply {
            gravity=android.view.Gravity.CENTER_VERTICAL;minimumHeight=dp(48)
            isFocusable=true;setOnClickListener { click() };MobileFeedback.apply(this)
        }
        header.addView(TextView(requireContext()).apply { text=title;textSize=16f;setTextColor(MobilePalette.text) },LinearLayout.LayoutParams(0,-2,1f))
        val arrow=ImageView(requireContext()).apply { imageTintList=android.content.res.ColorStateList.valueOf(MobilePalette.accent(requireContext()));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        header.addView(arrow,LinearLayout.LayoutParams(dp(26),dp(26)))
        parent.addView(header)
        return arrow
    }
    private fun heading(parent: LinearLayout,title: String) {
        parent.addView(TextView(requireContext()).apply { text=title;textSize=16f;setTextColor(MobilePalette.accent(requireContext()));setTypeface(null,android.graphics.Typeface.BOLD);setPadding(0,dp(10),0,dp(8)) })
    }
    private fun card(): LinearLayout {
        val content=column().apply { setPadding(dp(16),dp(10),dp(16),dp(10)) }
        body.addView(MaterialCardView(requireContext()).apply { radius=dp(20).toFloat();cardElevation=0f;strokeWidth=0;setCardBackgroundColor(MobilePalette.surface);addView(content) },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
        return content
    }
    private fun row(parent: LinearLayout,block: (LinearLayout)->Unit) {
        val r=LinearLayout(requireContext()).apply { orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL };parent.addView(r);block(r)
    }
    private fun serverTile(title: String,subtitle: String?,icon: Int,server: DeployServer?=null,click: ()->Unit) {
        val tile=LinearLayout(requireContext()).apply {
            orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(18),dp(16),dp(18),dp(16));minimumHeight=dp(64)
            background=android.graphics.drawable.GradientDrawable().apply {
                setColor(MobilePalette.surface);cornerRadius=dp(20).toFloat()
            }
            isFocusable=true;contentDescription=listOfNotNull(title,subtitle).joinToString(". ")
            setOnClickListener { click() };MobileFeedback.apply(this)
        }
        tile.addView(ImageView(requireContext()).apply {
            setImageResource(icon);imageTintList=android.content.res.ColorStateList.valueOf(MobilePalette.accent(requireContext()))
            importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
        },LinearLayout.LayoutParams(dp(26),dp(26)).apply { marginEnd=dp(20) })
        val labels=column()
        labels.addView(TextView(requireContext()).apply { text=title;textSize=16f;setTextColor(MobilePalette.text) })
        if(subtitle!=null) labels.addView(TextView(requireContext()).apply {
            text=subtitle;textSize=14f;setTextColor(MobilePalette.muted);setPadding(0,dp(3),0,0)
        })
        tile.addView(labels,LinearLayout.LayoutParams(0,-2,1f))
        val entry: View=if(server==null) tile else {
            val delete=ImageButton(requireContext()).apply {
                setImageResource(R.drawable.ic_mobile_delete);imageTintList=android.content.res.ColorStateList.valueOf(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.rgb(220,66,61));setPadding(dp(18),dp(18),dp(18),dp(18))
                contentDescription="Удалить VPS из списка"
                setOnClickListener {
                    if(DeployEngine.state.value.running && DeployEngine.state.value.serverId==server.id) {
                        Toast.makeText(context,"Дождитесь завершения операции",Toast.LENGTH_SHORT).show()
                    } else MobileAlertDialogBuilder(requireContext()).setMessage("Удалить VPS из списка? Сервис на сервере останется.")
                        .setPositiveButton("Удалить") { _,_ -> DeployStore.remove(server.id);build() }.setNegativeButton(android.R.string.cancel,null).show()
                }
            }
            MobileSwipeTile(requireContext(),tile,View(requireContext()),View(requireContext()),delete,deleteOnly=true).apply {
                background=android.graphics.drawable.GradientDrawable().apply { setColor(MobilePalette.surface);cornerRadius=dp(20).toFloat() }
                clipToOutline=true
                onOpened={ opened -> openSwipe?.takeIf { it!==opened }?.closeActions();openSwipe=opened }
            }
        }
        body.addView(entry,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
    }
    private fun button(parent: LinearLayout,title: String,icon: Int=0,pair: Boolean=false,click: ()->Unit): MaterialButton {
        val b=MaterialButton(requireContext()).apply {
            text=title;textSize=14f;isAllCaps=false;maxLines=3;minimumHeight=dp(48);cornerRadius=dp(16)
            setTextColor(MobilePalette.accent(requireContext()));backgroundTintList=android.content.res.ColorStateList.valueOf(MobilePalette.surface)
            if(icon!=0) { setIconResource(icon);iconSize=dp(22);iconTint=android.content.res.ColorStateList.valueOf(MobilePalette.accent(requireContext())) }
            setOnClickListener { click() }
        }
        parent.addView(b,(if(pair) LinearLayout.LayoutParams(0,-2,1f) else LinearLayout.LayoutParams(-1,-2)).apply { if(pair) marginEnd=dp(4) })
        return b
    }
    private fun field(parent: LinearLayout,s: DeployServer,key: String,label: String,secret: Boolean=false,number: Boolean=false,pair: Boolean=false) {
        val box=TextInputLayout(androidx.appcompat.view.ContextThemeWrapper(requireContext(),R.style.MobileDeployInputOverlay)).apply {
            hint=label;boxBackgroundMode=TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dp(14).toFloat(),dp(14).toFloat(),dp(14).toFloat(),dp(14).toFloat())
            defaultHintTextColor=android.content.res.ColorStateList.valueOf(MobilePalette.muted)
            hintTextColor=android.content.res.ColorStateList.valueOf(MobilePalette.accent(requireContext()))
            setBoxStrokeColorStateList(android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused),intArrayOf(android.R.attr.state_hovered),intArrayOf()),intArrayOf(MobilePalette.accent(requireContext()),MobilePalette.accent(requireContext()),MobilePalette.muted)))
            setEndIconTintList(android.content.res.ColorStateList.valueOf(MobilePalette.accent(requireContext())))
            if(secret) endIconMode=TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val editor=TextInputEditText(box.context).apply {
            textSize=16f;setTextColor(MobilePalette.text);setSingleLine(true)
            highlightColor=androidx.core.graphics.ColorUtils.setAlphaComponent(MobilePalette.accent(requireContext()),80)
            inputType=if(secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD else if(number) InputType.TYPE_CLASS_NUMBER else InputType.TYPE_CLASS_TEXT
            setText(s[key]);setPadding(dp(12),dp(16),dp(12),dp(16))
            fun tintCursor() {
                if(android.os.Build.VERSION.SDK_INT>=29) {
                    val accent=MobilePalette.accent(requireContext())
                    textCursorDrawable=android.graphics.drawable.GradientDrawable().apply { setColor(accent);setTint(accent);setSize(dp(2),dp(22)) }
                    textSelectHandle?.mutate()?.apply { setTint(accent) }?.let { setTextSelectHandle(it) }
                    textSelectHandleLeft?.mutate()?.apply { setTint(accent) }?.let { setTextSelectHandleLeft(it) }
                    textSelectHandleRight?.mutate()?.apply { setTint(accent) }?.let { setTextSelectHandleRight(it) }
                }
            }
            tintCursor()
            setOnFocusChangeListener { _,focused -> if(focused) post { tintCursor() } }
            addTextChangedListener(object: TextWatcher {
                override fun beforeTextChanged(value: CharSequence?,start: Int,count: Int,after: Int) {}
                override fun afterTextChanged(value: Editable?) {}
                override fun onTextChanged(value: CharSequence?,start: Int,before: Int,count: Int) {
                    if(s[key]==value.toString()) return
                    s[key]=value?.toString().orEmpty();if(key in listOf("host","sshPort")) s["hostKey"]=""
                    refreshSaveAction()
                }
            })
        }
        box.addView(editor,LinearLayout.LayoutParams(-1,-2))
        parent.addView(box,(if(pair) LinearLayout.LayoutParams(0,-2,1f) else LinearLayout.LayoutParams(-1,-2)).apply { topMargin=dp(6);bottomMargin=dp(6);if(pair) marginEnd=dp(4) })
    }
    private fun switch(parent: LinearLayout,s: DeployServer,key: String,label: String,rebuild: Boolean=false) {
        parent.addView(SwitchMaterial(requireContext()).apply {
            text=label;textSize=15f;setTextColor(MobilePalette.text);minHeight=dp(48);isChecked=s[key]=="true"
            setOnCheckedChangeListener { _,checked -> s[key]=checked.toString();refreshSaveAction();if(rebuild) build() }
        },LinearLayout.LayoutParams(-1,-2))
    }
    private fun secrets(s: DeployServer) {
        val c=column().apply { setPadding(dp(20),dp(8),dp(20),dp(16)) }
        field(c,s,"password",if(s.kind=="wdttplus") "Главный пароль" else "Пароль туннеля",secret=true)
        if(s.kind=="wdttplus") {
            heading(c,"DNS для клиентов (необязательно)")
            field(c,s,"dns","DNS через запятую")
        } else {
            heading(c,"Telegram-бот (необязательно)")
            field(c,s,"adminId","ID администратора",number=true);field(c,s,"botToken","Токен бота",secret=true)
        }
        heading(c,"SSH");field(c,s,"sshPort","Порт SSH",number=true)
        MobileAlertDialogBuilder(requireContext()).setTitle("Секреты деплоя")
            .setView(ScrollView(requireContext()).apply { addView(c) }).setPositiveButton("Готово",null).show()
    }
    fun build() {
        if(!this::body.isInitialized) return
        listLoad?.cancel()
        body.removeAllViews();host.toolbar.menu.clear();operations.clear();saveAction=null;openSwipe=null
        output=null;logScroll=null;status=null;progress=null;stop=null;consoleCard=null;details=null;toggle=null
        consoleHeader=null;configCard=null;configDetails=null;configArrow=null;configBox=null;shownConfig=null;displayedConfig=""
        val s=host.server
        host.toolbar.title=if(s==null) "Деплой "+DeployActivity.title(host.kind) else "Управление сервером"
        if(s==null) {
            listLoad=viewLifecycleOwner.lifecycleScope.launch {
                val result=withContext(Dispatchers.IO) { runCatching { DeployStore.savedServers().filter { it.kind==host.kind } } }
                if(host.server!=null) return@launch
                result.onSuccess { servers -> for(item in servers) {
                    serverTile(item["name"],item["host"].ifBlank { "Укажите адрес VPS" },R.drawable.ic_mobile_core,item) { host.server=item;build() }
                } }.onFailure { Toast.makeText(context,"Не удалось загрузить список VPS",Toast.LENGTH_LONG).show() }
                serverTile("Добавить VPS для деплоя",null,R.drawable.ic_mobile_add) {
                    host.server=DeployServer.create(host.kind)
                    build()
                }
            }
            return
        }
        saveAction=host.toolbar.menu.add("Сохранить").apply {
            setIcon(R.drawable.ic_mobile_apply);setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                val snapshot=DeployServer(org.json.JSONObject(s.json.toString()))
                val signature=snapshot.editSignature()
                isEnabled=false
                viewLifecycleOwner.lifecycleScope.launch {
                    val result=withContext(Dispatchers.IO) { runCatching { snapshot.validateAddress();DeployStore.save(snapshot) } }
                    if(host.server !== s) return@launch
                    saveAction?.isEnabled=true
                    result.onSuccess { Toast.makeText(context,"Сервер сохранён",Toast.LENGTH_SHORT).show();host.saved(signature) }
                        .onFailure { MobileAlertDialogBuilder(requireContext()).setMessage(it.message).setPositiveButton(android.R.string.ok,null).show() }
                }
                true
            }
        }
        refreshSaveAction()
        button(body,if(s.kind=="qwdtt") "Секреты (бот, пароли)" else "Авторизация",R.drawable.ic_baseline_vpn_key_24) { secrets(s) }
        row(body) { r ->
            operations+=button(r,"Установить",R.drawable.ic_mobile_core,true) { host.start(false) }
            operations+=button(r,"Удалить сервис с VPS",R.drawable.ic_mobile_delete,true) { host.start(true) }
            // Both take the taller one's height when the longer title wraps.
            operations.takeLast(2).forEach { it.layoutParams.height=LinearLayout.LayoutParams.MATCH_PARENT }
        }
        val access=card()
        field(access,s,"name","Название сервера")
        row(access) { r -> field(r,s,"host","IP / домен",pair=true);field(r,s,"user","Логин SSH",pair=true) }
        heading(access,"Вход на сервер")
        row(access) { r ->
            for((value,label) in listOf("password" to "Пароль","key" to "SSH-ключ")) button(r,label,pair=true) { s["auth"]=value;build() }.apply {
                strokeWidth=dp(1);strokeColor=android.content.res.ColorStateList.valueOf(if(s["auth"]==value) MobilePalette.accent(requireContext()) else MobilePalette.muted)
            }
        }
        if(s["auth"]=="key") {
            row(access) { r ->
                button(r,if(s["key"].isBlank()) "Загрузить ключ" else "Сменить ключ",R.drawable.ic_baseline_vpn_key_24,pair=true) { host.pickKey() }.apply {
                    strokeWidth=dp(1);strokeColor=android.content.res.ColorStateList.valueOf(MobilePalette.accent(requireContext()))
                    backgroundTintList=android.content.res.ColorStateList.valueOf(MobilePalette.background)
                }
                if(s["key"].isNotBlank()) button(r,"Удалить",R.drawable.ic_mobile_delete,true) { s["key"]="";s["keyName"]="";build() }
            }
            if(s["key"].isNotBlank()) heading(access,"Ключ загружен: "+s["keyName"])
            field(access,s,"keyPassword","Пароль SSH-ключа",secret=true);field(access,s,"sudoPassword","Пароль sudo (если требуется)",secret=true)
        } else field(access,s,"sshPassword","Пароль SSH",secret=true)
        if(s.kind=="qwdtt") row(access) { r -> field(r,s,"dns1","DNS 1",pair=true);field(r,s,"dns2","DNS 2",pair=true) }
        switch(access,s,"manualPorts","Ручное управление портами",true)
        if(s["manualPorts"]=="true") {
            row(access) { r -> field(r,s,"peerPort","Порт туннеля",number=true,pair=true);field(r,s,"wgPort","Порт WG",number=true,pair=true) }
            if(s.kind=="wdttplus") field(access,s,"localPort","Локальный порт клиента",number=true)
            if(s.kind=="qwdtt") {
                row(access) { r -> field(r,s,"rawPort","Порт RAW",number=true,pair=true);field(r,s,"directPort","Прямой порт",number=true,pair=true) }
                field(access,s,"adminPort","Порт админ-панели",number=true)
            }
        }
        val console=card();consoleCard=console.parent as View
        toggle=disclosure(console,"Консоль") { expanded=!expanded;expand() };consoleHeader=toggle?.parent as? View
        val content=column();console.addView(content);details=content
        status=TextView(requireContext()).apply { setTextColor(MobilePalette.text);textSize=14f };content.addView(status)
        progress=ProgressBar(requireContext(),null,android.R.attr.progressBarStyleHorizontal);content.addView(progress,LinearLayout.LayoutParams(-1,dp(16)))
        output=TextView(requireContext()).apply { setTextColor(android.graphics.Color.rgb(134,235,149));typeface=android.graphics.Typeface.MONOSPACE;textSize=12f;setTextIsSelectable(true);setPadding(dp(12),dp(12),dp(12),dp(12)) }
        logScroll=object: ScrollView(requireContext()) {
            override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
                if(event.actionMasked==android.view.MotionEvent.ACTION_DOWN) {
                    touchingLogs=true;followLogs=false;parent.requestDisallowInterceptTouchEvent(true)
                }
                val handled=super.dispatchTouchEvent(event)
                if(event.actionMasked==android.view.MotionEvent.ACTION_UP || event.actionMasked==android.view.MotionEvent.ACTION_CANCEL) {
                    touchingLogs=false;followLogs=!canScrollVertically(1);parent.requestDisallowInterceptTouchEvent(false)
                }
                return handled
            }
        }.apply {
            background=android.graphics.drawable.GradientDrawable().apply { setColor(android.graphics.Color.rgb(8,12,9));cornerRadius=dp(12).toFloat() };clipToOutline=true
            isVerticalScrollBarEnabled=false;isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER
            setOnScrollChangeListener { _: View, _: Int, _: Int, _: Int, _: Int ->
                if(touchingLogs) followLogs=!canScrollVertically(1)
                else if(!canScrollVertically(1)) followLogs=true
            }
            addView(output)
        }
        output?.addOnLayoutChangeListener { _,_,_,_,_,_,_,_,_ ->
            if(followLogs && !touchingLogs) logScroll?.scrollTo(0,output?.height ?: 0)
        }
        content.addView(logScroll,LinearLayout.LayoutParams(-1,dp(220)))
        stop=button(content,"Остановить") { DeployEngine.cancel() }.apply {
            backgroundTintList=android.content.res.ColorStateList.valueOf(MobilePalette.background)
            insetTop=0;insetBottom=0
            (layoutParams as LinearLayout.LayoutParams).apply { topMargin=dp(12);bottomMargin=dp(6);height=dp(48) }
        }
        val config=card();configCard=config.parent as View
        configArrow=disclosure(config,DeployActivity.title(s.kind)+" конфиг") { configExpanded=!configExpanded;expand() }
        val configBody=column();config.addView(configBody);configDetails=configBody
        // The same share blocks as every server screen: the link, its QR code and, for qWDTT, the legacy wdtt:// link.
        configBox=column().also { configBody.addView(it) }
        expand();render(DeployEngine.state.value)
    }
    private fun expand() {
        details?.visibility=if(expanded) View.VISIBLE else View.GONE
        toggle?.setImageResource(if(expanded) R.drawable.ic_baseline_expand_less_24 else R.drawable.ic_baseline_expand_more_24)
        consoleHeader?.contentDescription=if(expanded) "Консоль, раскрыта" else "Консоль, свёрнута"
        configDetails?.visibility=if(configExpanded) View.VISIBLE else View.GONE
        configArrow?.setImageResource(if(configExpanded) R.drawable.ic_baseline_expand_less_24 else R.drawable.ic_baseline_expand_more_24)
        (configArrow?.parent as? View)?.contentDescription=if(configExpanded) "Конфиг, раскрыт" else "Конфиг, свёрнут"
    }
    fun revealConsole() {
        expanded=true;followLogs=true;expand()
        (requireContext().getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(scroll.windowToken,0)
        scroll.post { consoleCard?.let { scroll.smoothScrollTo(0,it.top) } }
    }
    fun render(state: DeployState) {
        val relevant=state.serverId==host.server?.id
        val s=host.server
        if(relevant && s!=null) {
            if(state.configCleared) s.recordUninstall()
            else if(state.config.isNotBlank()) s["config"]=state.config
        }
        displayedConfig=DeployConfig.display(s?.get("config").orEmpty())
        val legacy=if(s!=null && s.kind=="qwdtt" && displayedConfig.isNotBlank()) runCatching {
            app.qthrone.mobile.deploy.QwdttAdmin.links(s,s["name"].ifBlank { s["password"] },s["password"],emptyList()).second
        }.getOrNull() else null
        val shown=displayedConfig+" "+legacy.orEmpty()
        if(configBox!=null && shown!=shownConfig) {
            shownConfig=shown
            val ui=ServerUi(requireContext())
            configBox?.removeAllViews()
            if(displayedConfig.isBlank()) configBox?.addView(ui.text("Конфиг появится после успешной установки",14f,MobilePalette.muted))
            else {
                configBox?.addView(ui.linkRow(null,displayedConfig,lines=4))
                configBox?.addView(ui.qrRow(displayedConfig))
                if(legacy!=null) configBox?.addView(ui.linkRow("Ссылка WDTT (легаси)",legacy))
            }
        }
        status?.text=if(relevant) state.step+(if(state.running && !state.installerProgress && state.percent in 45..99) "" else " · "+state.percent+"%") else "Готово к подключению"
        val logs="$ "+(s?.kind ?: "deploy")+" deploy\n"+(if(relevant) state.logs.ifBlank { "Ожидание подключения к VPS…" } else "Ожидание подключения к VPS…")
        if(output?.text?.toString()!=logs) {
            output?.text=logs
            if(followLogs) logScroll?.post { if(followLogs && !touchingLogs) logScroll?.scrollTo(0,output?.height ?: 0) }
        }
        progress?.progress=if(relevant) state.percent else 0
        progress?.isIndeterminate=relevant && state.running && !state.installerProgress && state.percent in 45..99
        stop?.apply {
            isEnabled=relevant && state.running && !state.stopping
            text=if(relevant && state.stopping) "Останавливаю…" else "Остановить"
            alpha=if(isEnabled) 1f else .45f
            setTextColor(if(isEnabled) MobilePalette.accent(requireContext()) else MobilePalette.muted)
        }
        operations.forEach { it.isEnabled=!state.running }
        // Keep the encrypted record consistent with the operation's SSH/settings snapshot.
        fun editable(view: View) {
            if(view===consoleCard || view===configCard) return
            if(view is TextInputLayout || view is SwitchMaterial || view is MaterialButton) view.isEnabled=!(relevant && state.running)
            if(view is ViewGroup) for(i in 0 until view.childCount) editable(view.getChildAt(i))
        }
        if(this::body.isInitialized) editable(body)
        operations.forEach { it.isEnabled=!state.running }
    }
    override fun onDestroyView() { output=null;logScroll=null;status=null;progress=null;stop=null;consoleCard=null;details=null;toggle=null;consoleHeader=null;configCard=null;configDetails=null;configArrow=null;configBox=null;shownConfig=null;saveAction=null;openSwipe=null;operations.clear();super.onDestroyView() }
}

