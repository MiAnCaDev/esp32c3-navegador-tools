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

## Modo lactancia: habilitar y configurar desde la app
- **Deshabilitado de fábrica.** `ENABLE_LACTANCIA true` en `ESP32.ino` solo significa "el firmware incluye el modo". El botón
  del ESP32 no hace nada (avisa "Lactancia off: activala en la app") hasta que se habilita en la app (tarjeta MODO LACTANCIA).
  El valor se guarda en la memoria NVS del ESP32 y sobrevive a reinicios y a reflashear el sketch.
- **Cambiar de modo reinicia el ESP32** (~2 s; el móvil se reconecta solo). Así cada modo arranca con la memoria limpia: en
  lactancia el Bluetooth ni se enciende (toda la RAM para WiFi/TLS) y se acaba el "no conecta" de la segunda vez; en modo GPS
  no existe la tarea de red (10 KB menos). El arranque en lactancia se pide con una marca en NVS que se borra al leerla, así
  que un apagado/encendido siempre vuelve a modo GPS.
- **Valores por defecto** de WiFi, URL, token e intervalo: `ESP32/LactanciaConfig.h` (solo se edita ese fichero).
  Desde la app ("Servidor y WiFi del ESP32") se pueden sustituir: URL, token, intervalo (10-3600 s) y hasta 3 redes. Se guardan
  en el ESP32; "Valores por defecto" los borra. El ESP32 nunca devuelve claves ni token, solo cuántos hay.
- **Protocolo** (`DeviceConfig.h` / `EspConfig.kt`): `{"t":"cfg","get":1|"lact":0/1|"api":".."|"tok":".."|"poll":60|"wi":0,"s":"..","p":".."|"reset":1}`;
  el ESP32 contesta `{"t":"cfg","lact":0,"api":0,"tok":0,"wifi":0,"poll":0}`. La configuración se envía en modo GPS (con
  Bluetooth) y se aplica la próxima vez que se entre en lactancia.
- Acceso: abierto por defecto (varios móviles); bloqueo opcional con emparejamiento por código (ver "Seguridad").

## Lactancia: diagnóstico del "error de servidor"
En `LactanciaApp.h` (solo cambian las funciones de red y el texto de estado; el bloque de WiFi/URL/token no se toca):
- La pantalla ya no dice "Error de servidor" a secas: enseña el motivo (`Error: HTTP 500`, `timeout`, `no conecta`,
  `sin RAM (xxKB)`, `respuesta no valida`, `URL invalida`...). El monitor serie añade una línea `[NET] ...` con el código,
  la memoria libre y los primeros 160 caracteres de la respuesta del servidor.
- Respuestas JSON: se guardan solo los campos usados (filtro de ArduinoJson) en vez de un documento fijo de 512 bytes
  que se quedaba corto si el servidor añadía campos (una respuesta 200 correcta contaba como error).
- Se siguen las redirecciones 301/302 y los tiempos pasan de 4/5 s a 6/8 s.

## Modo moto (inclinación máxima en el ESP32)
Interruptor en Moto > MODO MOTO. Activado, el móvil calcula la inclinación (ver "Moto: grabar viajes") y manda al ESP32 la **máxima desde
que se activó** (`{"t":"moto","on":1,"txt":"MOTO 47 IZQ"}`, solo ASCII); el ESP32 la dibuja en la esquina superior izquierda de la zona libre
(`motoChip`). Al activarlo el máximo vuelve a cero. Solo cuentan inclinaciones mantenidas 0,3 s (`SustainedLean`): un bache o un pico de
ruido no es "inclinación máxima".
- **Se desactiva solo cuando el ESP32 se desconecta** (`BridgeService` lo detecta) y hay que volver a activarlo a mano; no se puede activar
  sin el ESP32 conectado. El ESP32 también oculta el aviso si pierde el Bluetooth o pasan 8 s sin mensajes. Si el servicio se detiene
  ("Parar"), también se desactiva.
- Es independiente de **Grabar viaje**: se pueden usar a la vez o por separado; el modo moto no guarda nada.

## Botón de lactancia del ESP32: ocultarlo + indicador de Bluetooth
- Lactancia **habilitada**: el botón se ve y funciona.
- Lactancia **deshabilitada** (por defecto): si "Ocultar el botón del ESP32 si la lactancia está desactivada" está **apagado** (por defecto), el botón
  se ve y al pulsarlo avisa de que hay que activarla en la app (como antes). Si está **encendido**, el botón no se muestra y en su sitio sale
  un recuadro **BT**: azul = móvil conectado por Bluetooth, gris = sin conexión.
- El ajuste se guarda en el ESP32 (`hidebtn`, NVS; mismo control de acceso que el resto de ajustes) y la app muestra su estado real.

## Moto: grabar viajes (pestaña Moto)
Graba ruta, velocidad, aceleración e **inclinación** y las curvas del viaje, y lo guarda en el móvil (`moto/`).
- **Qué hardware usa**: el del móvil (GPS, acelerómetro, giroscopio). El ESP32 de esta placa solo tiene pantalla táctil por I2C y ningún
  acelerómetro conocido, así que no interviene en la medida. Sin giroscopio se graba la ruta pero no la inclinación.
