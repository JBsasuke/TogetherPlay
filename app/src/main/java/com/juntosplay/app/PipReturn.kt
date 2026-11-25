package com.juntosplay.app

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.lang.ref.WeakReference

/**
 * Imagen en imagen (PiP) y cómo volver a la pantalla grande.
 *
 * Cuando la pantalla que entra en PiP tiene otra de la app debajo (la película encima del catálogo, o los
 * canales encima de la pantalla de la sala), Android la saca a una tarea aparte y deja la de abajo a la
 * vista. Al volver a la app desde el ícono o desde Recientes aparecía esa otra pantalla y la ventanita se
 * quedaba chiquita para siempre (había que cerrar la app). Ahora:
 *  - al entrar en PiP, la pantalla de abajo se manda atrás (queda el inicio de la Shield con la ventanita);
 *  - al volver a la app, se le pide a Android abrir otra vez la pantalla que está en PiP y la Shield la
 *    agranda a pantalla completa.
 */
internal object PipReturn {
    private const val EXTRA = "tp_volver_de_pip"

    /** Lo que puede tardar en aparecer la pantalla de abajo después de entrar en PiP. */
    private const val JUST_ENTERED_MS = 5_000L

    private val main = Handler(Looper.getMainLooper())
    private var inPip: WeakReference<Activity>? = null
    private var enteredAt = 0L

    /** [activity] va a entrar en imagen en imagen (se llama justo antes de pedírselo a Android). */
    fun entering(activity: Activity) {
        inPip = WeakReference(activity)
        enteredAt = SystemClock.elapsedRealtime()
    }

    /** Aviso de Android: [activity] entró o salió de imagen en imagen (o se cerró). */
    fun changed(activity: Activity, active: Boolean) {
        if (active) {
            if (inPip?.get() !== activity) {
                inPip = WeakReference(activity)
                enteredAt = SystemClock.elapsedRealtime()
            }
        } else if (inPip?.get() === activity) {
            clear()
        }
    }

    private fun clear() {
        inPip = null
        enteredAt = 0L
    }

    private fun fresh(): Boolean = enteredAt > 0L && SystemClock.elapsedRealtime() - enteredAt < JUST_ENTERED_MS

    /** La otra pantalla de la app que está en imagen en imagen (no [from]), si hay una. */
    fun other(from: Activity): Activity? {
        val pip = inPip?.get()
        if (pip == null || pip.isFinishing || pip.isDestroyed) {
            clear()
            return null
        }
        if (pip === from) return null
        // Recién pedido, Android todavía puede no haber avisado que ya está en PiP.
        if (!fresh() && (Build.VERSION.SDK_INT < 24 || !pip.isInPictureInPictureMode)) {
            clear()
            return null
        }
        return pip
    }

    /**
     * Se ve [from] mientras otra pantalla de la app está en imagen en imagen. Si acaba de entrar en PiP,
     * [from] es la pantalla que quedó debajo: se manda atrás. Si no, el usuario volvió a la app (ícono o
     * Recientes): se agranda la ventanita. Devuelve true si había una pantalla en PiP.
     */
    fun appShown(from: Activity): Boolean {
        val pip = other(from) ?: return false
        if (fresh()) {
            enteredAt = 0L
            sendBack(from, pip, 0)
        } else {
            restore(from, pip)
        }
        return true
    }

    /**
     * Manda atrás la pantalla de abajo, pero solo cuando Android ya sacó la ventanita a su propia tarea. Si todavía
     * comparten tarea (pasaba a veces en la Shield con un fondo de pantalla de video, que hace más lento el inicio),
     * mandarla atrás se llevaba también la ventanita: se oía el audio pero no se veía. Se espera y se vuelve a mirar.
     */
    private fun sendBack(from: Activity, pip: Activity, tries: Int) {
        main.postDelayed({
            if (from.isFinishing || from.isDestroyed || pip.isFinishing || pip.isDestroyed) return@postDelayed
            when {
                pip.taskId != from.taskId -> from.moveTaskToBack(true)
                tries < 12 -> sendBack(from, pip, tries + 1)
                else -> PlaybackLog.add(from, "Imagen en imagen: la ventanita sigue en la misma tarea; no se manda atrás")
            }
        }, if (tries == 0) 0L else 250L)
    }

    /** Si hay otra pantalla en imagen en imagen, la vuelve a pantalla completa. Devuelve true si la había. */
    fun restoreOther(from: Activity): Boolean {
        val pip = other(from) ?: return false
        restore(from, pip)
        return true
    }

    /**
     * Volver a abrir la pantalla que está en PiP es la señal con la que Android TV la agranda. NEW_TASK la
     * busca en su propia tarea y REORDER_TO_FRONT reusa la que ya existe (no abre otra ni le cambia nada).
     */
    private fun restore(from: Activity, pip: Activity) {
        enteredAt = 0L
        val intent = Intent(from, pip.javaClass)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(EXTRA, true)
        runCatching { from.startActivity(intent) }
            .onFailure { PlaybackLog.add(from, "No se pudo agrandar la imagen en imagen: " + it.javaClass.simpleName) }
    }

    /** Trae al frente [target], abierta en otra tarea, sin abrir otra igual. Devuelve true si lo pidió. */
    fun bringToFront(from: Activity, target: Activity): Boolean {
        val intent = Intent(from, target.javaClass)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(EXTRA, true)
        return runCatching { from.startActivity(intent) }.isSuccess
    }

    /** Esta pantalla se abrió solo para agrandar la que está en PiP (no es para abrir nada nuevo). */
    fun isReturn(intent: Intent?): Boolean = intent?.getBooleanExtra(EXTRA, false) == true
}
