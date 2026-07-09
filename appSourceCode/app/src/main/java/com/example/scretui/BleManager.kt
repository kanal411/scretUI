package com.example.scretui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import java.time.Duration
import java.time.LocalTime
import java.util.UUID
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.location.FusedLocationProviderClient
import android.location.Location

private const val ESP32_MAC = "XX:XX:XX:XX:XX:XX" // change to your esp32 mac adress
private var DEFAULT_LAT = 52.2297 // Warsaw center (fallback if location is unavailable)
private var DEFAULT_LON = 21.0122

data class HourForecast(
    val time: String,
    val temperature: Double,
    val rainProbability: Int,
    val cloudCover: Int
)

private val client = OkHttpClient()

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ab")
        val CHARACTERISTIC_UUID: UUID = UUID.fromString("abcd1234-5678-90ab-cdef-1234567890ab")
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        manager?.adapter
    }

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

    private var servicesReadyDeferred: CompletableDeferred<Unit>? = null
    private var pendingWriteDeferred: CompletableDeferred<Int>? = null
    private var disconnectDeferred: CompletableDeferred<Unit>? = null

    private val writeMutex = Mutex()

    // Public callbacks
    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onStatus: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    var getToDoList: (() -> List<String>)? = null

    private fun hasBluetoothConnectPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_CONNECT
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun status(msg: String) {
        onStatus?.invoke(msg)
    }

    private fun error(msg: String) {
        onError?.invoke(msg)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            gattStatus: Int,
            newState: Int
        ) {
            if (gattStatus != BluetoothGatt.GATT_SUCCESS) {
                error("GATT error: $gattStatus")
                servicesReadyDeferred?.completeExceptionally(IOException("GATT error: $gattStatus"))
                pendingWriteDeferred?.completeExceptionally(IOException("GATT error: $gattStatus"))
                // jeśli aktywnie rozłączamy – odblokuj czekającego; inaczej posprzątaj sam
                val d = disconnectDeferred
                if (d != null) d.complete(Unit) else cleanupGatt()
                return
            }

            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    status("Connected. Discovering services...")
                    val started = gatt.discoverServices()
                    if (!started) {
                        error("Failed to start service discovery")
                        servicesReadyDeferred?.completeExceptionally(
                            IOException("discoverServices() returned false")
                        )
                        cleanupGatt()
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    status("Disconnected")
                    servicesReadyDeferred?.completeExceptionally(IOException("Disconnected"))
                    pendingWriteDeferred?.completeExceptionally(IOException("Disconnected"))
                    onDisconnected?.invoke()
                    // teardown sekwencjonowany: dokończ disconnectAndWait(); przy nieoczekiwanym
                    // rozłączeniu (brak czekającego) zamknij GATT od razu
                    val d = disconnectDeferred
                    if (d != null) d.complete(Unit) else cleanupGatt()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, gattStatus: Int) {
            if (gattStatus != BluetoothGatt.GATT_SUCCESS) {
                error("Service discovery failed: $gattStatus")
                servicesReadyDeferred?.completeExceptionally(
                    IOException("Services discovery failed: $gattStatus")
                )
                return
            }

            val service = gatt.getService(SERVICE_UUID)
            if (service == null) {
                error("ESP32 Service not found")
                servicesReadyDeferred?.completeExceptionally(IOException("Service not found"))
                return
            }

            val ch = service.getCharacteristic(CHARACTERISTIC_UUID)
            if (ch == null) {
                error("ESP32 Characteristic not found")
                servicesReadyDeferred?.completeExceptionally(IOException("Characteristic not found"))
                return
            }

            writeCharacteristic = ch
            status("Ready")
            onConnected?.invoke()
            servicesReadyDeferred?.complete(Unit)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            pendingWriteDeferred?.complete(status)
        }
    }

    suspend fun getWeather(): List<HourForecast> = withContext(Dispatchers.IO) {
        val weatherUrl =
            "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=$DEFAULT_LAT" +
                    "&longitude=$DEFAULT_LON" +
                    "&hourly=temperature_2m,precipitation_probability,cloudcover" +
                    "&forecast_hours=12" +
                    "&timezone=auto"

        val request = Request.Builder().url(weatherUrl).build()

        val weatherJson = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body?.string() ?: throw IOException("Brak odpowiedzi")
        }

        val root = JSONObject(weatherJson)
        val hourly = root.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val temps = hourly.getJSONArray("temperature_2m")
        val probs = hourly.getJSONArray("precipitation_probability")
        val clouds = hourly.getJSONArray("cloudcover")

        buildList {
            for (i in 0 until times.length()) {
                add(
                    HourForecast(
                        time = times.getString(i),
                        temperature = temps.optDouble(i),
                        rainProbability = probs.optInt(i),
                        cloudCover = clouds.optInt(i)
                    )
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun getCurrentLocation(): Location? =
        suspendCancellableCoroutine<Location?> { cont ->

            val fusedLocationClient =
                LocationServices.getFusedLocationProviderClient(context)

            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                null
            ).addOnSuccessListener { location ->
                if (cont.isActive) cont.resume(location, onCancellation = null)
            }.addOnFailureListener {
                if (cont.isActive) cont.resume(null, onCancellation = null)
            }
        }

    fun forecastListToString(list: List<HourForecast>): String {
        return list.joinToString(prefix = "[", postfix = "]", separator = ", ") { item ->
            "[${item.time}, ${item.temperature}, ${item.rainProbability}, ${item.cloudCover}]"
        }
    }

    fun listToFormattedString(list: List<String>): String {
        return list.joinToString(
            prefix = "[",
            postfix = "]",
            separator = ", "
        ) { "\"$it\"" }
    }

    suspend fun backgroundManager() {
        var consecutiveFailures = 0
        while (currentCoroutineContext().isActive) {
            status("Connecting...")
            val connected = connect(ESP32_MAC)
            if (!connected) {
                consecutiveFailures++
                // Eskalujący backoff: nie młóć stosu BT (po wielu szybkich próbach Android wpada
                // w GATT error 147). ESP32 nadaje ~2 min po wybudzeniu, więc i tak wpadniemy w okno.
                val backoff = when {
                    consecutiveFailures <= 1 -> 5_000L
                    consecutiveFailures == 2 -> 10_000L
                    consecutiveFailures == 3 -> 20_000L
                    else -> 30_000L
                }
                delay(backoff)
                continue
            }
            consecutiveFailures = 0

            var sleepMs = 0L
            try {
                status("Fetching weather...")

                val location = getCurrentLocation()

                if (location != null) {
                    DEFAULT_LAT = location.latitude
                    DEFAULT_LON = location.longitude
                }

                val forecastList = getWeather()
                val forecastString = forecastListToString(forecastList)
                status("Sending forecast...")
                withTimeout(15_000) {
                    sendText(forecastString + "\n")
                }

                val data = LocalDateTime.now()
                val formatter = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy")
                val dataString = data.format(formatter)
                withTimeout(15_000) {
                    sendText(dataString + "\n")
                }

                val toDoString = listToFormattedString(getToDoList?.invoke() ?: emptyList())
                withTimeout(15_000) {
                    sendText(toDoString + "\n")
                }

                val teraz = LocalTime.now()

                val nextFive = teraz
                    .plusMinutes((5 - (teraz.minute % 5)).toLong())
                    .withSecond(30)
                    .withNano(0)

                val target = if (Duration.between(teraz, nextFive) < Duration.ofMinutes(2)) {
                    nextFive.plusMinutes(5)
                } else {
                    nextFive
                }

                val millis = Duration.between(teraz, target).toMillis().coerceAtLeast(0L)
                status("Sending delay: $millis ms")
                withTimeout(15_000) {
                    sendText(millis.toString() + "\n")
                }

                sleepMs = (millis - 2000L).coerceAtLeast(0L)
            } catch (e: Exception) {
                error("Loop error: ${e.message}")
                sleepMs = 5000L
            } finally {
                status("Disconnecting...")
                disconnectAndWait()
            }

            status("Sleeping for $sleepMs ms")
            delay(sleepMs)
        }
    }

    suspend fun connect(macAddress: String): Boolean {
        val adapter = bluetoothAdapter ?: run {
            error("Bluetooth not supported")
            return false
        }

        if (!adapter.isEnabled) {
            error("Bluetooth disabled")
            return false
        }

        if (!hasBluetoothConnectPermission()) {
            error("Missing BLUETOOTH_CONNECT permission")
            return false
        }

        disconnectAndWait()

        return try {
            val deferred = CompletableDeferred<Unit>()
            servicesReadyDeferred = deferred

            val device = adapter.getRemoteDevice(macAddress)
            // autoConnect = false: bezpośrednie połączenie. autoConnect=true potrafi zawieszać
            // discoverServices() przy pierwszym połączeniu (pusty cache GATT). Wyścig czasowy
            // "telefon budzi się przed advertisingiem ESP" rozwiązuje krótki retry w pętli.
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(context, false, gattCallback)
            }

            withTimeout(30_000) {
                deferred.await()
            }
            true
        } catch (e: Exception) {
            error("Connection failed: ${e.message}")
            cleanupGatt()
            false
        } finally {
            servicesReadyDeferred = null
        }
    }

    suspend fun sendText(text: String): Boolean = writeMutex.withLock {
        val g = gatt ?: run {
            error("No active connection")
            return@withLock false
        }
        val ch = writeCharacteristic ?: run {
            error("Characteristic not ready")
            return@withLock false
        }

        return try {
            val bytes = text.toByteArray(Charsets.UTF_8)

            // Bezpieczny rozmiar, jeśli nie jesteś pewny MTU
            val maxChunkSize = 20

            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + maxChunkSize, bytes.size)
                val chunk = bytes.copyOfRange(offset, end)

                val deferred = CompletableDeferred<Int>()
                pendingWriteDeferred = deferred

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val result = g.writeCharacteristic(
                        ch,
                        chunk,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    )
                    if (result != BluetoothStatusCodes.SUCCESS) {
                        throw IOException("writeCharacteristic failed: $result")
                    }
                } else {
                    @Suppress("DEPRECATION")
                    ch.value = chunk
                    @Suppress("DEPRECATION")
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    if (!g.writeCharacteristic(ch)) {
                        throw IOException("writeCharacteristic returned false")
                    }
                }

                val writeStatus = withTimeout(10_000) { deferred.await() }
                pendingWriteDeferred = null

                if (writeStatus != BluetoothGatt.GATT_SUCCESS) {
                    error("Write error: $writeStatus")
                    return@withLock false
                }

                offset = end
            }

            status("Sent successfully")
            true
        } catch (e: Exception) {
            error("Send error: ${e.message}")
            false
        } finally {
            pendingWriteDeferred = null
        }
    }

    // Natychmiastowe rozłączenie (przycisk UI / onDestroy) – najlepszy wysiłek, bez czekania.
    fun disconnect() {
        try {
            gatt?.disconnect()
        } catch (_: Exception) {
        } finally {
            cleanupGatt()
        }
    }

    // Sekwencjonowane rozłączenie dla pętli automatycznej: disconnect() -> czekaj na callback
    // STATE_DISCONNECTED -> dopiero wtedy close(). Wywołanie close() tuż po disconnect() potrafi
    // zostawić "wiszące" połączenie w systemowym stosie BT, przez co kolejne connectGatt() do tego
    // samego MAC-a zawiesza się na "discovering services". Timeout 2 s chroni przed zawisem, gdy
    // callback nie przyjdzie (link już martwy).
    suspend fun disconnectAndWait() {
        val g = gatt ?: run {
            cleanupGatt()
            return
        }
        val deferred = CompletableDeferred<Unit>()
        disconnectDeferred = deferred
        try {
            g.disconnect()
            withTimeoutOrNull(2_000) { deferred.await() }
        } catch (_: Exception) {
        } finally {
            disconnectDeferred = null
            cleanupGatt()
        }
    }

    private fun cleanupGatt() {
        try {
            // anuluje też oczekujące, niezakończone żądanie połączenia (autoConnect=false),
            // żeby nie kumulowały się w stosie BT (objaw: GATT error 147)
            gatt?.disconnect()
        } catch (_: Exception) {
        }
        try {
            gatt?.close()
        } catch (_: Exception) {
        } finally {
            gatt = null
            writeCharacteristic = null
        }
    }

    private fun failPendingWrite(ex: Exception) {
        pendingWriteDeferred?.completeExceptionally(ex)
        pendingWriteDeferred = null
    }
}
