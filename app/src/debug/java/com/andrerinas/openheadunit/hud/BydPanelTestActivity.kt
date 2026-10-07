package com.andrerinas.openheadunit.hud

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.andrerinas.openheadunit.utils.AppLog
import java.lang.reflect.InvocationTargetException

/**
 * Debug-only, parked, manual test of the factory instrument SDK on units where no validated
 * cluster output exists (first target: DiLink 3.0 / QCM6125 / Android 10).
 *
 * Every write is one explicit button press, limited to the music-name and navigation-guidance
 * calls, and has a matching clear. Nothing runs in the background except the 15-second guidance
 * demo, which ends its own navigation state. Every result is written to the app log so it appears
 * in the next diagnostic report.
 */
class BydPanelTestActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var output: TextView
    private var device: Any? = null
    private var guidanceRunning = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        root.addView(TextView(this).apply {
            text = "Prueba del panel de instrumentos BYD — usar solo con el carro estacionado."
            textSize = 20f
        })
        fun button(label: String, action: () -> Unit) = root.addView(Button(this).apply {
            text = label
            textSize = 18f
            setOnClickListener { action() }
        })
        button("1. Pedir permiso del panel") { requestPanelPermission() }
        button("2. Leer estado (no escribe nada)") { readOnly() }
        button("3. Enviar canción de prueba") { musicName("DiAuto prueba") }
        button("   Borrar canción") { musicName("") }
        button("4. Probar flecha de navegación (15 s)") { startGuidanceDemo() }
        button("   Terminar navegación") { endGuidance("manual") }
        button("5. Buscar receptores de mapas (no escribe nada)") { findMapReceivers() }
        button("6. Probar flecha vía servicio de mapas (15 s)") { startAmapDemo() }
        button("   6a. Solo com.example.amapservice") { startAmapDemo(listOf("com.example.amapservice")) }
        button("   6b. Solo com.byd.automap") { startAmapDemo(listOf("com.byd.automap")) }
        button("   6c. Flecha a 0 m durante 10 s (ver texto en chino)") { zeroDistanceDemo() }
        button("   6d. Distancias cortas: 1 m, 5 m, 10 m, 20 m y fin de navegación") { shortDistanceDemo() }
        button("   Limpiar flecha del servicio de mapas") { endAmap("manual") }
        button("7. Inspeccionar servicios de mapas y música (no escribe nada)") { Thread { inspectMapServices() }.start() }
        button("8. Inspección profunda del media center (no escribe nada)") {
            Thread { listOf("com.byd.mediacenter", "com.byd.widget.mediacenter").forEach { deepScan(it) }; report("Inspección profunda terminada.") }.start()
        }
        button("10. Protocolo Android Auto: cambiar 1.2 ↔ 1.7 (próxima conexión)") {
            val settings = com.andrerinas.openheadunit.utils.Settings(this)
            settings.debugAaProtocolMinor = if (settings.debugAaProtocolMinor == 2) 7 else 2
            report("Protocolo para la próxima conexión: 1.${settings.debugAaProtocolMinor}. Cerrá DiAuto HUD Test y reconectá el teléfono.")
        }
        button("9. Inspeccionar botón de voz del volante (no escribe nada)") {
            Thread {
                val extra = runCatching {
                    packageManager.getInstalledPackages(0).map { it.packageName }.filter { name ->
                        listOf("zlink", "carlink", "autolink", "carplay", "androidauto", "projection", "carlife", "voice", "customkey", "keyevent", "keyservice")
                            .any { name.contains(it, ignoreCase = true) }
                    }
                }.getOrDefault(emptyList())
                report("Apps de voz/teclas/proyección: ${extra.joinToString()}")
                (listOf("com.byd.customkey", "com.byd.autovoice", "com.byd.vrassistant") + extra).distinct()
                    .filter { installed(it) }.forEach { deepScan(it) }
                report("Inspección del botón de voz terminada.")
            }.start()
        }
        output = TextView(this).apply { textSize = 16f; setTextIsSelectable(true) }
        root.addView(output)
        setContentView(ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        report("Paquete ${packageName}, Android ${Build.VERSION.RELEASE}, permiso=${hasPermission()}, " +
            "protocolo AA=1.${com.andrerinas.openheadunit.utils.Settings(this).debugAaProtocolMinor}")
    }

    /** Logs every key that reaches this screen, to learn what the steering-wheel voice button sends. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN || event.action == android.view.KeyEvent.ACTION_UP) {
            report("Tecla: ${android.view.KeyEvent.keyCodeToString(event.keyCode)} (${event.keyCode}) " +
                "${if (event.action == android.view.KeyEvent.ACTION_DOWN) "DOWN" else "UP"} repeat=${event.repeatCount} " +
                "largo=${event.isLongPress} scan=${event.scanCode} fuente=${event.source} dispositivo=${event.deviceId}")
        }
        // Back still closes the screen; every other key is only observed.
        return if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK) super.dispatchKeyEvent(event) else true
    }

    override fun onDestroy() {
        if (guidanceRunning) endGuidance("activity closed")
        if (amapRunning) endAmap("activity closed")
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun hasPermission() = checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun requestPanelPermission() {
        if (hasPermission()) { report("El permiso ya está concedido."); return }
        requestPermissions(arrayOf(PERMISSION), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE) report("Resultado del permiso: concedido=${hasPermission()}")
    }

    private fun sdk(): Any? {
        device?.let { return it }
        return try {
            Class.forName(DEVICE_CLASS).getMethod("getInstance", Context::class.java)
                .invoke(null, applicationContext).also { device = it; report("getInstance: ok") }
        } catch (t: Throwable) {
            report("getInstance falló: ${describe(t)}")
            null
        }
    }

    /** Calls a public SDK method by name and reports what it returned (0 means accepted). */
    private fun call(name: String, vararg args: Any): Any? {
        val sdk = sdk() ?: return null
        return try {
            val method = sdk.javaClass.methods.first { it.name == name && it.parameterTypes.size == args.size }
            method.invoke(sdk, *args).also { report("$name(${args.joinToString()}) -> $it") }
        } catch (t: Throwable) {
            report("$name(${args.joinToString()}) falló: ${describe(t)}")
            null
        }
    }

    private fun readOnly() {
        for (getter in listOf("getMusicInfoResult", "getTextInfo", "getTextColor", "getDirectionInfo", "getNaviDestinationCommand")) {
            call(getter)
        }
    }

    private fun musicName(name: String) {
        call("sendMusicName", name)
    }

    private fun startGuidanceDemo() {
        if (guidanceRunning) { report("La prueba de flecha ya está corriendo."); return }
        val ids = guidanceIds() ?: return
        if (call("sendAutoNaviStatus", NAVI_STATUS_START) == null) return
        guidanceRunning = true
        // Two steps, like the stock demo: a left-type turn at 500 m, then a right-type turn at 800 m.
        val steps = listOf(Triple(0L, 2, 500), Triple(7_500L, 3, 800))
        for ((delay, icon, distance) in steps) {
            handler.postDelayed({
                if (!guidanceRunning) return@postDelayed
                val turn = BydFactoryTurnCode.map(icon, 0) ?: return@postDelayed
                setGuidance(ids, distance, turn)
            }, delay)
        }
        handler.postDelayed({ if (guidanceRunning) endGuidance("demo complete") }, 15_000L)
    }

    private fun endGuidance(reason: String) {
        guidanceRunning = false
        handler.removeCallbacksAndMessages(null)
        report("Terminando navegación ($reason)")
        call("sendAutoNaviStatus", NAVI_STATUS_END)
    }

    /** Only the two guidance IDs this firmware lists; the third the DiLink 5 path uses is absent here. */
    private fun guidanceIds(): IntArray? = try {
        val ids = Class.forName("$FEATURE_IDS\$Instrument")
        intArrayOf(
            ids.getField("INSTRUMENT_FRONT_CROSSING_DISTANCE_SET").getInt(null),
            ids.getField("INSTRUMENT_GUIDE_INFO_SIMPLE_SET").getInt(null)
        )
    } catch (t: Throwable) {
        report("No se encontraron los IDs de guía: ${describe(t)}")
        null
    }

    private fun setGuidance(ids: IntArray, distance: Int, turn: Int) {
        val sdk = sdk() ?: return
        try {
            val valueClass = Class.forName(EVENT_VALUE_CLASS)
            val value = valueClass.getConstructor().newInstance()
            valueClass.getField("intArrayValue").set(value, intArrayOf(distance, turn))
            val result = sdk.javaClass.getMethod("set", IntArray::class.java, valueClass).invoke(sdk, ids, value)
            report("set(guía distancia=$distance giro=$turn) -> $result")
        } catch (t: Throwable) {
            report("set(guía) falló: ${describe(t)}")
            endGuidance("set failed")
        }
    }

    // --- AutoNavi standard broadcast path (what BydClusterOutput sends to com.byd.amapservice) ---

    private var amapRunning = false
    private var amapTargets: List<String> = emptyList()

    /** Packages with a manifest receiver for the AutoNavi action, plus the known service names. */
    private fun findMapReceivers(): List<String> {
        val found = try {
            packageManager.queryBroadcastReceivers(Intent(AMAP_ACTION), 0)
                .map { "${it.activityInfo.packageName}/${it.activityInfo.name}" }
        } catch (t: Throwable) {
            report("Búsqueda de receptores falló: ${describe(t)}"); emptyList()
        }
        report("Receptores de $AMAP_ACTION: ${found.ifEmpty { listOf("ninguno declarado") }.joinToString()}")
        val packages = (found.map { it.substringBefore('/') } + AMAP_PACKAGES.filter { installed(it) }).distinct()
        report("Destinos de la prueba: ${packages.ifEmpty { listOf("ninguno") }.joinToString()}")
        return packages
    }

    private fun installed(pkg: String) = runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess

    private fun startAmapDemo(only: List<String>? = null) {
        if (amapRunning) { report("La prueba del servicio de mapas ya está corriendo."); return }
        amapTargets = only?.filter { installed(it) }?.also { report("Destino único: ${it.joinToString()}") } ?: findMapReceivers()
        if (amapTargets.isEmpty()) return
        amapRunning = true
        val started = System.currentTimeMillis()
        val tick = object : Runnable {
            override fun run() {
                if (!amapRunning) return
                val elapsed = System.currentTimeMillis() - started
                if (elapsed >= 15_000) { endAmap("demo complete"); return }
                // AutoNavi icons: 2 = turn left, 3 = turn right. Distances count down like a real route.
                val (icon, distance, road) = if (elapsed < 7_500)
                    Triple(2, 500 - (elapsed / 50).toInt(), "Calle Prueba DiAuto")
                else Triple(3, 800 - ((elapsed - 7_500) / 50).toInt(), "Avenida Prueba DiAuto")
                sendAmap(guidance = true, icon = icon, distance = distance, road = road, log = elapsed < 1_000 || (elapsed in 7_500L..8_499L))
                handler.postDelayed(this, 1_000)
            }
        }
        handler.post(tick)
    }

    /** Raw distance 0, bypassing the production clamp, to show what the HUD draws for it. */
    private fun zeroDistanceDemo() {
        if (amapRunning) { report("Ya hay una prueba de flecha corriendo."); return }
        amapTargets = listOf("com.example.amapservice").filter { installed(it) }
        if (amapTargets.isEmpty()) { report("com.example.amapservice no está instalado"); return }
        amapRunning = true
        val started = System.currentTimeMillis()
        val tick = object : Runnable {
            override fun run() {
                if (!amapRunning) return
                val elapsed = System.currentTimeMillis() - started
                if (elapsed >= 10_000) { endAmap("demo complete"); return }
                sendAmap(guidance = true, icon = 2, distance = 0, road = "Calle Prueba DiAuto", log = elapsed < 1_000)
                handler.postDelayed(this, 1_000)
            }
        }
        handler.post(tick)
    }

    /**
     * Steps through short distances, 6 s each, then the end-of-guidance broadcast, announcing each
     * stage on screen, to learn which ones the DiLink 3.0 HUD replaces with a Chinese caption.
     */
    private fun shortDistanceDemo() {
        if (amapRunning) { report("Ya hay una prueba de flecha corriendo."); return }
        amapTargets = listOf("com.example.amapservice").filter { installed(it) }
        if (amapTargets.isEmpty()) { report("com.example.amapservice no está instalado"); return }
        amapRunning = true
        val stages = listOf(1, 5, 10, 20)
        val started = System.currentTimeMillis()
        var lastStage = -1
        val tick = object : Runnable {
            override fun run() {
                if (!amapRunning) return
                val stage = ((System.currentTimeMillis() - started) / 6_000).toInt()
                if (stage >= stages.size) {
                    report("Etapa final: mensaje de fin de navegación (mirá el HUD 5 s)")
                    endAmap("short-distance demo")
                    return
                }
                if (stage != lastStage) {
                    lastStage = stage
                    report("Etapa ${stage + 1}: flecha derecha a ${stages[stage]} m (mirá el HUD)")
                }
                sendAmap(guidance = true, icon = 3, distance = stages[stage], road = "Calle Prueba DiAuto", log = false)
                handler.postDelayed(this, 1_000)
            }
        }
        handler.post(tick)
    }

    private fun endAmap(reason: String) {
        if (!amapRunning && reason != "manual") return
        amapRunning = false
        handler.removeCallbacksAndMessages(null)
        report("Limpiando flecha del servicio de mapas ($reason)")
        if (amapTargets.isEmpty()) amapTargets = findMapReceivers()
        sendAmap(guidance = false, icon = -1, distance = -1, road = "", log = true)
    }

    /** Same extras as BydClusterOutput: KEY_TYPE 10001 carries guidance, 10019 ends it. */
    private fun sendAmap(guidance: Boolean, icon: Int, distance: Int, road: String, log: Boolean) {
        for (pkg in amapTargets) {
            try {
                val intent = Intent(AMAP_ACTION).setPackage(pkg).addFlags(0x01000000)
                    .putExtra("IS_BYD_MAP", true).putExtra("IS_BYD_BAIDU_MAP", false)
                if (guidance) {
                    intent.putExtra("KEY_TYPE", 10001).putExtra("TYPE", 0).putExtra("EXTRA_STATE", 0)
                        .putExtra("EXTRA_IS_FOREGROUND", 0).putExtra("NEW_ICON", icon)
                        .putExtra("ROUNG_ABOUT_NUM", 0).putExtra("SEG_REMAIN_DIS", distance)
                        .putExtra("NEXT_ROAD_NAME", road).putExtra("ROUTE_REMAIN_DIS", distance + 2_000)
                        .putExtra("ROUTE_REMAIN_TIME", 300)
                } else {
                    intent.putExtra("KEY_TYPE", 10019).putExtra("EXTRA_STATE", 9).putExtra("EXTRA_IS_FOREGROUND", 1)
                        .putExtra("NEW_ICON", -1).putExtra("SEG_REMAIN_DIS", -1).putExtra("NEXT_ROAD_NAME", "")
                        .putExtra("ROUTE_REMAIN_DIS", -1).putExtra("ROUTE_REMAIN_TIME", -1)
                }
                sendBroadcast(intent)
                if (log) report("Broadcast a $pkg: ${if (guidance) "icono=$icon distancia=$distance" else "fin de navegación"}")
            } catch (t: Throwable) {
                report("Broadcast a $pkg falló: ${describe(t)}")
            }
        }
    }

    /**
     * Read-only: lists the components of the map services and the intent action / extra names
     * found in their code, to learn what the cluster listens for on this firmware. The receivers
     * there are registered at runtime, so the package manager alone cannot show them.
     */
    private fun inspectMapServices() {
        // Two shapes carry the protocol: dotted intent actions / class names from BYD packages, and
        // UPPER_SNAKE extra keys. Library resource names (Widget_AppCompat..., ActionBar_...) are noise.
        val dotted = Regex("^(com\\.byd|com\\.example|byd|autonavi|com\\.autonavi|com\\.kuwo|com\\.ximalaya)[A-Za-z0-9_.]{3,90}$", RegexOption.IGNORE_CASE)
        val upperKey = Regex("^[A-Z][A-Z0-9]*(_[A-Z0-9]+)+$")
        val keyHint = Regex("AUTONAVI|NAVI|GUIDE|KEY_TYPE|REMAIN|ROAD|CLUSTER|INSTRUMENT|METER|HUD|MUSIC|SONG|SINGER|ARTIST|ALBUM|LYRIC|TITLE|PLAY|MEDIA|CALL|PHONE|NUMBER|CONTACT")
        val noise = Regex("^(ACTION_(ARGUMENT|SCROLL|PAGE|MODE|STATE|POINTER|HOVER|CLICK|LONG|SELECT|SET|SHOW|HIDE|CLEAR|COPY|CUT|PASTE|DISMISS|EXPAND|COLLAPSE|FOCUS|ACCESSIBILITY|MOVE|NEXT|PREVIOUS|UNKNOWN|VIEW|CONTEXT|MASK|IME)|TYPE_|FLAG_|STATE_|MODE_)")
        // Media side too: the stock players (BYD media center, widgets, online radio/podcast apps)
        // are what put song names on the cluster, so their action and extra names are the lead.
        val mediaHints = listOf("ximalaya", "himalaya", "kuwo", "radio", "music", "media")
        val media = runCatching {
            packageManager.getInstalledPackages(0).map { it.packageName }
                .filter { name -> mediaHints.any { name.contains(it, ignoreCase = true) } }
                // Stock players only: third-party apps (Apple Music, radio apps) cannot feed the cluster.
                .filter { name -> name.startsWith("com.byd.") || listOf("ximalaya", "himalaya", "kuwo").any { name.contains(it, ignoreCase = true) } }
        }.getOrDefault(emptyList())
        report("Apps de música/radio encontradas: ${media.joinToString()}")
        // com.byd.automap is the full map app (very large and not the cluster receiver; 6a confirmed
        // com.example.amapservice draws the HUD), so it is left out to keep the scan quick.
        val targets = (AMAP_PACKAGES + MEDIA_PACKAGES + media).distinct()
            .filter { it != "com.byd.automap" && installed(it) }
        for (pkg in targets) {
            try {
                val flags = PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES or PackageManager.GET_PROVIDERS or PackageManager.GET_ACTIVITIES
                val info = packageManager.getPackageInfo(pkg, flags)
                val apk = info.applicationInfo?.sourceDir
                report("[$pkg] version=${info.versionName} apk=$apk")
                info.receivers.orEmpty().forEach { report("  receiver ${it.name} exported=${it.exported} perm=${it.permission}") }
                info.services.orEmpty().forEach { report("  service ${it.name} exported=${it.exported} perm=${it.permission}") }
                info.providers.orEmpty().forEach { report("  provider ${it.name} authority=${it.authority} exported=${it.exported}") }
                info.activities.orEmpty().take(10).forEach { report("  activity ${it.name} exported=${it.exported}") }
                val strings = sortedSetOf<String>()
                if (apk == null) { report("  sin ruta de APK"); continue }
                java.util.zip.ZipFile(apk).use { zip ->
                    zip.entries().toList().filter { it.name.endsWith(".dex") }.forEach { entry ->
                        val bytes = zip.getInputStream(entry).readBytes()
                        val current = StringBuilder()
                        for (b in bytes) {
                            val c = b.toInt() and 0xFF
                            if (c in 0x20..0x7E) current.append(c.toChar()) else {
                                val text = current.toString()
                                val keep = dotted.matches(text) ||
                                    (upperKey.matches(text) && keyHint.containsMatchIn(text) && !noise.containsMatchIn(text))
                                if (keep) strings += text
                                current.setLength(0)
                            }
                        }
                    }
                }
                report("  [$pkg] ${strings.size} cadenas relevantes en el código:")
                strings.take(400).chunked(8).forEach { report("    " + it.joinToString(" | ")) }
            } catch (t: Throwable) {
                report("[$pkg] no se pudo inspeccionar: ${describe(t)}")
            }
        }
        report("Inspección terminada.")
    }

    /**
     * Read-only. Finer than [inspectMapServices] for one package: method/field identifiers, content
     * URIs and the class descriptors it references (dex keeps those as Lcom/x/Y; with slashes), so we
     * can see whether the media center itself writes the cluster, and whether it exposes an API that
     * another app could feed. Nothing is called on the package.
     */
    private fun deepScan(pkg: String) {
        val apk = runCatching { packageManager.getPackageInfo(pkg, 0).applicationInfo?.sourceDir }.getOrNull()
        if (apk == null) { report("[$pkg] no instalado o sin APK"); return }
        val hint = Regex("longpress|longclick|voice|assistant|wakeup|speech|keycode|keyevent|instrument|meter|cluster|musicinfo|musicname|songname|singer|artist|title|setmusic|sendmusic|updatemusic|thirdparty|third_party|externalsource|mediasource|source|playinfo|nowplaying|metadata|bydauto|aidl|stub|provider|api", RegexOption.IGNORE_CASE)
        val ident = Regex("^[a-z][A-Za-z0-9_]{4,60}$")
        val descriptor = Regex("^\\[*L(com/byd|android/hardware/bydauto|com/example)[A-Za-z0-9_/\\$]{3,120};$")
        val actionLike = Regex("^[a-z][a-z0-9_]*(\\.[A-Za-z0-9_]+){2,}$")
        val actionHint = Regex("action|intent|key|voice|vr|assist|speech|long|press|button|wakeup|mic|siri|google|carplay|android_?auto|projection|link", RegexOption.IGNORE_CASE)
        val actions = sortedSetOf<String>()
        val idents = sortedSetOf<String>(); val uris = sortedSetOf<String>(); val classes = sortedSetOf<String>()
        try {
            java.util.zip.ZipFile(apk).use { zip ->
                zip.entries().toList().filter { it.name.endsWith(".dex") }.forEach { entry ->
                    val bytes = zip.getInputStream(entry).readBytes()
                    val current = StringBuilder()
                    for (b in bytes) {
                        val c = b.toInt() and 0xFF
                        if (c in 0x20..0x7E) { current.append(c.toChar()); continue }
                        val text = current.toString(); current.setLength(0)
                        // The ULEB128 length byte before a dex string is often printable; try both.
                        for (t in listOf(text, text.drop(1))) {
                            when {
                                t.startsWith("content://") -> uris += t.take(150)
                                actionLike.matches(t) && actionHint.containsMatchIn(t) && !t.startsWith("android.view") -> actions += t
                                descriptor.matches(t) && hint.containsMatchIn(t) -> classes += t
                                descriptor.matches(t) && t.contains("bydauto") -> classes += t
                                ident.matches(t) && hint.containsMatchIn(t) -> idents += t
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            report("[$pkg] lectura falló: ${describe(t)}"); return
        }
        report("[$pkg] profunda: ${classes.size} clases, ${uris.size} URIs, ${idents.size} identificadores")
        report("  Acciones/intents (${actions.size}):"); actions.take(300).chunked(3).forEach { report("    " + it.joinToString(" | ")) }
        report("  URIs:"); uris.take(60).chunked(4).forEach { report("    " + it.joinToString(" | ")) }
        report("  Clases:"); classes.take(300).chunked(4).forEach { report("    " + it.joinToString(" | ")) }
        report("  Identificadores:"); idents.take(500).chunked(8).forEach { report("    " + it.joinToString(" | ")) }
    }

    private fun describe(t: Throwable): String {
        val cause = (t as? InvocationTargetException)?.targetException ?: t
        return "${cause.javaClass.simpleName}: ${cause.message}"
    }

    private fun report(line: String) {
        AppLog.i("PanelTest: $line")
        runOnUiThread { output.append(line + "\n") }
    }

    private companion object {
        const val PERMISSION = "android.permission.BYDAUTO_INSTRUMENT_COMMON"
        const val DEVICE_CLASS = "android.hardware.bydauto.instrument.BYDAutoInstrumentDevice"
        const val FEATURE_IDS = "android.hardware.bydauto.BYDAutoFeatureIds"
        const val EVENT_VALUE_CLASS = "android.hardware.bydauto.BYDAutoEventValue"
        const val REQUEST_CODE = 4711
        const val AMAP_ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND"
        // com.byd.amapservice is what DiLink 5 uses; DiLink 3.0 ships com.example.amapservice.
        val AMAP_PACKAGES = listOf("com.example.amapservice", "com.byd.amapservice", "com.byd.automap")
        val MEDIA_PACKAGES = listOf("com.byd.mediacenter", "com.byd.widget.mediacenter", "com.byd.musicwidget", "com.byd.kuwowidget", "com.byd.bluetoothcall")
        // Same values BydFactoryNavigationOutput uses: 2 starts navigation state, 1 ends it.
        const val NAVI_STATUS_START = 2
        const val NAVI_STATUS_END = 1
    }
}
