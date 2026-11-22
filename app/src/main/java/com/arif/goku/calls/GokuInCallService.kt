package com.arif.goku.calls

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference

enum class CallSignalType {
    RINGING,
    OFFHOOK,
    IDLE
}

data class IncomingCallSignal(
    val type: CallSignalType,
    val phoneNumber: String = "",
    val source: String = "PHONE_STATE",
    val timestampMs: Long = System.currentTimeMillis()
)

/**
 * Reactive bridge connecting both `GokuInCallService` (Default Dialer mode) and
 * `CallReceiver` (Stock Dialer `PHONE_STATE` fallback) into `SmartCallManager`.
 */
object SmartCallBridge {
    private val _isInCallServiceBound = MutableStateFlow(false)
    val isInCallServiceBound: StateFlow<Boolean> = _isInCallServiceBound.asStateFlow()

    private val _signals = MutableSharedFlow<IncomingCallSignal>(extraBufferCapacity = 16)
    val signals: SharedFlow<IncomingCallSignal> = _signals.asSharedFlow()

    internal fun setInCallBound(bound: Boolean) {
        _isInCallServiceBound.value = bound
    }

    fun emitSignal(type: CallSignalType, phoneNumber: String = "", source: String = "PHONE_STATE") {
        _signals.tryEmit(
            IncomingCallSignal(
                type = type,
                phoneNumber = phoneNumber.trim(),
                source = source
            )
        )
    }
}

/**
 * Phase 6 Default Dialer `InCallService` (`RoleManager.ROLE_DIALER`).
 *
 * When the user grants GokuAgent the Default Phone App role, Android binds this service on
 * every call, enabling direct `call.answer(...)`, `setAudioRoute(CallAudioState.ROUTE_SPEAKER)`,
 * and `call.disconnect()` without relying on fallback APIs.
 */
class GokuInCallService : InCallService() {

    private var activeCall: Call? = null

    private val callCallback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            val number = call.details?.handle?.schemeSpecificPart.orEmpty()
            when (state) {
                Call.STATE_RINGING -> {
                    SmartCallBridge.emitSignal(CallSignalType.RINGING, number, source = "INCALL_SERVICE")
                }
                Call.STATE_ACTIVE -> {
                    SmartCallBridge.emitSignal(CallSignalType.OFFHOOK, number, source = "INCALL_SERVICE")
                }
                Call.STATE_DISCONNECTED -> {
                    SmartCallBridge.emitSignal(CallSignalType.IDLE, number, source = "INCALL_SERVICE")
                    if (activeCall == call) {
                        activeCall = null
                    }
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceRef = WeakReference(this)
        SmartCallBridge.setInCallBound(true)
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        activeCall?.unregisterCallback(callCallback)
        activeCall = call
        call.registerCallback(callCallback)

        val number = call.details?.handle?.schemeSpecificPart.orEmpty()
        @Suppress("DEPRECATION")
        val state = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            call.details?.state ?: Call.STATE_NEW
        } else {
            call.state
        }
        if (state == Call.STATE_RINGING) {
            SmartCallBridge.emitSignal(CallSignalType.RINGING, number, source = "INCALL_SERVICE")
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        call.unregisterCallback(callCallback)
        if (activeCall == call) {
            val number = call.details?.handle?.schemeSpecificPart.orEmpty()
            activeCall = null
            SmartCallBridge.emitSignal(CallSignalType.IDLE, number, source = "INCALL_SERVICE")
        }
    }

    override fun onDestroy() {
        activeCall?.unregisterCallback(callCallback)
        activeCall = null
        serviceRef = WeakReference(null)
        SmartCallBridge.setInCallBound(false)
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var serviceRef: WeakReference<GokuInCallService> = WeakReference(null)

        fun isAvailable(): Boolean = serviceRef.get() != null

        fun answerRingingCall(): Boolean {
            val service = serviceRef.get() ?: return false
            val call = service.activeCall ?: return false
            return try {
                call.answer(VideoProfile.STATE_AUDIO_ONLY)
                true
            } catch (_: Exception) {
                false
            }
        }

        fun routeAudioToSpeaker(): Boolean {
            val service = serviceRef.get() ?: return false
            return try {
                @Suppress("DEPRECATION")
                service.setAudioRoute(CallAudioState.ROUTE_SPEAKER)
                true
            } catch (_: Exception) {
                false
            }
        }

        fun disconnectActiveCall(): Boolean {
            val service = serviceRef.get() ?: return false
            val call = service.activeCall ?: return false
            return try {
                call.disconnect()
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}
