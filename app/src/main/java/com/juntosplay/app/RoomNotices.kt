package com.juntosplay.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import org.json.JSONObject

/**
 * Avisos de la sala: quién entró (con su nombre) y los «toques» para llamar al otro. En el teléfono salen como
 * notificación; en la tele, como un cartel en pantalla. Solo funcionan mientras este equipo sigue en la sala (el
 * aviso fijo de TogetherPlay la mantiene conectada aunque la app no esté a la vista).
 */
object RoomNotices {
    const val PREF_ENABLED = "room_notify"
    private const val PREF_NAME = "room_name"
    private const val PREF_PEER = "room_peer_name"
    private const val CHANNEL = "togetherplay_room_alerts"
    private const val HELLO = "tp_hello"
    private const val NUDGE = "tp_nudge"

    private val main = Handler(Looper.getMainLooper())
    /** Cuándo se fue el otro por última vez: si vuelve enseguida es un corte de conexión, no una entrada nueva. */
    private var peerLeftAt = 0L
    private var joinNotice: Runnable? = null
    /** Nombre que mandó quien acaba de entrar (llega un momento después del aviso del servidor). */
    private var arrivedName: String? = null

    fun enabled(context: Context) = RoomSession.prefs(context).getBoolean(PREF_ENABLED, true)

    /** El nombre con que este equipo aparece en la sala (por defecto, el modelo del equipo). */
    fun myName(context: Context): String =
        RoomSession.prefs(context).getString(PREF_NAME, "").orEmpty().trim().ifBlank { Build.MODEL ?: "Un equipo" }

    fun setMyName(context: Context, name: String) {
        RoomSession.prefs(context).edit().putString(PREF_NAME, name.trim().take(24)).apply()
        hello(context, reply = true)
    }

    /** El nombre del otro, si ya lo dijo alguna vez. */
    fun peerName(context: Context): String = RoomSession.prefs(context).getString(PREF_PEER, "").orEmpty()

    fun isMine(type: String) = type == HELLO || type == NUDGE

    /** Le dice a la sala cómo se llama este equipo. [reply]: es solo una presentación, no una entrada nueva. */
    fun hello(context: Context, reply: Boolean) {
        RoomSession.client?.sendCustom(JSONObject().put("type", HELLO).put("name", myName(context)).put("reply", reply))
    }

    /** «Dar un toque»: al otro le sale el aviso de que lo esperan. false si no hay nadie más en la sala. */
    fun nudge(context: Context): Boolean {
        val client = RoomSession.client ?: return false
        if (!RoomSession.peerPresent) return false
        client.sendCustom(JSONObject().put("type", NUDGE).put("name", myName(context)))
        return true
    }

    /** Este equipo entró a la sala: se presenta. */
    fun joined(context: Context) = hello(context, reply = false)

    fun peerLeft() {
        peerLeftAt = SystemClock.elapsedRealtime()
        joinNotice?.let(main::removeCallbacks)
        joinNotice = null
    }

    /** El servidor avisa que entró otro equipo: se le dice el nombre de este y se avisa aquí (con su nombre si llega). */
    fun peerJoined(context: Context) {
        val app = context.applicationContext
        hello(app, reply = true)
        // Volvió enseguida: fue un corte de su conexión, no hace falta avisar otra vez.
        val blip = peerLeftAt != 0L && SystemClock.elapsedRealtime() - peerLeftAt < 120_000L
        if (blip || !enabled(app)) return
        arrivedName = null
        joinNotice?.let(main::removeCallbacks)
        val notice = Runnable {
            joinNotice = null
            val name = arrivedName ?: peerName(app)
            show(app, if (name.isBlank()) "Alguien entró a la sala" else "$name entró a la sala",
                "Te está esperando para ver algo juntos.", 7411)
        }
        joinNotice = notice
        main.postDelayed(notice, 2_500L)
    }

    fun onMessage(context: Context, msg: JSONObject) {
        val app = context.applicationContext
        val name = msg.optString("name").trim().take(24)
        if (name.isNotBlank()) RoomSession.prefs(app).edit().putString(PREF_PEER, name).apply()
        when (msg.optString("type")) {
            HELLO -> if (name.isNotBlank()) {
                arrivedName = name
                // El nombre llegó antes de mostrar el aviso de entrada: se muestra ya, con el nombre.
                joinNotice?.let { pending ->
                    main.removeCallbacks(pending)
                    pending.run()
                }
            }
            NUDGE -> if (enabled(app)) show(app, (name.ifBlank { "Tu pareja" }) + " te dio un toque",
                "Está en la sala esperándote.", 7412)
        }
    }

    private fun show(context: Context, title: String, text: String, id: Int) {
        if (Ui.isTelevision(context)) {
            // Las teles no muestran notificaciones: un cartel en pantalla.
            Toast.makeText(context, "$title\n$text", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "Avisos de la sala", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Cuando alguien entra a tu sala o te da un toque"
                    enableVibration(true)
                })
            }
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val open = launch?.let {
                PendingIntent.getActivity(context, 5, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(context, CHANNEL)
            else @Suppress("DEPRECATION") Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL)
            manager.notify(id, builder.setContentTitle(title).setContentText(text)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build())
        } catch (_: Exception) {
            // Sin permiso de notificaciones: al menos el cartel, si la app está a la vista.
            Toast.makeText(context, "$title\n$text", Toast.LENGTH_LONG).show()
        }
    }
}
