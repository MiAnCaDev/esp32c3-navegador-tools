# Esp Nav MiAnCa (NavBridge)

App Android mínima que sustituye a Gadgetbridge para el GPS-Nav-ESP32.
Lee la notificación de navegación de Google Maps y manda al ESP32:
la imagen de la flecha (tal cual la dibuja Google), distancia, calle y velocidad GPS.

## Puesta en marcha
1. Abre la carpeta `NavBridge` en Android Studio (genera el wrapper de Gradle solo) y ejecútala en el móvil.
2. En la app: "Conceder permisos" -> "Dar acceso a notificaciones" (activa NavBridge) -> "Iniciar".
3. Arranca una ruta en Google Maps. En la app verás la imagen que recibe el ESP32.
   Filtra el logcat por la etiqueta `NavBridge` para ver qué trae la notificación.

## Cómo ajustar
- Flecha invertida/rara: `IconProcessor.forceDarkOnLight` (true/false) en `notif/IconTools.kt`.
- Tamaño del icono: `IconProcessor.SIZE` (máx. 128; el firmware ya admite hasta 128x128).
- Si "no se pudo extraer ninguna imagen": mira en logcat las `extras` y ajusta `IconExtractor`.

## Cómo añadir otra fuente de notificaciones
1. Crea `MiHandler : NotificationHandler` (paquete(s) que escucha + qué tramas manda).
2. Añádelo a la lista de `HandlerRegistry.handlers`.
3. En el firmware, trata el nuevo JSON (`{"t":"lo-que-sea",...}`) en `processLine()`
   o, si necesitas un tipo binario nuevo, añade un tipo en `Frame.kt` y en `PhoneLink.h`.

## Rendimiento y consumo
**App**
- `AppLog` escribe en bloque (cada ~1,5 s) desde un hilo propio en vez de abrir el fichero en cada línea; avisos y errores al instante.
- Mensajes `dest` y `gps` solo se mandan al ESP32 si cambian (más un "sigo aquí" cada 3 s / 5 s; el ESP32 caduca a 6 s / 12 s).
- El GPS se pausa si el ESP32 lleva 30 s sin conexión (apagado, modo lactancia) y se reactiva solo al conectar.
- Pantalla principal: estado cada 1 s (antes 0,5 s) solo si cambia algo; los permisos se comprueban al volver a la app, no en cada tick.
- Radares: base como instantánea inmutable (segura si se actualiza conduciendo), celdas vecinas cacheadas, distancias sin
  trigonometría salvo en los candidatos cercanos; la base y la caché de límites se cargan en segundo plano al arrancar;
  las descargas corren con prioridad baja.

**ESP32** (parámetros en el bloque `Consumo / temperatura` de `ESP32.ino`)
- CPU a 80 MHz (`CPU_MHZ_IDLE`), 160 MHz solo durante el modo lactancia (`CPU_MHZ_WIFI`).
- `loop()` duerme lo que pide LVGL (5-20 ms) en vez de girar a 200 Hz.
- Las etiquetas no se repintan si el texto no cambia (`lv_label_set_text` envuelto).
- Trazas por mensaje recibido desactivadas (`LOG_RX 0`); BLE a -3 dBm (`BLE_TX_POWER`) y anuncio cada 100-150 ms.

## Avisos de radar
Todo el cálculo lo hace la app (`radar/`); el ESP32 solo dibuja lo que llega.
- **Datos** (`RadarUpdater`): DGT DATEX II (radares fijos y de tramo, CC-BY, sin Cataluña/País Vasco) + OpenStreetMap
  `highway=speed_camera` vía Overpass (cubre Cataluña/País Vasco y aporta `maxspeed`). Se descargan al pulsar
  "Base de radares" y se guardan en `filesDir/radares.json`; después funciona sin internet.
- **Lógica** (`RadarMonitor`): con cada fix de GPS busca el radar más cercano por delante (±50° del rumbo), elige la franja
  de velocidad (lenta/media/rápida) y avisa a X metros y otra vez a X/2. Los metros solo se envían si cumplen la
  distancia de "mostrar metros" de esa franja. Un radar ya pasado no vuelve a avisar en 5 min.
