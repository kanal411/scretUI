package com.example.scretui

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow

// Współdzielony stan serwisu – jedyne źródło prawdy o BLE dla UI.
// Aktywność tylko go obserwuje; połączenie GATT prowadzi wyłącznie serwis (jeden klient).
object BleServiceState {
    val status = MutableStateFlow("Bezczynny")
    val running = MutableStateFlow(false)
}

class mainService : Service() {

    private lateinit var bleManager: BleManager
    private lateinit var repository: ToDoRepository
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var backgroundJob: Job? = null

    @Volatile
    private var cachedToDoList: List<String> = emptyList()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        repository = ToDoRepository(this)

        bleManager = BleManager(this).apply {
            onStatus = { msg ->
                updateNotification(msg)
                BleServiceState.status.value = msg
            }
            onError = { msg ->
                updateNotification("Błąd: $msg")
                BleServiceState.status.value = "⚠️ $msg"
            }
            getToDoList = { cachedToDoList }   // <- DODANE: synchroniczny odczyt z cache
        }

        // Nasłuchuj zmian w DataStore i aktualizuj cache
        serviceScope.launch {
            repository.toDoListFlow.collect { lista ->
                cachedToDoList = lista
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val initialNotification = createNotification("Uruchamianie serwisu...")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Typ `location` dokładamy tylko, gdy lokalizacja jest przyznana – inaczej na
            // Androidzie 14+ start usługi z tym typem rzuca wyjątkiem. Z typem `location`
            // FusedLocation działa w tle przy uprawnieniu "tylko podczas używania".
            var serviceType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (hasLocationPermission()) {
                serviceType = serviceType or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
            startForeground(NOTIFICATION_ID, initialNotification, serviceType)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        BleServiceState.running.value = true

        if (backgroundJob?.isActive != true) {
            backgroundJob = serviceScope.launch {
                bleManager.backgroundManager()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        backgroundJob?.cancel()
        serviceScope.cancel()
        bleManager.disconnect()
        BleServiceState.running.value = false
        BleServiceState.status.value = "Zatrzymany"
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BLE Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Serwis działa")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "ble_service_channel"
    }
}
