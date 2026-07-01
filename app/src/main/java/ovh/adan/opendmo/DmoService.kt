package ovh.adan.opendmo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager

/** Estado observable por la UI (el servicio corre aunque la Activity no esté visible). */
object DmoState {
    @Volatile var running = false
    @Volatile var status = ""
    @Volatile var netConnected = false
    @Volatile var radioConnected = false
    @Volatile var lastHeard = ""
    val logLines = ArrayDeque<String>()
    @Volatile var listener: (() -> Unit)? = null

    fun pushLastHeard(s: String) { lastHeard = s; notifyUi() }

    fun log(line: String) {
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > 60) logLines.removeFirst()
        }
        notifyUi()
    }
    fun pushStatus(s: String) { status = s; notifyUi() }
    fun logSnapshot(): String = synchronized(logLines) { logLines.joinToString("\n") }
    private fun notifyUi() { listener?.invoke() }
}

/**
 * Servicio en primer plano del gateway DMO. Une el peer HBP y el controlador OTG:
 *   peer.onDmrd  -> (filtro TG) -> controller.onNetDmrd  (red -> RF)
 *   controller.sender -> peer.sendDmrd                   (RF -> red)
 *
 * Robustez OTG: receiver de ATTACH/DETACH (si la radio se desenchufa se cierra el módem;
 * al enchufarla se reabre solo, sin pasar por la Activity) + watchdog cada 10 s que
 * reabre el módem si sus hilos murieron (error USB) y la radio sigue presente.
 */
class DmoService : Service() {
    private var peer: HbpPeer? = null
    private var controller: DmoController? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    // OJO: estos broadcasts saltan con CUALQUIER dispositivo USB; se comprueba el estado
    // real del módem (con un margen para que el rx-loop note el fallo / termine la
    // enumeración) en vez de fiarse del evento.
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> handler.postDelayed({
                    val c = controller ?: return@postDelayed
                    if (c.active && !c.modemUp()) {
                        c.stop()
                        DmoState.radioConnected = false
                        DmoState.log(getString(R.string.st_radio_out))
                        DmoState.pushStatus(getString(R.string.st_radio_out))
                        updateNotification(getString(R.string.st_radio_out))
                    }
                }, 500)
                UsbManager.ACTION_USB_DEVICE_ATTACHED ->
                    handler.postDelayed({ reopenOtg() }, 800)
            }
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            val c = controller
            if (c != null && (!c.active || !c.modemUp())) reopenOtg()
            DmoState.radioConnected = controller?.modemUp() == true
            handler.postDelayed(this, 10_000)
        }
    }

    /** Reabre el módem si hay radio a la vista y no está ya funcionando. */
    private fun reopenOtg() {
        val c = controller ?: return
        if (c.active && c.modemUp()) return
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        if (MmdvmModem.findDriver(usb) == null) return
        DmoState.log("reabriendo módem OTG")
        val ok = c.start()
        DmoState.radioConnected = ok && c.modemUp()
        if (!ok) DmoState.log(getString(R.string.log_otg_down))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }

        startForeground(1, buildNotification(getString(R.string.notif_starting)))
        // WakeLock parcial: la CPU sigue procesando audio/red con la pantalla apagada.
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenDMO::gateway").apply {
                setReferenceCounted(false); acquire()
            }
        }
        // re-arranque (botón START con el servicio ya vivo): tirar lo anterior primero
        controller?.stop(); peer?.stop()
        val cfg = Config.load(this)
        val lookup = DmrIdLookup(this) { DmoState.log(it) }

        val ctrl = DmoController(
            context = this, cfg = cfg,
            sender = { pkt -> peer?.sendDmrd(pkt) ?: false },
            onStatus = { s -> DmoState.pushStatus(s); updateNotification(s) },
            log = { DmoState.log(it) },
            nameOf = { id -> lookup.name(id) },
            onCall = { DmoState.pushLastHeard(it) },
        )
        val p = HbpPeer(
            context = this, cfg = cfg,
            onDmrd = { ft, dv, src, dst, sid, burst ->
                // En dinámico (BM/TGIF/ADN) el master ya solo envía los TG suscritos -> pasamos todo.
                // En estático solo bajamos a RF el TG configurado.
                if (cfg.dynamicTg || dst == cfg.talkgroup) ctrl.onNetDmrd(ft, dv, src, dst, sid, burst)
            },
            onState = { s -> DmoState.netConnected = (peer?.connected == true); DmoState.pushStatus(s) },
            log = { DmoState.log(it) },
        )
        peer = p; controller = ctrl

        if (!usbRegistered) {
            val f = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                registerReceiver(usbReceiver, f, Context.RECEIVER_EXPORTED)
            else registerReceiver(usbReceiver, f)
            usbRegistered = true
        }
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, 10_000)

        p.start()
        val ok = ctrl.start()
        DmoState.radioConnected = ok && ctrl.modemUp()
        if (!ok) DmoState.log(getString(R.string.log_otg_down))
        DmoState.running = true
        return START_STICKY
    }

    private var usbRegistered = false

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (usbRegistered) { try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}; usbRegistered = false }
        controller?.stop()
        peer?.stop()
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
        DmoState.running = false
        DmoState.netConnected = false
        DmoState.radioConnected = false
        DmoState.pushStatus(getString(R.string.st_stopped))
        super.onDestroy()
    }

    private fun buildNotification(text: String): Notification {
        val chId = "opendmo"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(chId) == null) {
                nm.createNotificationChannel(NotificationChannel(chId, "OpenDMO", NotificationManager.IMPORTANCE_LOW))
            }
        }
        val piFlags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openApp = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), piFlags)
        val stop = PendingIntent.getService(this, 2,
            Intent(this, DmoService::class.java).setAction(ACTION_STOP), piFlags)
        return Notification.Builder(this, chId)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_antenna)
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_stat_antenna),
                getString(R.string.notif_stop), stop).build())
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, buildNotification(text))
    }

    companion object {
        const val ACTION_STOP = "ovh.adan.opendmo.STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, DmoService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, DmoService::class.java)) }
    }
}