- **Inclinación** (`LeanEstimator`): `sen φ = v·m/g`, con `v` = velocidad del GPS y `m` = giro del giroscopio proyectado sobre la dirección
  del acelerómetro filtrado. No depende de cómo vaya colocado el móvil ni necesita calibrar. Se corrige solo el sesgo del giroscopio
  (parado y en rectas). No se calcula por debajo de 14 km/h. Probado con una moto simulada (vibración, ruido, sesgo, 40 orientaciones
  aleatorias del móvil, 43-108 km/h): error típico ~0,75°, peor instante ~3°. Modelo de moto ideal: no incluye el grosor del neumático ni
  al piloto colgado del lateral, así que sirve para comparar curvas y viajes, no como medida de laboratorio.
- **Curvas** (`CurveDetector`): empiezan al pasar de 12°, acaban al bajar de 8° (o cambiar de lado: una "S" son dos curvas); solo cuentan
  las de al menos 15° y 1,2 s.
- **Datos guardados**: un punto por segundo (posición, altitud, velocidad, rumbo, inclinación y su pico del último segundo, g lateral y
  longitudinal) y las curvas. El archivo se escribe sobre la marcha: si la app se cierra a mitad de un viaje se recupera al listar.
- **Exportar**: GPX 1.1 (ruta + las curvas como puntos de interés "Curva izq 41°" + velocidad/inclinación/g como extensión `moto:`) y CSV.
  Se pueden compartir o guardar donde se quiera desde cada viaje.
- **Segundo plano**: usa el servicio en primer plano de la app (GPS) y un wake-lock parcial mientras graba (sin él, con la pantalla apagada
  Android deja de entregar muestras del giroscopio). Al parar el servicio ("Parar" en Inicio) el viaje se cierra y se guarda. El GPS no
  se pausa mientras se graba.
- **Dos usos independientes**: *Grabar viaje* (guarda el viaje) y *Modo moto* (inclinación máxima en el ESP32, ver arriba); comparten sensores
  y se apagan cuando no queda ninguno.
- **Pestañas**: Ajustes > Pestañas muestra u oculta Moto (visible por defecto) y Lactancia (oculta por defecto).

## Pantallas de la app (barra de pestañas inferior)
- **Inicio**: Iniciar/Parar con el estado y la velocidad, y justo debajo el dispositivo ESP32. Si falta algún permiso imprescindible
  sale un aviso que lleva directo a Ajustes (y la pestaña Ajustes muestra un punto rojo).
- **Navegación**: destino de la brújula, ubicaciones favoritas (hasta 8), avisos de radar y pruebas de mensajes al ESP32.
- **Moto**: grabación de viajes con medidor de inclinación, y lista de viajes guardados (GPX/CSV).
- **Lactancia** (opcional, oculta por defecto): habilitar el modo y servidor/WiFi del ESP32.
- **Ajustes**: arranque, configuración inicial, herramientas (logs) y acerca de.
"Atrás" desde cualquier pestaña vuelve primero a Inicio; la pestaña activa se recuerda al girar la pantalla.

## Ubicaciones favoritas (brújula)
Hasta 8, en Navegación (`Favorites.kt`). "Añadir" admite un enlace de Google Maps o coordenadas (como "Pegar coordenadas o enlace") más
un nombre; "Guardar mi ubicación actual" usa el GPS. Tocar una la fija como destino de la brújula (se marca como "Destino activo" cuando
coincide con el destino actual, venga de donde venga); el botón ⋮ (o pulsación larga) permite renombrar o eliminar. No se guardan dos
favoritas a menos de 15 m. Los nombres se guardan sin acentos porque la pantalla del ESP32 no los dibuja.

## Seguridad (revisión completa)
**Modelo de acceso a los ajustes del ESP32 (WiFi, URL, token, habilitar lactancia)**
- **Abierto por defecto**: cualquier móvil que se conecte puede mandarlos, así que se pueden usar varios móviles sin hacer nada. Es una
  decisión de uso casero: la app no envía datos sensibles y quien tuviera el ESP32 en la mano podría reflashearlo de todos modos.
- **Bloqueo opcional** (Lactancia > "Bloquear ajustes a móviles emparejados"): el ESP32 solo obedece a móviles **emparejados**, hasta 4 a la
  vez. Para emparejar un móvil hay que ver el ESP32: muestra un código de 6 cifras (90 s, 3 intentos, 60 s de espera tras fallarlos) y el
  móvil guarda una clave aleatoria de 128 bits que el ESP32 añade a su lista (el 5.º sustituye al más antiguo). Cada móvil se empareja una
  vez. Perderlos todos no es un problema: con el código de la pantalla se empareja otro. Con la clave de un móvil emparejado se puede
  quitar el bloqueo. (`DeviceConfig.h`, `EspConfig.kt`)

**Corregido en la revisión**
- La URL de la API debe ser `https://` (app y ESP32).
- `allowBackup=false` + `dataExtractionRules`: claves WiFi, token y favoritas ya no se suben a copias de seguridad en la nube ni se transfieren;
  para cambiar de móvil está Exportar/Importar (la clave de emparejamiento solo va si se incluyen las claves).
