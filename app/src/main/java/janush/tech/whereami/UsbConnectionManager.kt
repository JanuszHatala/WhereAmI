package janush.tech.whereami

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class UsbConnectionManager private constructor(private val context: Context) {

    companion object {
        @Volatile
        private var INSTANCE: UsbConnectionManager? = null

        fun getInstance(context: Context): UsbConnectionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UsbConnectionManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val _isUsbConnected = MutableStateFlow(false)
    val isUsbConnected: StateFlow<Boolean> = _isUsbConnected.asStateFlow()

    init {
        registerUsbReceiver()
        checkInitialUsbState()
    }

    private fun checkInitialUsbState() {
        try {
            // Check battery plug state
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1) ?: -1
            if (plugged == BatteryManager.BATTERY_PLUGGED_USB) {
                _isUsbConnected.value = true
                return
            }

            // Check sticky USB_STATE broadcast
            val usbIntent = context.registerReceiver(null, IntentFilter("android.hardware.usb.action.USB_STATE"))
            val connected = usbIntent?.getBooleanExtra("connected", false) ?: false
            _isUsbConnected.value = connected
        } catch (_: Exception) {
            _isUsbConnected.value = false
        }
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction("android.hardware.usb.action.USB_STATE")
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    "android.hardware.usb.action.USB_STATE" -> {
                        val connected = intent.getBooleanExtra("connected", false)
                        _isUsbConnected.value = connected
                        TelemetryLogger.log("USB", "USB_STATE connected=$connected")
                    }
                    Intent.ACTION_BATTERY_CHANGED -> {
                        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                        if (plugged == BatteryManager.BATTERY_PLUGGED_USB) {
                            _isUsbConnected.value = true
                        } else if (plugged != -1 && !_isUsbConnected.value) {
                            // If plugged into AC or wireless, verify via sticky USB_STATE
                            val usbIntent = context.registerReceiver(null, IntentFilter("android.hardware.usb.action.USB_STATE"))
                            _isUsbConnected.value = usbIntent?.getBooleanExtra("connected", false) ?: false
                        }
                    }
                    Intent.ACTION_POWER_DISCONNECTED -> {
                        _isUsbConnected.value = false
                        TelemetryLogger.log("USB", "Power disconnected, USB set to false")
                    }
                }
            }
        }
        context.registerReceiver(receiver, filter)
    }
}
