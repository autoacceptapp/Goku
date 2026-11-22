package com.arif.goku.calls

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager

/**
 * Phase 6 BroadcastReceiver monitoring `ACTION_PHONE_STATE_CHANGED` when the user keeps their
 * stock phone dialer (`TelecomManager.acceptRingingCall()` fallback mode).
 */
class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return

        val stateStr = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        @Suppress("DEPRECATION")
        val incomingNumber = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()

        when (stateStr) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                SmartCallBridge.emitSignal(
                    type = CallSignalType.RINGING,
                    phoneNumber = incomingNumber,
                    source = "PHONE_STATE"
                )
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                SmartCallBridge.emitSignal(
                    type = CallSignalType.OFFHOOK,
                    phoneNumber = incomingNumber,
                    source = "PHONE_STATE"
                )
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                SmartCallBridge.emitSignal(
                    type = CallSignalType.IDLE,
                    phoneNumber = incomingNumber,
                    source = "PHONE_STATE"
                )
            }
        }
    }
}
