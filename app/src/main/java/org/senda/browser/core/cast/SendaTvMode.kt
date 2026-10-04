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
 *  - Proporción de la TV (16:9) en vez de la del móvil (20:9): la TV ya no pone franjas negras.
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

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var appContext: Context
    private var overlay: View? = null
    private var active = false

    /** true mientras la pantalla del teléfono se duplica en una TV (Miracast, «Enviar pantalla» o HDMI). */
    var tvConnected by mutableStateOf(false)
        private set

    /**
     * Solo solicita pantalla completa horizontal si el usuario activó expresamente la opción en ajustes
     * y el reproductor de TV está activo. Por defecto nunca fuerza la orientación.
     */
    fun wantsTvVideoLayout(tab: org.senda.browser.ui.model.BrowserTab?): Boolean =
        tvConnected && tab != null &&
            (::appContext.isInitialized && PreferencesManager(appContext).tvModeLandscape) &&
            SendaTvPlayer.playback != null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = evaluate()
        override fun onDisplayRemoved(displayId: Int) = evaluate()
        override fun onDisplayChanged(displayId: Int) {}
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
        val enabled = PreferencesManager(appContext).tvModeEnabled
        if (tv != null && enabled) activate(tv) else deactivate()
    }

    private fun mirroringDisplay(): Display? =
        appContext.getSystemService(DisplayManager::class.java)
            ?.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            ?.firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }

    private fun activate(tv: Display) {
        val prefs = PreferencesManager(appContext)
        if (active) {
            // Ya activo: solo reaplicar la orientación por si el usuario cambió el ajuste
            updateOverlay(prefs.tvModeLandscape)
            return
        }
        active = true
        Log.i(TAG, "TV conectada (${tv.name} ${tv.mode.physicalWidth}x${tv.mode.physicalHeight}): activando modo TV")
        updateOverlay(prefs.tvModeLandscape)
        forceTvAspect(tv)
        try {
            appContext.startForegroundService(Intent(appContext, SendaTvModeService::class.java))
        } catch (e: Exception) {
            // Android puede negar el servicio si Senda está en segundo plano: el modo TV sigue aplicado
            // mientras el proceso viva, y se restaura igual al cortar la transmisión
            Log.w(TAG, "No se pudo iniciar el servicio del modo TV: ${e.message}")
        }
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

            val tvLong = maxOf(tv.mode.physicalWidth, tv.mode.physicalHeight).toFloat()
            val tvShort = minOf(tv.mode.physicalWidth, tv.mode.physicalHeight).toFloat()
            if (tvShort <= 0f) return
            val aspect = tvLong / tvShort
            val phoneShort = minOf(initial.x, initial.y)
            val phoneLong = maxOf(initial.x, initial.y)
            // Lado largo para igualar la TV, en par para el codificador y sin pasar del panel físico
            val targetLong = ((phoneShort * aspect).toInt() and 1.inv()).coerceAtMost(phoneLong)
            if (targetLong >= phoneLong - 8) return // el móvil ya tiene la proporción de la TV
            val (w, h) = if (initial.x < initial.y) phoneShort to targetLong else targetLong to phoneShort

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
            state.edit().putBoolean(KEY_FORCED, false).commit()
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
        val isSpanish = resources.configuration.locales[0].language == "es"
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, if (isSpanish) "Modo TV" else "TV mode", NotificationManager.IMPORTANCE_LOW)
        )
        val openSenda = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_senda_monochrome)
            .setContentTitle(if (isSpanish) "Modo TV activo" else "TV mode on")
            .setContentText(
                if (isSpanish) "La pantalla se adapta a la TV. Vuelve a la normalidad al dejar de transmitir."
                else "The screen is adapted to the TV. It returns to normal when you stop casting."
            )
            .setContentIntent(openSenda)
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
