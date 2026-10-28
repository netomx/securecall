package com.cim.securecall.call

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Call playback; other apps, including system screen recorders, may not capture it. */
fun callAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE) }
    .build()

/** One audio-only WebRTC peer connection at a time (DTLS-SRTP, Opus). */
class CallEngine(private val context: Context, private val scope: CoroutineScope) {

    interface Listener {
        fun onIceCandidate(candidate: IceCandidate)
        fun onConnected()
        fun onDisconnected()  // audio interrupted; WebRTC may still recover on its own
        fun onFailed()
    }

    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
        )
        val adm = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setAudioAttributes(callAudioAttributes())
            .createAudioDeviceModule()
        PeerConnectionFactory.builder().setAudioDeviceModule(adm).createPeerConnectionFactory()
    }

    private var pc: PeerConnection? = null
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private val pendingRemote = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false

    fun open(iceServers: List<PeerConnection.IceServer>, listener: Listener) {
        close()
        val config = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val conn = factory.createPeerConnection(config, Observer(listener)) ?: error("createPeerConnection failed")
        val src = factory.createAudioSource(MediaConstraints())
        val trk = factory.createAudioTrack("audio0", src)
        conn.addTrack(trk, listOf("stream0"))
        pc = conn
        source = src
        track = trk
    }

    suspend fun createOffer(): String {
        val conn = pc ?: error("no connection")
        val offer = conn.create(offer = true)
        conn.setDescription(local = true, offer)
        return offer.description
    }

    suspend fun acceptOffer(offerSdp: String): String {
        val conn = pc ?: error("no connection")
        conn.setDescription(local = false, SessionDescription(SessionDescription.Type.OFFER, offerSdp))
        remoteSet()
        val answer = conn.create(offer = false)
        conn.setDescription(local = true, answer)
        return answer.description
    }

    /** New offer that gathers fresh network paths (after a network change). */
    suspend fun restartOffer(): String {
        val conn = pc ?: error("no connection")
        conn.restartIce()
        return createOffer()
    }

    /** Round-trip time of the audio path in milliseconds, null before it is known. */
    suspend fun rttMs(): Int? {
        val conn = pc ?: return null
        val report = suspendCancellableCoroutine<RTCStatsReport> { cont -> conn.getStats { cont.resume(it) } }
        val stats = report.statsMap.values
        val pairId = stats.firstOrNull { it.type == "transport" }?.members?.get("selectedCandidatePairId") as? String
        val pair = stats.firstOrNull { it.id == pairId }
            ?: stats.firstOrNull { it.type == "candidate-pair" && it.members["nominated"] == true && it.members["state"] == "succeeded" }
        val rtt = pair?.members?.get("currentRoundTripTime") as? Double ?: return null
        return (rtt * 1000).toInt()
    }

    suspend fun setAnswer(answerSdp: String) {
        val conn = pc ?: error("no connection")
        conn.setDescription(local = false, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
        remoteSet()
    }

    /** Candidates can arrive before the remote description: queue them until then. */
    fun addRemoteCandidate(candidate: IceCandidate) {
        val conn = pc ?: return
        if (remoteDescriptionSet) conn.addIceCandidate(candidate) else pendingRemote += candidate
    }

    fun setMuted(muted: Boolean) {
        track?.setEnabled(!muted)
    }

    fun close() {
        pc?.dispose()
        track?.dispose()
        source?.dispose()
        pc = null
        track = null
        source = null
        pendingRemote.clear()
        remoteDescriptionSet = false
    }

    private fun remoteSet() {
        remoteDescriptionSet = true
        pendingRemote.forEach { pc?.addIceCandidate(it) }
        pendingRemote.clear()
    }

    private inner class Observer(private val listener: Listener) : PeerConnection.Observer {
        override fun onIceCandidate(candidate: IceCandidate) {
            scope.launch { listener.onIceCandidate(candidate) }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            scope.launch {
                when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> listener.onConnected()
                    PeerConnection.PeerConnectionState.DISCONNECTED -> listener.onDisconnected()
                    PeerConnection.PeerConnectionState.FAILED -> listener.onFailed()
                    else -> Unit
                }
            }
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(channel: DataChannel?) {}
        override fun onRenegotiationNeeded() {}
    }
}

private val AUDIO_ONLY = MediaConstraints().apply {
    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
    mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
}

private suspend fun PeerConnection.create(offer: Boolean): SessionDescription = suspendCancellableCoroutine { cont ->
    val observer = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = cont.resume(desc)
        override fun onCreateFailure(error: String?) = cont.resumeWithException(IllegalStateException(error))
        override fun onSetSuccess() {}
        override fun onSetFailure(error: String?) {}
    }
    if (offer) createOffer(observer, AUDIO_ONLY) else createAnswer(observer, AUDIO_ONLY)
}

private suspend fun PeerConnection.setDescription(local: Boolean, desc: SessionDescription): Unit =
    suspendCancellableCoroutine { cont ->
        val observer = object : SdpObserver {
            override fun onCreateSuccess(desc: SessionDescription?) {}
            override fun onCreateFailure(error: String?) {}
            override fun onSetSuccess() = cont.resume(Unit)
            override fun onSetFailure(error: String?) = cont.resumeWithException(IllegalStateException(error))
        }
        if (local) setLocalDescription(observer, desc) else setRemoteDescription(observer, desc)
    }
