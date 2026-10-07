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
 * TV mode: while the phone is mirrored to a TV (Miracast, "Cast screen" or HDMI), it adapts the whole
 * phone, not just Senda, so the TV shows it full screen and with the least delay:
 *
 *  - 60 Hz across the system: the TV shows no more, and composing and encoding 120 frames per second
 *    is what delays and stutters the mirroring.
 *  - Landscape: Telegram, the gallery or any app fill the TV instead of showing as a vertical strip.
 *  - The TV's aspect ratio and resolution (16:9, usually 1920×1080) instead of the phone's (20:9, e.g.
 *    720×1600): the TV shows no black bars and gets a sharp picture, not stretched 720p.
 *
 * The first two use an invisible 1 px window over other apps ("Display over other apps"
 * permission). The aspect ratio needs WRITE_SECURE_SETTINGS, which is only granted through ADB; without it, it is
 * skipped. When casting stops everything goes back exactly to how it was.
 */
object SendaTvMode {

    private const val TAG = "SendaTvMode"
    private const val STATE_PREFS = "senda_tv_mode_state"
    private const val KEY_FORCED = "forced_size_active"
    private const val KEY_ORIGINAL_W = "original_w"
    private const val KEY_ORIGINAL_H = "original_h"
    private const val KEY_ORIGINAL_DENSITY = "original_density"
    // Android user Senda runs as (with USER_CURRENT, -2, Android requires INTERACT_ACROSS_USERS)
    private val userId: Int
        get() = android.os.Process.myUid() / 100_000

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var appContext: Context
    private var overlay: View? = null
    /** true while the phone is adapted to the TV (landscape, 16:9). */
    var active by mutableStateOf(false)
        private set


    /** true while the phone's screen is mirrored to a TV (Miracast, "Cast screen" or HDMI). */
    var tvConnected by mutableStateOf(false)
        private set

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = evaluate()
        override fun onDisplayRemoved(displayId: Int) = evaluate()
        // The phone rotated (or changed size): 16:9 only in landscape
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY && active) applyForRotation()
        }
    }

    /** Called once when the main process starts. */
    fun init(context: Context) {
        if (::appContext.isInitialized) {
            evaluate()
            return
        }
        appContext = context.applicationContext
        // If the process died with the TV aspect ratio in place, return the screen to normal
        if (mirroringDisplay() == null) restoreDisplaySize()
        appContext.getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, mainHandler)
        evaluate()
    }

    fun canDrawOverlay(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun canAdaptAspect(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Checks whether a TV is connected and turns TV mode on or off accordingly. */
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
     * The user is about to pick the TV in Android's menu: meanwhile Senda is in the background and Android
     * freezes its process, so TV mode was not applied until returning to Senda. The service is started now
     * (Senda is still in front) and keeps the process awake; if no TV connects within 2 minutes, it stops.
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

    /** Name of the connected TV, without the Miracast suffix ("[R1]"). */
    var tvName by mutableStateOf<String?>(null)
        private set

    /** TV display where Senda can show its own content (null if no TV is connected). */
    fun tvDisplay(): Display? = if (::appContext.isInitialized) mirroringDisplay() else null

    /**
     * Disconnects the TV without going through Android's settings. Connecting Miracast is only for system apps
     * (CONFIGURE_WIFI_DISPLAY); disconnecting can be requested by any app, through a hidden function (tested on a
     * moto g34; MediaRouter did not do it). If it did not work, the "Cast screen" panel is opened to tap "Disconnect".
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
            // Already active: reapply in case the user changed the settings
            applyForRotation()
            return
        }
        active = true
        Log.i(TAG, "TV conectada (${tv.name} ${tv.mode.physicalWidth}x${tv.mode.physicalHeight}): activando modo TV")
        // The phone is never rotated: the invisible window only sets 60 Hz (the TV shows no more and mirrors with
        // less delay)
        updateOverlay(landscape = false)
        applyForRotation()
        try {
            appContext.startForegroundService(Intent(appContext, SendaTvModeService::class.java))
        } catch (e: Exception) {
            // Android may refuse the service if Senda is in the background: TV mode stays applied
            // while the process lives, and it is restored all the same when casting stops
            Log.w(TAG, "No se pudo iniciar el servicio del modo TV: ${e.message}")
        }
    }

    /**
     * In portrait the phone stays exactly as it is (the TV shows it as is: a portrait picture cannot
     * fill a TV without changing the phone's). When the user rotates the phone (a full-screen video in
     * any app) the picture switches to 16:9 and fills the TV; the phone shows two thin strips at the ends.
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

    // --- 60 Hz and landscape across the system ---

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

    /** Phone display mode with the lowest refresh rate ≥ 59 Hz and the same resolution (0 if there is none). */
    private fun lowestRefreshModeId(): Int {
        val display = appContext.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return 0
        val current = display.mode
        return display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight && it.refreshRate >= 59f }
            .minByOrNull { it.refreshRate }?.modeId ?: 0
    }

    // --- TV aspect ratio ---

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
            // TV aspect ratio using the phone's short side (720×1600 → 720×1280): measured on 2026-10-06 on a
            // moto g34 mirroring to an LG, 0 of 943 frames dropped. Only the size changes, never the density:
            // at the TV's resolution (1080×1920, density 459) the phone dropped 6 % of the frames and, when the
            // density changed, Android restarted Telegram and Senda internally on rotation (a video that would not
            // seek, a tab that "closes"). Even numbers for the encoder
            val phoneShort = minOf(initial.x, initial.y)
            val phoneLong = maxOf(initial.x, initial.y)
            val shortSide = phoneShort and 1.inv()
            val longSide = (phoneShort.toLong() * tvLong / tvShort).toInt().coerceAtMost(phoneLong) and 1.inv()
            if (longSide >= phoneLong - 8) {
                // The phone already has the TV's aspect ratio: if a forced size was left from before, it is removed
                restoreDisplaySize()
                return
            }
            val (w, h) = if (initial.x < initial.y) shortSide to longSide else longSide to shortSide
            if (base.x == w && base.y == h) return
            val state = appContext.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            if (!state.getBoolean(KEY_FORCED, false)) {
                // Save the size the user had (usually the factory one) to give it back exactly
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
            // Only a 2026-10-06 version changed the scale (and saved it): the others do not touch it
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
 * Keeps Senda's process alive while casting lasts, so the screen can be returned
 * to normal as soon as it stops, even if another app is in use.
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
 * The forced TV aspect ratio survives a restart or an update of Senda. Receiving this
 * broadcast starts the process, and [SendaTvMode.init] (from SendaApplication) returns the screen to how it was.
 */
class SendaTvModeRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SendaTvMode.init(context)
    }
}
