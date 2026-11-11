package com.cim.securecall.call

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsReport
import org.webrtc.RtpParameters
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Call playback; other apps, including system screen recorders, may not capture it. */
fun callAudioAttributes(): AudioAttributes = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
    .apply { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) setAllowedCapturePolicy(AudioAttributes.ALLOW_CAPTURE_BY_NONE) }
    .build()

/** How much video we send: [maxBitrateBps] caps the encoder, the capture size keeps the camera from doing extra work. */
enum class VideoQuality(val maxBitrateBps: Int, val width: Int, val height: Int) {
    High(1_500_000, 1280, 720),
    Saver(200_000, 640, 360),  // about 256 kbps with the audio
}

/**
 * One WebRTC peer connection at a time (DTLS-SRTP): Opus audio and, in every call, a video track that only
 * carries pictures while the camera is on. Turning the camera on or off mid-call needs no renegotiation.
 */
class CallEngine(private val context: Context, private val scope: CoroutineScope) {

    interface Listener {
        fun onIceCandidate(candidate: IceCandidate)
        fun onConnected()
        fun onDisconnected()  // media interrupted; WebRTC may still recover on its own
        fun onFailed()
    }

    /** Shared by the encoder, the decoder and the renderers on the call screen. */
    val egl: EglBase by lazy { EglBase.create() }

