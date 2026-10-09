package com.andrerinas.openheadunit.hud

import android.annotation.SuppressLint
import android.content.Context

/**
 * Debug-only. Runs under the head unit's own adb shell (via app_process), not inside DiAuto, to test
 * whether the instrument cluster's music card accepts a write from the shell user on DiLink 3.0.
 *
 * An ordinary app cannot write it: BYDAutoInstrumentDevice.getInstance checks BYDAUTO_INSTRUMENT_SET,
 * a signature permission the app does not hold (panel test button 3 got "permission deny"). The
 * question this answers is whether BYD's autoservice, which backs the device, accepts the adb shell
 * user instead, as it reportedly does on DiLink 4/5 for DiPlay.
 *
 * DiLink 3.0 exposes sendMusicName(String), sendMusicState(int) and sendMusicSource(int) on the
 * instrument device (confirmed in the read-only inventory). This calls them with a visible test
 * string and prints each result; 0 is success. It writes a song title only — no vehicle control.
 *
 * Args: the title text (one argument). Prints "name=…", "state=…", "source=…".
 */
object BydClusterSongProbeTool {
    private const val STATE_PLAYING = 1
    private const val SOURCE_OTHERS = 11

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            write(args.getOrNull(0) ?: "DiAuto prueba")
        } catch (error: Throwable) {
            println("probe=ERR ${error.javaClass.name}: ${error.message}")
        } finally {
            // ActivityThread leaves non-daemon threads behind; without this the shell never returns.
            System.exit(0)
        }
    }

    @SuppressLint("PrivateApi")
    private fun write(title: String) {
        runCatching { android.os.Looper.prepareMainLooper() }
        val thread = Class.forName("android.app.ActivityThread")
        val main = thread.getMethod("systemMain").invoke(null)
        val context = thread.getMethod("getSystemContext").invoke(main) as Context
        val deviceClass = Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice")
        // getInstance performs the caller-side permission check; if it refuses, build the device the
        // way it does, so the actual call still goes through autoservice as the shell user.
        val device = try {
            deviceClass.getMethod("getInstance", Context::class.java).invoke(null, context)
        } catch (_: java.lang.reflect.InvocationTargetException) {
            deviceClass.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context)
        }
        runCatching {
            val setSource = deviceClass.getMethod("sendMusicSource", Int::class.java)
            println("source=${setSource.invoke(device, SOURCE_OTHERS)}")
        }.onFailure { println("source=ERR ${it.cause?.javaClass?.simpleName ?: it.javaClass.simpleName}") }
        runCatching {
            val setState = deviceClass.getMethod("sendMusicState", Int::class.java)
            println("state=${setState.invoke(device, STATE_PLAYING)}")
        }.onFailure { println("state=ERR ${it.cause?.javaClass?.simpleName ?: it.javaClass.simpleName}") }
        runCatching {
            val setName = deviceClass.getMethod("sendMusicName", String::class.java)
            println("name=${setName.invoke(device, title)}")
        }.onFailure { println("name=ERR ${it.cause?.javaClass?.simpleName ?: it.javaClass.simpleName}") }
    }
}
