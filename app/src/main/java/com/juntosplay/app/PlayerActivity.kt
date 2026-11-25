package com.juntosplay.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import org.videolan.libvlc.util.VLCVideoLayout
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Reproductor sincronizado. Abre la URL del relay (127.0.0.1 en el anfitrión,
 * la IP de Tailscale o de la red de casa en el otro televisor) y mantiene
 * play/pausa/seek a la par mediante los mensajes "playback" de la sala.
 */
class PlayerActivity : Activity() {
    /** Tamaño del texto elegido en Color del tema. */
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(Ui.scaledContext(newBase))
    }

    private var player: ExoPlayer? = null
    private var softwarePlayer: SoftwareVideoPlayer? = null
    private lateinit var softwareSurface: VLCVideoLayout
    private fun activePlayer(): Player? = softwarePlayer ?: player
    private val backgroundPlayback by lazy {
        if (Ui.isTelevision(this)) null else BackgroundPlayback(this) { intent.getStringExtra(EXTRA_TITLE).orEmpty() }
    }
    private lateinit var playerView: PlayerView
    private lateinit var controls: PlaybackPanel
    /** Episodios y Enlaces: panel a la derecha. */
    private lateinit var sidePanel: PlayerSidePanel
    private var remux = false
    private var droppedFrames = 0
    private var rebufferCount = 0
    private var startedVideo = false
    private var decoderName = ""
    private lateinit var localFallbackButton: TextView
    private lateinit var otherLinksButton: TextView
    private lateinit var serversButton: TextView
    private lateinit var errorBox: LinearLayout
    private lateinit var errorText: TextView
    private var isHost = false
    private var solo = false
    private var useHlsForVod = false
    private var manualPrompt: android.app.AlertDialog? = null
    private var pendingManualUrl: String? = null
    private var acceptedManualUrl: String? = null
    private var advancing = false
    private val prefs by lazy { RoomSession.prefs(this) }
    private val roomPlayback: (JSONObject) -> Unit = { msg -> if (!solo) onRemote(msg) }
    private var kind = "vod"
    private var url = ""
    /** Dirección tal como llegó del otro equipo (url puede ser la versión por Tailscale integrado). */
    private var sourceUrl = ""
    /** Mientras se aplica una orden remota, los cambios locales no se reenvían. */
    private var ignoreLocalUntil = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val refreshMatch = VideoDisplay.RefreshMatch()
    private var stopWatchingScreen: () -> Unit = {}
    private var videoFrameRate: VideoFrameRate? = null
    private var matchWhileVisible = false
    private var trackSelector: DefaultTrackSelector? = null
    private var lastTracks = Tracks.EMPTY
    private val triedTracks = mutableSetOf<TrackSelectionOverride>()
    @Volatile private var preferDolbyBaseLayer = false
    private var synchronousCodec = false
    @Volatile private var failedDecoders = emptySet<String>()
    /** Este equipo pidió sincronizar y espera a que el otro esté listo en el mismo punto. */
    private var leadingSync = false
    private var leaderPosition = 0L
    private val readyPeers = HashSet<String>()
    /** El otro pidió sincronizar: al quedar listo en ese punto, se le avisa. */
    private var awaitingSyncReady = false
    private val leaderTimeout = Runnable { finishLeadSync() }
    private val history by lazy { WatchHistory(this) }
    private var watchKey: String? = null

    /** Enlace con cabeceras propias (otra app): no cuenta como conexión de la cuenta. */
    private var addonStream = false

    // --- Saltar intro, resumen y créditos (series) ---
    private var skipEpisode: SkipSegments.Episode? = null
    private var skipFound: List<SkipSegments.Segment> = emptyList()
    private var skipMarks = SkipSegments.Marks()
    private var skipSource = ""
    private var skipRequested = false
    private val skipDone = HashSet<SkipSegments.Segment>()
    private var skipShown: SkipSegments.Segment? = null
    private lateinit var skipButton: TextView
    private val skipTick = object : Runnable {
        override fun run() {
            checkSkip()
            if (skipEpisode != null) handler.postDelayed(this, 500L)
        }
    }

    /** Último estado avisado ("start", "pause" o "stop"). */
    private var scrobbled: String? = null
    private val scrobbleUpdate = Runnable { sendScrobble(if (activePlayer()?.isPlaying == true) "start" else "pause") }

    private fun sendScrobble(action: String) {
        if (kind != "vod" || watchKey == null) return
        if (action == scrobbled) return
        val p = activePlayer() ?: return
        val duration = p.duration
        if (duration <= 0) return
        scrobbled = action
    }

    private fun saveWatch(ended: Boolean = false) {
        val key = watchKey ?: return
        val p = activePlayer() ?: return
        history.track(key, p.currentPosition, p.duration, ended)
    }

    private val speedReset = Runnable { activePlayer()?.setPlaybackSpeed(1f) }
    private val ticker = object : Runnable {
        override fun run() {
            // El que comparte manda su posición cada 10 s; el otro corrige si se va desfasando.
            val p = activePlayer()
            if (p?.isPlaying == true) saveWatch()
            if (p != null && isHost && kind == "vod" && p.isPlaying) send("tick", p.currentPosition)
            // Viendo juntos: cada equipo avisa «sigo aquí» para que el otro muestre la marca JUNTOS.
            if (p != null && !solo) send("here", p.currentPosition)
            // En pausa y fuera de la pantalla (teléfono con la pantalla apagada o en otra app), un video
            // pausado sigue ocupando la conexión de la cuenta: pasados 90 s se cierra (el punto queda guardado).
            if (solo && stoppedAt > 0 && p != null && !p.playWhenReady) {
                if (pausedInBackgroundSince == 0L) pausedInBackgroundSince = SystemClock.elapsedRealtime()
                else if (SystemClock.elapsedRealtime() - pausedInBackgroundSince > 90_000L) {
                    android.util.Log.i(StreamGuard.TAG, "Pausado en segundo plano: se cierra para soltar la conexión")
                    closeReason = "estuvo en pausa fuera de la pantalla más de 90 s"
                    saveWatch()
                    releasePlayback()
                    finish()
                    return
                }
            } else pausedInBackgroundSince = 0L
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** Se abrió solo para agrandar la película que estaba en imagen en imagen (esa ya lo hace). */
    private var pipReturnOnly = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (PipReturn.isReturn(intent)) {
            pipReturnOnly = true
            closeReason = "solo venía a agrandar la imagen en imagen"
            finish()
            return
        }
        current = java.lang.ref.WeakReference(this)
        // Cuando la TV cambia de frecuencia se actualiza la etiqueta "TV … Hz".
        stopWatchingScreen = VideoDisplay.watchScreen(this) { if (::controls.isInitialized) controls.refresh() }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        softwareSurface = VLCVideoLayout(this).apply { visibility = View.GONE }
        root.addView(softwareSurface, FrameLayout.LayoutParams(-1, -1))
        playerView = PlayerView(this).apply {
            controllerAutoShow = false
            // Sin los controles de Media3 (botones grandes en el medio): la barra de abajo es la de la app.
            useController = false
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }
        root.addView(playerView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        errorText = Ui.label(this, "", 15f, Ui.muted)
        errorBox = Ui.card(this).apply {
            visibility = View.GONE
            addView(Ui.label(context, "No se pudo reproducir", 20f, bold = true),
                Ui.matchWrap(bottomMargin = Ui.dp(context, 10)))
            // El texto puede ser largo: se desplaza sin empujar los botones fuera de la pantalla.
            val textScroll = ScrollView(context).apply { addView(errorText) }
            addView(textScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
                bottomMargin = Ui.dp(context, 18)
            })
            addView(Ui.button(context, "Reintentar") { retry() }, Ui.matchWrap(bottomMargin = Ui.dp(context, 10)))
            localFallbackButton = Ui.button(context, "Ver aquí", Ui.Style.SECONDARY, ::openDirectFallback)
            addView(localFallbackButton, Ui.matchWrap(bottomMargin = Ui.dp(context, 8)))
            otherLinksButton = Ui.button(context, "Otros enlaces", Ui.Style.SECONDARY) {
                otherLinksRequested = true
                closeReason = "pidió otros enlaces (no abrió)"
                saveWatch()
                releasePlayback()
                finish()
            }
            addView(otherLinksButton, Ui.matchWrap(bottomMargin = Ui.dp(context, 8)))
            serversButton = Ui.button(context, "Probar otro servidor", Ui.Style.SECONDARY) {
                errorBox.visibility = View.GONE
                showServersPanel()
            }
            addView(serversButton, Ui.matchWrap(bottomMargin = Ui.dp(context, 8)))
            addView(Ui.button(context, "Salir", Ui.Style.SECONDARY) {
                closeReason = "botón Salir del aviso de error"
                finish()
            }, Ui.matchWrap())
        }
        val metrics = resources.displayMetrics
        val margin = Ui.dp(this, 16)
        val boxWidth = minOf(Ui.dp(this, 520), metrics.widthPixels - 2 * margin)
        val boxHeight = minOf(Ui.dp(this, 460), metrics.heightPixels - 2 * margin)
        root.addView(errorBox, FrameLayout.LayoutParams(boxWidth, boxHeight, Gravity.CENTER))

        // OK: barra de reproducción de abajo. ▼: ajustes (formato, audio, subtítulos…).
        controls = PlaybackPanel(this, root, ::playbackInfo, quick = { emptyList() }, options = {
            buildList {
                // Series: todos los episodios de la temporada (y las demás) para elegir uno.
                if (currentGuide() != null) add(PlaybackAction("Episodios", icon = R.drawable.catalog_series) {
                    controls.close()
                    showEpisodePanel()
                })
                add(PlaybackAction("Formato", icon = R.drawable.catalog_format) {
                    VideoDisplay.choose(this@PlayerActivity, ::updateVideoDisplay)
                })
                add(PlaybackAction("Audio", icon = R.drawable.catalog_audio) {
                    choosePlaybackTracks(this@PlayerActivity, player, C.TRACK_TYPE_AUDIO, softwarePlayer)
                })
                add(PlaybackAction("Subtítulos", icon = R.drawable.catalog_subtitles) {
                    choosePlaybackTracks(this@PlayerActivity, player, C.TRACK_TYPE_TEXT, softwarePlayer)
                })
                add(PlaybackAction("Frecuencia", icon = R.drawable.catalog_frequency, showFrameRate = true) {
                    VideoDisplay.chooseFrequency(this@PlayerActivity, ::updateVideoDisplay)
                })
                add(PlaybackAction("Diagnóstico", icon = R.drawable.catalog_info) { showPlaybackDiagnostics() })
                if (skipEpisode != null) add(PlaybackAction("Saltar intro", icon = R.drawable.catalog_skip) {
                    controls.close()
                    showSkipSettings()
                })
                if (!solo) add(PlaybackAction("Sincronizar", icon = R.drawable.catalog_sync, run = ::requestSync))
                if (kind == "live") add(PlaybackAction("Sintonizar", icon = R.drawable.catalog_guide) { controls.close(); reloadLive() })
                else add(PlaybackAction("Reanudar", icon = R.drawable.catalog_play) { activePlayer()?.play(); controls.close() })
            }
        }, onClosed = {
            root.isFocusableInTouchMode = true
            root.requestFocus()
        }, timeline = movieTimeline, liveBadge = { kind == "live" && url !in movieChannels })
        sidePanel = PlayerSidePanel(this, root).apply {
            onClosed = {
                root.isFocusableInTouchMode = true
                root.requestFocus()
            }
        }
        // Con el dedo (teléfono): un toque muestra u oculta la barra; doble toque cambia entre la pantalla chica
        // (arriba, con el teléfono derecho) y la pantalla completa acostada, como en la guía de TV.
        if (Ui.isTelevision(this)) root.setOnClickListener { if (controls.isOpen()) controls.close() else controls.showQuick() }
        else {
            // Debajo del video (pantalla chica): el nombre y, en las series, los episodios para elegir otro.
            val list = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(context, 14), 0, Ui.dp(context, 14), Ui.dp(context, 24))
            }
            miniList = list
            miniInfo = ScrollView(this).apply {
                visibility = View.GONE
                isVerticalScrollBarEnabled = false
                addView(list)
            }
            root.addView(miniInfo, FrameLayout.LayoutParams(-1, -1, Gravity.TOP))
            val gestures = android.view.GestureDetector(this, object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: android.view.MotionEvent) = true
                override fun onSingleTapConfirmed(e: android.view.MotionEvent): Boolean {
                    if (controls.isOpen()) controls.close() else controls.showQuick()
                    return true
                }
                override fun onDoubleTap(e: android.view.MotionEvent): Boolean {
                    setPhoneMini(!phoneMini)
                    return true
                }
            })
            root.setOnTouchListener { _, event -> gestures.onTouchEvent(event) }
        }
        // «Saltar intro» / «Siguiente episodio» abajo a la derecha (como Netflix): se aprieta con OK.
        skipButton = Ui.button(this, "", Ui.Style.PRIMARY, R.drawable.catalog_skip) { skipShown?.let { performSkip(it) } }.apply {
            visibility = View.GONE
            isFocusable = false
            textSize = 16f
            elevation = Ui.dp(context, 6).toFloat()
        }
        root.addView(skipButton, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, Ui.dp(this@PlayerActivity, 48), Ui.dp(this@PlayerActivity, 56))
        })
        setContentView(root)
        // Teléfono: empieza en la pantalla chica (arriba, el teléfono derecho); doble toque la agranda.
        if (!Ui.isTelevision(this)) root.post { setPhoneMini(true) }
        handler.postDelayed(ticker, TICK_MS)
        RoomSession.playbackListener = roomPlayback
        if (savedInstanceState != null && requiresManualPlayback(intent)) {
            intent.putExtra(EXTRA_WAIT_FOR_PLAY, true)
        }
        load(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Volver de imagen en imagen: Android agranda esta misma película; no se cambia nada.
        if (PipReturn.isReturn(intent)) return
        setIntent(intent)
        load(intent)
    }

    private fun requiresManualPlayback(intent: Intent): Boolean =
        intent.getBooleanExtra(PlaybackAccess.MANUAL_EXTRA, false) ||
            PlaybackAccess.marked(intent.getStringExtra(EXTRA_TITLE).orEmpty())

    private fun load(intent: Intent) {
        // Teléfono en pantalla chica: al cambiar de episodio se pone al día la lista de abajo.
        if (phoneMini) playerView.postDelayed({ if (phoneMini) fillMini() }, 600)
        val newUrl = intent.getStringExtra(EXTRA_URL)
        if (newUrl.isNullOrBlank()) {
            closeReason = "no llegó la dirección del video"
            finish()
            return
        }
        busyRetries = 0
        triedLiveHls = false
        if (intent.getBooleanExtra(EXTRA_WAIT_FOR_PLAY, false) && requiresManualPlayback(intent) &&
            acceptedManualUrl != newUrl) {
            if (pendingManualUrl == newUrl) return
            pendingManualUrl = newUrl
            manualPrompt?.dismiss()
            ignoreLocal(1500)
            activePlayer()?.pause()
            manualPrompt = android.app.AlertDialog.Builder(this)
                .setTitle("Reproducción manual")
                .setMessage("Selecciona Reproducir para abrir este contenido.")
                .setNegativeButton("Cancelar") { _, _ ->
                    closeReason = "se canceló la reproducción manual"
                    finish()
                }
                .setPositiveButton("Reproducir") { _, _ ->
                    pendingManualUrl = null
                    acceptedManualUrl = newUrl
                    load(intent)
                }.setOnCancelListener {
                    closeReason = "se canceló la reproducción manual"
                    finish()
                }.create().also(Ui::showDialog)
            return
        }
        manualPrompt?.dismiss()
        manualPrompt = null
        pendingManualUrl = null
        if (acceptedManualUrl != newUrl) acceptedManualUrl = null
        // Al reconectarse la sala llega el mismo aviso otra vez: si ya se está viendo, no se reinicia.
        if (newUrl == sourceUrl && activePlayer() != null && errorBox.visibility != View.VISIBLE) return
        saveWatch()
        refreshMatch.newSource()
        watchKey = intent.getStringExtra(EXTRA_WATCH_KEY)
        sourceUrl = newUrl
        sourceBytes = 0L
        solo = intent.getBooleanExtra(EXTRA_SOLO, false)
        addonStream = intent.getBooleanExtra(EXTRA_ADDON, false)
        startSkip(SkipSegments.Episode.from(intent.getBundleExtra(EXTRA_SKIP)))
        useHlsForVod = false
        controls.close()
        advancing = false
        leadingSync = false
        awaitingSyncReady = false
        handler.removeCallbacks(leaderTimeout)
        handler.removeCallbacks(speedReset)
        url = if (solo) newUrl else TailnetBridge.rewriteForPlayback(newUrl)
        isHost = intent.getBooleanExtra(EXTRA_HOST, false)
        kind = intent.getStringExtra(EXTRA_KIND) ?: "vod"
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        remux = kind == "vod" && isRemuxTitle(title)
        droppedFrames = 0
        rebufferCount = 0
        startedVideo = false
        decoderName = ""
        if (title.isNotBlank()) {
            toast(when {
                solo -> title
                isHost -> "Compartiendo: $title"
                else -> "Viendo juntos: $title"
            })
        }
        hideError()

        synchronousCodec = false
        preferDolbyBaseLayer = false
        failedDecoders = emptySet()
        triedTracks.clear()
        createPlayer(intent.getLongExtra(EXTRA_START, 0L))
    }

    private fun createPlayer(startMs: Long, previousParameters: DefaultTrackSelector.Parameters? = null) {
        // Una sola conexión con el proveedor: se cierra lo que la app tuviera abierto (vista previa…).
        StreamGuard.closeOthers(this)
        StreamGuard.opened(this, (if (kind == "live") "Canal: " else "Película/serie: ") +
            intent.getStringExtra(EXTRA_TITLE).orEmpty()) {
            closeReason = "se abrió otro video en la app (la cuenta permite una sola conexión)"
            saveWatch()
            releasePlayback()
            finish()
        }
        releaseSoftwarePlayer()
        val previous = player
        backgroundPlayback?.attach(null)
        player = null
        playerView.player = null
        previous?.release()
        lastTracks = Tracks.EMPTY
        // Tiempos amplios: el relay espera al proveedor antes de responder.
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(if (kind == "vod") 50_000 else 30_000)
        if (solo) http.setUserAgent(UserAgents.current(prefs)).setAllowCrossProtocolRedirects(true)
        // Algunas apps mandan cabeceras para su enlace (User-Agent, Referer…).
        intent.getBundleExtra(EXTRA_HEADERS)?.let { headers ->
            val values = headers.keySet().associateWith { headers.getString(it).orEmpty() }.filterValues { it.isNotBlank() }
            values.entries.firstOrNull { it.key.equals("User-Agent", ignoreCase = true) }?.let { http.setUserAgent(it.value) }
            http.setDefaultRequestProperties(values.filterKeys { !it.equals("User-Agent", ignoreCase = true) })
        }
        val responseIsHls = AtomicBoolean(false)
        if (solo && kind == "vod" && !useHlsForVod) {
            http.setTransferListener(object : TransferListener {
                override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit

                override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
                    val contentType = source.responseHeaders.entries
                        .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
                        ?.value?.firstOrNull()?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
                    val redirectedPath = source.uri?.path.orEmpty()
                    if (contentType in HLS_CONTENT_TYPES || redirectedPath.endsWith(".m3u8", ignoreCase = true)) {
                        responseIsHls.set(true)
                    }
                    // Tamaño del archivo (para la etiqueta REMUX y los GB): «Content-Range: bytes a-b/total» o Content-Length.
                    fun header(name: String) = source.responseHeaders.entries
                        .firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
                    val total = header("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull()
                        ?: header("Content-Length")?.trim()?.toLongOrNull()?.takeIf { dataSpec.position == 0L }
                    if (total != null && total > sourceBytes) sourceBytes = total
                }

                override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) = Unit

                override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) = Unit
            })
        }
        val buffer = DefaultLoadControl.Builder()
        // El búfer sale de la memoria de la app. Con un REMUX 4K (archivos enormes) y el catálogo
        // cargado detrás, un búfer fijo grande dejaba la app sin memoria: se cerraba y volvía a abrir
        // en la TV en vivo. Ahora se ajusta a la memoria que de verdad queda libre.
        // Películas y series (no solo REMUX): los pósters guardados en memoria se sueltan para dejarle más al
        // video; al volver al catálogo se cargan otra vez del disco.
        // (Se probó soltar solo la mitad y las películas 4K REMUX se cerraban al abrir: se sueltan todas.)
        if (kind == "vod") runCatching { coil.Coil.imageLoader(this).memoryCache?.clear() }
        val runtime = Runtime.getRuntime()
        val freeBytes = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        fun fitted(desiredMiB: Int): Int =
            minOf(desiredMiB * 1024L * 1024L, (freeBytes * 0.45).toLong()).coerceAtLeast(32L * 1024 * 1024).toInt()
        // Búfer elegido en Configuración (sin búfer, pequeño, medio o grande).
        val level = PlaybackBuffer.current(this)
        if (remux) {
            val memory = getSystemService(android.app.ActivityManager::class.java)?.memoryClass ?: 256
            val bufferMiB = (memory / 2).coerceIn(80, 160)
            // Un REMUX 4K pasa de 60 Mbps: con un búfer muy chico se queda parando. Mínimo ~80 MB
            // si la memoria libre lo permite.
            val floor = minOf(80L * 1024 * 1024, (freeBytes * 0.6).toLong()).toInt()
            buffer.setBufferDurationsMs(level.remuxMinMs, level.remuxMaxMs, level.remuxStartMs, level.remuxRebufferMs)
                .setTargetBufferBytes(maxOf(fitted(bufferMiB), floor))
        } else {
            buffer.setBufferDurationsMs(level.minMs, level.maxMs, level.startMs, level.rebufferMs)
                .setTargetBufferBytes(fitted(level.memoryMiB))
        }
        val loadControl = buffer.build()
        PlaybackLog.add(this, "Abre " + (if (kind == "live") "canal" else "película/serie") + ": " +
            intent.getStringExtra(EXTRA_TITLE).orEmpty() + (if (remux) " (REMUX)" else "") +
            " · búfer ${level.key} · " + PlaybackLog.memory())
        // Si el decodificador por hardware falla, se prueba otro; los audios que el equipo no
        // trae (DTS, TrueHD, AC3...) se decodifican con FFmpeg dentro de la app.
        val renderers = DefaultRenderersFactory(this)
            .setMediaCodecSelector { mimeType, secure, tunneling ->
                if (preferDolbyBaseLayer && mimeType == MimeTypes.VIDEO_DOLBY_VISION) emptyList()
                else MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, secure, tunneling)
                    .filterNot { it.name in failedDecoders }
                    .let { candidates ->
                        if (remux && MimeTypes.getTrackType(mimeType) == C.TRACK_TYPE_VIDEO) {
                            candidates.sortedByDescending { it.hardwareAccelerated }
                        } else candidates
                    }
            }
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
        if (synchronousCodec) renderers.forceDisableMediaCodecAsynchronousQueueing()
        val selector = DefaultTrackSelector(this)
        val tracks = previousParameters?.buildUpon() ?: selector.buildUponParameters()
        if (prefs.getBoolean(PREF_AUTO_AUDIO, true)) {
            tracks.setPreferredAudioLanguages("es", "en")
        }
        if (prefs.getBoolean(PREF_AUTO_SUBTITLES, true)) {
            tracks.setPreferredTextLanguage("es")
                .setSelectUndeterminedTextLanguage(false)
                .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT or C.SELECTION_FLAG_FORCED)
        }
        selector.parameters = tracks.build()
        trackSelector = selector
        val p = ExoPlayer.Builder(this, renderers)
            .setTrackSelector(selector)
            // También abre archivos del equipo (grabaciones): lo que es http sigue yendo por «http».
            .setMediaSourceFactory(DefaultMediaSourceFactory(this).setDataSourceFactory(
                androidx.media3.datasource.DefaultDataSource.Factory(this, http)))
            .setLoadControl(loadControl)
            .build()
        p.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
            override fun onVideoDecoderInitialized(
                eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long
            ) {
                if (player === p) {
                    this@PlayerActivity.decoderName = decoderName
                    PlaybackLog.add(this@PlayerActivity, "Decodificador de video: $decoderName")
                }
            }

            override fun onDroppedVideoFrames(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                                             droppedFrames: Int, elapsedMs: Long) {
                if (player === p) this@PlayerActivity.droppedFrames += droppedFrames
            }

            override fun onRenderedFirstFrame(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                                              output: Any, renderTimeMs: Long) {
                if (player === p) startedVideo = true
            }
        })
        player = p
        backgroundPlayback?.let {
            p.setWakeMode(C.WAKE_MODE_NETWORK)
            it.attach(p)
        }
        playerView.player = p
        videoFrameRate = VideoFrameRate { rate ->
            if (player === p && videoFrameRate === rate) updateVideoDisplay()
        }.also(p::setVideoFrameMetadataListener)
        // Toma el control del audio: las apps que respetan esto (como el reproductor de Nuvio) se pausan solas.
        p.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            true
        )
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Se espera un momento para no avisar en cada pequeño corte de carga.
                handler.removeCallbacks(scrobbleUpdate)
                handler.postDelayed(scrobbleUpdate, if (isPlaying) 4_000L else 6_000L)
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!solo && isLocalChange()) {
                    RoomSession.client?.sendPlayback(if (playWhenReady) "play" else "pause", p.currentPosition)
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                // Solo el anfitrión mueve la posición del otro lado.
                if (reason == Player.DISCONTINUITY_REASON_SEEK && kind == "vod" && isHost && isLocalChange()) {
                    RoomSession.client?.sendPlayback("seek", newPosition.positionMs)
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (player === p && playbackState == Player.STATE_BUFFERING && startedVideo &&
                    p.playWhenReady && !leadingSync && !awaitingSyncReady
                ) rebufferCount++
                if (playbackState == Player.STATE_READY) {
                    hideError()
                    updateVideoDisplay()
                    reportSyncReady()
                } else if (playbackState == Player.STATE_ENDED && player === p) {
                    // Se marca visto en el historial.
                    handler.removeCallbacks(scrobbleUpdate)
                    sendScrobble("stop")
                    saveWatch(ended = true)
                    advanceEpisode()
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                if (tracks.groups.isNotEmpty()) lastTracks = tracks
                if (player === p) logFormats(p.videoFormat, p.audioFormat)
                updateVideoDisplay()
            }

            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                updateVideoDisplay()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (player !== p) return
                PlaybackLog.add(this@PlayerActivity, "Error del reproductor: ${error.errorCodeName} · " +
                    PlaybackLog.cause(error) + " · " + PlaybackLog.memory())
                // En vivo, si se quedó atrás, se vuelve al directo sin molestar.
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                    p.seekToDefaultPosition()
                    p.prepare()
                    return
                }
                val containerError = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                    error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
                if (containerError && responseIsHls.get() && !useHlsForVod) {
                    // Algunos servidores anuncian MP4 y redirigen a una lista HLS.
                    useHlsForVod = true
                    createPlayer(maxOf(startMs, p.currentPosition), selector.parameters)
                    return
                }
                android.util.Log.w(StreamGuard.TAG, "Error al reproducir: ${error.errorCodeName}" +
                    "")
                if (tryLiveHls(error)) return
                if (retryWhenProviderBusy(p, error)) return
                if (trySoftwareHigh10(p, error) || retryWithSynchronousCodec(p, error) || tryDecodeFallback(p, error)) return
                // Enlace caído o cambiado (p. ej. al reanudar días después): se busca otro en español y sigue igual.
                if (tryOtherSource(error)) return
                val message = if (triedLiveHls && isUnknownContainer(error))
                    "Este canal no está transmitiendo ahora mismo: el proveedor no manda video. Prueba más tarde u otro canal."
                    else describe(error)
                showError(message)
                if (!solo) appendProviderInfo(message)
            }
        })
        val item = MediaItem.Builder().setUri(url)
        if (useHlsForVod || Uri.parse(url).path?.endsWith(".m3u8", ignoreCase = true) == true) {
            item.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        // Si la otra app ya iba por un punto de la película, se sigue desde ahí.
        if (startMs > 0 && kind == "vod") p.setMediaItem(item.build(), startMs) else p.setMediaItem(item.build())
        p.playWhenReady = true
        openedAt = SystemClock.elapsedRealtime()
        // Película que ya se sabe que el chip no soporta: directo a VLC y a sus FPS (p. ej. 24).
        if (kind == "vod" && prefs.contains(softwareKey())) {
            softwareFallbackFps = prefs.getFloat(softwareKey(), 0f)
            startSoftware(p, startMs, true, null)
            return
        }
        p.prepare()
    }

    /** [manual]: se apretó «Siguiente episodio» (pasa aunque esté apagado el siguiente automático). */
    @Suppress("DEPRECATION")
    private fun advanceEpisode(manual: Boolean = false) {
        if (advancing || isFinishing || (!isHost && !solo) || (!manual && !prefs.getBoolean(PREF_AUTO_NEXT, true)) ||
            requiresManualPlayback(intent)) return
        val episodes = intent.getParcelableArrayListExtra<Bundle>(EXTRA_QUEUE) ?: return
        val next = episodes.firstOrNull() ?: return
        if (next.getBoolean(PlaybackAccess.MANUAL_EXTRA) || PlaybackAccess.marked(next.getString("title").orEmpty())) return
        val queue = ArrayList(episodes.drop(1))
        val upstream = next.getString("url")
        if (upstream == null) return
        val title = next.getString("title").orEmpty()
        val nextWatch = next.getString("watch_key")
        val resumeAt = nextWatch?.let { history.get(it).positionMs } ?: 0L
        // La misma serie, el número del episodio que sigue (para sus tiempos de intro y créditos).
        val nextSkip = SkipSegments.Episode.from(intent.getBundleExtra(EXTRA_SKIP))?.let { current ->
            val season = next.getInt("season_num", -1)
            val number = next.getInt("episode_num", -1)
            if (season >= 0 && number > 0) current.copy(season = season, episode = number).toBundle() else null
        }
        val headers = intent.getBundleExtra(EXTRA_HEADERS)
        if (solo) {
            val nextIntent = intent(this, upstream, "vod", title, host = false, startMs = resumeAt, solo = true, queue = queue,
                watchKey = nextWatch, skip = nextSkip, headers = headers, addon = addonStream,
                linksType = next.getString("addon_type") ?: next.getString("links_type"),
                linksId = next.getString("addon_id") ?: next.getString("links_id"))
            setIntent(nextIntent)
            load(nextIntent)
            return
        }

        val client = RoomSession.client ?: run {
            toast("Conéctate a la sala para continuar con el siguiente episodio.")
            return
        }
        val previousShare = RoomSession.currentShare
        advancing = true
        toast("Abriendo el siguiente episodio…")
        val appContext = applicationContext
        thread {
            try {
                val p = Sharing.prepareUrl(appContext, upstream, live = false, title = title).withPlaybackId()
                runOnUiThread {
                    if (isDestroyed || isFinishing || !advancing || RoomSession.client !== client ||
                        RoomSession.currentShare !== previousShare) {
                        if (RoomSession.currentShare == null) thread {
                            if (RoomSession.currentShare == null) RoomSession.relay(appContext).stopSharing()
                        }
                        advancing = false
                        return@runOnUiThread
                    }
                    Sharing.announce(p, resumeAt, watchKey = nextWatch)
                    val nextIntent = intent(this, p.localUrl, p.kind, title, host = true, startMs = resumeAt, queue = queue,
                        watchKey = nextWatch, skip = nextSkip)
                    setIntent(nextIntent)
                    load(nextIntent)
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        advancing = false
                        toast("No se pudo abrir el siguiente episodio.")
                    }
                }
            }
        }
    }

    /** Los episodios de la serie que se está viendo (si este episodio está entre ellos). */
    private fun currentGuide(): ArrayList<Bundle>? {
        if (kind == "live" || (!isHost && !solo)) return null
        val key = watchKey ?: return null
        return episodeGuide?.takeIf { guide -> guide.size > 1 && guide.any { it.getString("watch_key") == key } }
    }

    private fun showEpisodePanel() {
        val guide = currentGuide() ?: return
        val series = intent.getStringExtra(EXTRA_TITLE).orEmpty().substringBefore(" · ")
        sidePanel.showEpisodes(series, guide, watchKey) { picked -> playFromGuide(guide, picked) }
    }

    /**
     * Abre el episodio elegido en «Episodios»: va primero en la cola y siguen los de después (así, al terminar,
     * pasa solo al siguiente).
     */
    private fun playFromGuide(guide: List<Bundle>, picked: Bundle) {
        val index = guide.indexOfFirst { it.getString("watch_key") == picked.getString("watch_key") }
        if (index < 0) return
        val queue = ArrayList(guide.drop(index).take(61).map { Bundle(it) })
        intent.putParcelableArrayListExtra(EXTRA_QUEUE, queue)
        if (picked.getString("url") != null) {
            // Sin las cabeceras del enlace que se estaba viendo.
            addonStream = false
            intent.removeExtra(EXTRA_HEADERS)
        }
        advancing = false
        advanceEpisode(manual = true)
    }

    private fun showLinksPanel() = Unit
    @Suppress("UNUSED_PARAMETER")
    private fun tryOtherSource(error: PlaybackException): Boolean = false
    private fun showServersPanel() = Unit

    /** Reintentos automáticos cuando el proveedor todavía ve la conexión anterior. */
    private var busyRetries = 0
    /** Canal en vivo: ya se probó la versión HLS (.m3u8) después de fallar la .ts. */
    private var triedLiveHls = false

    private fun isUnknownContainer(error: PlaybackException) = error.errorCode in setOf(
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
    )

    /**
     * Algunos canales (p. ej. los de Pay Per View) no mandan video por el enlace .ts; el mismo canal
     * en .m3u8 sí funciona. Se prueba solo, una vez, antes de mostrar el error.
     */
    private fun tryLiveHls(error: PlaybackException): Boolean {
        if (!solo || kind != "live" || triedLiveHls || !isUnknownContainer(error)) return false
        val bare = url.substringBefore('?')
        if (!bare.endsWith(".ts", ignoreCase = true)) return false
        triedLiveHls = true
        url = bare.dropLast(3) + ".m3u8"
        handler.post { if (!isFinishing) createPlayer(0L) }
        return true
    }
    private var openedAt = 0L

    @Suppress("UNUSED_PARAMETER")
    private fun retryWhenProviderBusy(p: Player, error: PlaybackException): Boolean = false

    private fun retry() {
        val p = activePlayer() ?: return
        hideError()
        p.prepare()
        p.playWhenReady = true
    }

    private fun openDirectFallback() {
        val direct = intent.getStringExtra(EXTRA_DIRECT_FALLBACK) ?: return
        if (!isHost) return
        val position = maxOf(activePlayer()?.currentPosition ?: 0L, intent.getLongExtra(EXTRA_START, 0L))
        RoomSession.currentShare = null
        RoomSession.client?.sendRelayStop()
        val relay = RoomSession.relay(applicationContext)
        relay.stopSharing()
        val local = Intent(intent).putExtra(EXTRA_URL, direct).putExtra(EXTRA_HOST, false)
            .putExtra(EXTRA_SOLO, true).putExtra(EXTRA_START, position)
        local.removeExtra(EXTRA_DIRECT_FALLBACK)
        setIntent(local)
        load(local)
    }

    private fun showError(message: String) {
        PlaybackLog.add(this, "Aviso de error en pantalla: $message")
        errorText.text = message
        controls.close()
        playerView.hideController()
        localFallbackButton.visibility =
            if (isHost && !intent.getStringExtra(EXTRA_DIRECT_FALLBACK).isNullOrBlank()) View.VISIBLE else View.GONE
        otherLinksButton.visibility = View.GONE
        serversButton.visibility = View.GONE
        errorBox.visibility = View.VISIBLE
        errorBox.getChildAt(2)?.requestFocus()
    }

    /** Pide al relay qué respondió el proveedor y lo añade al mensaje (sirve para diagnosticar). */
    private fun appendProviderInfo(message: String) {
        val infoUrl = url.substringBefore('?').replace(Regex("/stream(\\.m3u8)?$"), "/info")
        if (infoUrl == url.substringBefore('?')) return
        thread {
            val line = try {
                val c = URL(infoUrl).openConnection() as HttpURLConnection
                c.connectTimeout = 4000
                c.readTimeout = 4000
                val body = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                c.disconnect()
                val j = JSONObject(body)
                if (!j.has("status")) null else buildString {
                    append("Proveedor: HTTP ").append(j.optInt("status"))
                    val type = j.optString("contentType")
                    if (type.isNotBlank()) append(" · ").append(type)
                    if (type.contains("mpegurl", ignoreCase = true)) {
                        append(" (lista HLS)")
                    } else {
                        val length = j.optLong("length", -1)
                        if (length > 0) append(" · ").append((length + 1023) / 1024).append(" KB")
                    }
                }
            } catch (_: Exception) {
                null
            }
            if (line != null) runOnUiThread {
                if (errorBox.visibility == View.VISIBLE && errorText.text.toString() == message) {
                    errorText.text = "$message\n\n$line"
                }
            }
        }
    }

    private fun hideError() {
        if (::errorBox.isInitialized) errorBox.visibility = View.GONE
    }

    private fun isHigh10(error: PlaybackException): Boolean {
        val codec = (error as? ExoPlaybackException)?.rendererFormat?.codecs.orEmpty()
        return codec.matches(Regex("""(?:avc1|avc3)\.6e[0-9a-f]{4}""", RegexOption.IGNORE_CASE))
    }

    private fun trySoftwareHigh10(p: ExoPlayer, error: PlaybackException): Boolean {
        if (kind != "vod" || !isHigh10(error) ||
            error.errorCode !in setOf(PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED)) return false
        val position = p.currentPosition
        val playOnStart = p.playWhenReady
        // La TV se pone a la frecuencia de la película (p. ej. 4K a 24) aunque decodifique VLC.
        softwareFallbackFps = (error as? ExoPlaybackException)?.rendererFormat?.frameRate?.takeIf { it > 0f }
            ?: (p.videoFormat?.frameRate ?: 0f).takeIf { it > 0f }
            ?: p.currentTracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
                .flatMap { g -> (0 until g.length).map { g.getTrackFormat(it).frameRate } }
                .firstOrNull { it > 0f } ?: 0f
        // Se recuerda esta película para que la próxima vez arranque directo con VLC.
        prefs.edit().putFloat(softwareKey(), softwareFallbackFps).apply()
        startSoftware(p, position, playOnStart, error)
        return true
    }

    /** Clave para recordar las películas que el chip no puede decodificar. */
    private fun softwareKey() = "vlc_fps_" + url.substringBefore('?').hashCode()

    /** Arranca la película con el motor por software (VLC) en lugar del reproductor normal. */
    private fun startSoftware(p: ExoPlayer, position: Long, playOnStart: Boolean, error: PlaybackException?) {
        handler.post {
            if (player !== p || isFinishing || isDestroyed) return@post
            val software = try {
                SoftwareVideoPlayer(this, softwareSurface, url, prefs.getBoolean(PREF_AUTO_AUDIO, true),
                    prefs.getBoolean(PREF_AUTO_SUBTITLES, true), position)
            } catch (_: Exception) {
                null
            } catch (_: LinkageError) {
                null
            }
            if (software == null) {
                showError("No se pudo iniciar el decodificador por software." +
                    (error?.let { "\n\n" + describe(it) } ?: ""))
                return@post
            }
            ignoreLocal(2000)
            playerView.player = null
            player = null
            backgroundPlayback?.attach(software)
            p.release()
            softwarePlayer = software
            softwareSurface.visibility = View.VISIBLE
            playerView.videoSurfaceView?.visibility = View.GONE
            playerView.setBackgroundColor(Color.TRANSPARENT)
            playerView.setShutterBackgroundColor(Color.TRANSPARENT)
            software.addListener(object : Player.Listener {
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!solo && isLocalChange()) send(if (playWhenReady) "play" else "pause", software.currentPosition)
                }

                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                    if (isHost && isLocalChange() && reason == Player.DISCONTINUITY_REASON_SEEK) {
                        send("seek", newPosition.positionMs)
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (softwarePlayer !== software) return
                    if (playbackState == Player.STATE_READY) {
                        hideError()
                        updateVideoDisplay()
                        reportSyncReady()
                    } else if (playbackState == Player.STATE_ENDED) {
                        saveWatch(ended = true)
                        advanceEpisode()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (softwarePlayer === software) {
                        PlaybackLog.add(this@PlayerActivity, "Error de VLC: ${error.errorCodeName} · " + PlaybackLog.cause(error))
                        showError("El decodificador por software tampoco pudo reproducir esta pista H.264 High 10.")
                    }
                }
            })
            playerView.player = software
            software.playWhenReady = playOnStart
            software.prepare()
            PlaybackLog.add(this, "Pasa a VLC (software)" + (error?.let { ": " + it.errorCodeName } ?: ""))
            toast("Reproduciendo H.264 High 10 por software…")
        }
    }

    private fun releaseSoftwarePlayer() {
        val software = softwarePlayer ?: return
        softwarePlayer = null
        backgroundPlayback?.attach(null)
        playerView.player = null
        software.release()
        softwareSurface.visibility = View.GONE
        playerView.videoSurfaceView?.visibility = View.VISIBLE
        playerView.setBackgroundColor(Color.BLACK)
        playerView.setShutterBackgroundColor(Color.BLACK)
    }

    private fun retryWithSynchronousCodec(p: ExoPlayer, error: PlaybackException): Boolean {
        if (synchronousCodec || error.errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED) return false
        val format = (error as? ExoPlaybackException)?.rendererFormat ?: return false
        if (MimeTypes.getTrackType(format.sampleMimeType) != C.TRACK_TYPE_VIDEO) return false
        val releasedDuringInput = generateSequence(error.cause) { it.cause }.any {
            val detail = it.message.orEmpty()
            detail.contains("queueInputBuffer", ignoreCase = true) &&
                detail.contains("Released state", ignoreCase = true)
        }
        if (!releasedDuringInput) return false

        synchronousCodec = true
        val position = p.currentPosition
        val parameters = trackSelector?.parameters
        handler.post {
            if (player === p && !isFinishing && !isDestroyed) {
                toast("Reiniciando el decodificador en modo síncrono…")
                ignoreLocal(2000)
                createPlayer(position, parameters)
            }
        }
        return true
    }

    private fun tryDecodeFallback(p: ExoPlayer, error: PlaybackException): Boolean {
        val selector = trackSelector ?: return false
        val format = (error as? ExoPlaybackException)?.rendererFormat ?: return false
        val type = MimeTypes.getTrackType(format.sampleMimeType)
        val isTrackError = when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> type == C.TRACK_TYPE_AUDIO
            else -> false
        }
        if (!isTrackError || (type != C.TRACK_TYPE_VIDEO && type != C.TRACK_TYPE_AUDIO)) return false

        val baseMime = if (type == C.TRACK_TYPE_VIDEO && format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) {
            MediaCodecUtil.getAlternativeCodecMimeType(format)
        } else null
        val hasBaseDecoder = baseMime != null && runCatching {
            MediaCodecSelector.DEFAULT.getDecoderInfos(baseMime, format.drmInitData != null, false).isNotEmpty()
        }.getOrDefault(false)
        if (!preferDolbyBaseLayer && hasBaseDecoder) {
            preferDolbyBaseLayer = true
            toast("Probando la capa de video compatible de Dolby Vision…")
        } else if (tryOtherDecoder(error, format)) {
            toast("Probando otro decodificador compatible…")
        } else {
            if (triedTracks.size >= 3) return false
            val candidates = lastTracks.groups.filter { it.type == type }.flatMap { group ->
                (0 until group.length).mapNotNull { index ->
                    val candidate = group.getTrackFormat(index)
                    val override = TrackSelectionOverride(group.mediaTrackGroup, index)
                    // En HLS el grupo adaptativo puede marcar varias variantes como seleccionadas.
                    if (!group.isTrackSupported(index) || sameVariant(candidate, format) ||
                        override in triedTracks) null else override to candidate
                }
            }
            val replacement = if (type == C.TRACK_TYPE_AUDIO) {
                candidates.firstOrNull { it.second.language == format.language } ?: candidates.firstOrNull()
            } else {
                val lighter = candidates.filter { (_, candidate) ->
                    candidate.height > 0 && candidate.height < format.height ||
                        candidate.height == format.height && candidate.bitrate > 0 &&
                        format.bitrate > 0 && candidate.bitrate < format.bitrate
                }
                lighter.maxByOrNull { (_, candidate) -> candidate.height }
                    ?: candidates.firstOrNull { (_, candidate) -> candidate.sampleMimeType != format.sampleMimeType }
                    ?: candidates.minByOrNull { (_, candidate) -> candidate.height }
            }
            val choice = replacement?.first ?: return false
            triedTracks.add(choice)
            if (type == C.TRACK_TYPE_VIDEO) preferDolbyBaseLayer = false
            selector.parameters = selector.buildUponParameters().setOverrideForType(choice).build()
            toast(if (type == C.TRACK_TYPE_AUDIO) "Probando otra pista de audio compatible…" else "Probando otra pista de video compatible…")
        }

        val position = p.currentPosition
        ignoreLocal(2000)
        p.stop()
        if (kind == "vod") p.seekTo(position)
        p.prepare()
        p.playWhenReady = true
        return true
    }

    private fun sameVariant(candidate: Format, failed: Format): Boolean {
        if (candidate == failed) return true
        return candidate.sampleMimeType == failed.sampleMimeType &&
            candidate.codecs == failed.codecs && candidate.language == failed.language &&
            candidate.width == failed.width && candidate.height == failed.height &&
            candidate.channelCount == failed.channelCount &&
            (candidate.bitrate == failed.bitrate || candidate.id != null && candidate.id == failed.id)
    }

    private fun decoderFailure(error: PlaybackException): MediaCodecDecoderException? =
        generateSequence(error.cause) { it.cause }.filterIsInstance<MediaCodecDecoderException>().firstOrNull()

    private fun tryOtherDecoder(error: PlaybackException, format: Format): Boolean {
        val failed = decoderFailure(error)?.codecInfo?.name ?: return false
        val mime = format.sampleMimeType ?: return false
        if (failed in failedDecoders || failedDecoders.size >= 2) return false
        val rejected = failedDecoders + failed
        val supported = runCatching {
            val codecMime = if (preferDolbyBaseLayer) MediaCodecUtil.getAlternativeCodecMimeType(format) ?: mime else mime
            MediaCodecSelector.DEFAULT.getDecoderInfos(codecMime, format.drmInitData != null, false)
                .any { it.name !in rejected && it.isFormatSupported(format) }
        }.getOrDefault(false)
        if (supported) failedDecoders = rejected
        return supported
    }

    private fun formatsLine(error: PlaybackException): String {
        val failed = (error as? ExoPlaybackException)?.rendererFormat
        val v = failed?.takeIf { MimeTypes.getTrackType(it.sampleMimeType) == C.TRACK_TYPE_VIDEO } ?: player?.videoFormat
        val a = failed?.takeIf { MimeTypes.getTrackType(it.sampleMimeType) == C.TRACK_TYPE_AUDIO } ?: player?.audioFormat
        fun describe(format: Format) = listOfNotNull(
            format.sampleMimeType, format.codecs,
            if (format.width > 0 && format.height > 0) "${format.width}×${format.height}" else null,
            if (format.channelCount > 0) "${format.channelCount} canales" else null
        ).joinToString(" · ")
        return listOfNotNull(
            v?.let { "Video: " + describe(it) },
            a?.let { "Audio: " + describe(it) },
            decoderFailure(error)?.codecInfo?.name?.let { "Decodificador: " + it }
        ).joinToString("\n")
    }

    /** Explica el error en palabras, con el detalle técnico al final. */
    private fun describe(error: PlaybackException): String {
        val causes = generateSequence(error.cause) { it.cause }.toList()
        val http = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
        val where = Uri.parse(url).let { "${it.host}:${it.port}" }
        val message = if (http != null) {
            val relayReason = http.headerFields.entries
                .firstOrNull { it.key?.equals("X-Relay-Error", ignoreCase = true) == true }
                ?.value?.firstOrNull()
                ?.let { raw -> try { URLDecoder.decode(raw, "UTF-8") } catch (_: Exception) { raw } }
            when {
                relayReason != null -> "El servidor no entregó el video: $relayReason."
                // Sin sala (o viendo aquí) el 404 viene del servidor del video, no del relay.
                solo && http.responseCode == 404 -> "El servidor respondió 404: el contenido no existe o la cuenta " +
                    "está bloqueada un rato por demasiadas conexiones. Espera unos minutos antes de reintentar."
                solo && (http.responseCode == 401 || http.responseCode == 403) ->
                    "El servidor rechazó la conexión (HTTP ${http.responseCode}). ¿Hay otra app usando la cuenta?"
                solo -> "El servidor respondió con un error (HTTP ${http.responseCode})."
                http.responseCode == 404 -> "Ese contenido ya no se está compartiendo."
                else -> "El televisor que comparte respondió con un error (HTTP ${http.responseCode})."
            }
        } else {
            when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                    if (solo) "No se pudo conectar con el servidor del video."
                    else if (isHost) "No se pudo abrir el relay en este equipo."
                    else "No se pudo conectar con el equipo que comparte ($where). Revisen que los dos estén en el mismo Wi-Fi, " +
                        "o los dos con Tailscale conectado, según la red elegida en ese equipo."
                PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED ->
                    "Este equipo bloquea las conexiones sin cifrar."
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
                PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                    "Lo que llegó del proveedor no se reconoce como video."
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FAILED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES ->
                    "Este equipo no pudo decodificar las pistas disponibles. " +
                        "El códec o perfil de la película puede requerir otro dispositivo o una versión distinta."
                PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ->
                    "El canal en vivo se atrasó demasiado. Pulsa Reintentar."
                else -> "Ocurrió un error al reproducir."
            }
        }
        // Algunas URL llevan los datos de acceso en la ruta.
        val detail = if (solo) null else causes.lastOrNull()?.message?.take(160)
        val failedCodec = (error as? ExoPlaybackException)?.rendererFormat?.codecs.orEmpty()
        val high10 = failedCodec.matches(Regex("""(?:avc1|avc3)\.6e[0-9a-f]{4}""", RegexOption.IGNORE_CASE))
        return buildString {
            append(message)
            append("\n\n").append(error.errorCodeName)
            if (high10) append("\nPerfil: H.264 High 10")
            formatsLine(error).takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
            val parsing = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
            if (!detail.isNullOrBlank() && http == null && !parsing) append(" · ").append(detail)
        }
    }

    private fun isLocalChange() = SystemClock.elapsedRealtime() > ignoreLocalUntil
    private fun ignoreLocal(ms: Long) { ignoreLocalUntil = SystemClock.elapsedRealtime() + ms }
    private fun send(action: String, position: Long) { RoomSession.client?.sendPlayback(action, position) }

    // --- sincronizar ---

    private fun isHlsLive(p: Player) = p.isCurrentMediaItemLive && p.currentLiveOffset != C.TIME_UNSET

    /**
     * Este equipo pasa a ser la referencia.
     * Películas y series: se pausa aquí, el otro salta al mismo punto, avisa que está listo y arrancan juntos.
     * En vivo (HLS): el otro se pone a la misma distancia del directo. En vivo (TS): los dos vuelven a abrir el canal a la vez.
     */
    private fun requestSync() {
        val p = activePlayer() ?: return
        if (RoomSession.client == null) { toast("No hay conexión con la sala."); return }
        when {
            kind == "vod" -> {
                ignoreLocal(1500)
                p.playWhenReady = false
                leaderPosition = p.currentPosition
                leadingSync = true
                readyPeers.clear()
                send("sync_hold", leaderPosition)
                handler.removeCallbacks(leaderTimeout)
                handler.postDelayed(leaderTimeout, 8_000)
                toast("Sincronizando…")
            }
            isHlsLive(p) -> {
                send("sync_live", p.currentLiveOffset)
                toast("Ajustando la sala a este equipo.")
            }
            else -> {
                send("resync_live", 0)
                reloadLive()
                toast("Sincronizando el canal en la sala…")
            }
        }
    }

    private fun finishLeadSync() {
        if (!leadingSync) return
        leadingSync = false
        handler.removeCallbacks(leaderTimeout)
        val p = activePlayer() ?: return
        ignoreLocal(1500)
        p.seekTo(leaderPosition)
        p.playWhenReady = true
        send("play", leaderPosition)
    }

    private fun reportSyncReady() {
        val p = activePlayer() ?: return
        if (!awaitingSyncReady || p.playbackState != Player.STATE_READY) return
        awaitingSyncReady = false
        send("sync_ready", p.currentPosition)
    }

    private fun reloadLive() {
        val p = player ?: return
        ignoreLocal(2000)
        p.stop()
        p.prepare()
        p.playWhenReady = true
    }

    /** Corrección automática: desfases pequeños se arreglan acelerando o frenando un poco; grandes, saltando. */
    private fun correctDrift(p: Player, hostPosition: Long) {
        if (isHost || kind != "vod" || !p.isPlaying || leadingSync || awaitingSyncReady) return
        val target = hostPosition + TRANSIT_MS
        val drift = p.currentPosition - target
        when {
            abs(drift) > 3_000 -> { ignoreLocal(1500); p.seekTo(target) }
            abs(drift) > 400 && !remux -> {
                p.setPlaybackSpeed(if (drift > 0) 0.95f else 1.05f)
                handler.removeCallbacks(speedReset)
                handler.postDelayed(speedReset, (abs(drift) / 0.05).toLong().coerceAtMost(30_000))
            }
        }
    }

    private fun onRemote(msg: JSONObject) {
        val p = activePlayer() ?: return
        when (msg.optString("type")) {
            "relay_stop" -> if (!isHost && !solo) {
                toast("Se dejó de compartir.")
                closeReason = "el otro equipo dejó de compartir"
                finish()
            }
            "playback" -> {
                val position = msg.optLong("positionMs", 0L)
                // Cualquier señal del otro reproductor confirma que está viendo esto contigo.
                peerSeenAt = SystemClock.elapsedRealtime()
                when (msg.optString("action")) {
                    "here" -> return
                    "tick" -> { correctDrift(p, position); return }
                    "sync_ready" -> {
                        if (leadingSync) {
                            readyPeers.add(msg.optString("sender").ifBlank { "remote" })
                            if (readyPeers.size >= (RoomSession.memberCount - 1).coerceAtLeast(1)) finishLeadSync()
                        }
                        return
                    }
                }
                ignoreLocal(1000)
                when (msg.optString("action")) {
                    "pause" -> { alignTo(p, position); p.playWhenReady = false }
                    "play" -> { alignTo(p, position); p.playWhenReady = true }
                    "seek" -> if (kind == "vod") p.seekTo(position)
                    "sync_hold" -> {
                        ignoreLocal(2000)
                        p.playWhenReady = false
                        p.setPlaybackSpeed(1f)
                        awaitingSyncReady = true
                        p.seekTo(position)
                        // Si el salto cae en lo ya cargado puede no haber cambio de estado: se comprueba igual.
                        handler.postDelayed({ reportSyncReady() }, 400)
                    }
                    "sync_live" -> if (isHlsLive(p)) {
                        val delta = p.currentLiveOffset - position
                        if (abs(delta) > 300) p.seekTo(p.currentPosition + delta)
                    }
                    "resync_live" -> reloadLive()
                }
            }
        }
    }

    /** En series y películas, iguala la posición si la diferencia se nota. */
    private fun alignTo(p: Player, position: Long) {
        if (kind == "vod" && abs(p.currentPosition - position) > 1500) p.seekTo(position)
    }

    private fun showPlaybackDiagnostics() {
        val p = activePlayer()
        val bufferedSeconds = (((p?.bufferedPosition ?: 0L) - (p?.currentPosition ?: 0L))
            .coerceAtLeast(0L) / 1000)
        val details = mutableListOf(playbackInfo().technical.joinToString(" · "), "En búfer · $bufferedSeconds s")
        if (softwarePlayer == null) {
            if (decoderName.isNotEmpty()) details.add("Decodificador · $decoderName")
            details.add("Fotogramas perdidos · $droppedFrames")
            details.add("Pausas por búfer · $rebufferCount")
        } else details.add("Decodificador por software")
        details.addAll(VideoDisplay.diagnostics(this))
        android.app.AlertDialog.Builder(this).setTitle("Diagnóstico de reproducción")
            .setMessage(details.joinToString("\n")).setPositiveButton("Cerrar", null).create().also(Ui::showDialog)
    }

    /** FPS del video que se conocían antes de pasar al motor por software (VLC no siempre los da). */
    private var softwareFallbackFps = 0f

    private fun currentVideoFrameRate(): Float =
        softwarePlayer?.videoFps()?.takeIf { it > 0f }
            ?: softwareFallbackFps.takeIf { softwarePlayer != null && it > 0f }
            ?: videoFrameRate?.rate(player?.videoFormat) ?: 0f

    /** Cuándo llegó la última señal del reproductor del otro equipo (0 = ninguna todavía). */
    private var peerSeenAt = 0L

    /**
     * Viendo juntos: etiqueta junto a la calidad y los FPS. «● JUNTOS» cuando el otro equipo tiene este mismo video
     * abierto (manda señal cada pocos segundos); «● EN LA SALA» si está conectado pero su reproductor no ha dado
     * señal; «SOLO TÚ» si no hay nadie más en la sala; «SIN CONEXIÓN» si se cayó la conexión con el servidor.
     */
    private fun togetherChip(): String? = when {
        solo -> null
        RoomSession.client == null || RoomSession.lastStatus == "Desconectado" -> "✕ SIN CONEXIÓN"
        peerSeenAt > 0 && SystemClock.elapsedRealtime() - peerSeenAt < 3 * TICK_MS -> "● JUNTOS"
        RoomSession.peerPresent -> "● EN LA SALA"
        else -> "SOLO TÚ"
    }

    /** Tamaño del archivo que se está viendo (0 si el servidor no lo dijo). */
    @Volatile private var sourceBytes = 0L

    /**
     * Qué tipo de archivo es la película: «REMUX», «BLURAY», «WEB-DL»… y su formato (MKV, MP4) y tamaño. Sale del
     * nombre del título o del archivo; si no lo dicen, se deduce «REMUX» cuando el archivo pesa lo que pesa un remux
     * (los datos por segundo de un Blu-ray sin recomprimir).
     */
    private fun fileChip(video: androidx.media3.common.Format?, height: Int): String? {
        if (kind != "vod") return null
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val upstream = intent.getStringExtra(EXTRA_DIRECT_FALLBACK)?.takeIf { it.isNotBlank() } ?: sourceUrl
        val path = runCatching { Uri.decode(upstream.substringBefore('?')) }.getOrDefault(upstream)
        val text = "$title $path"
        val seconds = (activePlayer()?.duration ?: 0L).takeIf { it > 0L }?.div(1000.0) ?: 0.0
        val mbps = if (sourceBytes > 0L && seconds > 60) sourceBytes * 8 / seconds / 1_000_000.0 else 0.0
        fun has(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(text)
        val release = when {
            isRemuxTitle(text) -> "REMUX"
            has("""\bblu-?ray\b|\bbd-?rip\b|\bbr-?rip\b|\bbdmv\b""") -> "BLURAY"
            has("""\bweb-?dl\b""") -> "WEB-DL"
            has("""\bweb-?rip\b""") -> "WEBRIP"
            has("""\bhdtv\b""") -> "HDTV"
            has("""\bdvd-?rip\b""") -> "DVDRIP"
            has("""\bhd-?cam\b|\bcam-?rip\b|\btelesync\b""") -> "CAM"
            // Sin etiqueta en el nombre: por el peso. Un remux 4K anda por 40 Mbps o más; uno 1080p, por 18 o más.
            mbps >= (if (height >= 2000) 38.0 else if (height >= 1000) 18.0 else 1e9) -> "REMUX"
            else -> null
        }
        val container = when {
            path.endsWith(".mkv", true) -> "MKV"
            path.endsWith(".mp4", true) || path.endsWith(".m4v", true) -> "MP4"
            path.endsWith(".avi", true) -> "AVI"
            path.endsWith(".ts", true) -> "TS"
            path.endsWith(".m3u8", true) -> "HLS"
            else -> when {
                video?.containerMimeType?.contains("matroska") == true -> "MKV"
                video?.containerMimeType?.contains("mp4") == true -> "MP4"
                video?.containerMimeType?.contains("mpegurl", true) == true -> "HLS"
                else -> null
            }
        }
        val size = if (sourceBytes >= 300L * 1024 * 1024)
            String.format(java.util.Locale.US, "%.1f GB", sourceBytes / 1024.0 / 1024.0 / 1024.0) else null
        return listOfNotNull(release, container, size).joinToString(" · ").ifBlank { null }
    }

    private fun playbackInfo(): PlaybackInfo {
        val video = player?.videoFormat
        val size = player?.videoSize
        val (width, height) = softwarePlayer?.videoDimensions() ?: ((size?.width ?: 0) to (size?.height ?: 0))
        val fps = currentVideoFrameRate()
        val technical = listOfNotNull(togetherChip(), fileChip(video, height)) +
            playbackTechnical(video, player?.audioFormat, width, height, fps, VideoDisplay.screenLabel(this))
        return PlaybackInfo(intent.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "Reproducción" },
            if (kind == "live") "En directo" else "", technical, frameRate = fps)
    }

    private fun updateVideoDisplay() {
        val size = player?.videoSize
        val aspect = if (size != null && size.height > 0) size.width * size.pixelWidthHeightRatio / size.height else 0f
        VideoDisplay.apply(playerView, aspect)
        softwarePlayer?.updateDisplay()
        if (matchWhileVisible) {
            val (videoWidth, videoHeight) = softwarePlayer?.videoDimensions() ?: ((size?.width ?: 0) to (size?.height ?: 0))
            refreshMatch.update(window, currentVideoFrameRate(), videoWidth, videoHeight)
        }
        if (::controls.isInitialized) controls.refresh()
    }

    /**
     * Barra de reproducción de abajo: película, serie o PPV con su raya de tiempo; en un
     * canal, EN VIVO. Funciona igual con el reproductor normal y con VLC.
     */
    private val movieTimeline = object : PlaybackTimeline {
        override fun positionMs(): Long = activePlayer()?.currentPosition ?: 0L
        override fun durationMs(): Long = activePlayer()?.duration?.takeIf { it > 0 } ?: 0L
        override fun seekTo(positionMs: Long) { activePlayer()?.seekTo(positionMs.coerceAtLeast(0L)) }
        override fun togglePlay() { activePlayer()?.let { if (it.playWhenReady) it.pause() else it.play() } }
        override fun isPlaying(): Boolean = activePlayer()?.playWhenReady == true
        override fun isLive(): Boolean = kind == "live" && url !in movieChannels
        override fun canSeek(): Boolean = !isLive() && durationMs() > 0 && activePlayer()?.isCurrentMediaItemSeekable != false
        override fun atLiveEdge(): Boolean = activePlayer()?.playWhenReady == true
        override fun goLive() {
            reloadLive()
        }
    }

    // --- Saltar intro, resumen y créditos ---

    /** Un episodio nuevo: se olvidan los tiempos del anterior y se buscan los de este cuando se sepa su duración. */
    private fun startSkip(episode: SkipSegments.Episode?) {
        handler.removeCallbacks(skipTick)
        hideSkip()
        skipEpisode = episode
        skipFound = emptyList()
        skipSource = ""
        skipRequested = false
        skipDone.clear()
        skipMarks = episode?.let { SkipSegments.marks(this, it.series) } ?: SkipSegments.Marks()
        if (episode != null) handler.postDelayed(skipTick, 1_000L)
    }

    private fun skipDuration(p: Player): Long = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L

    private fun skipSegments(duration: Long): List<SkipSegments.Segment> =
        SkipSegments.withMarks(skipFound, skipMarks, duration)

    @Suppress("DEPRECATION")
    private fun hasNextEpisode(): Boolean = (isHost || solo) &&
        intent.getParcelableArrayListExtra<Bundle>(EXTRA_QUEUE)?.firstOrNull() != null

    private fun checkSkip() {
        val episode = skipEpisode ?: return
        val p = activePlayer() ?: return
        val duration = skipDuration(p)
        if (!skipRequested && (duration > 0 || p.playbackState == Player.STATE_READY)) {
            skipRequested = true
            SkipSegments.fetch(this, episode, duration) { list, source ->
                if (skipEpisode !== episode || isFinishing || isDestroyed) return@fetch
                skipFound = list
                skipSource = source
                if (list.isNotEmpty()) PlaybackLog.add(this, "Tiempos de rellenos ($source): " + list.joinToString(" · ") {
                    it.type.key + " " + SkipSegments.clock(it.startMs) + "–" + (it.endMs?.let(SkipSegments::clock) ?: "final")
                })
            }
        }
        val mode = SkipSegments.mode(this)
        if (mode == SkipSegments.Mode.OFF || errorBox.visibility == View.VISIBLE) { hideSkip(); return }
        val position = p.currentPosition
        val active = skipSegments(duration).firstOrNull { segment ->
            val end = segment.endMs ?: duration.takeIf { it > 0 } ?: Long.MAX_VALUE
            // A segundo y medio del final ya no vale la pena saltar.
            position >= segment.startMs && position < end - 1_500L
        }?.takeUnless { it.atEnd && it.endMs == null && !hasNextEpisode() }
        if (active == null) { hideSkip(); return }
        if (mode == SkipSegments.Mode.AUTO && p.isPlaying && active !in skipDone) {
            skipDone.add(active)
            performSkip(active, auto = true)
            return
        }
        showSkip(active)
    }

    private fun showSkip(segment: SkipSegments.Segment) {
        skipShown = segment
        // Con la barra abierta o en imagen en imagen no estorba (OK es de la barra).
        if (controls.isOpen() || (android.os.Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode)) {
            skipButton.visibility = View.GONE
            return
        }
        skipButton.text = if (segment.atEnd && hasNextEpisode()) "Siguiente episodio" else segment.type.button
        if (skipButton.visibility != View.VISIBLE) {
            skipButton.alpha = 0f
            skipButton.visibility = View.VISIBLE
            skipButton.animate().alpha(1f).setDuration(180L).start()
        }
    }

    private fun hideSkip() {
        skipShown = null
        if (::skipButton.isInitialized && skipButton.visibility != View.GONE) skipButton.visibility = View.GONE
    }

    /** Salta el segmento: al final del episodio (créditos, avance) pasa al siguiente si lo hay. */
    private fun performSkip(segment: SkipSegments.Segment, auto: Boolean = false) {
        val p = activePlayer() ?: return
        skipDone.add(segment)
        hideSkip()
        if (segment.atEnd && hasNextEpisode()) {
            PlaybackLog.add(this, (if (auto) "Siguiente episodio solo" else "Siguiente episodio") + " (créditos)")
            saveWatch(ended = true)
            advanceEpisode(manual = true)
            return
        }
        val end = segment.endMs ?: return
        p.seekTo(end)
        if (auto) Toast.makeText(this, segment.type.done, Toast.LENGTH_SHORT).show()
    }

    /** Opciones ▸ Saltar intro: el modo y las marcas a mano de esta serie. */
    private fun showSkipSettings() {
        val episode = skipEpisode ?: return
        val p = activePlayer()
        val position = p?.currentPosition ?: 0L
        val duration = p?.let(::skipDuration) ?: 0L
        val found = skipSegments(duration)
        val status = when {
            found.isEmpty() && !skipRequested -> "Buscando los tiempos de este episodio…"
            found.isEmpty() -> "No hay tiempos en internet para este episodio. Puedes marcarlos aquí y quedan para toda la serie."
            else -> found.joinToString("\n") { segment ->
                segment.type.button.removePrefix("Saltar ").replaceFirstChar { it.uppercase() } + ": " +
                    SkipSegments.clock(segment.startMs) + " – " + (segment.endMs?.let(SkipSegments::clock) ?: "final")
            } + if (skipMarks.empty) "\nDe: " + skipSource.ifBlank { "internet" } else "\nCon tus marcas"
        }
        Ui.showMenu(this, "Saltar intro y créditos") { dialog, rows ->
            rows.addView(Ui.label(this, status, 14f, Ui.muted), Ui.matchWrap(bottomMargin = Ui.dp(this, 12)))
            fun action(label: String, icon: Int, run: () -> Unit) {
                rows.addView(Ui.button(this, label, Ui.Style.SECONDARY, icon) {
                    dialog.dismiss()
                    run()
                }.apply { gravity = Gravity.CENTER_VERTICAL or Gravity.START }, Ui.matchWrap(bottomMargin = Ui.dp(this, 6)))
            }
            fun save(marks: SkipSegments.Marks, message: String) {
                skipMarks = marks
                SkipSegments.saveMarks(this, episode.series, marks)
                skipDone.clear()
                toast(message)
            }
            action(SkipSegments.label(this), R.drawable.catalog_skip) { SkipSegments.choose(this) { checkSkip() } }
            val now = SkipSegments.clock(position)
            action("La intro empieza aquí ($now)", R.drawable.tp_bar_restart) {
                save(skipMarks.copy(introStart = position,
                    introEnd = skipMarks.introEnd?.takeIf { it > position }), "Inicio de la intro: $now")
            }
            action("La intro termina aquí ($now)", R.drawable.tp_bar_next) {
                val start = skipMarks.introStart?.takeIf { it < position } ?: 0L
                save(skipMarks.copy(introStart = start, introEnd = position),
                    "Intro de ${SkipSegments.clock(start)} a $now: se salta en todos los episodios")
            }
            if (duration > position) action("Los créditos empiezan aquí ($now)", R.drawable.tp_bar_forward) {
                save(skipMarks.copy(creditsFromEnd = duration - position),
                    "Créditos: los últimos ${SkipSegments.clock(duration - position)} de cada episodio")
            }
            if (!skipMarks.empty || skipMarks.introStart != null) action("Borrar mis marcas de esta serie", R.drawable.catalog_delete) {
                save(SkipSegments.Marks(), "Marcas borradas: se usan los tiempos de internet")
            }
        }
    }

    /** Atrás apretado (para imagen en imagen si se mantiene unos 2 segundos). */
    private var heldBack: KeyEvent? = null
    private var backOpenedPip = false
    private val backPip = Runnable {
        heldBack = null
        backOpenedPip = enterPip()
    }

    private fun canPip(): Boolean =
        android.os.Build.VERSION.SDK_INT >= 26 && !isFinishing && !isInPictureInPictureMode &&
            errorBox.visibility != View.VISIBLE && activePlayer() != null &&
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)

    /** Imagen en imagen: el video sigue en una ventanita y se puede usar la Shield para otra cosa. */
    private fun enterPip(): Boolean {
        if (!canPip()) return false
        controls.close()
        val size = player?.videoSize
        val width = (size?.width ?: 0) * (size?.pixelWidthHeightRatio ?: 1f)
        val height = size?.height ?: 0
        val ratio = if (height > 0 && width > 0f && width.isFinite()) (width / height).coerceIn(1f / 2.39f, 2.39f) else 16f / 9f
        val params = android.app.PictureInPictureParams.Builder()
            .setAspectRatio(android.util.Rational((ratio * 1000).toInt().coerceIn(419, 2390), 1000))
        if (android.os.Build.VERSION.SDK_INT >= 33) params.setTitle(intent.getStringExtra(EXTRA_TITLE).orEmpty())
        // Antes de pedirlo: la pantalla del catálogo que queda debajo se manda atrás al aparecer.
        PipReturn.entering(this)
        val entered = runCatching { enterPictureInPictureMode(params.build()) }.getOrDefault(false)
        if (entered) PlaybackLog.add(this, "Imagen en imagen") else PipReturn.changed(this, false)
        return entered
    }

    private var ownLaunchAt = 0L

    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        // Abrir otra pantalla de la app no es «irse al inicio»: no debe pasar a imagen en imagen.
        ownLaunchAt = SystemClock.elapsedRealtime()
        super.startActivityForResult(intent, requestCode, options)
    }

    /**
     * Teléfono o tableta: al tocar Inicio (o cambiar de app) con algo reproduciéndose, el video pasa a una ventanita
     * (imagen en imagen), como YouTube. En pausa no: la app se va al fondo como siempre.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Ui.isTelevision(this) || SystemClock.elapsedRealtime() - ownLaunchAt < 1500) return
        if (activePlayer()?.playWhenReady == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PipReturn.changed(this, isInPictureInPictureMode)
        if (isInPictureInPictureMode) {
            controls.close()
            hideSkip()
        }
        else PlaybackLog.add(this, "Salió de imagen en imagen")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Atrás apretado unos 2 segundos: imagen en imagen (PiP). Si se suelta antes es un Atrás normal
        // (se hace al soltar, como siempre).
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> if (event.repeatCount > 0) {
                    if (heldBack != null || backOpenedPip) return true
                } else {
                    backOpenedPip = false
                    handler.removeCallbacks(backPip)
                    heldBack = null
                    if (canPip()) {
                        heldBack = KeyEvent(event)
                        handler.postDelayed(backPip, 2_000L)
                        return true
                    }
                }
                KeyEvent.ACTION_UP -> {
                    handler.removeCallbacks(backPip)
                    if (backOpenedPip) {
                        backOpenedPip = false
                        return true
                    }
                    val down = heldBack
                    if (down != null) {
                        heldBack = null
                        handleKey(down)
                        return handleKey(event)
                    }
                }
            }
        }
        return handleKey(event)
    }

    private fun handleKey(event: KeyEvent): Boolean {
        // Episodios o Enlaces abiertos: el control se mueve por el panel; Atrás lo cierra.
        if (::sidePanel.isInitialized && sidePanel.isOpen) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) sidePanel.close()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        // Con el cuadro de error visible, el control remoto maneja sus botones y no el reproductor.
        if (errorBox.visibility == View.VISIBLE) return super.dispatchKeyEvent(event)
        // Atajo en el control remoto: botón de menú (o rojo) = sincronizar.
        if (!solo && (event.keyCode == KeyEvent.KEYCODE_MENU || event.keyCode == KeyEvent.KEYCODE_PROG_RED)) {
            if (event.action == KeyEvent.ACTION_UP) requestSync()
            return true
        }
        if (event.keyCode == KeyEvent.KEYCODE_SETTINGS) {
            if (event.action == KeyEvent.ACTION_UP) VideoDisplay.choose(this, ::updateVideoDisplay)
            return true
        }
        // «Saltar intro» a la vista y la barra cerrada: OK lo aprieta.
        if (skipShown != null && skipButton.visibility == View.VISIBLE && !controls.isOpen() && event.keyCode in okKeys) {
            if (event.action == KeyEvent.ACTION_UP) skipShown?.let { performSkip(it) }
            return true
        }
        // OK: barra de reproducción. ▼: ajustes. ◀ ▶: atrasa/adelanta. Atrás: cierra la barra o sale.
        if (controls.handle(event)) return true
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && !controls.isOpen()) {
            closeReason = "botón Atrás del control"
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onResume() {
        super.onResume()
        matchWhileVisible = true
        backgroundPlayback?.update()
        updateVideoDisplay()
    }

    override fun onPause() {
        matchWhileVisible = false
        refreshMatch.clear()
        saveWatch()
        super.onPause()
        // Al salir del reproductor se suelta la conexión con el proveedor YA, no cuando Android
        // destruya la pantalla (puede tardar segundos y la cuenta veía dos conexiones).
        if (isFinishing) releasePlayback()
    }

    private var playbackReleased = false

    private fun releasePlayback() {
        if (playbackReleased) return
        playbackReleased = true
        if (scrobbled != null && scrobbled != "stop") sendScrobble("stop")
        handler.removeCallbacksAndMessages(null)
        if (RoomSession.playbackListener === roomPlayback) RoomSession.playbackListener = null
        backgroundPlayback?.attach(null)
        StreamGuard.closed(this)
        // También cuenta la película por VLC: el proveedor la sigue viendo conectada unos segundos.
        releaseSoftwarePlayer()
        player?.release()
        player = null
        // Si Android rehace la pantalla (p. ej. al cambiar la TV de resolución), no se corta lo compartido.
        if (isHost && !isChangingConfigurations) {
            RoomSession.currentShare = null
            RoomSession.client?.sendRelayStop()
            val appContext = applicationContext
            thread {
                // Si ya se eligió otro contenido, ese relay sigue en uso.
                if (RoomSession.currentShare == null) RoomSession.relay(appContext).stopSharing()
            }
        }
    }

    /** Cuándo dejó de verse la pantalla del reproductor (0 = está a la vista). */
    private var stoppedAt = 0L
    private var pausedInBackgroundSince = 0L

    override fun onStart() {
        super.onStart()
        stoppedAt = 0L
        pausedInBackgroundSince = 0L
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) PlaybackLog.add(this, "El reproductor quedó fuera de la pantalla (onStop)")
        stoppedAt = SystemClock.elapsedRealtime()
        if (isChangingConfigurations || isFinishing) return
        // Con la pantalla apagada se sigue reproduciendo (y compartiendo) como si nada.
        val screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        if (!screenOn) return
        // En el teléfono la película sigue sonando en segundo plano, así que solo se hace en la TV.
        if (solo && backgroundPlayback == null) {
            // Viendo solo: al irse a otra app (botón Home, otra app) se cierra la película del todo
            // para soltar la conexión con el proveedor. Si quedaba pausada pero abierta, al entrar a
            // un canal la cuenta veía dos conexiones (error 404). El punto se guarda y se puede seguir.
            closeReason = "se fue a otra app o al inicio de la Shield"
            saveWatch()
            releasePlayback()
            finish()
            return
        }
        // Si se fue a otra app, se pausa solo aquí; el otro equipo sigue viendo.
        ignoreLocal(1500)
        activePlayer()?.playWhenReady = false
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) Ui.hideSystemBars(this)
    }

    // --- Teléfono: pantalla chica o completa ---
    private var phoneMini = false
    private var miniInfo: View? = null
    private var miniList: LinearLayout? = null
    private var miniFilledFor: String? = null

    /** Lo de debajo del video en la pantalla chica: el título y los episodios de la serie (toca uno para verlo). */
    private fun fillMini() {
        val list = miniList ?: return
        val key = (watchKey ?: "") + "|" + intent.getStringExtra(EXTRA_TITLE)
        if (miniFilledFor == key) return
        miniFilledFor = key
        list.removeAllViews()
        val shownTitle = intent.getStringExtra(EXTRA_TITLE).orEmpty().replace(Regex("""[(\[][^)\]]{1,14}[)\]]"""), " ")
            .replace(Regex("\\s+"), " ").trim().ifBlank { intent.getStringExtra(EXTRA_TITLE).orEmpty() }
        list.addView(Ui.label(this, shownTitle, 18f, bold = true), Ui.matchWrap(bottomMargin = Ui.dp(this, 4)))
        list.addView(Ui.label(this, "Doble toque en el video: pantalla completa", 12.5f, Ui.hint), Ui.matchWrap(bottomMargin = Ui.dp(this, 14)))
        val guide = currentGuide()
        if (guide != null) {
            list.addView(Ui.sectionTitle(this, "Episodios"), Ui.matchWrap())
            val history = WatchHistory(this)
            for (episode in guide.sortedWith(compareBy({ it.getInt("season_num", 0) }, { it.getInt("episode_num", 0) }))) {
                val episodeKey = episode.getString("watch_key")
                val playing = episodeKey != null && episodeKey == watchKey
                val row = sidePanel.episodeRow(episode, playing, episodeKey?.let { history.get(it) })
                row.setOnClickListener { if (!playing) playFromGuide(guide, episode) }
                list.addView(row, Ui.matchWrap(bottomMargin = Ui.dp(this, 8)))
            }
        }
    }

    /**
     * [mini]: el video arriba a lo ancho (16:9) con el teléfono derecho y el nombre debajo; si no, la pantalla
     * completa con el teléfono acostado.
     */
    private fun setPhoneMini(mini: Boolean) {
        if (Ui.isTelevision(this) || isInPictureInPictureMode) return
        phoneMini = mini
        requestedOrientation = if (mini) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        playerView.post { layoutPhone() }
    }

    private fun layoutPhone() {
        if (Ui.isTelevision(this)) return
        val metrics = resources.displayMetrics
        val top = Ui.dp(this, 28)
        if (phoneMini) {
            val width = minOf(metrics.widthPixels, metrics.heightPixels)
            val height = width * 9 / 16
            playerView.layoutParams = FrameLayout.LayoutParams(-1, height, Gravity.TOP).apply { topMargin = top }
            softwareSurface.layoutParams = FrameLayout.LayoutParams(-1, height, Gravity.TOP).apply { topMargin = top }
            miniInfo?.layoutParams = FrameLayout.LayoutParams(-1, -1, Gravity.TOP).apply { topMargin = top + height + Ui.dp(this@PlayerActivity, 12) }
            miniInfo?.visibility = View.VISIBLE
            // Los controles van encima del video, no tapando la lista de abajo.
            val screen = (playerView.parent as? View)?.height?.takeIf { it > 0 } ?: metrics.heightPixels
            controls.bottomInset((screen - top - height).coerceAtLeast(0))
            fillMini()
        } else {
            playerView.layoutParams = FrameLayout.LayoutParams(-1, -1)
            softwareSurface.layoutParams = FrameLayout.LayoutParams(-1, -1)
            miniInfo?.visibility = View.GONE
            controls.bottomInset(0)
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!Ui.isTelevision(this)) playerView.post { layoutPhone() }
    }

    private var loggedFormats = ""

    /** Anota en reproductor.txt el video y el audio que se están reproduciendo (solo cuando cambian). */
    private fun logFormats(video: Format?, audio: Format?) {
        val summary = listOfNotNull(
            video?.let {
                listOfNotNull("video", it.sampleMimeType?.substringAfter('/'), it.codecs,
                    if (it.width > 0) "${it.width}x${it.height}" else null, playbackFpsLabel(it.frameRate),
                    if (it.bitrate > 0) "${it.bitrate / 1000} kbps" else null).joinToString(" ")
            },
            audio?.let {
                listOfNotNull("audio", it.sampleMimeType?.substringAfter('/'),
                    if (it.channelCount > 0) "${it.channelCount} canales" else null).joinToString(" ")
            }
        ).joinToString(" · ")
        if (summary.isBlank() || summary == loggedFormats) return
        loggedFormats = summary
        PlaybackLog.add(this, "Pistas: $summary")
    }

    /** Por qué se cerró el reproductor: queda en reproductor.txt (se saca con revisar-error.bat). */
    internal var closeReason = ""

    override fun finish() {
        if (!isFinishing) {
            PlaybackLog.add(this, "Se cierra el reproductor: " + closeReason.ifBlank { "otro motivo" } +
                " · " + PlaybackLog.memory())
        }
        super.finish()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            PlaybackLog.add(this, "Android pide memoria (nivel $level) · " + PlaybackLog.memory())
        }
    }

    override fun onDestroy() {
        PipReturn.changed(this, false)
        if (pipReturnOnly) {
            super.onDestroy()
            return
        }
        manualPrompt?.dismiss()
        stopWatchingScreen()
        controls.close()
        releasePlayback()
        super.onDestroy()
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    companion object {
        private var current: java.lang.ref.WeakReference<PlayerActivity>? = null

        /** Se apretó «Otros enlaces» (el catálogo abre la lista de enlaces de ese título al volver). */
        private var otherLinksRequested = false

        fun takeOtherLinksRequest(): Boolean = otherLinksRequested.also { otherLinksRequested = false }

        class Rescue(val name: String, val year: Int?, val type: String, val id: String?, val positionMs: Long,
                     val tried: HashSet<String>, val at: Long)
        private var rescue: Rescue? = null
        fun takeRescue(): Rescue? = rescue.also { rescue = null }?.takeIf { SystemClock.elapsedRealtime() - it.at < 30_000L }
        /** Lo deja la pantalla de atrás justo antes de abrir el reemplazo: el reproductor nuevo lo recoge. */
        @Volatile var rescueChain: Rescue? = null

        /** Canales que en realidad son películas (Pay Per View): no llevan la marca "EN VIVO". */
        /** Las películas del grupo desde donde pusiste la película (para la pantalla chica del teléfono). */
        @Volatile var related: List<org.json.JSONObject> = emptyList()
        @Volatile var relatedTitle: String = ""
        val movieChannels: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

        /**
         * Cierra la película o serie que siguiera abierta (por ejemplo, sonando en segundo plano en
         * el teléfono) antes de abrir un canal u otra cosa, para no tener dos conexiones con la cuenta.
         * Devuelve true si había una abierta.
         */
        fun closeOpenPlayback(reason: String = "se abrió otro canal o película"): Boolean {
            val open = current?.get() ?: return false
            current = null
            if (open.isFinishing || open.isDestroyed) return false
            open.closeReason = reason
            open.saveWatch()
            open.releasePlayback()
            open.finish()
            return true
        }

        private const val EXTRA_URL = "url"
        private const val EXTRA_KIND = "kind"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_HOST = "host"
        private const val EXTRA_START = "start_ms"
        private const val EXTRA_SOLO = "solo"
        private const val EXTRA_QUEUE = "episode_queue"
        private const val EXTRA_WATCH_KEY = "watch_key"
        private const val EXTRA_DIRECT_FALLBACK = "direct_fallback"
        private const val EXTRA_WAIT_FOR_PLAY = "wait_for_play"
        private const val EXTRA_SKIP = "skip_episode"
        private const val EXTRA_HEADERS = "stream_headers"
        private const val EXTRA_ADDON = "addon_stream"
        private const val EXTRA_LINKS_TYPE = "links_type"
        private const val EXTRA_LINKS_ID = "links_id"

        /**
         * Los episodios de la serie que se está viendo (todas las temporadas, en orden), para el botón «Episodios».
         * Cada uno como los de la cola (url, title, watch_key, season_num, episode_num) y, para mostrarlo,
         * name, plot, image y date.
         */
        @Volatile var episodeGuide: ArrayList<Bundle>? = null
        private val okKeys = setOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A)
        const val PREF_AUTO_NEXT = "auto_next_episode"
        const val PREF_AUTO_AUDIO = "auto_spanish_audio"
        const val PREF_AUTO_SUBTITLES = "auto_spanish_subtitles"
        private val HLS_CONTENT_TYPES = setOf(
            "application/vnd.apple.mpegurl", "application/x-mpegurl", "application/mpegurl",
            "audio/mpegurl", "audio/x-mpegurl",
        )
        private const val TICK_MS = 10_000L
        /** Tiempo aproximado que tarda un mensaje en llegar al otro equipo. */
        private const val TRANSIT_MS = 150L

        fun intent(
            context: Context,
            url: String,
            kind: String,
            title: String,
            host: Boolean,
            startMs: Long = 0L,
            solo: Boolean = false,
            queue: ArrayList<Bundle> = arrayListOf(),
            watchKey: String? = null,
            directFallback: String? = null,
            manualOnly: Boolean = false,
            shared: Boolean = false,
            skip: Bundle? = null,
            headers: Bundle? = null,
            addon: Boolean = false,
            linksType: String? = null,
            linksId: String? = null
        ): Intent =
            Intent(context, PlayerActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_URL, url)
                .putExtra(EXTRA_KIND, kind)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_HOST, host)
                .putExtra(EXTRA_START, startMs)
                .putExtra(EXTRA_SOLO, solo)
                .putParcelableArrayListExtra(EXTRA_QUEUE, queue)
                .putExtra(EXTRA_WATCH_KEY, watchKey)
                .putExtra(EXTRA_DIRECT_FALLBACK, directFallback)
                .putExtra(PlaybackAccess.MANUAL_EXTRA, manualOnly || PlaybackAccess.marked(title))
                .putExtra(EXTRA_WAIT_FOR_PLAY, shared)
                .putExtra(EXTRA_SKIP, skip)
                .putExtra(EXTRA_HEADERS, headers)
                .putExtra(EXTRA_ADDON, addon)
                .putExtra(EXTRA_LINKS_TYPE, linksType)
                .putExtra(EXTRA_LINKS_ID, linksId)
    }
}