    private val factory: PeerConnectionFactory by lazy {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext).createInitializationOptions()
        )
        val adm = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setAudioAttributes(callAudioAttributes())
            .createAudioDeviceModule()
        PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
    }

    private var pc: PeerConnection? = null
    private var source: AudioSource? = null
    private var track: AudioTrack? = null
    private val pendingRemote = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false

    // Video. The camera is opened and closed on its own thread: stopping it blocks.
    private val cameraThread = Executors.newSingleThreadExecutor()
    private var videoSource: VideoSource? = null
    private var videoSender: RtpSender? = null
    private var capturer: CameraVideoCapturer? = null
    private var textureHelper: SurfaceTextureHelper? = null
    private var capturing: VideoQuality? = null
    private var cameraOn = false
    private var held = false
    private var maxBitrateBps = VideoQuality.High.maxBitrateBps
    private val _localVideo = MutableStateFlow<VideoTrack?>(null)
    /** Our camera, for the preview. Null when the camera is off. */
    val localVideo: StateFlow<VideoTrack?> = _localVideo
    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    /** The other side's video track (pictures only while their camera is on). */
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo
    private val _frontCamera = MutableStateFlow(true)
    val frontCamera: StateFlow<Boolean> = _frontCamera
    private var localTrack: VideoTrack? = null

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
        // addTrack (not addTransceiver): the callee's track then takes the video section of the caller's offer.
        // An offer without video (older app) leaves it unused, and the call is audio only.
        val vsrc = factory.createVideoSource(false)
        val vtrk = factory.createVideoTrack("video0", vsrc).apply { setEnabled(false) }
        videoSender = conn.addTrack(vtrk, listOf("stream0"))
        pc = conn
        source = src
        track = trk
        videoSource = vsrc
        localTrack = vtrk
        applyBitrate()
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
        publishRemoteVideo()
        return answer.description
    }

    /** New offer that gathers fresh network paths (after a network change). */
    suspend fun restartOffer(): String {
        val conn = pc ?: error("no connection")
        conn.restartIce()
        return createOffer()
    }

    /** Round-trip time of the media path in milliseconds, null before it is known. */
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

    /** The video we send, for the log of test builds: encoder target, size, frame rate and what limits it. */
    suspend fun videoOutStats(): String? {
        val conn = pc ?: return null
        val report = suspendCancellableCoroutine<RTCStatsReport> { cont -> conn.getStats { cont.resume(it) } }
        val out = report.statsMap.values.firstOrNull { it.type == "outbound-rtp" && it.members["kind"] == "video" } ?: return null
        val m = out.members
        return "target=${(m["targetBitrate"] as? Number)?.toInt()?.div(1000)}kbps " +
            "size=${m["frameWidth"]}x${m["frameHeight"]} fps=${m["framesPerSecond"]} sent=${(m["bytesSent"] as? Number)?.toLong()?.div(1000)}KB " +
            "limit=${m["qualityLimitationReason"]} cap=${maxBitrateBps / 1000}kbps"
    }

    suspend fun setAnswer(answerSdp: String) {
        val conn = pc ?: error("no connection")
        conn.setDescription(local = false, SessionDescription(SessionDescription.Type.ANSWER, answerSdp))
        remoteSet()
        publishRemoteVideo()
    }

    /** Candidates can arrive before the remote description: queue them until then. */
    fun addRemoteCandidate(candidate: IceCandidate) {
        val conn = pc ?: return
        if (remoteDescriptionSet) conn.addIceCandidate(candidate) else pendingRemote += candidate
    }

    fun setMuted(muted: Boolean) {
        track?.setEnabled(!muted)
    }

    /** On hold: no microphone, no camera and nothing from the other side. [muted] is the user's own mute. */
    fun setOnHold(held: Boolean, muted: Boolean) {
        this.held = held
        track?.setEnabled(!held && !muted)
        localTrack?.setEnabled(cameraOn && !held)
        pc?.receivers?.forEach { it.track()?.setEnabled(!held) }
    }

    /** Opens or closes the camera. [quality] sets the capture size; the bitrate is [setMaxBitrate]'s. */
    fun setCamera(on: Boolean, quality: VideoQuality) {
        cameraOn = on
        val trk = localTrack ?: return
        val src = videoSource ?: return
        trk.setEnabled(on && !held)
        _localVideo.value = if (on) trk else null
        cameraThread.execute {
            try {
                if (!on) {
                    stopCapture()
                    return@execute
                }
                if (capturer == null) {
                    val names = Camera2Enumerator(context)
                    val name = names.deviceNames.firstOrNull { names.isFrontFacing(it) == _frontCamera.value }
                        ?: names.deviceNames.firstOrNull() ?: return@execute
                    _frontCamera.value = names.isFrontFacing(name)
                    val helper = SurfaceTextureHelper.create("camera", egl.eglBaseContext)
                    val cap = names.createCapturer(name, null)
                    cap.initialize(helper, context.applicationContext, src.capturerObserver)
                    textureHelper = helper
                    capturer = cap
                }
                if (capturing != quality) {
                    if (capturing != null) capturer?.changeCaptureFormat(quality.width, quality.height, 30)
                    else capturer?.startCapture(quality.width, quality.height, 30)
                    capturing = quality
                }
            } catch (e: Exception) {
                Log.w(TAG, "camera", e)
            }
        }
    }

    fun switchCamera() {
        val cap = capturer ?: return
        cap.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFront: Boolean) { _frontCamera.value = isFront }
            override fun onCameraSwitchError(error: String?) { Log.w(TAG, "switch camera: $error") }
        })
    }

    /** Upper limit for the video we send: our quality setting, or less if the other side asked for less. */
    fun setMaxBitrate(bps: Int) {
        maxBitrateBps = bps
        applyBitrate()
    }

    private fun applyBitrate() {
        val sender = videoSender ?: return
        runCatching {
            val p = sender.parameters
            p.degradationPreference = RtpParameters.DegradationPreference.BALANCED
            p.encodings.forEach { it.maxBitrateBps = maxBitrateBps; it.maxFramerate = 30 }
            sender.parameters = p
        }.onFailure { Log.w(TAG, "bitrate", it) }
    }

    private fun stopCapture() {
        runCatching { capturer?.stopCapture() }
        capturer?.dispose()
        textureHelper?.dispose()
        capturer = null
        textureHelper = null
        capturing = null
    }

    fun close() {
        cameraOn = false
        held = false
        _localVideo.value = null
        _remoteVideo.value = null
        // The camera thread closes the camera first; the tracks and sources go after it, in order.
        val vsrc = videoSource
        val vtrk = localTrack
        val conn = pc
        val trk = track
        val src = source
        pc = null
        track = null
        source = null
        videoSource = null
        videoSender = null
        localTrack = null
        pendingRemote.clear()
        remoteDescriptionSet = false
        cameraThread.execute {
            stopCapture()
            conn?.dispose()
            trk?.dispose()
            src?.dispose()
            vtrk?.dispose()
            vsrc?.dispose()
        }
    }

    private fun remoteSet() {
        remoteDescriptionSet = true
        pendingRemote.forEach { pc?.addIceCandidate(it) }
        pendingRemote.clear()
        applyBitrate()  // the sender's encodings are only final once negotiated
    }

    private fun publishRemoteVideo() {
        _remoteVideo.value = pc?.transceivers
            ?.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO && it.mid != null }
            ?.receiver?.track() as? VideoTrack
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

        override fun onTrack(transceiver: RtpTransceiver) {
            if (transceiver.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) scope.launch(Dispatchers.Main.immediate) { publishRemoteVideo() }
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

    private companion object {
        const val TAG = "CallEngine"
    }
}

// Unified Plan: the tracks added in open() decide what is offered and answered, not constraints.
private val MEDIA = MediaConstraints()

private suspend fun PeerConnection.create(offer: Boolean): SessionDescription = suspendCancellableCoroutine { cont ->
    val observer = object : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) = cont.resume(desc)
        override fun onCreateFailure(error: String?) = cont.resumeWithException(IllegalStateException(error))
        override fun onSetSuccess() {}
        override fun onSetFailure(error: String?) {}
    }
    if (offer) createOffer(observer, MEDIA) else createAnswer(observer, MEDIA)
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
