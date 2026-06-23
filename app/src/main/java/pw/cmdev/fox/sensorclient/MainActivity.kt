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

class MainActivity : AppCompatActivity(), SensorEventListener {

    private val TAG = "SensorClient"
    private val PREFS_NAME = "SensorClientPrefs"
    private val KEY_SERVER_ADDRESS = "server_address"

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null

    private var sendOrientation = false
    private var intervalMs = 16L
    private var lastSent = 0L

    private val client = OkHttpClient()
    private var ws: WebSocket? = null

    // UI elements
    private lateinit var addressInput: EditText
    private lateinit var connectButton: Button
    private lateinit var statusText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI elements
        addressInput = findViewById(R.id.addressInput)
        connectButton = findViewById(R.id.connectButton)
        statusText = findViewById(R.id.statusText)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        // Load last saved address
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastAddress = prefs.getString(KEY_SERVER_ADDRESS, "192.168.1.1:8765") ?: "192.168.1.1:8765"
        addressInput.setText(lastAddress)

        // Connect button listener
        connectButton.setOnClickListener {
            val address = addressInput.text.toString().trim()
            if (address.isNotEmpty()) {
                // Save address
                prefs.edit().putString(KEY_SERVER_ADDRESS, address).apply()
                // Connect
                connectWebSocket(address)
            } else {
                statusText.text = "Please enter server address"
            }
        }

        // Auto-connect on app launch
        connectWebSocket(lastAddress)
    }

    private fun connectWebSocket(address: String) {
        try {
            val url = if (address.startsWith("ws://") || address.startsWith("wss://")) {
                address
            } else {
                "ws://$address"
            }

            val request = Request.Builder()
                .url(url)
                .build()

            ws?.close(1000, "Reconnecting")

            ws = client.newWebSocket(request, object : WebSocketListener() {

                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket connected")
                    runOnUiThread {
                        statusText.text = "Connected to $address"
                        addressInput.isEnabled = false
                        connectButton.text = "Disconnect"
                        connectButton.setOnClickListener {
                            disconnectWebSocket()
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "Received: $text")
                    try {
                        val json = JSONObject(text)

                        if (json.getString("type") == "config") {
                            val rateHz = json.getInt("rate_hz")
                            intervalMs = 1000L / rateHz
                            sendOrientation = json.getBoolean("send_orientation")

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
                    } catch (e: Exception) {
                        Log.e(TAG, "Error parsing message: ${e.message}")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket error: ${t.message}")
                    runOnUiThread {
                        statusText.text = "Connection failed: ${t.message}"
                        addressInput.isEnabled = true
                        connectButton.text = "Connect"
                        connectButton.setOnClickListener {
                            val address = addressInput.text.toString().trim()
                            if (address.isNotEmpty()) {
                                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                prefs.edit().putString(KEY_SERVER_ADDRESS, address).apply()
                                connectWebSocket(address)
                            }
                        }
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket closed: $reason")
                    runOnUiThread {
                        statusText.text = "Disconnected"
                        addressInput.isEnabled = true
                        connectButton.text = "Connect"
                        connectButton.setOnClickListener {
                            val address = addressInput.text.toString().trim()
                            if (address.isNotEmpty()) {
                                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                                prefs.edit().putString(KEY_SERVER_ADDRESS, address).apply()
                                connectWebSocket(address)
                            }
                        }
                    }
                    sensorManager.unregisterListener(this@MainActivity)
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Connection error: ${e.message}")
            statusText.text = "Error: ${e.message}"
        }
    }

    private fun disconnectWebSocket() {
        ws?.close(1000, "Client disconnect")
        sensorManager.unregisterListener(this)
        statusText.text = "Disconnected"
        addressInput.isEnabled = true
        connectButton.text = "Connect"
        connectButton.setOnClickListener {
            val address = addressInput.text.toString().trim()
            if (address.isNotEmpty()) {
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs.edit().putString(KEY_SERVER_ADDRESS, address).apply()
                connectWebSocket(address)
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = System.currentTimeMillis()
        if (now - lastSent < intervalMs) return
        lastSent = now

        val rotMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)

        val orient = FloatArray(3)
        SensorManager.getOrientation(rotMatrix, orient)

        val yaw = Math.toDegrees(orient[0].toDouble())
        val pitch = Math.toDegrees(orient[1].toDouble())
        val roll = Math.toDegrees(orient[2].toDouble())

        val json = JSONObject()
        json.put("type", "orientation")
        json.put("ts", System.currentTimeMillis() / 1000.0)
        json.put("yaw", yaw)
        json.put("pitch", pitch)
        json.put("roll", roll)

        ws?.send(json.toString())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        disconnectWebSocket()
    }
}
