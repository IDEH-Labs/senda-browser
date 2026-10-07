package org.senda.browser.core.cast

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.content.pm.ActivityInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.senda.browser.MainActivity
import org.senda.browser.R
import org.senda.browser.core.PreferencesManager

/**
 * Modo TV: mientras el teléfono se duplica en una TV (Miracast, «Enviar pantalla» o HDMI), adapta todo
 * el teléfono, no solo Senda, para que en la TV se vea a pantalla completa y con el menor retraso:
 *
 *  - 60 Hz en todo el sistema: la TV no muestra más, y componer y codificar 120 imágenes por segundo
 *    es lo que retrasa y entrecorta el duplicado.
 *  - Horizontal: Telegram, la galería o cualquier app se ven ocupando la TV y no como una tira vertical.
 *  - Proporción y resolución de la TV (16:9, normalmente 1920×1080) en vez de las del móvil (20:9, p. ej.
 *    720×1600): la TV no pone franjas negras y recibe una imagen nítida, no 720p estirado.
 *
 * Los dos primeros usan una ventana invisible de 1 px sobre las demás apps (permiso «Mostrar sobre
 * otras apps»). La proporción necesita WRITE_SECURE_SETTINGS, que solo se concede por ADB; sin él se
 * omite. Al cortar la transmisión todo vuelve exactamente a como estaba.
 */
object SendaTvMode {

    private const val TAG = "SendaTvMode"
    private const val STATE_PREFS = "senda_tv_mode_state"
    private const val KEY_FORCED = "forced_size_active"
    private const val KEY_ORIGINAL_W = "original_w"
    private const val KEY_ORIGINAL_H = "original_h"
    private const val KEY_ORIGINAL_DENSITY = "original_density"
    // Usuario de Android en el que corre Senda (con USER_CURRENT, -2, Android exige INTERACT_ACROSS_USERS)
    private val userId: Int
        get() = android.os.Process.myUid() / 100_000

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var appContext: Context
    private var overlay: View? = null
    /** true mientras el teléfono está adaptado a la TV (horizontal, 16:9). */
    var active by mutableStateOf(false)
        private set


