# KarPlayer SRT — Android SRT Player
## Техническое задание для Claude Code

---

## Контекст проекта

**KarPlayer** — нативное Android-приложение на Kotlin, принимающее видеопоток по протоколу SRT. Задача приложения: получать MPEG-TS поток через SRT, декодировать H.264/H.265 и отображать видео с минимальной задержкой. Приложение ориентировано на профессиональное использование (live-видеопроизводство), поэтому приоритет — низкая латентность и надёжность, а не красота UI.

---

## Архитектура — мультимодульный проект

```
KarPlayer/
├── app/                  — точка входа, DI, навигация
├── srt/                  — JNI-обёртка над libsrt (NDK модуль)
├── player/               — MediaSource + ExoPlayer интеграция
└── ui/                   — фрагменты, ViewModel, Compose-компоненты
```

### Граф зависимостей
```
:app → :player → :srt
:app → :ui    → :player
```

---

## Технический стек

| Компонент       | Решение                                      |
|-----------------|----------------------------------------------|
| Язык            | Kotlin 2.0                                   |
| Build           | AGP 8.4, Gradle 8.7, Version Catalog (TOML)  |
| NDK             | libsrt (prebuilt .so или сборка из исходников)|
| JNI             | Kotlin → C++ через `srt_jni.cpp`             |
| Видеоплеер      | Media3 / ExoPlayer 1.3.x                     |
| Рендеринг       | SurfaceView (низкая латентность)             |
| Async           | Kotlin Coroutines + Flow                     |
| UI              | Jetpack Compose + Material3                  |
| DI              | Hilt или ручной DI (на усмотрение)           |
| Min SDK         | 26 (Android 8.0)                             |
| Target SDK      | 34                                           |
| ABI             | arm64-v8a (основной), x86_64 (эмулятор)     |

---

## Модуль `:srt` — JNI-слой

### Задача
Обернуть нативную библиотеку **libsrt** в Kotlin API через JNI.

### Что нужно создать

**`src/main/cpp/CMakeLists.txt`**
- Подключить libsrt как prebuilt shared library (`srt/libs/arm64-v8a/libsrt.so`)
- Либо собрать из исходников как ExternalProject (предпочтительно если позволяет время)
- Скомпилировать `srt_jni.cpp`
- Флаги: `-std=c++17`, `-DSRT_ENABLE_ENCRYPTION=OFF` (без OpenSSL на старте)

**`srt_jni.cpp`**
Реализовать JNI-функции для следующего Kotlin-интерфейса:
```kotlin
// Жизненный цикл сокета
fun nativeCreate(): Long                           // → handle
fun nativeConnect(handle: Long, host: String, port: Int, options: SrtOptions): Int
fun nativeRead(handle: Long, buffer: ByteArray, offset: Int, length: Int): Int
fun nativeClose(handle: Long)
fun nativeGetStats(handle: Long): SrtStats        // RTT, bitrate, loss, jitter
```

**`SrtSocket.kt`**
```kotlin
class SrtSocket {
    // Обёртка над нативными функциями
    // Управление жизненным циклом через Kotlin (open/close/read)
    // Expose stats через StateFlow<SrtStats>
}
```

**`SrtOptions.kt`** — data class:
```kotlin
data class SrtOptions(
    val latency: Int = 120,         // мс, целевая латентность
    val maxBandwidth: Long = -1,    // -1 = auto
    val inputBandwidth: Long = 0,
    val mode: SrtMode = SrtMode.CALLER,
    val streamId: String = "",
    val timeout: Int = 3000         // мс на connect
)

enum class SrtMode { CALLER, LISTENER, RENDEZVOUS }
```

**`SrtStats.kt`** — data class:
```kotlin
data class SrtStats(
    val rttMs: Double = 0.0,
    val bitrateKbps: Double = 0.0,
    val packetLossPct: Double = 0.0,
    val jitterMs: Double = 0.0,
    val receivedPackets: Long = 0,
    val lostPackets: Long = 0,
    val retransmittedPackets: Long = 0,
    val timestamp: Long = System.currentTimeMillis()
)
```

**`SrtDataSource.kt`** — реализует `androidx.media3.datasource.DataSource`:
```kotlin
class SrtDataSource(
    private val socket: SrtSocket,
    private val options: SrtOptions
) : DataSource {
    // open(DataSpec) → устанавливает SRT-соединение
    // read(buffer, offset, length) → делегирует в socket.read()
    // close() → закрывает сокет
    // getUri() → srt://host:port
}
```

---

## Модуль `:player` — Media3 интеграция

### Задача
Подружить `SrtDataSource` с ExoPlayer так, чтобы он воспринимал SRT-поток как live MPEG-TS источник.

### Что нужно создать

**`SrtDataSourceFactory.kt`**
```kotlin
class SrtDataSourceFactory(
    private val options: SrtOptions
) : DataSource.Factory {
    override fun createDataSource(): DataSource
}
```

**`SrtMediaSourceFactory.kt`** — создаёт `ProgressiveMediaSource` с MPEG-TS экстрактором:
```kotlin
class SrtMediaSourceFactory(
    private val dataSourceFactory: SrtDataSourceFactory
) {
    fun create(uri: Uri): MediaSource {
        // ProgressiveMediaSource с TsExtractor
        // extractorFactory с H.264 + H.265 поддержкой
        // bufferSize минимальный (live-режим)
    }
}
```

