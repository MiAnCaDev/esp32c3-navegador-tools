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

## Protocolo (ver Frame.kt / PhoneLink.h)
`0xA5 | tipo | largo(2, LE) | payload | checksum XOR`
- 0x01 JSON UTF-8 · 0x02 icono (slot, w, h, bpp=4, píxeles 4 bits)

## Autoría
- Desarrollador: **MiAnCa**
- Web: https://www.miguelandrescaballero.es/
- Repositorio: https://github.com/MiAnCaDev/esp32c3-navegador-tools
