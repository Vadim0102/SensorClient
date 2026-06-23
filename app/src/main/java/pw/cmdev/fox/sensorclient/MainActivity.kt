package pw.cmdev.fox.sensorclient

import android.content.Context
import android.hardware.*
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import org.json.JSONObject
import kotlin.math.*

class MainActivity : AppCompatActivity(), SensorEventListener {

    private val TAG = "SensorClient"
    private val PREFS_NAME = "SensorClientPrefs"
    private val KEY_SERVER_ADDRESS = "server_address"

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null

    private var sendOrientation = false
    
    private var lastSent = 0L
    private var intervalMs = 16L
    
    // Переменные для калибровки
    private var baseQ = FloatArray(4) { 0f }
    private var hasCalibration = false
    private var currentQuaternion = FloatArray(4) { 0f } // Хранит последнее значение до калибровки

    private val client = OkHttpClient()
    private var ws: WebSocket? = null

    // Элементы UI
    private lateinit var addressInput: EditText
    private lateinit var connectButton: Button
    private lateinit var calibrateButton: Button
    private lateinit var statusText: TextView

    // Жизненный цикл подключения
    enum class ConnectionState {
        DISCONNECTED, CONNECTING, CONNECTED
    }
    private var connectionState = ConnectionState.DISCONNECTED

    private fun quatFromRotationVector(v: FloatArray): FloatArray {
        val q = FloatArray(4)
        SensorManager.getQuaternionFromVector(q, v) // Заполняет q как [w, x, y, z] [1]
        
        // Переводим в формат Three.js: [x, y, z, w]
        return floatArrayOf(q[1], q[2], q[3], q[0])
    }

    fun inverse(q: FloatArray): FloatArray {
        return floatArrayOf(-q[0], -q[1], -q[2], q[3])
    }
    
    fun multiplyQuaternions(a: FloatArray, b: FloatArray): FloatArray {
        val ax = a[0]; val ay = a[1]; val az = a[2]; val aw = a[3]
        val bx = b[0]; val by = b[1]; val bz = b[2]; val bw = b[3]
    
        return floatArrayOf(
            aw*bx + ax*bw + ay*bz - az*by,
            aw*by - ax*bz + ay*bw + az*bx,
            aw*bz + ax*by - ay*bx + az*bw,
            aw*bw - ax*bx - ay*by - az*bz
        )
    }

    fun calibrate() {
        baseQ = currentQuaternion.copyOf()
        hasCalibration = true
        Log.d(TAG, "Calibrated offset applied.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Инициализация UI
        addressInput = findViewById(R.id.addressInput)
        connectButton = findViewById(R.id.connectButton)
        calibrateButton = findViewById(R.id.calibrateButton)
        statusText = findViewById(R.id.statusText)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        // Восстановление адреса
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastAddress = prefs.getString(KEY_SERVER_ADDRESS, "192.168.1.1:8765") ?: "192.168.1.1:8765"
        addressInput.setText(lastAddress)

        // Логика нажатия на кнопку Подключения/Отключения
        connectButton.setOnClickListener {
            when (connectionState) {
                ConnectionState.DISCONNECTED -> {
                    val address = addressInput.text.toString().trim()
                    if (address.isNotEmpty()) {
                        prefs.edit().putString(KEY_SERVER_ADDRESS, address).apply()
                        connectWebSocket(address)
                    } else {
                        statusText.text = "Please enter server address"
                    }
                }
                ConnectionState.CONNECTING, ConnectionState.CONNECTED -> {
                    disconnectWebSocket()
                }
            }
        }

        calibrateButton.setOnClickListener {
            calibrate()
        }

        // Автоподключение при старте
        connectWebSocket(lastAddress)
    }

    private fun connectWebSocket(address: String) {
        try {
            val baseTarget = if (address.startsWith("ws://") || address.startsWith("wss://")) {
                address
            } else {
                "ws://$address"
            }

            // Автоматически добавляем суффикс пути для телефона, если его нет
            val url = if (baseTarget.endsWith("/phone") || baseTarget.endsWith("/viewer")) {
                baseTarget
            } else {
                if (baseTarget.endsWith("/")) "${baseTarget}phone" else "$baseTarget/phone"
            }

            val request = Request.Builder()
                .url(url)
                .build()

            ws?.close(1000, "Reconnecting")
            updateUI(ConnectionState.CONNECTING, "Connecting to $url...")

            ws = client.newWebSocket(request, object : WebSocketListener() {

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket connected")
                    updateUI(ConnectionState.CONNECTED, "Connected to: $address")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val json = JSONObject(text)
                        if (json.getString("type") == "config") {
                            val rateHz = json.getInt("rate_hz")
                            intervalMs = 1000L / rateHz
                            sendOrientation = json.getBoolean("send_orientation")

                            runOnUiThread {
                                if (sendOrientation) {
                                    sensorManager.registerListener(
                                        this@MainActivity,
                                        rotationSensor,
                                        SensorManager.SENSOR_DELAY_GAME
                                    )
                                } else {
                                    sensorManager.unregisterListener(this@MainActivity)
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing config: ${e.message}")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket error: ${t.message}")
                    updateUI(ConnectionState.DISCONNECTED, "Connection failed: ${t.message}")
                    sensorManager.unregisterListener(this@MainActivity)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closed: $reason")
                    updateUI(ConnectionState.DISCONNECTED, "Disconnected")
                    sensorManager.unregisterListener(this@MainActivity)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Connection error: ${e.message}")
            updateUI(ConnectionState.DISCONNECTED, "Error: ${e.message}")
        }
    }

    private fun disconnectWebSocket() {
        ws?.close(1000, "Client disconnect")
        ws = null
        sensorManager.unregisterListener(this)
        updateUI(ConnectionState.DISCONNECTED, "Disconnected")
    }

    private fun updateUI(state: ConnectionState, message: String) {
        runOnUiThread {
            connectionState = state
            statusText.text = message
            when (state) {
                ConnectionState.DISCONNECTED -> {
                    addressInput.isEnabled = true
                    connectButton.text = "Connect"
                }
                ConnectionState.CONNECTING -> {
                    addressInput.isEnabled = false
                    connectButton.text = "Cancel"
                }
                ConnectionState.CONNECTED -> {
                    addressInput.isEnabled = false
                    connectButton.text = "Disconnect"
                }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
            val q = quatFromRotationVector(event.values)
            currentQuaternion = q.copyOf()

            val now = System.currentTimeMillis()
            if (now - lastSent < intervalMs) return
            lastSent = now
        
            val finalQ = if (hasCalibration) {
                multiplyQuaternions(inverse(baseQ), q)
            } else {
                q
            }
        
            val json = JSONObject()
            json.put("type", "orientation")
            json.put("x", finalQ[0])
            json.put("y", finalQ[1])
            json.put("z", finalQ[2])
            json.put("w", finalQ[3])
        
            ws?.send(json.toString())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onPause() {
        super.onPause()
        // Приостанавливаем опрос датчиков, чтобы не тратить батарею в фоне
        sensorManager.unregisterListener(this)
    }

    override fun onResume() {
        super.onResume()
        if (connectionState == ConnectionState.CONNECTED && sendOrientation) {
            sensorManager.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectWebSocket()
    }
}