**`PlayerManager.kt`**
```kotlin
class PlayerManager(context: Context) {
    val player: ExoPlayer
    val stats: StateFlow<SrtStats>
    val playerState: StateFlow<PlayerState>

    fun connect(host: String, port: Int, options: SrtOptions)
    fun disconnect()
    fun setSurface(surface: Surface)
    fun release()
}

enum class PlayerState {
    IDLE, CONNECTING, BUFFERING, PLAYING, ERROR
}
```

**Конфигурация ExoPlayer:**
```kotlin
ExoPlayer.Builder(context)
    .setLoadControl(
        DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                500,   // minBufferMs
                2000,  // maxBufferMs
                500,   // bufferForPlaybackMs
                500    // bufferForPlaybackAfterRebufferMs
            )
            .build()
    )
    .build()
```

---

## Модуль `:ui` — интерфейс

### Экраны

#### 1. ConnectionFragment / ConnectionScreen
Поля ввода:
- SRT Host (IP или hostname)
- Port (default: 9000)
- Latency (мс, slider 60–500, default 120)
- Stream ID (опционально)
- Кнопка "Connect"

Сохранение последних настроек в `SharedPreferences`.

#### 2. PlayerFragment / PlayerScreen
- `SurfaceView` или `PlayerView` (Media3) во весь экран
- `StatsOverlay` — полупрозрачный оверлей поверх видео (можно скрыть тапом):
  ```
  RTT: 45ms  |  Bitrate: 8.2 Mbps  |  Loss: 0.1%  |  Jitter: 2ms
  ```
- `PlayerState` индикаторы: spinner при BUFFERING, текст ошибки при ERROR
- Кнопка "Disconnect" (назад к ConnectionScreen)
- Поддержка landscape + portrait (SurfaceView масштабируется корректно)

#### 3. ViewModel
```kotlin
class PlayerViewModel(
    private val playerManager: PlayerManager
) : ViewModel() {
    val playerState: StateFlow<PlayerState>
    val stats: StateFlow<SrtStats>
    val connectionConfig: StateFlow<ConnectionConfig>

    fun connect(config: ConnectionConfig)
    fun disconnect()

    override fun onCleared() { playerManager.release() }
}
```

---

## Разрешения в AndroidManifest.xml

```xml
<uses-permission android:name="android.permission.INTERNET"/>
<uses-permission android:name="android.permission.CHANGE_NETWORK_STATE"/>
<!-- Для multicast/bonding, если нужно -->
<uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE"/>
```

---

## Получение libsrt

**Вариант A (быстро):** Скачать prebuilt `.so` из репозитория:
- https://github.com/Haivision/srt/releases
- Нужны: `libsrt.so` для `arm64-v8a` и `x86_64`
- Положить в `srt/src/main/jniLibs/arm64-v8a/libsrt.so`
- Хедеры: `srt/src/main/cpp/include/srt/srt.h`

**Вариант B (правильно):** Собрать через CMake ExternalProject_Add из исходников тега `v1.5.3`.

Использовать **Вариант A** для начального билда, затем можно перейти на B.

---

## Порядок разработки

1. Создать структуру проекта (все `build.gradle.kts`, `settings.gradle.kts`, `libs.versions.toml`)
2. Скачать / разместить `libsrt.so` и хедеры
3. Написать `CMakeLists.txt` + `srt_jni.cpp` (минимум: create/connect/read/close/stats)
4. Написать `SrtSocket.kt` + `SrtDataSource.kt` — убедиться что компилируется
5. Написать `SrtMediaSourceFactory.kt` + `PlayerManager.kt`
6. Написать UI (ConnectionScreen → PlayerScreen)
7. Сквозной тест: подключиться к ffmpeg-тестовому потоку:
   ```bash
   ffmpeg -re -f lavfi -i testsrc=size=1280x720:rate=30 \
          -c:v libx264 -preset ultrafast -tune zerolatency \
          -f mpegts "srt://0.0.0.0:9000?mode=listener"
   ```
8. Отладить латентность (целевой показатель: <200ms при локальной сети)

---

## Тестирование

- Юнит-тесты для `SrtOptions`, `SrtStats` (парсинг/сериализация)
- Интеграционный тест `SrtDataSource` против mock-сокета
- Ручной тест с ffmpeg-источником на ПК в той же сети

---

## Что НЕ входит в этот этап

- Шифрование SRT (AES) — отдельная задача
- Multi-link bonding на стороне RX — отдельная задача
- ARQ/FEC на уровне приложения (используется встроенный ARQ libsrt)
- Запись потока на диск

---

## Справочные материалы

- [SRT Protocol Technical Overview](https://github.com/Haivision/srt/blob/master/docs/srt-protocol-technical-overview.md)
- [Media3 DataSource API](https://developer.android.com/reference/androidx/media3/datasource/DataSource)
- [SRT API Functions](https://github.com/Haivision/srt/blob/master/docs/API/API-functions.md)
- libsrt options: `SRTO_LATENCY`, `SRTO_MAXBW`, `SRTO_INPUTBW`, `SRTO_STREAMID`

---

*KarPlayer v0.1 · Kotlin + NDK + Media3 · Цель: <200ms latency на LAN/5G*
