package pw.cmdev.fox.sensorclient

import android.hardware.*
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import okhttp3.*
import org.json.JSONObject
import android.util.Log

class MainActivity : AppCompatActivity(), SensorEventListener {

    private val TAG = "SensorClient"

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null

    private var sendOrientation = false
    private var intervalMs = 16L
    private var lastSent = 0L

    private val client = OkHttpClient()
    private lateinit var ws: WebSocket

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        connectWebSocket()

//        sensorManager.registerListener(
//            this,
//            rotationSensor,
//            SensorManager.SENSOR_DELAY_GAME
//        )

    }

    private fun connectWebSocket() {
        val request = Request.Builder()
            .url("ws://[IP_ADDRESS]:8765")
            .build()

        ws = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "WebSocket connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received: $text")
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
            }
        })
    }

    override fun onSensorChanged(event: SensorEvent) {
//        Log.d(TAG, "Sensor event")

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

        ws.send(json.toString())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
