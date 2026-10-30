# Tests

Comprueban que la lógica del proyecto funciona **sin necesitar la placa ni un móvil**. Hay dos grupos.

## Cómo lanzarlos

| Qué | Cómo | Necesita |
|---|---|---|
| Todo | `tests/run_tests.sh` (Linux/macOS/Git Bash/WSL) o `tests\run_tests.bat` (Windows) | g++ y el SDK de Android |
| Solo firmware | `tests/run_tests.sh esp32` / `tests\run_tests.bat esp32` | `g++` y `curl` (descarga ArduinoJson la primera vez) |
| Solo app (Kotlin) | `tests/run_tests.sh kotlin`, o `./gradlew :app:testDebugUnitTest`, o en Android Studio: clic derecho en `app/src/test` > *Run Tests* | Android Studio / SDK de Android |

Los scripts terminan con código distinto de 0 si algo falla, y escriben `TODOS LOS TESTS OK` si todo va bien.

- **g++ en Windows**: instala [MSYS2](https://www.msys2.org) (`pacman -S mingw-w64-ucrt-x86_64-gcc`) o usa WSL. Si el compilador no soporta
  AddressSanitizer, el script lo avisa y compila sin él (la prueba de fuzzing es entonces menos estricta).
- **ArduinoJson**: si ya la tienes instalada en Arduino, apunta a ella con `ARDUINOJSON_H=/ruta/ArduinoJson.h` y no descarga nada. Hace falta la
  **versión 6** (la que usa el firmware).

## Firmware (`tests/esp32/`, C++ en el PC)

Arduino, `Preferences` (la memoria NVS) y `esp_random()` están **simulados** en `stubs/`; el código bajo prueba es el real (`ESP32/*.h`).

| Test | Qué comprueba |
|---|---|
| `test_deviceconfig.cpp` (49 comprobaciones) sobre `DeviceConfig.h` | Arranca **abierto**: sin clave se aceptan lactancia, URL, WiFi, etc. · URL solo `https://` · el bloqueo no se puede activar a distancia (solo emparejando) · emparejar por código de pantalla: 3 intentos, 60 s de espera tras fallar, caducidad a 90 s, una petición cada 8 s · **varios móviles** (hasta 4; el 5.º sustituye al más antiguo; volver a emparejar el mismo no duplica) · con bloqueo: sin clave o con clave ajena → `auth` y no cambia nada · quitar el bloqueo exige clave · recuperación si se pierden todos los móviles (con el código de la pantalla) · persistencia tras reiniciar · límites de longitud (sin desbordes) · mínimo de 10 s de intervalo · marca de arranque en lactancia (se consume, y no arranca así con la lactancia deshabilitada) · ocultar el botón de lactancia (abierto y bloqueado) · funciona al desbordar `millis()` y tras 30 días encendido |
| `fuzz_phonelink.cpp` sobre `PhoneLink.h` | El parser de tramas Bluetooth (datos **no confiables**): 200 tramas válidas troceadas al azar llegan enteras; 20.000 entradas aleatorias, longitudes enormes, checksums malos y 2.000 iconos con campos hostiles no producen desbordes ni comportamiento indefinido (bajo AddressSanitizer + UBSan) |

## App (`app/src/test/`, JUnit 4)

Es la parte que no depende de Android, así que se prueba en el PC.

| Test | Qué comprueba |
|---|---|
| `LeanEstimatorTest` | **Moto simulada** (dinámica real de una curva, vibración, ruido del giroscopio y del GPS, sesgo del giroscopio, móvil con orientación aleatoria): sin ruido el error es < 0,3° (valida la física); con ruido realista, de 43 a 108 km/h, el error típico es < 1,5° y el peor instante < 3,5° (medido: ≈0,75° y ≈3°); izquierda = positivo, derecha = negativo; aprender el sesgo en rectas mejora el resultado; por debajo de 14 km/h no calcula |
| `CurveDetectorTest` | Una «S» cuenta como dos curvas con su signo y pico; un meneo de 10° y un cambio de carril de 0,5 s a 20° se ignoran; ir recto no genera curvas |
| `SustainedLeanTest` | El máximo del modo moto: un pico corto (bache/ruido) se ignora, una inclinación mantenida 0,3 s cuenta con su signo, datos no válidos no cuentan, y coincide con el valor esperado en 5.000 muestras aleatorias |
| `TripStoreTest` | Un viaje interrumpido (sin resumen, con la última línea cortada) se reconstruye con distancia, velocidad máxima, tiempo en movimiento, g, curvas y desnivel correctos · el desnivel ignora la vibración del GPS · parado no suma distancia · renombrar y escapar nombres · **GPX bien formado** (XML válido, curvas como puntos de interés antes de la ruta, nombres escapados) · CSV con todas las filas y columnas · un viaje vacío se descarta |

## Lo que estos tests NO cubren

- El dibujo en la pantalla del ESP32 (LVGL): chips, alerta roja de radar, líneas de exceso de velocidad, indicador BT.
- La conexión Bluetooth real, los sensores reales del móvil, el GPS y la interfaz de la app. Eso se prueba con el aparato en la mano.
- La precisión de la inclinación **en una moto real**: la simulación valida la física y el código, no la colocación ni la vibración de tu moto.
- `LactanciaApp.h` (WiFi/TLS contra tu servidor).

## Última ejecución

Ver `ULTIMA_EJECUCION.txt` (resultado de los tests cuando se preparó esta versión).
