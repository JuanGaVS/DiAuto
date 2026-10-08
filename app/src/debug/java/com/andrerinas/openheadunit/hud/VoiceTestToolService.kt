package com.andrerinas.openheadunit.hud

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import com.andrerinas.openheadunit.utils.AppLog

/**
 * Debug-only. Impersonates the BYD factory "test tool" so we can see whether com.byd.autovoice's
 * automated-test framework will bind back to an ordinary app. The real flow is: the test tool
 * broadcasts com.byd.AUTOMATED_TEST_TASKS (extra AUTOMATED_TEST_KEY = 0, "bind service"); the
 * assistant then binds to the test tool's service and calls it over AIDL to pull the list of
 * phrases to recognise, running each through its NLU as if spoken.
 *
 * This service only observes: on bind it logs the request, and every AIDL transaction the assistant
 * makes is logged with its code and payload. It replies with a one-item task list holding a single
 * harmless Mandarin information query ("what time is it"). It never sends a vehicle-action command.
 * If the assistant never binds here, the automated-test path is not reachable from an ordinary app
 * and the experiment stops.
 */
class VoiceTestToolService : Service() {

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val token = runCatching { data.readString() }.getOrNull()
            // Peek at a few more values without assuming the layout; failures are expected and ignored.
            val peeked = buildString {
                repeat(4) {
                    val before = data.dataPosition()
                    val s = runCatching { data.readString() }.getOrNull()
                    if (s != null && s.isNotEmpty()) append("[$s]") else { data.setDataPosition(before); return@repeat }
                }
            }
            AppLog.i("PanelTest: TestTool onTransact code=$code flags=$flags token=$token extra=$peeked")
            // Answer anything that looks like a request with a one-task JSON carrying the safe phrase.
            runCatching {
                reply?.writeNoException()
                reply?.writeString(TASKS_JSON)
            }
            return true
        }

        override fun getInterfaceDescriptor(): String = AIDL_DESCRIPTOR
    }

    override fun onBind(intent: Intent?): IBinder {
        AppLog.i("PanelTest: TestTool onBind action=${intent?.action} extras=${intent?.extras?.keySet()?.joinToString()}")
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AppLog.i("PanelTest: TestTool onUnbind action=${intent?.action}")
        return false
    }

    companion object {
        const val AIDL_DESCRIPTOR = "com.byd.autovoice.automata.AutomatedTestAidlInterface"
        // One task: service/operation left generic, the text is the harmless Mandarin "what time is it".
        const val TASKS_JSON = "[{\"taskId\":\"1\",\"cid\":\"0\",\"text\":\"现在几点\",\"mode\":0}]"
    }
}
