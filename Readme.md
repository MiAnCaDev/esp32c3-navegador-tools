Bot lactancia: [@lactancia_bot](https://t.me/lactancia_bot)

## Conexión WIFI
Modificar estos datos en LactanciaApp.h

```
// ------------------------- CONFIGURACION -----------------------------------
#define WIFI_SSID  "TU_WIFI"
#define WIFI_PASS  "TU_CLAVE"
#define API_BASE   "https://control-panel.legioagro.com/app_bebe/lactancia/api/esp32.php"
#define API_TOKEN  "CAMBIA_ESTE_TOKEN"   // el mismo que valida verificarAutenticacion()
#define POLL_MS    20000                 // cada cuanto se consulta el estado en reposo
// ---------------------------------------------------------------------------
```


## Batería
Si quieres medir la batería de verdad, tendrías que modificar el hardware: soldar un divisor resistivo (por ejemplo 100k + 100k, como comentaste) entre BAT+ y GND, y llevar el punto medio a un GPIO libre con ADC. En el ESP32-C3 los pines con ADC1 son GPIO0–GPIO4; de esos, según el pin_config.h de Spotpear, están libres (no usados por pantalla ni táctil): GPIO0 y GPIO3. Cualquiera de los dos serviría como BATTERY_ADC_PIN si haces esa modificación casera.

Habrá que cambiar:
```
#define ENABLE_BATTERY 0
#define BATTERY_ADC_PIN 0
```
