package com.juntosplay.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Window
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

private val remuxTitle = Regex("""\b(?:remux|rmx|grm)\b""", RegexOption.IGNORE_CASE)

internal fun isRemuxTitle(title: String): Boolean = remuxTitle.containsMatchIn(title)

internal object VideoDisplay {
    private const val MATCH_FRAME_RATE = "video_match_refresh"
    private const val MATCH_DELAY = "video_match_refresh_delay_sec"
    const val ASPECT = "video_aspect"
    private val labels = arrayOf("Automático", "Original", "16:9", "Zoom")

    fun label(context: android.content.Context): String =
        labels.getOrElse(RoomSession.prefs(context).getInt(ASPECT, 0)) { labels[0] }

    fun apply(view: PlayerView, aspect: Float) {
        val mode = RoomSession.prefs(view.context).getInt(ASPECT, 0)
        view.resizeMode = when (mode) {
            2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            3 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            0 -> if (aspect in 1.28f..1.39f) AspectRatioFrameLayout.RESIZE_MODE_FILL
                else AspectRatioFrameLayout.RESIZE_MODE_FIT
            else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
    }

    fun choose(activity: Activity, update: () -> Unit) {
        val prefs = RoomSession.prefs(activity)
        AlertDialog.Builder(activity).setTitle("Formato de imagen")
            .setSingleChoiceItems(labels, prefs.getInt(ASPECT, 0)) { dialog, index ->
                prefs.edit().putInt(ASPECT, index).apply()
                update()
                dialog.dismiss()
            }.setNegativeButton("Cerrar", null).create().also(Ui::showDialog)
    }

    // 0 = cambia resolución y frecuencia según el video, 1 = solo la frecuencia, 2 = lo decide el televisor.
    private const val MATCH_MODE = "video_match_mode"
    /**
     * La 1.4.0 puso a todos en «Solo FPS» para que la app no cambiara de tamaño (Google TV Streamer), pero así la TV se
     * quedaba siempre en 4K y en los canales de 29.97/59.94 FPS no se veía ningún cambio (ni en el Shield). Desde la
     * 1.4.3 vuelve «Resolución y FPS» y la app ya no cambia de tamaño: la TV nunca baja de la resolución de la
     * interfaz (1920×1080 en el Streamer). Era bajar a 720p lo que agrandaba los íconos. En el NVIDIA Shield eso no
     * pasa, así que ahí la TV baja a 720p y 480p como antes (1.4.4).
     */
    private const val MATCH_MODE_REVIEWED = "video_match_mode_v2"
    private const val MATCH_MODE_V3 = "video_match_mode_v3"
    private val matchLabels = arrayOf("Resolución y FPS del video (recomendado)",
        "Solo FPS del video (la TV no cambia de resolución)", "Del televisor")

    private fun matchMode(context: android.content.Context): Int {
        val prefs = RoomSession.prefs(context)
        if (!prefs.getBoolean(MATCH_MODE_V3, false)) {
            // Una sola vez: quien quedó en «Solo FPS» por la 1.4.0 vuelve a «Resolución y FPS»; quien lo tenía
            // apagado («Del televisor»), lo sigue teniendo apagado.
            val before = if (prefs.contains(MATCH_MODE)) prefs.getInt(MATCH_MODE, 0)
                else if (prefs.getBoolean(MATCH_FRAME_RATE, true)) 0 else 2
            prefs.edit().putInt(MATCH_MODE, if (before == 2) 2 else 0).putBoolean(MATCH_MODE_REVIEWED, true)
                .putBoolean(MATCH_MODE_V3, true).apply()
        }
        return prefs.getInt(MATCH_MODE, 0).coerceIn(matchLabels.indices)
    }

    private fun matchFrameRate(context: android.content.Context) = matchMode(context) != 2
    private fun matchResolution(context: android.content.Context) = matchMode(context) == 0

    fun frequencyLabel(context: android.content.Context): String = matchLabels[matchMode(context)]

    fun chooseFrequency(activity: Activity, update: () -> Unit) {
        val prefs = RoomSession.prefs(activity)
        val limit = systemSwitchLimit(activity)
        val builder = AlertDialog.Builder(activity).setTitle("Resolución y frecuencia de pantalla")
            .setSingleChoiceItems(matchLabels, matchMode(activity)) { dialog, index ->
                prefs.edit().putInt(MATCH_MODE, index).putBoolean(MATCH_FRAME_RATE, index != 2).apply()
                update()
                dialog.dismiss()
                // La última vez Google TV no dejó cambiar la TV: se explica cómo activarlo.
                if (index != 2 && limit != null && lastSwitchFailed) AlertDialog.Builder(activity)
                    .setTitle("Falta un ajuste de Google TV").setMessage(hintText(limit))
                    .setPositiveButton("Abrir Ajustes") { _, _ -> openDisplaySettings(activity) }
                    .setNegativeButton("Entendido", null).create().also(Ui::showDialog)
            }
            .setPositiveButton("Estado de la TV") { _, _ -> showScreenState(activity) }
            .setNeutralButton("Videos de 25/30 FPS") { _, _ -> chooseLowRates(activity, update) }
            .setNegativeButton("Cerrar", null)
        if (limit != null) builder.setTitle("Resolución y frecuencia (Google TV: «$limit»)")
        Ui.showDialog(builder.create())
    }

    // 25, 29.97 y 30 FPS: la TV a 50, 59.94 y 60 Hz (cada cuadro sale dos veces, igual de fluido;
    // así lo hacen Plex, Apple TV y el Fire TV) o a los mismos 25/30 Hz que los FPS.
    private const val DOUBLE_LOW_FPS = "video_double_low_fps"
    private val lowRateLabels = arrayOf("TV a 50 / 60 Hz (recomendado)", "TV a 25 / 30 Hz (igual que los FPS)")

    private fun doubleLowRates(context: Context) = RoomSession.prefs(context).getBoolean(DOUBLE_LOW_FPS, true)

    fun lowRateLabel(context: Context): String =
        "Videos de 25/30 FPS · " + if (doubleLowRates(context)) "TV a 50/60 Hz" else "TV a 25/30 Hz"

    fun chooseLowRates(activity: Activity, update: () -> Unit) {
        val dialog = AlertDialog.Builder(activity).setTitle("Videos de 25 y 30 FPS")
            .setSingleChoiceItems(lowRateLabels, if (doubleLowRates(activity)) 0 else 1) { popup, index ->
                RoomSession.prefs(activity).edit().putBoolean(DOUBLE_LOW_FPS, index == 0).apply()
                popup.dismiss()
                update()
            }.setNegativeButton("Cerrar", null).create()
        Ui.showDialog(dialog)
    }

    /** Cuántas veces sale cada cuadro en la TV (1 = la TV a los mismos Hz que los FPS del video). */
    private fun preferredMultiple(context: Context, fps: Float): Int = when {
        fps in 23.5f..24.5f -> 1                  // cine: 23.976 → 23.976 Hz, 24 → 24 Hz
        !doubleLowRates(context) -> 1
        fps < 23.5f -> ceil(47.5f / fps).toInt()  // 15 → 60 Hz, 12.5 → 50 Hz, 20 → 60 Hz
        fps < 35f -> 2                            // 25 / 29.97 / 30 → 50 / 59.94 / 60 Hz
        else -> 1                                 // 50 / 59.94 / 60 → los mismos Hz
    }

    private var television: Boolean? = null

    /** Resolución y frecuencia a las que está la TV ahora mismo ("TV 4K · 59.94 Hz", "TV 1080p · 23.98 Hz"). */
    @Suppress("DEPRECATION")
    fun screenLabel(activity: Activity): String? {
        val tv = television ?: Ui.isTelevision(activity).also { television = it }
        if (!tv) return null
        val mode = activity.windowManager.defaultDisplay?.mode ?: return null
        val hz = mode.refreshRate
        if (!hz.isFinite() || hz < 1f) return null
        return "TV " + sizeName(mode.physicalWidth, mode.physicalHeight) + " · " + hzText(hz) + " Hz"
    }

    private fun hzText(hz: Float): String = String.format(Locale.US, "%.2f", hz).trimEnd('0').trimEnd('.')

    /** 3840×2160 → «4K», 1920×1080 → «1080p», 1280×720 → «720p». */
    private fun sizeName(width: Int, height: Int): String {
        val long = maxOf(width, height)
        val short = minOf(width, height)
        return when {
            long >= 3840 || short >= 2160 -> "4K"
            long >= 2560 || short >= 1440 -> "1440p"
            long >= 1920 || short >= 1080 -> "1080p"
            long >= 1280 || short >= 720 -> "720p"
            short > 0 -> "${short}p"
            else -> "?"
        }
    }

    /**
     * Avisa cuando la TV cambia de modo (al ajustar la frecuencia a los FPS), para actualizar la
     * etiqueta "TV … Hz". Devuelve la función que deja de avisar.
     */
    fun watchScreen(context: Context, changed: () -> Unit): () -> Unit {
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager ?: return {}
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (displayId == Display.DEFAULT_DISPLAY) changed()
            }
        }
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        return { manager.unregisterDisplayListener(listener) }
    }

    private val delayLabels = arrayOf("Sin espera", "1 segundo", "2 segundos", "3 segundos", "4 segundos", "5 segundos")

    private fun delaySeconds(context: android.content.Context): Int =
        RoomSession.prefs(context).getInt(MATCH_DELAY, 0).coerceIn(delayLabels.indices)

    fun delayLabel(context: android.content.Context): String = delayLabels[delaySeconds(context)]

    fun chooseDelay(activity: Activity, update: () -> Unit) {
        val dialog = AlertDialog.Builder(activity).setTitle("Espera para ajustar los FPS")
            .setSingleChoiceItems(delayLabels, delaySeconds(activity)) { popup, index ->
                RoomSession.prefs(activity).edit().putInt(MATCH_DELAY, index).apply()
                popup.dismiss()
                update()
            }.setNegativeButton("Cerrar", null).create()
        Ui.showDialog(dialog)
    }

    class RefreshMatch {
        private val handler = Handler(Looper.getMainLooper())
        private var target: Window? = null
        private var rate = 0f
        private var width = 0
        private var height = 0
        private var delay = 0
        private var doubled = true
        private val apply = Runnable { target?.let { match(it, rate, width, height); mirror(it) } }
        /**
         * Las otras ventanas por las que pasó este mismo video (la guía mientras se ve en pantalla completa): se les
         * deja el mismo modo de la TV. Si no, al volver con Atrás la ventana de la guía quedaba un instante sin modo
         * pedido, la TV volvía al normal y enseguida otra vez al del video (el parpadeo de más).
         */
        private val others = ArrayList<java.lang.ref.WeakReference<Window>>()
        private fun mirror(from: Window) {
            val id = from.attributes.preferredDisplayModeId
            others.removeAll { it.get() == null }
            for (ref in others) {
                val window = ref.get() ?: continue
                if (window === from) continue
                val params = window.attributes
                if (params.preferredDisplayModeId != id) {
                    params.preferredDisplayModeId = id
                    window.attributes = params
                }
            }
        }
        /** Los FPS llegan antes que el tamaño del video: se espera un poco para cambiar todo de una vez. */
        private var sizeWaitSince = 0L
        private var waitingWindow: Window? = null
        private var waitingFps = 0f
        private val sizeTimeout = Runnable { waitingWindow?.let { update(it, waitingFps, sizeTimedOut = true) } }

        /** [videoWidth]/[videoHeight]: tamaño real del video, para que la TV use la misma resolución. */
        fun update(window: Window, fps: Float, videoWidth: Int = 0, videoHeight: Int = 0, sizeTimedOut: Boolean = false) {
            val context = window.context
            if (!matchFrameRate(context)) {
                clear()
                return
            }
            if (target != window) {
                // Al pasar de la guía a pantalla completa (o volver con Atrás) cambia la ventana,
                // pero el canal es el mismo: la nueva ventana hereda el modo de la TV que ya estaba
                // puesto, sin volver al modo normal ni recalcular. Así la pantalla no parpadea.
                val previous = target
                val keep = previous?.attributes?.preferredDisplayModeId ?: 0
                if (previous != null && others.none { it.get() === previous }) others.add(java.lang.ref.WeakReference(previous))
                target = window
                if (keep != 0) {
                    val params = window.attributes
                    if (params.preferredDisplayModeId != keep) {
                        params.preferredDisplayModeId = keep
                        window.attributes = params
                    }
                } else if (previous != null) {
                    newSource()
                }
            }
            val validFps = fps.isFinite() && fps in 10f..240f
            // Se espera a conocer los FPS para cambiar resolución y frecuencia en un solo paso
            // (cada cambio de modo deja la pantalla en negro un momento).
            if (!validFps) return
            // Con "Resolución y FPS del video", si todavía no se sabe el tamaño se espera hasta 2 s: así la TV
            // cambia resolución y Hz en un solo paso (antes pasaba a 4K 50 Hz y enseguida a 1080p 50 Hz).
            if (matchResolution(context) && (videoWidth <= 0 || videoHeight <= 0) && width == 0 && !sizeTimedOut) {
                val now = android.os.SystemClock.elapsedRealtime()
                if (sizeWaitSince == 0L) sizeWaitSince = now
                val left = 2_000L - (now - sizeWaitSince)
                if (left > 0) {
                    waitingWindow = window
                    waitingFps = fps
                    handler.removeCallbacks(sizeTimeout)
                    handler.postDelayed(sizeTimeout, left)
                    return
                }
            }
            handler.removeCallbacks(sizeTimeout)
            waitingWindow = null
            var (w, h) = if (matchResolution(context) && videoWidth > 0 && videoHeight > 0) videoWidth to videoHeight else 0 to 0
            // Las listas HLS cambian de calidad solas: dentro del mismo canal o video la TV solo
            // sube de resolución, nunca baja, para no quedarse en negro a cada rato.
            if (w > 0 && width.toLong() * height > w.toLong() * h) {
                w = width
                h = height
            }
            val nextDelay = delaySeconds(context)
            val nextDoubled = doubleLowRates(context)
            if (rate == fps && width == w && height == h && delay == nextDelay && doubled == nextDoubled) return
            val sizeChanged = width != w || height != h
            handler.removeCallbacks(apply)
            rate = fps
            width = w
            height = h
            delay = nextDelay
            doubled = nextDoubled
            // Si cambió el tamaño se espera un momento a que la calidad se estabilice.
            val waitMs = maxOf(delay * 1000L, if (sizeChanged && w > 0) 1500L else 0L)
            if (waitMs == 0L) apply.run() else handler.postDelayed(apply, waitMs)
        }

        fun newSource() {
            handler.removeCallbacks(apply)
            handler.removeCallbacks(sizeTimeout)
            sizeWaitSince = 0L
            waitingWindow = null
            rate = 0f
            width = 0
            height = 0
        }

        fun clear() {
            newSource()
            target?.let { match(it, 0f); mirror(it) }
            others.clear()
            target = null
        }
    }

    /** Modo de la TV (resolución y Hz) tal como lo da Android. */
    internal data class ScreenMode(val id: Int, val width: Int, val height: Int, val hz: Float)

    @Suppress("DEPRECATION")
    fun match(window: Window, fps: Float, videoWidth: Int = 0, videoHeight: Int = 0) {
        val display = window.windowManager.defaultDisplay
        fun screen(m: Display.Mode) = ScreenMode(m.modeId, m.physicalWidth, m.physicalHeight, m.refreshRate)
        val context = window.context
        val current = screen(display.mode)
        val (uiLong, uiShort) = if (smallModesOk) 0 to 0 else uiSize(display)
        val mode = if (matchFrameRate(context)) {
            pickScreenMode(display.supportedModes.map(::screen), current, fps,
                videoWidth.takeIf { matchResolution(context) } ?: 0, videoHeight.takeIf { matchResolution(context) } ?: 0,
                preferredMultiple(context, fps), uiLong, uiShort)
        } else null
        val params = window.attributes
        val id = mode?.id ?: 0
        if (params.preferredDisplayModeId != id) {
            params.preferredDisplayModeId = id
            window.attributes = params
        }
        pendingCheck?.let(checks::removeCallbacks)
        pendingCheck = null
        when {
            mode == null -> if (fps > 0f && matchFrameRate(context)) {
                lastSwitch = "la TV no tiene un modo para ${hzText(fps)} FPS (se quedó en ${describe(current)})"
            }
            mode.id == current.id -> lastSwitch = "${describe(mode)} para ${hzText(fps)} FPS (la TV ya estaba así)"
            else -> verifySwitch(window, mode, fps)
        }
    }

    private fun describe(m: ScreenMode) = "${m.width}×${m.height} · ${hzText(m.hz)} Hz"

    /**
     * En el NVIDIA Shield la app no cambia de tamaño aunque la TV baje a 720p: ahí la TV sigue al video como antes
     * (720p, 480p). En Google TV y los demás aparatos la TV no baja de la interfaz (1080p).
     */
    private val smallModesOk: Boolean by lazy { android.os.Build.MANUFACTURER.equals("NVIDIA", ignoreCase = true) }

    private var uiLong = 0
    private var uiShort = 0

    /**
     * Tamaño de la interfaz: 1920×1080 en el Streamer y el Shield aunque la TV esté en 4K. Si la TV baja de ahí
     * (720p), Android achica la pantalla de la app y todo se ve grande; por eso la TV nunca baja de este tamaño.
     */
    @Suppress("DEPRECATION")
    private fun uiSize(display: Display): Pair<Int, Int> {
        val point = android.graphics.Point()
        try {
            display.getRealSize(point)
        } catch (_: Exception) {
        }
        val long = maxOf(point.x, point.y)
        val short = minOf(point.x, point.y)
        if (long.toLong() * short > uiLong.toLong() * uiShort) {
            uiLong = long
            uiShort = short
        }
        return uiLong to uiShort
    }

    private val checks = Handler(Looper.getMainLooper())
    private var pendingCheck: Runnable? = null
    /** Lo que pasó con el último cambio de modo, para «Estado de la TV» y el diagnóstico. */
    private var lastSwitch: String? = null
    private var lastSwitchFailed = false

    /** Unos segundos después de pedir el modo se mira si la TV de verdad cambió (si no, se explica por qué). */
    @Suppress("DEPRECATION")
    private fun verifySwitch(window: Window, wanted: ScreenMode, fps: Float) {
        val check = Runnable {
            pendingCheck = null
            try {
                if (window.attributes.preferredDisplayModeId != wanted.id) return@Runnable
                val now = window.windowManager.defaultDisplay?.mode ?: return@Runnable
                val ok = now.modeId == wanted.id || (now.physicalWidth == wanted.width &&
                    now.physicalHeight == wanted.height && abs(now.refreshRate - wanted.hz) < 0.05f)
                lastSwitchFailed = !ok
                lastSwitch = "${describe(wanted)} para ${hzText(fps)} FPS → " + if (ok) "hecho" else
                    "la TV no cambió (sigue en ${now.physicalWidth}×${now.physicalHeight} · ${hzText(now.refreshRate)} Hz)"
                if (!ok) warnIfBlocked(window.context, wanted.hz)
            } catch (_: Exception) {
            }
        }
        lastSwitch = "${describe(wanted)} para ${hzText(fps)} FPS → pedido…"
        pendingCheck = check
        checks.postDelayed(check, 5_000L)
    }

    /**
     * Google TV (Android 12 o más nuevo): «Ajustar a la frecuencia de fotogramas del contenido». Si no está en
     * «Siempre», Android no deja que ninguna app cambie la TV de 60 a 24, 25 o 50 Hz (solo los cambios sin cortes).
     * Devuelve cómo está ese ajuste si lo impide, o null si deja cambiar.
     */
    fun systemSwitchLimit(context: Context): String? {
        if (android.os.Build.VERSION.SDK_INT < 31) return null
        val manager = context.getSystemService(DisplayManager::class.java) ?: return null
        return when (manager.matchContentFrameRateUserPreference) {
            DisplayManager.MATCH_CONTENT_FRAMERATE_NEVER -> "Nunca"
            DisplayManager.MATCH_CONTENT_FRAMERATE_SEAMLESSS_ONLY -> "Solo sin interrupciones"
            else -> null
        }
    }

    /** Cómo está la TV y qué modos tiene, para «Estado de la TV» y el diagnóstico del reproductor. */
    @Suppress("DEPRECATION")
    fun diagnostics(activity: Activity): List<String> {
        val display = activity.windowManager.defaultDisplay ?: return emptyList()
        val lines = mutableListOf("Ajuste · " + matchLabels[matchMode(activity)])
        val now = display.mode
        lines.add("TV ahora · ${now.physicalWidth}×${now.physicalHeight} · ${hzText(now.refreshRate)} Hz")
        val (long, short) = uiSize(display)
        if (matchResolution(activity)) {
            if (smallModesOk) lines.add("Resolución mínima · ninguna (NVIDIA: la TV baja a 720p y 480p como antes)")
            else if (long > 0) lines.add("Resolución mínima · ${long}×$short (la de la app, así no se agranda)")
        }
        display.supportedModes.groupBy { it.physicalWidth to it.physicalHeight }.entries
            .sortedByDescending { (size, _) -> size.first.toLong() * size.second }
            .forEach { (size, modes) ->
                lines.add("Modos ${size.first}×${size.second} · " +
                    modes.map { it.refreshRate }.distinct().sorted().joinToString(" · ") { hzText(it) } + " Hz")
            }
        lastSwitch?.let { lines.add("Último cambio · $it") }
        systemSwitchLimit(activity)?.let {
            lines.add("Google TV · «Ajustar a la frecuencia de fotogramas del contenido» en «$it» (ponlo en «Siempre» si la TV no cambia)")
        }
        return lines
    }

    /** Ventana con el estado de la TV (resolución, Hz, modos y el último cambio). */
    fun showScreenState(activity: Activity) {
        val lines = diagnostics(activity).ifEmpty { listOf("No se pudo leer la pantalla.") }
        val builder = AlertDialog.Builder(activity).setTitle("Estado de la TV")
            .setMessage(lines.joinToString("\n") + "\n\nLa TV muestra 59.94 Hz como «60» y 23.976 como «24». Los canales " +
                "de EE. UU. van a 29.97 o 59.94 FPS, así que ahí la TV se queda en «60» y está bien; las películas pasan a «24».")
            .setNegativeButton("Cerrar", null)
        if (systemSwitchLimit(activity) != null) builder.setPositiveButton("Abrir Ajustes") { _, _ -> openDisplaySettings(activity) }
        Ui.showDialog(builder.create())
    }

    private const val BLOCK_HINTS = "frame_rate_block_hints"
    private var hintShownThisRun = false

    private fun hintText(limit: String, hz: Float? = null): String =
        (if (hz != null) "Este video va mejor con la TV a " + String.format(Locale.US, "%.2f", hz).trimEnd('0').trimEnd('.') +
            " Hz, pero " else "") +
            "Google TV no deja que las apps cambien la frecuencia de la pantalla: el ajuste «Ajustar a la frecuencia de " +
            "fotogramas del contenido» está en «$limit».\n\nPara que cambie sola con cada canal y película: Ajustes de " +
            "Google TV ▸ Pantalla y sonido ▸ Avanzado (o Configuración avanzada de pantalla) ▸ Ajustar a la frecuencia " +
            "de fotogramas del contenido ▸ «Siempre»."

    /** Ajustes de pantalla de la TV (ahí está «Ajustar a la frecuencia de fotogramas del contenido»). */
    fun openDisplaySettings(context: Context) {
        val intents = listOf(android.content.Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS),
            android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
        for (intent in intents) {
            try {
                context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: Exception) {
            }
        }
    }

    private fun activityOf(context: Context): Activity? {
        var c: Context? = context
        while (c is android.content.ContextWrapper) {
            if (c is Activity) return c
            c = c.baseContext
        }
        return null
    }

    /**
     * La TV no cambió y Google TV tiene «Ajustar a la frecuencia de fotogramas del contenido» limitado: se avisa
     * (pocas veces) con el botón para ir a Ajustes.
     */
    private fun warnIfBlocked(context: Context, hz: Float) {
        if (!Ui.isTelevision(context)) return
        val limit = systemSwitchLimit(context) ?: return
        if (hintShownThisRun) return
        val prefs = RoomSession.prefs(context)
        val shown = prefs.getInt(BLOCK_HINTS, 0)
        if (shown >= 3) return
        hintShownThisRun = true
        prefs.edit().putInt(BLOCK_HINTS, shown + 1).apply()
        val activity = activityOf(context)
        if (activity == null || activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity).setTitle("La TV no cambia de frecuencia")
            .setMessage(hintText(limit, hz))
            .setPositiveButton("Abrir Ajustes") { _, _ -> openDisplaySettings(activity) }
            .setNegativeButton("Entendido", null).create().also(Ui::showDialog)
    }

    /**
     * Elige el modo de la TV: la resolución más pequeña donde cabe el video (1080p → 1920×1080, 4K → 3840×2160),
     * pero nunca menor que la interfaz ([minLong]×[minShort], 1920×1080 en el Streamer y el Shield): un canal 720p
     * pone la TV en 1080p y así la app no se agranda. La frecuencia va con los FPS: [multiple] veces los FPS
     * (25/29.97/30 FPS → 50/59.94/60 Hz) o, si no la hay, igual a los FPS. Si esa resolución no tiene la
     * frecuencia, se usa la siguiente más grande que sí la tenga: importa más que el movimiento vaya fino.
     * Sin tamaño de video ([videoWidth] 0, «Solo FPS») se mantiene la resolución actual y solo se ajustan los Hz.
     */
    internal fun pickScreenMode(
        all: List<ScreenMode>, current: ScreenMode, fps: Float, videoWidth: Int, videoHeight: Int, multiple: Int,
        minLong: Int = 0, minShort: Int = 0
    ): ScreenMode? {
        if (!fps.isFinite() || fps !in 10f..240f || all.isEmpty()) return null
        fun longSide(m: ScreenMode) = maxOf(m.width, m.height)
        fun shortSide(m: ScreenMode) = minOf(m.width, m.height)
        fun area(m: ScreenMode) = longSide(m).toLong() * shortSide(m)
        val wantResolution = videoWidth > 0 && videoHeight > 0

        // Una lista por resolución, de menor a mayor; las menores que la interfaz no se usan (si queda alguna).
        val sizes = all.groupBy { it.width to it.height }.values.sortedBy { area(it.first()) }
        val allowed = sizes.filter { longSide(it.first()) >= minLong && shortSide(it.first()) >= minShort }.ifEmpty { sizes }

        // 1) Resolución.
        val sizeModes = if (wantResolution) {
            val vLong = maxOf(videoWidth, videoHeight)
            val vShort = minOf(videoWidth, videoHeight)
            // Pequeño margen: 1920×1088, 1440×1080 o 3832×2160 también cuentan como su resolución.
            allowed.firstOrNull { longSide(it.first()) >= vLong * 0.95f && shortSide(it.first()) >= vShort * 0.95f }
                ?: allowed.last()
        } else {
            allowed.firstOrNull { it.first().width == current.width && it.first().height == current.height }
                ?: allowed.firstOrNull { area(it.first()) >= area(current) } ?: allowed.last()
        }
        // Las resoluciones más grandes, de menor a mayor, por si esa no tiene la frecuencia buscada.
        val bigger = allowed.filter { area(it.first()) > area(sizeModes.first()) }

        // 2) Frecuencia.
        fun matches(rate: Float, times: Int) = times >= 1 && abs(rate - fps * times) < 0.05f
        fun pick(modes: List<ScreenMode>, times: Int) =
            modes.filter { matches(it.hz, times) }.minByOrNull { abs(it.hz - fps * times) }
        return listOf(multiple.coerceAtLeast(1), 1).distinct().firstNotNullOfOrNull { times ->
            pick(sizeModes, times) ?: bigger.firstNotNullOfOrNull { pick(it, times) }
        }
            ?: current.takeIf { it in sizeModes && matches(it.hz, (it.hz / fps).roundToInt()) }
            ?: sizeModes.filter { matches(it.hz, (it.hz / fps).roundToInt()) }.minByOrNull { it.hz }
            // Sin frecuencia exacta: al menos la resolución, con la frecuencia más cercana a la actual.
            ?: sizeModes.takeIf { wantResolution }?.minByOrNull { abs(it.hz - current.hz) }
    }
}
