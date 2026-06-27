package ovh.adan.opendmo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Estado observable por la UI (el servicio corre aunque la Activity no esté visible). */
object DmoState {
    @Volatile var running = false
    @Volatile var status = ""
    @Volatile var netConnected = false
    val logLines = ArrayDeque<String>()
    @Volatile var listener: (() -> Unit)? = null

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
 */
class DmoService : Service() {
    private var peer: HbpPeer? = null
    private var controller: DmoController? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification(getString(R.string.notif_starting)))
        // WakeLock parcial: la CPU sigue procesando audio/red con la pantalla apagada.
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenDMO::gateway").apply {
                setReferenceCounted(false); acquire()
            }
        }
        val cfg = Config.load(this)

        val ctrl = DmoController(
            context = this, cfg = cfg,
            sender = { pkt -> peer?.sendDmrd(pkt) ?: false },
            onStatus = { s -> DmoState.pushStatus(s); updateNotification(s) },
            log = { DmoState.log(it) },
        )
        val p = HbpPeer(
            context = this, cfg = cfg,
            onDmrd = { ft, dv, src, dst, sid, burst ->
                // gateway: solo bajamos a RF el tráfico del TG configurado (group call)
                if (dst == cfg.talkgroup) ctrl.onNetDmrd(ft, dv, src, dst, sid, burst)
            },
            onState = { s -> DmoState.netConnected = (peer?.connected == true); DmoState.pushStatus(s) },
            log = { DmoState.log(it) },
        )
        peer = p; controller = ctrl

        p.start()
        val ok = ctrl.start()
        if (!ok) DmoState.log(getString(R.string.log_otg_down))
        DmoState.running = true
        return START_STICKY
    }

    override fun onDestroy() {
        controller?.stop()
        peer?.stop()
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
        DmoState.running = false
        DmoState.netConnected = false
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
        return Notification.Builder(this, chId)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(1, buildNotification(text))
    }

    companion object {
        fun start(ctx: Context) {
            val i = Intent(ctx, DmoService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, DmoService::class.java)) }
    }
}
