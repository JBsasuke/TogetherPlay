package com.juntosplay.app

import android.app.Application
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Si la app se cierra por un error, guarda el detalle para mostrarlo al volver a abrirla
 * (Ajustes → copiar), y así poder corregirlo sin adivinar.
 */
class TogetherPlayApp : Application(), coil.ImageLoaderFactory {
    /**
     * Cargador de imágenes único para toda la app: guarda pósters y miniaturas en disco aunque
     * el servidor diga "no guardar", usa menos memoria con imágenes sin transparencia
     * y así el catálogo se desliza más fluido y no vuelve a descargar lo mismo.
     */
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        // 20 % de la memoria para pósters y logos ya cargados (con 30 % la Shield se quedaba corta al ver 4K).
        .memoryCache { SteadyImages(coil.memory.MemoryCache.Builder(this).maxSizePercent(0.25).build()).also { images = it } }
        .diskCache {
            // Según el espacio libre del equipo: entre 300 MB y 1,5 GB (con 300 fijos las carátulas nuevas iban
            // sacando del disco a las que ya estaban).
            coil.disk.DiskCache.Builder()
                .directory(cacheDir.resolve("image_cache"))
                .maxSizePercent(0.03)
                .minimumMaxSizeBytes(300L * 1024 * 1024)
                .maximumMaxSizeBytes(1536L * 1024 * 1024)
                .build()
        }
        // Varios sitios (los que están detrás de Cloudflare) no entregan imágenes a quien se presenta como «okhttp»:
        // las imágenes se piden identificándose como un navegador, salvo que la petición traiga su propio User-Agent.
        .okHttpClient {
            okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
                val request = chain.request()
                chain.proceed(if (request.header("User-Agent") != null) request
                    else request.newBuilder().header("User-Agent", IMAGE_USER_AGENT).build())
            }.build()
        }
        .allowRgb565(true)
        .respectCacheHeaders(false)
        .crossfade(150)
        .build()

    /**
     * Memoria de carátulas y logos que no se vacía sola mientras la app está en pantalla. La Shield anda siempre justa
     * de memoria y Android manda avisos de «poca memoria» a cada rato; con cada aviso se tiraba la mitad de las
     * imágenes ya cargadas, y por eso las carátulas y los logos volvían a cargar al subir, bajar o cambiar de sección.
     * Ahora solo se sueltan cuando la app pasa a segundo plano.
     */
    class SteadyImages(private val real: coil.memory.MemoryCache) : coil.memory.MemoryCache by real {
        override fun trimMemory(level: Int) {
            @Suppress("DEPRECATION")
            if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) real.trimMemory(level)
        }
        /** Suelta la mitad (las que hace más tiempo que no se ven): para dejarle sitio a una película. */
        fun halve() {
            @Suppress("DEPRECATION")
            real.trimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
        }
    }


    override fun onCreate() {
        super.onCreate()
        Ui.loadTheme(this)
        // La pantalla que se está viendo.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: android.app.Activity) { topActivity = java.lang.ref.WeakReference(activity) }
            override fun onActivityDestroyed(activity: android.app.Activity) { if (topActivity?.get() === activity) topActivity = null }
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) = Unit
            override fun onActivityStarted(activity: android.app.Activity) = Unit
            override fun onActivityPaused(activity: android.app.Activity) = Unit
            override fun onActivityStopped(activity: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) = Unit
        })
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Fallo conocido de la red de Android («Unbalanced enter/exit») al cerrar una conexión que otra parte ya
            // estaba cerrando. Pasa en un hilo de fondo y no daña nada: ese hilo termina y la app sigue abierta
            // (antes cerraba la app entera y dejaba este aviso).
            if (thread !== android.os.Looper.getMainLooper().thread && error is IllegalStateException &&
                error.message?.contains("Unbalanced enter/exit") == true) {
                android.util.Log.w("TogetherPlay", "Conexión cerrada dos veces (se ignora): " + thread.name)
                return@setDefaultUncaughtExceptionHandler
            }
            try {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val text = buildString {
                    append("TogetherPlay ").append(versionName()).append(" · ")
                    append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(" · Android ").append(Build.VERSION.RELEASE).append('\n')
                    append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append(" · hilo ").append(thread.name).append("\n\n")
                    append(sw.toString().take(6000))
                }
                crashFile(this).writeText(text)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (_: Exception) { "" }

    companion object {
        @Volatile var images: SteadyImages? = null
        @Volatile private var logos: coil.ImageLoader? = null

        /**
         * Cargador aparte para los logos de los canales, con su propia memoria y su propio lugar en el disco. Antes
         * compartían sitio con las carátulas: al ver muchas películas, las carátulas sacaban los logos del disco y
         * había que bajarlos otra vez (y varios sitios de logos no dejan bajar tantos seguidos: quedaban en blanco).
         */
        fun logoLoader(context: android.content.Context): coil.ImageLoader {
            logos?.let { return it }
            synchronized(this) {
                logos?.let { return it }
                val app = context.applicationContext
                val made = coil.ImageLoader.Builder(app)
                    .memoryCache { SteadyImages(coil.memory.MemoryCache.Builder(app).maxSizePercent(0.06).build()) }
                    .diskCache {
                        coil.disk.DiskCache.Builder().directory(app.filesDir.resolve("logo_cache"))
                            .maxSizeBytes(200L * 1024 * 1024).build()
                    }
                    .okHttpClient {
                        // Pocos a la vez por sitio: casi todos los logos salen de dos o tres servidores y, si se les
                        // piden decenas de golpe (al abrir la lista), cortan y los logos quedan en blanco.
                        okhttp3.OkHttpClient.Builder().dispatcher(okhttp3.Dispatcher().apply { maxRequestsPerHost = 3; maxRequests = 12 })
                            .addInterceptor { chain ->
                            val request = chain.request()
                            chain.proceed(if (request.header("User-Agent") != null) request
                                else request.newBuilder().header("User-Agent", IMAGE_USER_AGENT).build())
                        }.build()
                    }
                    .respectCacheHeaders(false)
                    .build()
                logos = made
                return made
            }
        }
        const val IMAGE_USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        @Volatile var topActivity: java.lang.ref.WeakReference<android.app.Activity>? = null

        fun crashFile(app: android.content.Context) = File(app.filesDir, "last_crash.txt")
        /** Los errores graves de la parte de Tailscale (Go) se escriben aquí. */
        fun nativeCrashFile(app: android.content.Context) = File(app.filesDir, "last_crash_native.txt")

        /** Texto del último cierre inesperado, o null si no hubo. */
        fun lastCrash(app: android.content.Context): String? {
            val parts = listOf(crashFile(app), nativeCrashFile(app))
                .filter { it.exists() && it.length() > 0 }
                .map { it.readText().take(6000) }
            return parts.joinToString("\n\n----\n\n").ifBlank { null }
        }

        fun clearCrash(app: android.content.Context) {
            crashFile(app).delete()
            nativeCrashFile(app).delete()
        }
    }
}
