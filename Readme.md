# 🍼 Proyecto de Lactancia y Navegación ESP32

Bienvenido al repositorio del proyecto. Aquí encontrarás las herramientas de software y la configuración necesarias para la pantalla y el bot de asistencia.

La parte del bot de Lactancia es algo mas personal e individualizado, no afecta si solo quieres usar la parte de GPS pero está separado y se puede quitar.

Placa de desarrollo ESP32-C3 con pantalla ST7789 de 1.69 pulgadas (Imágenes al final del Readme.md)

---

## 🤖 Enlaces de Interés
* **Bot de Lactancia:** [@lactancia_bot](https://t.me/lactancia_bot)

---

## 📂 Estructura del Repositorio

El proyecto se divide en las siguientes carpetas y componentes principales:

| Carpeta / Componente | Descripción |
| :--- | :--- |
| **`GPS_Nav_Display`** | Versión que utiliza la aplicación de terceros **GadgetBridge** para enviar los datos de Google Maps. |
| **`GPS_Nav_NavBridge`** | Versión con aplicación propia desarrollada para el proyecto. ¡Puedes descargar el APK directamente [aquí](https://github.com/MiAnCaDev/esp32c3-navegador-tools/tree/main/GPS_Nav_NavBridge/apk)! |
| **`Informacion adicional`** | Documentación, imágenes y guías de configuración del hardware y software. |
| **`prueba_colores`** | Script de prueba para verificar la correcta visualización de colores en la pantalla. |
| **`prueba_tactil`** | Script de prueba para comprobar el funcionamiento del panel táctil. |

---

## 📶 Configuración WiFi

Para configurar las redes y los parámetros de conexión, debes modificar el fichero **`LactanciaApp.h`** con los siguientes datos:

```cpp
// ---------------------------------------------------------------------------
// Redes WiFi, en orden de preferencia: { "NOMBRE_RED", "CONTRASEÑA" }
// (solo 2,4 GHz). Para una red abierta deja la clave vacía: { "Cafeteria", "" }
struct WifiCred { const char *ssid; const char *pass; };
static const WifiCred WIFI_LIST[] = {
  { "TU_WIFI",    "TU_CLAVE"    },
  { "OtraRed",    "otraClave"  },
  { "MovilMiki",  "claveMovil" },
};

#define API_BASE   "CAMBIA_ESTA_URL"
#define API_TOKEN  "CAMBIA_ESTE_TOKEN"   
#define POLL_MS    60000                 // Frecuencia de consulta en reposo (ms)
// ---------------------------------------------------------------------------
```

## Variables ESP32

Hay ciertas variables que pueden modificar rápidamente el funcionamiento en el ESP32
```
#define ENABLE_LACTANCIA true 
  por defecto se muestra la parte de lactancia conectada al bot de telegram.
#define SOUND_MUTED_BY_DEFAULT true 
  por defecto el sonido está muteado y puedes activarlo desde el dispositivo.
#define SHOW_SPEED false 
  por defecto la velocidad no se muestra en el modo GPS

#define ENABLE_BATTERY 0
#define BATTERY_ADC_PIN 0
  por defecto en el dispositivo en el que probó el funcionamiento no se puede medir la batería, pero se prepara para otros
```

Estas variables están disponibles en el esp32 con conexión con app propia. No todas están en la versión con GadgetBridge.

## Imagenes ESP32

![Imagen del dispositivo](Informacion%20adicional/Esp32%20-%202.avif)

![Imagen del dispositivo - Esquema](Informacion%20adicional/Esp32%20-%201.avif)
