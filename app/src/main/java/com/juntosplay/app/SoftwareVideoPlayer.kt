package com.juntosplay.app

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.SimpleBasePlayer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/** Mantiene los controles y la sincronización de Media3 mientras VLC decodifica el video por CPU. */
internal class SoftwareVideoPlayer(
    private val context: Context,
    layout: VLCVideoLayout,
    private val url: String,
    private val audioSpanish: Boolean,
    private val subtitlesSpanish: Boolean,
    startMs: Long
) : SimpleBasePlayer(Looper.getMainLooper()) {
    // Este motor se usa con videos que el chip no puede decodificar (H.264 de 10 bits, p. ej.).
    // En la Shield el procesador no alcanza para 4K, así que se le quita trabajo: se salta el
    // filtro de suavizado de bloques (casi no se nota en pantalla y ahorra mucho), se usan todos los
    // núcleos y se descartan a tiempo los cuadros atrasados en vez de quedarse parado y luego correr.
    private val lib = createLibVlc(context)
    private val vlc = MediaPlayer(lib)
    private val item = MediaItemData.Builder(url).setMediaItem(MediaItem.fromUri(url))
    private val commands = Player.Commands.Builder().addAll(
        COMMAND_PLAY_PAUSE, COMMAND_PREPARE, COMMAND_STOP, COMMAND_RELEASE,
        COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_TIMELINE,
        COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, COMMAND_SEEK_TO_DEFAULT_POSITION,
        COMMAND_SEEK_BACK, COMMAND_SEEK_FORWARD, COMMAND_SET_SPEED_AND_PITCH
    ).build()
    private var state = STATE_IDLE
    private var failure: PlaybackException? = null
    private var wantPlay = false
    private var pendingSeek: Long? = startMs.takeIf { it > 0 }
    private var rate = PlaybackParameters.DEFAULT
    private var released = false
    private var opened = false

    init {
        try {
            vlc.attachViews(layout, null, false, false)
            vlc.setEventListener { event ->
                if (released || !opened) return@setEventListener
                when (event.type) {
                    MediaPlayer.Event.Playing -> {
                        applyPendingSeek()
                        state = STATE_READY
                        if (!wantPlay) vlc.pause()
                    }
                    MediaPlayer.Event.Paused -> state = STATE_READY
                    MediaPlayer.Event.Buffering -> state =
                        if (event.buffering < 100f) STATE_BUFFERING else STATE_READY
                    MediaPlayer.Event.EndReached -> state = STATE_ENDED
                    MediaPlayer.Event.EncounteredError -> {
                        state = STATE_IDLE
                        opened = false
                        failure = PlaybackException(
                            "El motor de video por software no pudo reproducir esta pista.",
                            null, PlaybackException.ERROR_CODE_DECODING_FAILED
                        )
                    }
                    MediaPlayer.Event.SeekableChanged -> applyPendingSeek()
                    MediaPlayer.Event.LengthChanged -> Unit
                    else -> return@setEventListener
                }
                invalidateState()
            }
        } catch (error: Throwable) {
            vlc.release()
            lib.release()
            throw error
        }
    }

    fun updateDisplay() {
        val track = vlc.currentVideoTrack
        val aspect = if (track != null && track.height > 0 && track.sarDen > 0) {
            track.width.toFloat() * track.sarNum / (track.height * track.sarDen)
        } else 0f
        val mode = RoomSession.prefs(context).getInt(VideoDisplay.ASPECT, 0)
        vlc.aspectRatio = if (mode == 2 || (mode == 0 && aspect in 1.28f..1.39f)) "16:9" else null
    }

    fun videoDimensions(): Pair<Int, Int> =
        vlc.currentVideoTrack?.let { it.width to it.height } ?: (0 to 0)

    fun playbackTracks(type: Int): List<PlaybackTrack> {
        val audio = type == C.TRACK_TYPE_AUDIO
        val tracks = if (audio) vlc.audioTracks else vlc.spuTracks
        val selected = if (audio) vlc.audioTrack else vlc.spuTrack
        val available = tracks.orEmpty().filter { it.id >= 0 }
        if (available.isEmpty()) return emptyList()
        return buildList {
            if (!audio) add(PlaybackTrack("Desactivados", selected < 0) { vlc.setSpuTrack(-1) })
            available.forEach { track ->
                add(PlaybackTrack(track.name, track.id == selected) {
                    if (audio) vlc.setAudioTrack(track.id) else vlc.setSpuTrack(track.id)
                })
            }
        }
    }

    fun videoFps(): Float {
        val track = vlc.currentVideoTrack ?: return 0f
        return if (track.frameRateDen > 0) track.frameRateNum.toFloat() / track.frameRateDen else 0f
    }

    private fun applyPendingSeek() {
        val position = pendingSeek ?: return
        if (!vlc.isSeekable) return
        pendingSeek = null
        vlc.setTime(position)
    }

    override fun getState(): State {
        if (released) return State.Builder().build()
        val duration = vlc.length
        return State.Builder()
            .setAvailableCommands(commands)
            .setPlaylist(listOf(item.setDurationUs(if (duration > 0) duration * 1000 else C.TIME_UNSET)
                .setIsSeekable(vlc.isSeekable).build()))
            .setCurrentMediaItemIndex(0)
            .setPlaybackState(state)
            .setPlayerError(failure)
            .setPlayWhenReady(wantPlay, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
            .setPlaybackParameters(rate)
            .setContentPositionMs { if (released) 0L else pendingSeek ?: vlc.time.coerceAtLeast(0L) }
            .build()
    }

    override fun handlePrepare(): ListenableFuture<*> {
        failure = null
        state = STATE_BUFFERING
        if (!opened) {
            val media = Media(lib, Uri.parse(url))
            try {
                media.setHWDecoderEnabled(false, false)
                // Colchón según el búfer elegido en Configuración (medio = 8 s, como antes).
                media.addOption(":network-caching=" + PlaybackBuffer.current(context).vlcMs)
                media.addOption(":avcodec-skiploopfilter=4")
                media.addOption(":avcodec-hurry-up")
                if (audioSpanish) media.addOption(":audio-language=es,spa,en,eng")
                if (subtitlesSpanish) media.addOption(":sub-language=es,spa")
                vlc.media = media
            } finally {
                media.release()
            }
            opened = true
        }
        if (wantPlay) vlc.play()
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlayWhenReady(playWhenReady: Boolean): ListenableFuture<*> {
        wantPlay = playWhenReady
        if (opened) {
            if (wantPlay) vlc.play() else vlc.pause()
        }
        return Futures.immediateVoidFuture()
    }

    override fun handleSeek(mediaItemIndex: Int, positionMs: Long, seekCommand: Int): ListenableFuture<*> {
        val position = if (positionMs == C.TIME_UNSET) 0 else positionMs
        if (vlc.isSeekable) {
            pendingSeek = null
            vlc.setTime(position)
        } else pendingSeek = position
        return Futures.immediateVoidFuture()
    }

    override fun handleSetPlaybackParameters(playbackParameters: PlaybackParameters): ListenableFuture<*> {
        rate = playbackParameters
        vlc.rate = playbackParameters.speed
        return Futures.immediateVoidFuture()
    }

    override fun handleStop(): ListenableFuture<*> {
        opened = false
        state = STATE_IDLE
        vlc.stop()
        return Futures.immediateVoidFuture()
    }

    override fun handleRelease(): ListenableFuture<*> {
        released = true
        vlc.setEventListener(null)
        // Se para primero: así VLC cierra la conexión con el proveedor en ese momento.
        try { vlc.stop() } catch (_: RuntimeException) { }
        vlc.detachViews()
        vlc.release()
        lib.release()
        return Futures.immediateVoidFuture()
    }
}

/**
 * Crea VLC con las opciones de velocidad. Si esta versión de VLC no acepta alguna opción,
 * no arranca; entonces se prueba con menos opciones hasta llegar a las básicas.
 */
private fun createLibVlc(context: Context): LibVLC {
    val basic = listOf("--no-video-title-show")
    val fast = basic + listOf(
        "--avcodec-skiploopfilter=4",
        "--avcodec-fast",
        "--avcodec-threads=0",
        "--avcodec-hurry-up",
        "--drop-late-frames",
        "--skip-frames",
        "--network-caching=" + PlaybackBuffer.current(context).vlcMs,
        "--file-caching=" + PlaybackBuffer.current(context).vlcMs
    )
    // Extras que solo quitan trabajo que no se ve; algunas versiones de VLC no las conocen.
    val extra = fast + listOf("--no-stats", "--no-sub-autodetect-file")
    var lastError: Throwable? = null
    for (options in listOf(extra, fast, basic)) {
        try {
            return LibVLC(context, ArrayList(options))
        } catch (error: Throwable) {
            if (error is LinkageError) throw error
            lastError = error
        }
    }
    throw lastError ?: IllegalStateException("No se pudo iniciar VLC")
}
