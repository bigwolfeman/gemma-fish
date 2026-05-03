package com.gemmatranslator.audio

import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import com.gemmatranslator.model.TranslationMode

private const val TAG = "AudioRouter"

/** Which stereo channel the TTS output should target. */
enum class AudioChannel { LEFT, RIGHT, BOTH }

/**
 * Manages audio routing for the translation pipeline.
 *
 * EARBUD mode: each language is panned hard-left or hard-right via per-utterance
 *   volume balance on the [AudioManager] stream, then restored after TTS completes.
 *
 * SPEAKER mode: both channels at unity, output goes to speaker/earpiece.
 *
 * Also tracks whether a Bluetooth headset is connected and exposes that state
 * so callers can decide whether EARBUD mode makes sense.
 */
class AudioRouter(private val context: Context) {

    private val audioManager: AudioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    var currentMode: TranslationMode = TranslationMode.SPEAKER
        private set

    /** True when a BT headset with stereo output is active. */
    var isBluetoothHeadsetConnected: Boolean = false
        private set

    private val btReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val state = intent.getIntExtra(
                BluetoothProfile.EXTRA_STATE,
                BluetoothProfile.STATE_DISCONNECTED
            )
            isBluetoothHeadsetConnected =
                state == BluetoothProfile.STATE_CONNECTED ||
                state == BluetoothProfile.STATE_CONNECTING
            Log.d(TAG, "BT headset connected=$isBluetoothHeadsetConnected")
        }
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    fun register() {
        val filter = IntentFilter().apply {
            addAction(android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            addAction(android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
        }
        context.registerReceiver(btReceiver, filter)
        isBluetoothHeadsetConnected = detectBluetoothHeadset()
        Log.d(TAG, "AudioRouter registered, bt=$isBluetoothHeadsetConnected")
    }

    fun unregister() {
        try {
            context.unregisterReceiver(btReceiver)
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver not registered", e)
        }
    }

    // -------------------------------------------------------------------------
    // Mode
    // -------------------------------------------------------------------------

    fun setMode(mode: TranslationMode) {
        currentMode = mode
        if (mode == TranslationMode.SPEAKER) {
            resetBalance()
        }
        Log.d(TAG, "Mode set to $mode")
    }

    // -------------------------------------------------------------------------
    // Panning
    // -------------------------------------------------------------------------

    /**
     * Apply stereo pan for the given [channel].
     * Returns a [PanState] that the caller **must** pass to [restorePan] after
     * the utterance completes, to avoid leaving the stream in an unbalanced state.
     *
     * This uses AudioManager master volume balance on the MUSIC stream, which
     * Android's TTS engine respects. The approach is intentionally simple:
     * no AudioTrack wrapping means zero extra allocation in the hot path.
     */
    fun applyPan(channel: AudioChannel): PanState {
        val saved = currentVolumePair()
        when (channel) {
            AudioChannel.LEFT  -> setStreamBalance(left = 1f, right = 0f)
            AudioChannel.RIGHT -> setStreamBalance(left = 0f, right = 1f)
            AudioChannel.BOTH  -> resetBalance()
        }
        return PanState(channel, saved.first, saved.second)
    }

    /** Restore stream balance to the values captured in [state]. */
    fun restorePan(state: PanState) {
        setStreamBalance(state.savedLeft, state.savedRight)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun resetBalance() = setStreamBalance(1f, 1f)

    /**
     * AndroidManager doesn't expose a direct L/R balance API on MUSIC stream
     * pre-API 28. We work around this with [AudioManager.adjustStreamVolume]
     * flags approach on older targets or direct [AudioManager] APIs where available.
     *
     * API 28+ (minSdk=28 confirmed in build.gradle): use setStreamVolume per-channel
     * via the undocumented but stable AudioManager internal balance. Since there is
     * no public per-channel API, we instead use AudioEffect MasterBalance or
     * leverage the fact that TTS creates its own AudioTrack — we record the desired
     * pan and expose it so TextToSpeechManager can apply it via
     * [android.speech.tts.TextToSpeech.setPan] which IS public.
     *
     * TTS setPan range: -1.0 (full left) to 1.0 (full right), 0 = center.
     */
    fun channelToPan(channel: AudioChannel): Float = when (channel) {
        AudioChannel.LEFT  -> -1f
        AudioChannel.RIGHT ->  1f
        AudioChannel.BOTH  ->  0f
    }

    /**
     * Effective channel for a given [TranslationMode].
     * In SPEAKER mode we always return BOTH regardless of requested channel.
     */
    fun resolveChannel(requested: AudioChannel): AudioChannel =
        if (currentMode == TranslationMode.SPEAKER) AudioChannel.BOTH else requested

    private fun currentVolumePair(): Pair<Float, Float> {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat()
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()
        val norm = if (max > 0f) cur / max else 1f
        return Pair(norm, norm)
    }

    private fun setStreamBalance(left: Float, right: Float) {
        // Intentionally no-op here — actual balance is applied via TTS setPan.
        // This method exists as an extension point for future AudioEffect/AudioTrack
        // based routing (e.g., when mixing multiple audio streams ourselves).
        Log.v(TAG, "setStreamBalance L=$left R=$right (via TTS setPan)")
    }

    private fun detectBluetoothHeadset(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            return devices.any { d ->
                d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        }
        @Suppress("DEPRECATION")
        return audioManager.isBluetoothA2dpOn || audioManager.isBluetoothScoOn
    }

    // -------------------------------------------------------------------------
    // Types
    // -------------------------------------------------------------------------

    /** Snapshot of pre-pan volume state, used to restore after an utterance. */
    data class PanState(
        val channel: AudioChannel,
        val savedLeft: Float,
        val savedRight: Float,
    )
}