- `BootReceiver` ya no está exportado y solo atiende las acciones esperadas; `usesCleartextTraffic=false` explícito.
- `LinkResolver`: lista cerrada de dominios de Google (antes valía cualquier `google.xx`, y algunos, como `.tk` o `.top`, los registra cualquiera).
- El texto que otras apps comparten con la app se acota a 4000 caracteres.
- Los logs ya no incluyen nombres de destinos ni de favoritas (se comparten con otras personas).
- Verificación opcional del certificado TLS: pon el certificado raíz de tu servidor en `API_CA_CERT` (`LactanciaConfig.h`).
- Pruebas: la app compila contra el SDK real de Android (sin errores ni avisos); la lógica de `DeviceConfig.h` pasa 43 casos en un
  entorno simulado (modo abierto, bloqueo, varios móviles, sustitución, persistencia, recuperación, caducidades, desbordamiento de
  `millis()`); el parser de tramas BLE se probó con 20.000 entradas aleatorias bajo AddressSanitizer sin fallos.

**Riesgos que quedan (conocidos)**
- Con el bloqueo desactivado (por defecto), cualquiera con un móvil cerca del ESP32 puede cambiar sus ajustes (por ejemplo la URL de la API,
  y con ella a dónde se envía el token). Activa el bloqueo si ese riesgo te importa.
- El Bluetooth del ESP32 no cifra el enlace (emparejamiento "Just Works" sin exigir cifrado a las características): quien tenga un
  sniffer BLE cerca *en el momento exacto* de emparejar o enviar ajustes podría leerlos.
- Sin `API_CA_CERT` el ESP32 no comprueba el certificado del servidor (cifra, pero no sabe con quién habla): quien controle la red WiFi
  podría interceptar el token. El token va en la URL (así lo exige la API), por lo que también queda en los logs del servidor.
- Los mensajes de navegación/radar/brújula no se autentican (solo afectan a lo que se dibuja; no cambian ajustes).
- Claves y token se guardan en texto plano en el almacenamiento privado del móvil y en la flash del ESP32 (sin cifrado de flash).

## Exportar / importar configuración (Ajustes > Herramientas)
Genera/lee un `.json` (`ConfigBackup.kt`) con: ESP32 elegido, opciones de arranque, destino de la brújula, favoritas, avisos de radar y
ajustes de lactancia (servidor, intervalo, redes WiFi). Al exportar se pregunta si se incluyen las claves WiFi y el token (texto plano).
Importar muestra qué se va a sustituir y pide confirmación; solo cambia las secciones presentes en el archivo y valida cada valor.
No incluye la base de radares, la caché de límites ni los logs (se vuelven a obtener), ni el "habilitado" de lactancia (vive en el ESP32).

## Si la descarga de la DGT falla
El resumen de la descarga ahora dice el motivo por dirección (`DGT falló (nap.dgt.es: HTTP 502; ...)`). Si la DGT responde pero el
XML no se reconoce, el log guarda las etiquetas vistas y el inicio del archivo (`W/Radar: DGT ...: respuesta sin radares
reconocibles`) para poder ajustar el lector. Overpass (OpenStreetMap) se consulta en tres espejos por si uno está saturado.

## Tests
`tests/` contiene las pruebas automáticas (firmware en C++ que se ejecuta en el PC, y tests JUnit de la app). Se lanzan con
`tests/run_tests.sh` o `tests\run_tests.bat` (o `./gradlew :app:testDebugUnitTest` para la parte de la app). Qué cubre cada una, cómo
lanzarlas y qué NO cubren: `tests/README.md`; resultado de la última ejecución: `tests/ULTIMA_EJECUCION.txt`.

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
- **Datos** (`RadarUpdater`): DGT DATEX II desde `nap.dgt.es` (radares fijos y de tramo, CC-BY, sin Cataluña/País Vasco; la dirección antigua `infocar.dgt.es` daba HTTP 502 y queda como respaldo) + OpenStreetMap
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
- **Exceso de velocidad** (línea en el borde DERECHO del ESP32; la app decide, el ESP32 solo dibuja):
  con la alerta roja en pantalla -> línea blanca fina pegada al borde (`{"t":"radar","on":1,...,"ov":1}`);
  sin alerta roja -> línea roja gruesa (`{"t":"radar","on":0,"ov":1}`), mientras sigas por encima del límite y hasta pasar
  el radar. Solo se activa cuando ya ha saltado el primer aviso de ese radar. Se quita con `{"t":"radar","on":0}`.
- **Distancia en la pantalla normal**: cuando acaba la alerta roja, una pastilla "Radar 850 m" en la esquina superior derecha
  (`{"t":"radar","on":0,"d":850}`) sigue mostrando los metros hasta pasar el radar. Misma regla que en la alerta: solo si estás
  a menos de la distancia de "mostrar metros" de tu franja de velocidad, en pasos de 100/50/20 m. Interruptor propio en la app.
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
