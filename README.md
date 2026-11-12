# karooMQTT

A [Hammerhead Karoo](https://www.hammerhead.io/) extension that publishes live indoor-training **power** and **heart-rate** data to a local **MQTT** broker.

I use it to control my **fan speed during indoor trainer sessions** — the averaged power/HR values are streamed to a broker that drives the fan controller, so the fan speeds up and slows down with my effort automatically.

## Features

- **Automatic activation** — the background service starts by itself when you begin recording on an *indoor (trainer)* ride profile, and stops when the activity ends. No manual start needed.
- **Live sensor data** — subscribes to `POWER` and `HEART_RATE` via `KarooSystemService`.
- **Rolling average** — configurable 1s / 3s / 5s window to smooth out noisy power readings.
- **MQTT output** — publishes a JSON payload `{"power":200,"hr":140}` to the root topic, plus individual values on `<topic>/power` and `<topic>/hr`.
- **Connection status topic** — publishes retained `online` / `offline` messages to `<topic>/status` (Last Will & Testament covers unexpected disconnects).
- **Battery friendly** — Wi-Fi (`WifiLock` + `ConnectivityManager.requestNetwork`) and the MQTT connection are active **only** during indoor recording. Outdoor rides keep Wi-Fi asleep and never touch the broker.
- **In-ride status fields** — custom graphical data fields (**MQTT**, **Wi-Fi**) that can be added to any ride profile page; green = active, red = inactive.
- **Settings UI** — broker host/port/credentials/topic, averaging window, send interval (5/7/10s or custom), live readings, and a one-tap MQTT test that works without starting a ride.
- **Wi-Fi toggle BonusAction** — optional in-ride action to toggle Wi-Fi (availability depends on Karoo OS restrictions).

## MQTT topics

| Topic            | Payload                        | Notes                     |
|------------------|--------------------------------|---------------------------|
| `<topic>`        | `{"power":200,"hr":140}`      | JSON, every N seconds     |
| `<topic>/power`  | `200`                          | Watts                     |
| `<topic>/hr`     | `140`                          | bpm                       |
| `<topic>/status` | `online` / `offline`           | retained                  |
| `<topic>/test`   | `{"power":200,"hr":140,"test":true}` | sent by "Test MQTT" |

## Installation

Prebuilt APKs are attached to [Releases](https://github.com/Wolek1234/karoo-mqtt/releases). The app also appears in the Karoo **Extensions** library (via `manifest.json`).

**Tested on Karoo 2.** It should work on Karoo 3 as well, but I don't have one to verify.

## Building from source

Requirements: JDK 17, Android SDK 34.

```bash
git clone https://github.com/Wolek1234/karoo-mqtt.git
cd karoo-mqtt
./gradlew assembleDebug
```

The resulting APK is `app/build/outputs/apk/debug/karooMQTT-<version>.apk`.

`karoo-ext` is hosted on GitHub Packages and requires credentials. Create a token with `read:packages` scope and put it in `local.properties` (gitignored):

```properties
gpr.user=YourGitHubUsername
gpr.key=ghp_xxxxxxxxxxxx
```

## Configuration

Open the app on the Karoo (or via the Extensions entry), set your broker IP/port/credentials and root topic (default `karoo`), pick the averaging window and send interval, then tap the green check to save. Use **Test MQTT** to verify the broker connection without riding.

Mark your trainer profile as *indoor* in Karoo (ride profile settings) — that is what triggers automatic Wi-Fi + MQTT activation.

## Data flow for fan control (example)

```
Karoo sensors → karooMQTT (avg) → MQTT broker → your fan controller
```

Subscribe to `karoo/power` (or the JSON topic) and map the wattage to fan speed however you like.

## License

See [LICENSE](LICENSE).
