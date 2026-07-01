package ovh.adan.opendmo

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import ovh.adan.opendmo.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private val ui = Handler(Looper.getMainLooper())
    private val ACTION_USB = "ovh.adan.opendmo.USB_PERMISSION"

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == ACTION_USB) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (granted) launchService() else toast(getString(R.string.toast_usb_denied))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Context.RECEIVER_NOT_EXPORTED else 0
        registerReceiver(usbReceiver, IntentFilter(ACTION_USB), flags)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
        }

        val cfg = Config.load(this)
        loadFields(cfg)
        // ajustes plegados si ya hay config; desplegados en el primer arranque
        setSettingsOpen(cfg.host.isBlank())
        b.btnSettings.setOnClickListener { setSettingsOpen(b.llConfig.visibility != android.view.View.VISIBLE) }
        b.btnToggle.setOnClickListener { if (DmoState.running) stopAll() else startAll() }
        // Minimizar: deja el gateway corriendo en 2º plano y va al fondo (pantalla apagada OK).
        b.btnMinimize.setOnClickListener { moveTaskToBack(true) }
        // Salir: para el servicio y cierra la app por completo.
        b.btnExit.setOnClickListener {
            DmoService.stop(this)
            finishAndRemoveTask()
        }

        DmoState.listener = { ui.post { refresh() } }
        refresh()
        handleUsbAttach(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleUsbAttach(intent)
    }

    /** Si Android nos abre porque se ha enchufado la OpenGD77 (filtro USB del manifest),
     *  ofrecemos arrancar el gateway directamente. */
    private fun handleUsbAttach(intent: Intent?) {
        if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val cfg = Config.load(this)
            if (cfg.host.isNotBlank() && cfg.radioId != 0 && (cfg.dynamicTg || cfg.talkgroup != 0)) {
                if (!DmoState.running) {
                    toast(getString(R.string.toast_radio_start))
                    startAll()
                }
            } else {
                toast(getString(R.string.toast_radio_config))
            }
        }
    }

    override fun onDestroy() {
        DmoState.listener = null
        try { unregisterReceiver(usbReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    /** Pide salir de la optimización de batería para que el gateway no muera en Doze. */
    private fun askIgnoreBatteryOptim() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        }
    }

    private fun startAll() {
        val cfg = readFields() ?: return
        cfg.save(this)
        askIgnoreBatteryOptim()
        // pedir permiso USB para el OpenGD77 (si hace falta) antes de arrancar el servicio
        val usb = getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = MmdvmModem.findDriver(usb)
        if (driver == null) { toast(MmdvmModem.usbInventory(usb)); return }
        if (!usb.hasPermission(driver.device)) {
            val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_USB).setPackage(packageName),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
            usb.requestPermission(driver.device, pi)
            return
        }
        launchService()
    }

    private fun launchService() {
        DmoService.start(this)
        ui.postDelayed({ refresh() }, 400)
    }

    private fun stopAll() {
        DmoService.stop(this)
        ui.postDelayed({ refresh() }, 300)
    }

    private fun refresh() {
        b.btnToggle.text = getString(if (DmoState.running) R.string.btn_stop else R.string.btn_start)
        val net = getString(if (DmoState.netConnected) R.string.net_ok else R.string.net_ko)
        val st = DmoState.status.ifBlank { getString(R.string.st_stopped) }
        b.tvStatus.text = getString(R.string.status_fmt, st, net)
        b.tvLog.text = DmoState.logSnapshot()
        setLed(b.ledNet, DmoState.running && DmoState.netConnected)
        setLed(b.ledRadio, DmoState.running && DmoState.radioConnected)
        b.tvLastHeard.text = DmoState.lastHeard.ifBlank { "—" }
        updateBigTg()
    }

    private fun setLed(led: android.view.View, on: Boolean) {
        led.backgroundTintList = android.content.res.ColorStateList.valueOf(
            getColor(if (on) R.color.led_on else R.color.led_off))
    }

    /** Línea grande del dashboard: TG (o "dinámico") + frecuencia DMO. */
    private fun updateBigTg() {
        val tg = b.etTg.text.toString().trim()
        val freq = b.etFreq.text.toString().trim().ifBlank { "439.025" }
        val tgTxt = if (b.cbDynamic.isChecked) getString(R.string.tg_dynamic)
                    else if (tg.isNotBlank()) "TG $tg" else "TG —"
        b.tvBigTg.text = "$tgTxt · $freq MHz"
    }

    private fun setSettingsOpen(open: Boolean) {
        b.llConfig.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
        b.btnSettings.text = getString(if (open) R.string.lbl_settings_open else R.string.lbl_settings_closed)
    }

    private fun loadFields(c: Config) {
        b.etHost.setText(c.host); b.etPort.setText(c.port.toString())
        b.etPass.setText(c.passphrase)
        b.etRadioId.setText(if (c.radioId != 0) c.radioId.toString() else "")
        b.etSuffix.setText("%02d".format(c.suffix))
        b.etCall.setText(c.callsign)
        b.etTg.setText(if (c.talkgroup != 0) c.talkgroup.toString() else "")
        b.etSlot.setText(c.slot.toString())
        b.etCc.setText(c.colorCode.toString()); b.etFreq.setText(c.freqMHz)
        b.cbDynamic.isChecked = c.dynamicTg
        b.sbPower.progress = c.txPowerPct.coerceIn(0, 100)
        updatePowerHint(b.sbPower.progress)
        b.etOptions.setText(c.options)
        updatePeerHint()
        // recalcular la vista previa del ID de login al teclear
        b.etRadioId.doAfterTextChanged { updatePeerHint() }
        b.etSuffix.doAfterTextChanged { updatePeerHint() }
        // la línea grande del dashboard sigue lo que se teclea en ajustes
        b.etTg.doAfterTextChanged { updateBigTg() }
        b.etFreq.doAfterTextChanged { updateBigTg() }
        b.cbDynamic.setOnCheckedChangeListener { _, _ -> updateBigTg() }
        b.sbPower.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) = updatePowerHint(p)
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
    }

    /** Etiqueta del slider: % + vatiaje aproximado (escalón OpenGD77 más cercano). 100% = la radio decide. */
    private fun updatePowerHint(pct: Int) {
        if (pct >= 100) { b.tvPower.text = getString(R.string.power_radio); return }
        val steps = doubleArrayOf(0.05, 0.25, 0.5, 0.75, 1.0, 2.0, 3.0, 4.0, 5.0)
        val target = pct / 100.0 * 5.0
        val w = steps.minByOrNull { Math.abs(it - target) } ?: 1.0
        val wTxt = if (w < 1.0) "${Math.round(w * 1000)} mW"
                   else "${if (w == Math.floor(w)) w.toInt().toString() else w.toString()} W"
        b.tvPower.text = getString(R.string.power_fmt, pct, wTxt)
    }

    private fun updatePeerHint() {
        val id = b.etRadioId.text.toString().trim().toIntOrNull() ?: 0
        val sfx = (b.etSuffix.text.toString().trim().toIntOrNull() ?: 0).coerceIn(0, 99)
        if (id != 0) b.tvPeerId.text = getString(R.string.peer_login_fmt, id * 100 + sfx)
        else b.tvPeerId.text = getString(R.string.hint_peer)
    }

    private fun readFields(): Config? {
        fun i(s: String, def: Int) = s.trim().toIntOrNull() ?: def
        val c = Config(
            host = b.etHost.text.toString().trim(),
            port = i(b.etPort.text.toString(), 62031),
            passphrase = b.etPass.text.toString(),
            radioId = i(b.etRadioId.text.toString(), 0),
            suffix = i(b.etSuffix.text.toString(), 1).coerceIn(0, 99),
            callsign = b.etCall.text.toString().trim(),
            talkgroup = i(b.etTg.text.toString(), 0),
            slot = i(b.etSlot.text.toString(), 2).coerceIn(1, 2),
            colorCode = i(b.etCc.text.toString(), 1).coerceIn(0, 15),
            freqMHz = b.etFreq.text.toString().trim().ifBlank { "439.025" },
            dynamicTg = b.cbDynamic.isChecked,
            txPowerPct = b.sbPower.progress.coerceIn(0, 100),
            options = b.etOptions.text.toString().trim(),
        )
        if (c.host.isBlank()) { toast(getString(R.string.toast_need_host)); return null }
        if (c.radioId == 0) { toast(getString(R.string.toast_need_dmrid)); return null }
        if (!c.dynamicTg && c.talkgroup == 0) { toast(getString(R.string.toast_need_tg)); return null }
        return c
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
