package com.andrerinas.openheadunit.hud

import android.app.Activity
import android.content.Context
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
        output = TextView(this).apply { textSize = 16f; setTextIsSelectable(true) }
        root.addView(output)
        setContentView(ScrollView(this).apply {
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        report("Paquete ${packageName}, Android ${Build.VERSION.RELEASE}, permiso=${hasPermission()}")
    }

    override fun onDestroy() {
        if (guidanceRunning) endGuidance("activity closed")
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
        // Same values BydFactoryNavigationOutput uses: 2 starts navigation state, 1 ends it.
        const val NAVI_STATUS_START = 2
        const val NAVI_STATUS_END = 1
    }
}
