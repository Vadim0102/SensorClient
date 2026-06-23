# SensorClient

An Android WebSocket client that broadcasts device rotation and orientation sensor data to a remote WebSocket server in real-time.

## Features

- **Dynamic Server Configuration**: Input any WebSocket server address (hostname:port or IP:port)
- **Auto-Connection**: Automatically connects to the last used server address on app launch
- **Address Persistence**: Saves the last server address using SharedPreferences
- **Real-time Sensor Data**: Streams rotation vector and orientation (yaw, pitch, roll) data
- **Server-Side Rate Control**: Responds to server configuration for update frequency
- **Error Handling**: Graceful error messages and reconnection capability
- **Both ws:// and wss:// Support**: Works with secure and non-secure WebSocket connections

## Architecture

### Client-Server Communication

#### Client (This App)
1. **Initialization Phase**
   - Loads saved server address from SharedPreferences
   - Attempts WebSocket connection with auto-retry on failure
   - Waits for server configuration

2. **Configuration Phase**
   - Receives JSON configuration from server with format:
     ```json
     {
       "type": "config",
       "rate_hz": 60,
       "send_orientation": true
     }
     ```
   - Registers/unregisters sensor listeners based on server requirements
   - Sets update frequency according to `rate_hz`

3. **Data Streaming Phase**
   - Sends orientation data at configured rate:
     ```json
     {
       "type": "orientation",
       "ts": 1234567890.123,
       "yaw": 45.5,
       "pitch": -12.3,
       "roll": 78.2
     }
     ```
   - All angles in degrees
   - Timestamp in Unix epoch seconds (float)

#### Server (Expected API)
The server should:
1. Listen for WebSocket connections on specified address and port
2. Send initial configuration after client connects
3. Receive orientation data messages from client
4. Update configuration anytime and client will adapt
5. Handle connection/disconnection gracefully

**Example Server Protocol**:
```
Client connects → Server sends config → Client starts sensor capture → 
Client sends orientation data → Server processes data → 
Repeat until disconnect
```

## Building

### Prerequisites
- Android Studio Arctic Fox or newer
- SDK 36 (or update `compileSdk` in `build.gradle.kts`)
- Kotlin support
- API level 26+ (minSdk)

### Build Steps
1. Clone the repository:
   ```bash
   git clone https://github.com/Vadim0102/SensorClient.git
   cd SensorClient
   ```

2. Open in Android Studio or build from command line:
   ```bash
   ./gradlew build
   ```

3. Install on device:
   ```bash
   ./gradlew installDebug
   ```

4. For release build:
   ```bash
   ./gradlew build -Pbuild_type=release
   ```

## Usage

1. **Launch the App**
   - The app automatically attempts to connect to the last saved server address
   - Status will show "Connected" if successful

2. **Change Server Address**
   - Enter a new server address in format: `hostname:port` or `IP:port`
   - Examples: `192.168.1.100:8765`, `server.example.com:8765`
   - Supports both `ws://` and `wss://` prefixes (auto-added if omitted)
   - Click "Connect" button
   - The address is automatically saved

3. **Monitor Connection Status**
   - Status display shows current connection state
   - Button toggles between "Connect" and "Disconnect"
   - On success: "Connected to [address]"
   - On failure: "Connection failed: [error message]"

4. **Sensor Permissions**
   - INTERNET permission is required (automatically requested)
   - Sensor access is granted by default for TYPE_ROTATION_VECTOR

## Dependencies

- **OkHttp3** (4.12.0): WebSocket client
- **Android Core KTX**: Kotlin extensions
- **AppCompat**: Backward compatibility
- **Material**: UI components

## API Level Support

- **minSdk**: 26 (Android 8.0)
- **targetSdk**: 36 (Android 15)
- **compileSdk**: 36 (Android 15)

## Manifest Permissions

```xml
<uses-permission android:name="android.permission.INTERNET"/>
```

## File Structure

```
app/
├── src/
│   └── main/
│       ├── java/pw/cmdev/fox/sensorclient/
│       │   └── MainActivity.kt          # Main activity with WebSocket logic
│       ├── res/
│       │   ├── layout/
│       │   │   └── activity_main.xml    # UI layout
│       │   └── drawable/
│       │       ├── edit_text_background.xml
│       │       └── status_background.xml
│       └── AndroidManifest.xml
├── build.gradle.kts                    # Build configuration
└── proguard-rules.pro
```

## Configuration

### SharedPreferences
- **File**: `SensorClientPrefs`
- **Stored Keys**:
  - `server_address`: Last used WebSocket server address (default: `192.168.1.1:8765`)

## Sensor Data

The app uses the **Rotation Vector Sensor** (`TYPE_ROTATION_VECTOR`):
- Combines accelerometer, gyroscope, and magnetometer data
- Provides accurate device orientation
- Updates at configured rate (typically 60Hz)

**Output Angles**:
- **Yaw** (Z-axis rotation): -180° to 180°
- **Pitch** (X-axis rotation): -90° to 90°
- **Roll** (Y-axis rotation): -180° to 180°

## Troubleshooting

| Issue | Solution |
|-------|----------|
| Connection refused | Check server is running on correct address/port |
| Connection timeout | Verify network connectivity and firewall settings |
| "Please enter server address" | Address field is empty, enter valid address |
| Connection failed: timeout | Server unreachable; check hostname/IP and port |
| No data being sent | Server must send config message first; check "send_orientation": true |
| Sensor data inaccurate | Keep device away from strong magnetic fields; calibrate compass |

## License

MIT License - see LICENSE file for details

## Server Implementation Reference

For reference, here's a minimal Python WebSocket server:

```python
import asyncio
import json
import websockets

async def handle_client(websocket, path):
    try:
        # Send configuration
        config = {
            "type": "config",
            "rate_hz": 60,
            "send_orientation": True
        }
        await websocket.send(json.dumps(config))
        
        # Receive data
        async for message in websocket:
            data = json.loads(message)
            print(f"Received: {data}")
    except websockets.exceptions.ConnectionClosed:
        print("Client disconnected")

async def main():
    async with websockets.serve(handle_client, "0.0.0.0", 8765):
        print("Server started on ws://0.0.0.0:8765")
        await asyncio.Future()  # Run forever

if __name__ == "__main__":
    asyncio.run(main())
```

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## Contact

For issues or questions, please open an issue on GitHub.