    /** true mientras la pantalla del teléfono se duplica en una TV (Miracast, «Enviar pantalla» o HDMI). */
    var tvConnected by mutableStateOf(false)
        private set

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = evaluate()
        override fun onDisplayRemoved(displayId: Int) = evaluate()
        // El teléfono giró (o cambió de tamaño): 16:9 solo en horizontal
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY && active) applyForRotation()
        }
    }

    /** Se llama una vez al arrancar el proceso principal. */
    fun init(context: Context) {
        if (::appContext.isInitialized) {
            evaluate()
            return
        }
        appContext = context.applicationContext
        // Si el proceso murió con la proporción de TV puesta, devolver la pantalla a la normalidad
        if (mirroringDisplay() == null) restoreDisplaySize()
        appContext.getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, mainHandler)
        evaluate()
    }

    fun canDrawOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun canAdaptAspect(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Revisa si hay una TV conectada y activa o retira el modo TV según corresponda. */
    fun evaluate() {
        if (!::appContext.isInitialized) return
        val tv = mirroringDisplay()
        tvConnected = tv != null
        tvName = tv?.name?.replace(Regex("\\[R\\d+]$"), "")?.trim()
        val enabled = PreferencesManager(appContext).tvModeEnabled
        if (tv != null && enabled) activate(tv) else deactivate()
    }


    private val stopWaiting = Runnable {
        if (!active) appContext.stopService(Intent(appContext, SendaTvModeService::class.java))
    }

    /**
     * El usuario va a elegir la TV en el menú de Android: mientras tanto Senda queda en segundo plano y Android
     * congela su proceso, así que el modo TV no se aplicaba hasta volver a Senda. El servicio se arranca ya
     * (Senda aún está delante) y mantiene el proceso despierto; si en 2 minutos no se conecta ninguna TV, se detiene.
     */
    fun awaitTv() {
        if (!::appContext.isInitialized) return
        try {
            appContext.startForegroundService(Intent(appContext, SendaTvModeService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo iniciar el servicio del modo TV: ${e.message}")
            return
        }
        mainHandler.removeCallbacks(stopWaiting)
        mainHandler.postDelayed(stopWaiting, 120_000L)
    }

    /** Nombre de la TV conectada, sin el sufijo de Miracast («[R1]»). */
    var tvName by mutableStateOf<String?>(null)
        private set

    /** Pantalla de la TV en la que Senda puede mostrar contenido propio (null si no hay TV conectada). */
    fun tvDisplay(): Display? = if (::appContext.isInitialized) mirroringDisplay() else null

    /**
     * Desconecta la TV sin pasar por los ajustes de Android. Conectar Miracast es solo de las apps del sistema
     * (CONFIGURE_WIFI_DISPLAY); desconectar lo puede pedir cualquier app, con una función oculta (probado en un
     * moto g34; MediaRouter no lo hacía). Si no funcionara, se abre el panel «Enviar pantalla» para tocar «Desconectar».
     */
    fun disconnect(context: Context) {
        SendaTvPlayer.requestReturn?.invoke()
        try {
            DisplayManager::class.java.getMethod("disconnectWifiDisplay")
                .invoke(context.getSystemService(DisplayManager::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo desconectar la TV directamente: ${e.cause?.message ?: e.message}")
        }
        mainHandler.postDelayed({
            evaluate()
            if (tvConnected) org.senda.browser.ui.components.CastHelper.openSystemCast(context)
        }, 2500)
    }

    private fun mirroringDisplay(): Display? =
        appContext.getSystemService(DisplayManager::class.java)
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }

    private fun activate(tv: Display) {
        if (active) {
            // Ya activo: reaplicar por si el usuario cambió los ajustes
            applyForRotation()
            return
        }
        active = true
        Log.i(TAG, "TV conectada (${tv.name} ${tv.mode.physicalWidth}x${tv.mode.physicalHeight}): activando modo TV")
        // Nunca se gira el teléfono: la ventana invisible solo fija 60 Hz (la TV no muestra más y se duplica con
        // menos retraso)
        updateOverlay(landscape = false)
        applyForRotation()
        try {
            appContext.startForegroundService(Intent(appContext, SendaTvModeService::class.java))
        } catch (e: Exception) {
            // Android puede negar el servicio si Senda está en segundo plano: el modo TV sigue aplicado
            // mientras el proceso viva, y se restaura igual al cortar la transmisión
            Log.w(TAG, "No se pudo iniciar el servicio del modo TV: ${e.message}")
        }
    }

    /**
     * En vertical el teléfono queda exactamente como está (la TV lo muestra tal cual: una imagen vertical no puede
     * llenar una TV sin cambiar la del teléfono). Cuando el usuario gira el teléfono —un video a pantalla completa en
     * cualquier app— la imagen pasa a 16:9 y la TV se llena; en el teléfono quedan dos franjas finas en los extremos.
     */
    private fun applyForRotation() {
        val tv = mirroringDisplay() ?: return
        val rotation = appContext.getSystemService(DisplayManager::class.java)
            ?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: return
        val landscape = rotation == android.view.Surface.ROTATION_90 || rotation == android.view.Surface.ROTATION_270
        if (landscape) forceTvAspect(tv) else restoreDisplaySize()
    }

    private fun deactivate() {
        if (!active) return
        active = false
        Log.i(TAG, "TV desconectada: restaurando pantalla")
        removeOverlay()
        restoreDisplaySize()
        appContext.stopService(Intent(appContext, SendaTvModeService::class.java))
    }

    // --- 60 Hz y horizontal en todo el sistema ---

    private fun updateOverlay(landscape: Boolean) {
        if (!canDrawOverlay(appContext)) {
            Log.w(TAG, "Sin permiso «Mostrar sobre otras apps»: 60 Hz y horizontal solo dentro de Senda")
            return
        }
        val wm = appContext.getSystemService(WindowManager::class.java) ?: return
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "SendaTvMode"
            preferredDisplayModeId = lowestRefreshModeId()
            screenOrientation = if (landscape) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
        try {
            val view = overlay
            if (view == null) {
                overlay = View(appContext).also { wm.addView(it, lp) }
            } else {
                wm.updateViewLayout(view, lp)
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo colocar la ventana del modo TV: ${e.message}")
        }
    }

    private fun removeOverlay() {
        val view = overlay ?: return
        overlay = null
        try {
            appContext.getSystemService(WindowManager::class.java)?.removeViewImmediate(view)
        } catch (_: Exception) {
        }
    }

    /** Modo de la pantalla del móvil con la menor frecuencia ≥ 59 Hz y la misma resolución (0 si no hay). */
    private fun lowestRefreshModeId(): Int {
        val display = appContext.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return 0
        val current = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight && it.refreshRate >= 59f }
            .minByOrNull { it.refreshRate }?.modeId ?: 0
    }

    // --- Proporción de la TV ---

    private fun forceTvAspect(tv: Display) {
        if (!canAdaptAspect(appContext)) {
            Log.i(TAG, "Sin WRITE_SECURE_SETTINGS: la TV mostrará la proporción del móvil")
            return
        }
        try {
            val wms = windowManagerService()
            val iface = Class.forName("android.view.IWindowManager")
            val initial = Point().also { iface.getMethod("getInitialDisplaySize", Int::class.java, Point::class.java).invoke(wms, Display.DEFAULT_DISPLAY, it) }
            val base = Point().also { iface.getMethod("getBaseDisplaySize", Int::class.java, Point::class.java).invoke(wms, Display.DEFAULT_DISPLAY, it) }

            val tvLong = maxOf(tv.mode.physicalWidth, tv.mode.physicalHeight)
            val tvShort = minOf(tv.mode.physicalWidth, tv.mode.physicalHeight)
            if (tvShort <= 0) return
            // Proporción de la TV con el lado corto del móvil (720×1600 → 720×1280): medido el 2026-10-06 en un
            // moto g34 duplicando en un LG, 0 imágenes perdidas de 943. Solo cambia el tamaño, nunca la densidad:
            // a la resolución de la TV (1080×1920, densidad 459) el teléfono perdía el 6 % de las imágenes y, al
            // cambiar la densidad, Android reiniciaba Telegram y Senda por dentro al girar (video que no deja
            // adelantar, pestaña que «se cierra»). En par para el codificador
            val phoneShort = minOf(initial.x, initial.y)
            val phoneLong = maxOf(initial.x, initial.y)
            val shortSide = phoneShort and 1.inv()
            val longSide = (phoneShort.toLong() * tvLong / tvShort).toInt().coerceAtMost(phoneLong) and 1.inv()
            if (longSide >= phoneLong - 8) {
                // El móvil ya tiene la proporción de la TV: si quedó un tamaño forzado de antes, se quita
                restoreDisplaySize()
                return
            }
            val (w, h) = if (initial.x < initial.y) shortSide to longSide else longSide to shortSide
            if (base.x == w && base.y == h) return
            val state = appContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            if (!state.getBoolean(KEY_FORCED, false)) {
                // Guardar el tamaño que tenía el usuario (normalmente el de fábrica) para devolverlo tal cual
                state.edit()
                    .putBoolean(KEY_FORCED, true)
                    .putInt(KEY_ORIGINAL_W, if (base == initial) 0 else base.x)
                    .putInt(KEY_ORIGINAL_H, if (base == initial) 0 else base.y)
                    .commit()
            }
            iface.getMethod("setForcedDisplaySize", Int::class.java, Int::class.java, Int::class.java)
                .invoke(wms, Display.DEFAULT_DISPLAY, w, h)
            Log.i(TAG, "Pantalla adaptada a la TV: ${w}x$h (TV ${tv.mode.physicalWidth}x${tv.mode.physicalHeight})")
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo adaptar la proporción a la TV: ${e.cause?.message ?: e.message}")
        }
    }

    private fun restoreDisplaySize() {
        val state = appContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
        if (!state.getBoolean(KEY_FORCED, false)) return
        try {
            val wms = windowManagerService()
            val iface = Class.forName("android.view.IWindowManager")
            val w = state.getInt(KEY_ORIGINAL_W, 0)
            val h = state.getInt(KEY_ORIGINAL_H, 0)
            if (w > 0 && h > 0) {
                iface.getMethod("setForcedDisplaySize", Int::class.java, Int::class.java, Int::class.java)
                    .invoke(wms, Display.DEFAULT_DISPLAY, w, h)
            } else {
                iface.getMethod("clearForcedDisplaySize", Int::class.java).invoke(wms, Display.DEFAULT_DISPLAY)
            }
            // Solo una versión del 2026-10-06 cambiaba la escala (y la guardaba): las demás no la tocan
            if (state.contains(KEY_ORIGINAL_DENSITY)) {
                val density = state.getInt(KEY_ORIGINAL_DENSITY, 0)
                if (density > 0) {
                    iface.getMethod("setForcedDisplayDensityForUser", Int::class.java, Int::class.java, Int::class.java)
                        .invoke(wms, Display.DEFAULT_DISPLAY, density, userId)
                } else {
                    iface.getMethod("clearForcedDisplayDensityForUser", Int::class.java, Int::class.java)
                        .invoke(wms, Display.DEFAULT_DISPLAY, userId)
                }
            }
            state.edit().putBoolean(KEY_FORCED, false).remove(KEY_ORIGINAL_DENSITY).commit()
            Log.i(TAG, "Pantalla del móvil restaurada")
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo restaurar la pantalla: ${e.cause?.message ?: e.message}")
        }
    }

    private fun windowManagerService(): Any =
        Class.forName("android.view.WindowManagerGlobal").getMethod("getWindowManagerService").invoke(null)!!
}

/**
 * Mantiene vivo el proceso de Senda mientras dura la transmisión, para poder devolver la pantalla
 * a la normalidad en cuanto se corta, aunque se esté usando otra app.
 */
class SendaTvModeService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            SendaTvMode.disconnect(applicationContext)
            return START_NOT_STICKY
        }
        val strings = org.senda.browser.core.SendaStrings.forApp(this)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, strings.dlg_tv_mode, NotificationManager.IMPORTANCE_LOW)
        )
        val openSenda = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_senda_monochrome)
            .setContentTitle(strings.tv_notif_title)
            .setContentText(strings.tv_notif_text)
            .setContentIntent(openSenda)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_senda_monochrome),
                    strings.cast_disconnect_tv,
                    PendingIntent.getService(
                        this, 1, Intent(this, SendaTvModeService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_IMMUTABLE
                    )
                ).build()
            )
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        return START_NOT_STICKY
    }

    private companion object {
        const val CHANNEL = "senda_tv_mode"
        const val ACTION_STOP = "org.senda.browser.TV_STOP"
        const val NOTIFICATION_ID = 4210
    }
}

/**
 * La proporción forzada de la TV sobrevive a un reinicio o a una actualización de Senda. Recibir este
 * aviso arranca el proceso, y [SendaTvMode.init] (desde SendaApplication) devuelve la pantalla a como estaba.
 */
class SendaTvModeRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SendaTvMode.init(context)
    }
}
