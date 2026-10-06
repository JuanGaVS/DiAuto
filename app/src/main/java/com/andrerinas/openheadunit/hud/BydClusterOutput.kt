package com.andrerinas.openheadunit.hud

import android.content.Context
import android.content.Intent

/**
 * AutoNavi standard broadcast to the stock map service that feeds the cluster/HUD.
 * DiLink 5 ships it as com.byd.amapservice; DiLink 3.0 (Android 10) ships the same role as
 * com.example.amapservice. On a DiLink 3.0 unit the same extras sent to it (together with
 * com.byd.automap) drew the turn arrow and countdown on the HUD.
 */
internal class BydClusterOutput(private val context: Context, private val targetPackage: String = PACKAGES.first()) {
    companion object {
        val PACKAGES = listOf("com.byd.amapservice", "com.example.amapservice")
    }

    private var showing = false
    fun update(frame: BydGuidance?) {
        if (frame == null && !showing) return
        val intent = Intent("AUTONAVI_STANDARD_BROADCAST_SEND").setPackage(targetPackage)
            .addFlags(0x01000000)
            .putExtra("IS_BYD_MAP", true).putExtra("IS_BYD_BAIDU_MAP", false)
        if (frame != null) {
            intent.putExtra("KEY_TYPE", 10001).putExtra("TYPE", 0).putExtra("EXTRA_STATE", 0)
                .putExtra("EXTRA_IS_FOREGROUND", 0).putExtra("NEW_ICON", frame.clusterIcon)
                .putExtra("ROUNG_ABOUT_NUM", frame.roundaboutExit).putExtra("SEG_REMAIN_DIS", frame.distanceMeters)
                .putExtra("NEXT_ROAD_NAME", frame.road).putExtra("ROUTE_REMAIN_DIS", frame.remainingMeters)
                .putExtra("ROUTE_REMAIN_TIME", frame.remainingSeconds)
        } else {
            intent.putExtra("KEY_TYPE", 10019).putExtra("EXTRA_STATE", 9).putExtra("EXTRA_IS_FOREGROUND", 1)
                .putExtra("NEW_ICON", -1).putExtra("SEG_REMAIN_DIS", -1).putExtra("NEXT_ROAD_NAME", "")
                .putExtra("ROUTE_REMAIN_DIS", -1).putExtra("ROUTE_REMAIN_TIME", -1)
        }
        context.sendBroadcast(intent)
        showing = frame != null
    }
}
