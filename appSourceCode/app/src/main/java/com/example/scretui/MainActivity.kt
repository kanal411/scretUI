package com.example.scretui

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

class MainActivity : AppCompatActivity() {

    // Adres MAC Twojego ESP32
    private val ESP32_MAC = "XX:XX:XX:XX:XX:XX" // change to your esp32 mac adress

    private lateinit var btnConnect:  Button
    private lateinit var btnRemoveTask:  Button
    private lateinit var btnAddTask:  Button
    private lateinit var btnSend:     Button
    private lateinit var etAddTask:   EditText
    private lateinit var etRemoveTask:   EditText
    private lateinit var tvStatus:    TextView
    private lateinit var toDoListInter: TextView

    private lateinit var repository: ToDoRepository
    private val toDoList: MutableList<String> = mutableListOf()

    // -------------------------------------------------------------------------
    //  Launcher do żądania uprawnień BLE
    // -------------------------------------------------------------------------
    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Wymagany jest tylko Bluetooth; lokalizacja jest opcjonalna (pogoda → domyślne współrzędne).
        if (bluetoothPermissions().all { isGranted(it) }) {
            startBleService()
        } else {
            updateStatus("Brak wymaganych uprawnień Bluetooth")
        }
    }

    // Launcher do włączenia Bluetooth (jeśli wyłączony)
    private val enableBtLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            requestPermissionsAndConnect()
        } else {
            updateStatus("Bluetooth nie został włączony")
        }
    }

    // -------------------------------------------------------------------------
    //  Cykl życia
    // -------------------------------------------------------------------------


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Widoki
        btnConnect = findViewById(R.id.btnConnect)

        etAddTask  = findViewById(R.id.addTaskU)

        etRemoveTask  = findViewById(R.id.removeTaskU)

        tvStatus   = findViewById(R.id.tvStatus)

        btnRemoveTask = findViewById(R.id.sendTaskRemovalU)

        btnAddTask = findViewById(R.id.sendTaskU)


        toDoListInter = findViewById<TextView>(R.id.toDoList)

        repository = ToDoRepository(this)

        lifecycleScope.launch {
            repository.toDoListFlow.collect { zapisana ->
                toDoList.clear()
                toDoList.addAll(zapisana)
                refreshToDo()
            }
        }


        // Aktywność NIE prowadzi własnego połączenia BLE – robi to wyłącznie serwis (jeden klient GATT).
        // Tu tylko obserwujemy współdzielony stan i odzwierciedlamy go w UI.
        lifecycleScope.launch {
            BleServiceState.status.collect { msg ->
                updateStatus(msg)
            }
        }
        lifecycleScope.launch {
            BleServiceState.running.collect { running ->
                btnConnect.text = if (running) "Disconnect" else "Connect"
            }
        }

        // Przyciski
        btnConnect.setOnClickListener {
            if (BleServiceState.running.value) {
                stopBleService()
            } else {
                // Najpierw BT + uprawnienia; po ich przyznaniu wystartuje serwis.
                checkBluetoothAndConnect()
            }
        }

        btnAddTask.setOnClickListener {
            val text = etAddTask.text.toString().trim()
            if (text.isEmpty()) {
                Toast.makeText(this, "Write something", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            toDoList.add(text)
            lifecycleScope.launch {
                repository.zapisz(toDoList)
            }
            refreshToDo()
            etAddTask.setText("")
        }

        btnRemoveTask.setOnClickListener {
            val text = etRemoveTask.text.toString().trim()
            val ktory = text.toIntOrNull()

            if (text.isEmpty()) {
                Toast.makeText(this, "Write something", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (ktory != null) {
                if (ktory in 1..toDoList.size) {
                    toDoList.removeAt(ktory - 1)
                    lifecycleScope.launch {
                        repository.zapisz(toDoList)
                    }
                    refreshToDo()
                }
            } else {
                Toast.makeText(this, "Write number of task that you want to remove", Toast.LENGTH_SHORT).show()
            }

            etRemoveTask.setText("")
        }

//        btnSend.setOnClickListener {
//            val text = etMessage.text.toString().trim()
//            if (text.isEmpty()) {
//                Toast.makeText(this, "Wpisz wiadomość", Toast.LENGTH_SHORT).show()
//                return@setOnClickListener
//            }
//
//            // Wywołanie funkcji suspend z korutyny
//            lifecycleScope.launch {
//                val success = bleManager.sendText(text)
//                if (success) {
//                    etMessage.setText("")
//                    Toast.makeText(this@MainActivity, "Wysłano: $text", Toast.LENGTH_SHORT).show()
//                }
//            }
//        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Serwis działa niezależnie od aktywności – NIE zatrzymujemy go tutaj
        // (to on utrzymuje cykl BLE w tle). Zatrzymanie tylko przez przycisk Disconnect.
    }

    // -------------------------------------------------------------------------
    //  Logika połączenia
    // -------------------------------------------------------------------------

    /** Sprawdza czy Bluetooth jest włączony, jeśli nie – pyta użytkownika */
    private fun checkBluetoothAndConnect() {
        val btAdapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (btAdapter == null) {
            updateStatus("Urządzenie nie obsługuje Bluetooth")
            return
        }
        if (!btAdapter.isEnabled) {
            enableBtLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
        } else {
            requestPermissionsAndConnect()
        }
    }

    private fun refreshToDo()
    {
        toDoListInter.text = ""
        var i = 1
        for (zadanie in toDoList)
        {
            toDoListInter.append(i.toString() + ". " + zadanie + "\n")
            i++
        }
    }

    /** Uprawnienia WYMAGANE do BLE (bez nich serwis nie ma sensu). */
    private fun bluetoothPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN
            )
        } else {
            arrayOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        }

    private fun isGranted(perm: String): Boolean =
        ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED

    /**
     * Prosi o uprawnienia. Bluetooth jest wymagany; lokalizacja jest OPCJONALNA
     * (pogoda działa na fallbacku Zabrza), ale prosimy o nią, bo pozwala usłudze typu
     * `location` pobierać pozycję w tle. Odmowa lokalizacji nie blokuje startu serwisu.
     */
    private fun requestPermissionsAndConnect() {
        val btOk = bluetoothPermissions().all { isGranted(it) }
        val locOk = isGranted(Manifest.permission.ACCESS_FINE_LOCATION)

        if (btOk && locOk) {
            startBleService()
            return
        }

        // brakujące BT + (opcjonalnie) lokalizacja – w jednym przebiegu
        val toRequest = (bluetoothPermissions().toList() +
                Manifest.permission.ACCESS_FINE_LOCATION).distinct().toTypedArray()
        permissionsLauncher.launch(toRequest)
    }

    /** Startuje serwis BLE (foreground). Całe połączenie prowadzi serwis. */
    private fun startBleService() {
        val intent = Intent(this, mainService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        updateStatus("🔄 Uruchamianie serwisu...")
    }

    /** Zatrzymuje serwis BLE (przycisk Disconnect). */
    private fun stopBleService() {
        stopService(Intent(this, mainService::class.java))
        updateStatus("Zatrzymywanie...")
    }

    // -------------------------------------------------------------------------
    //  Helper
    // -------------------------------------------------------------------------

    fun updateStatus(msg: String) {
        runOnUiThread { tvStatus.text = msg }
    }
}
