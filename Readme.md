Bot lactancia: [@lactancia_bot](https://t.me/lactancia_bot)

## Conexión WIFI
Modificar estos datos en LactanciaApp.h

```
// ------------------------- CONFIGURACION -----------------------------------
// Redes WiFi, en orden de preferencia: { "NOMBRE_RED", "CONTRASEÑA" }
// (solo 2,4 GHz). Para una red abierta deja la clave vacía: { "Cafeteria", "" }
struct WifiCred { const char *ssid; const char *pass; };
static const WifiCred WIFI_LIST[] = {
  { "TU_WIFI",    "TU_CLAVE"   },
  { "OtraRed",    "otraClave"  },
  { "MovilMiki",  "claveMovil" },
};
#define API_BASE   "CAMBIA_ESTA_URL"
#define API_TOKEN  "CAMBIA_ESTE_TOKEN"   
#define POLL_MS    60000                 // cada cuanto se consulta el estado en reposo
// ---------------------------------------------------------------------------
```

Si alguna contraseña lleva " o \, hay que escaparlas en C++ (\" y \\). Las !, ñ y demás símbolos normales no dan problema, aunque evita la ñ y los acentos en la clave. El ESP32 las manda tal cual y suelen dar guerra según cómo guarde el fichero tu editor.

## Batería
Si quieres medir la batería de verdad, tendrías que modificar el hardware: soldar un divisor resistivo (por ejemplo 100k + 100k, como comentaste) entre BAT+ y GND, y llevar el punto medio a un GPIO libre con ADC. En el ESP32-C3 los pines con ADC1 son GPIO0–GPIO4; de esos, según el pin_config.h de Spotpear, están libres (no usados por pantalla ni táctil): GPIO0 y GPIO3. Cualquiera de los dos serviría como BATTERY_ADC_PIN si haces esa modificación casera.

Habrá que cambiar:
```
#define ENABLE_BATTERY 0
#define BATTERY_ADC_PIN 0
```