- **Ajustes** (`RadarConfig`, ventana "Configurar avisos"): umbrales de velocidad, distancia de 1.er aviso y de mostrar
  metros por franja, duración de la alerta. Por defecto 1000 / 400 / 180 m (rápido >90 / medio 40-90 / lento <40 km/h) y 5 s.
- **Protocolo** móvil -> ESP32: `{"t":"radar","on":1,"n":1,"s":1,"d":850,"txt":"Fijo N-340 90"}` (mostrar) y
  `{"t":"radar","on":0}` (quitar). `n` = nº de aviso, `s` = 1 solo en el primer mensaje de cada aviso (pitido propio,
  solo si el sonido del ESP32 está activado), `d` = metros (opcional). El ESP32 quita la alerta solo si pasan 3,5 s sin
  mensajes, y tocar la pantalla cierra el aviso en curso.
- **Menos mensajes al ESP32**: la distancia se redondea hacia arriba a pasos de 100 m (>90 km/h), 50 m (40-90) o 20 m (<40)
  y solo se manda un mensaje cuando cambia el valor mostrado (más uno de "sigo aquí" cada 4 s; el ESP32 quita la alerta
  si pasan 8 s sin nada). Pasos configurables.
- **Exceso de velocidad**: mientras se muestran los metros, si la velocidad supera el límite de la vía en el radar
  (+ margen configurable) se manda `"ov":1` y el ESP32 pinta el borde de toda la pantalla en rojo (con filo blanco y fondo
  más oscuro para que se vea) hasta que bajes del límite.
- **Límite de la vía** (`RoadLimit`): primero el del propio radar (si OpenStreetMap lo anota); si no, el `maxspeed` de la vía
  más cercana al radar y con sentido acorde al rumbo, consultado a Overpass una vez por radar (cuando es el siguiente
  radar por delante a <3 km) y guardado en `filesDir/limites.json`. Solo se envían las coordenadas del radar. Sin dato o sin
  conexión no hay borde rojo. Se puede desactivar en "Configurar avisos".
- **Interruptores** (tarjeta AVISOS DE RADAR): "Avisar de radares" (todo el sistema) y "Alerta por exceso de velocidad"
  (solo el borde rojo). La app avisa de que **faltan los radares de Cataluña** (fichero oficial del Servei Català de Trànsit,
  pendiente de integrar; mientras tanto solo hay los de OpenStreetMap).
- **Prueba**: "Probar aviso de radar" en la tarjeta PRUEBAS DEL ESP32.

## Pruebas del ESP32 (sección "PRUEBAS DEL ESP32" de la app)
- **Probar indicación GPS**: elige una maniobra (recto, giros, cambio de sentido, mantente, rotondas 1.ª/2.ª/3.ª salida).
  La app dibuja la flecha y la manda igual que con una ruta real (icono + `{"t":"nav"}`) durante 12 s;
  después limpia la pantalla y reenvía la ruta real si había una. El ESP32 debe estar en modo GPS.
- **Probar dirección de brújula**: elige un ángulo (0°, 90°, 180°, -90°, ±45°), "Has llegado" o un giro completo animado.
  Manda `{"t":"dest",...}` durante 12 s y luego restablece el destino real. El ESP32 debe estar en modo brújula.
- Código: `TestSignals.kt` (+ `Destination.testOverride` y una pausa de `GoogleMapsHandler` mientras dura la prueba GPS).

## Protocolo (ver Frame.kt / PhoneLink.h)
`0xA5 | tipo | largo(2, LE) | payload | checksum XOR`
- 0x01 JSON UTF-8 · 0x02 icono (slot, w, h, bpp=4, píxeles 4 bits)

## Autoría
- Desarrollador: **MiAnCa**
- Web: https://www.miguelandrescaballero.es/
- Repositorio: https://github.com/MiAnCaDev/esp32c3-navegador-tools
