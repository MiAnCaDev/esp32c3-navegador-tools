#pragma once
/*
  AJUSTES GUARDADOS EN EL ESP32 (memoria NVS, sobreviven a reinicios y a reflashear el sketch)

  Los envia la app del movil por Bluetooth con mensajes {"t":"cfg",...}.

  ABIERTO POR DEFECTO: cualquier movil que se conecte por Bluetooth puede mandar estos ajustes (varios moviles
  funcionan sin hacer nada). Es lo que se quiere para uso casero: la app no envia datos sensibles.

  BLOQUEO OPCIONAL (lo activa el usuario desde la app): solo obedece a moviles EMPAREJADOS (hasta CFG_MAX_KEYS = 4,
  p. ej. dos moviles a la vez). Para emparejar un movil hay que ver el ESP32:
    {"t":"cfg","pair":1}                     -> pide emparejarse: el ESP32 muestra un codigo de 6 cifras en SU pantalla
    {"t":"cfg","code":"123456","nk":"<32hex>"} -> el movil escribe ese codigo y propone SU clave; si coincide
                                                 (3 intentos, 90 s) el ESP32 la AÑADE a la lista y activa el bloqueo
  Con el bloqueo activo, todo mensaje cfg (salvo get y el emparejamiento) debe llevar  "k":"<clave del movil>".
  Cada movil se empareja una vez (el 5.º sustituye al mas antiguo). Perder todos los moviles no es un problema: basta
  ver el codigo en la pantalla para emparejar otro. Sin el bloqueo, las claves y el emparejamiento no se usan.
  Limites: una peticion de emparejamiento cada 8 s y 60 s de espera tras 3 codigos fallidos.

  Mensajes:
    {"t":"cfg","get":1}                      -> (sin clave) el ESP32 contesta con su estado (sin secretos)
    {"t":"cfg","lock":0}                     -> (con clave) quita el bloqueo y olvida las claves: otra vez abierto
    {"t":"cfg","lact":1}                     -> habilita (1) / deshabilita (0) el modo lactancia
    {"t":"cfg","hidebtn":1}                  -> oculta el boton de lactancia del ESP32 mientras la lactancia esta deshabilitada
                                                (en su lugar se ve si el movil esta conectado por Bluetooth)
    {"t":"cfg","api":"https://..."}          -> URL de la API, solo https ("" = volver a la del firmware)
    {"t":"cfg","tok":"..."}                  -> token de la API ("" = volver al del firmware)
    {"t":"cfg","poll":60}                    -> segundos entre consultas (0 = el valor del firmware)
    {"t":"cfg","wi":0,"s":"red","p":"clave"} -> red WiFi n.º 0..2 (s vacio = borrar esa red)
    {"t":"cfg","reset":1}                    -> borra API, token, intervalo y redes: vuelven los valores por defecto

  Respuesta (notificacion, nunca incluye claves ni token):
    {"t":"cfg","lact":0,"api":0,"tok":0,"wifi":0,"poll":0,"lock":0,"keys":0,"mine":0,"hide":0,"note":"..."}
    lock = bloqueo activo · keys = moviles emparejados · mine = la clave "k" de ESTE mensaje es valida
    note (opcional): show = codigo en pantalla · paired = codigo aceptado · wrongcode · expired · bad · wait · auth (bloqueado y
    la clave falta o no vale)

  OJO: el modo lactancia se habilita desde la app y estos ajustes solo se aplican en el SIGUIENTE arranque en
  modo lactancia (en ese modo el Bluetooth esta apagado, asi que la configuracion se envia desde el modo GPS).
*/
#include <Arduino.h>
#include <Preferences.h>
#include <ArduinoJson.h>
#include <esp_system.h>      // esp_random()

#define CFG_MAX_WIFI   3
#define CFG_POLL_MIN_S 10
#define CFG_POLL_MAX_S 3600

struct CfgWifi { char ssid[33]; char pass[65]; };

static bool     lactEnabled = false;    // el boton de modo lactancia solo funciona si es true (por defecto false)
static bool     cfgHideBtn  = false;    // true = con la lactancia deshabilitada el boton NO se muestra (sale el indicador de Bluetooth)
static bool     cfgBootLact = false;    // este arranque es en modo lactancia (BLE apagado desde el principio)
static char     cfgApi[200] = "";       // "" = usar API_BASE del firmware
static char     cfgTok[80]  = "";       // "" = usar API_TOKEN del firmware
static uint32_t cfgPollMs   = 0;        // 0  = usar POLL_MS del firmware
static CfgWifi  cfgWifi[CFG_MAX_WIFI];  // si hay alguna, sustituyen a WIFI_LIST
static Preferences cfgPrefs;

// Definidas en el .ino (pantalla y Bluetooth)
void cfgSendStatus(const char *note, int mine);   // notifica el estado al movil; note = nullptr o un aviso; mine = 0/1
void cfgShowPairCode(const char *code); // enseña el codigo de emparejamiento en la pantalla
void cfgHidePairCode();
void cfgApplied();                      // se llama tras aplicar un mensaje cfg: el .ino actualiza lo que dependa (botones...)

#define CFG_MAX_KEYS 4
static char     cfgKeys[CFG_MAX_KEYS][33];  // claves (32 hex) de los moviles emparejados; "" = hueco libre
static bool     cfgLock       = false;  // false = abierto (por defecto) · true = solo moviles emparejados
static uint8_t  cfgKeyNext    = 0;      // hueco que sustituira el 5.º movil (rotatorio)
static char     pairCode[8]   = "";     // codigo de 6 cifras en curso
static uint32_t pairUntil     = 0;      // millis() hasta el que vale el codigo (0 = ninguno)
static uint8_t  pairTries     = 0;
static uint32_t pairLastReq   = 0;
static uint32_t pairLockUntil = 0;

static bool cfgBefore(uint32_t until) { return until != 0 && (int32_t)(until - millis()) > 0; }   // seguro ante el desbordamiento de millis()

static bool cfgHexKeyOk(const char *k)
{
  if (!k || strlen(k) != 32) return false;
  for (int i = 0; i < 32; i++) if (!isxdigit((unsigned char)k[i])) return false;
  return true;
}

// Comparacion en tiempo constante (no revela por el tiempo cuantos caracteres coinciden)
static bool cfgCtEq(const char *a, const char *b)
{
  const size_t la = strlen(a), lb = strlen(b);
  uint8_t d = (la != lb) ? 1 : 0;
  const size_t n = la < lb ? la : lb;
  for (size_t i = 0; i < n; i++) d |= (uint8_t)(a[i] ^ b[i]);
  return d == 0;
}

static int cfgKeyCount()
{
  int n = 0;
  for (int i = 0; i < CFG_MAX_KEYS; i++) if (cfgKeys[i][0]) n++;
  return n;
}

// ¿Es k la clave de algun movil emparejado? Se recorren TODAS (sin salida anticipada) para no revelar nada por el tiempo.
static bool cfgKeyMatch(const char *k)
{
  if (!k || !k[0]) return false;
  bool ok = false;
  for (int i = 0; i < CFG_MAX_KEYS; i++) if (cfgKeys[i][0] && cfgCtEq(k, cfgKeys[i])) ok = true;
  return ok;
}

// Añade la clave de un movil recien emparejado y activa el bloqueo
static void cfgAddKey(const char *nk)
{
  if (!cfgKeyMatch(nk)) {
    int slot = -1;
    for (int i = 0; i < CFG_MAX_KEYS && slot < 0; i++) if (!cfgKeys[i][0]) slot = i;
    if (slot < 0) { slot = cfgKeyNext % CFG_MAX_KEYS; }          // lleno: sustituye al mas antiguo
    strlcpy(cfgKeys[slot], nk, sizeof(cfgKeys[slot]));
    char key[8]; snprintf(key, sizeof(key), "ck%d", slot);
    cfgPrefs.putString(key, cfgKeys[slot]);
    cfgKeyNext = (uint8_t)((slot + 1) % CFG_MAX_KEYS);
    cfgPrefs.putUChar("kn", cfgKeyNext);
  }
  if (!cfgLock) { cfgLock = true; cfgPrefs.putBool("lock", true); }
}

// Quita el bloqueo y olvida todas las claves: vuelve a estar abierto
static void cfgUnlock()
{
  cfgLock = false;
  cfgPrefs.remove("lock"); cfgPrefs.remove("kn");
  for (int i = 0; i < CFG_MAX_KEYS; i++) {
    char key[8]; snprintf(key, sizeof(key), "ck%d", i);
    cfgPrefs.remove(key); cfgKeys[i][0] = 0;
  }
  cfgKeyNext = 0;
}

static void cfgReadStr(const char *key, char *dst, size_t n)
{
  String v = cfgPrefs.getString(key, "");
  strlcpy(dst, v.c_str(), n);
}

// Llamar lo primero de setup(): decide si este arranque es en modo lactancia
// Llamar desde loop(): borra en cuanto caducan el codigo, el bloqueo y el anti-spam. Sin esto, tras ~25 dias encendido
// (millis() cruza la mitad de su rango) un valor viejo podria parecer "futuro" y bloquear el emparejamiento.
static void cfgTick()
{
  const uint32_t now = millis();
  if (pairUntil && !cfgBefore(pairUntil)) { pairUntil = 0; pairCode[0] = 0; cfgHidePairCode(); }
  if (pairLockUntil && !cfgBefore(pairLockUntil)) pairLockUntil = 0;
  if (pairLastReq && (uint32_t)(now - pairLastReq) >= 8000UL) pairLastReq = 0;
}

static void cfgLoad()
{
  cfgBootLact = false;
  cfgPrefs.begin("navcfg", false);
  lactEnabled = (ENABLE_LACTANCIA) && cfgPrefs.getBool("lact", false);
  cfgHideBtn  = cfgPrefs.getBool("hidebtn", false);
  cfgReadStr("api", cfgApi, sizeof(cfgApi));
  cfgReadStr("tok", cfgTok, sizeof(cfgTok));
  cfgPollMs = cfgPrefs.getUInt("poll", 0) * 1000UL;
  cfgPrefs.remove("ckey");                          // formato antiguo (una sola clave): ya no se usa
  for (int i = 0; i < CFG_MAX_KEYS; i++) {
    char key[8]; snprintf(key, sizeof(key), "ck%d", i);
    cfgReadStr(key, cfgKeys[i], sizeof(cfgKeys[i]));
    if (!cfgHexKeyOk(cfgKeys[i])) cfgKeys[i][0] = 0; // lo guardado no es una clave valida: hueco libre
  }
  cfgKeyNext = cfgPrefs.getUChar("kn", 0) % CFG_MAX_KEYS;
  cfgLock = cfgPrefs.getBool("lock", false) && cfgKeyCount() > 0;   // bloqueo sin ninguna clave no tendria sentido
  for (int i = 0; i < CFG_MAX_WIFI; i++) {
    char k[8];
    snprintf(k, sizeof(k), "ws%d", i); cfgReadStr(k, cfgWifi[i].ssid, sizeof(cfgWifi[i].ssid));
    snprintf(k, sizeof(k), "wp%d", i); cfgReadStr(k, cfgWifi[i].pass, sizeof(cfgWifi[i].pass));
  }
  // "boot"=1 lo deja el boton de cambio de modo justo antes de reiniciar. Se borra en el acto: si algo fallara,
  // el siguiente arranque es normal (modo GPS) y no puede quedar en bucle.
  if (cfgPrefs.getUChar("boot", 0) == 1) {
    cfgPrefs.putUChar("boot", 0);
    cfgBootLact = lactEnabled;
  }
}

// Pide que el proximo arranque sea en modo lactancia (true) o GPS (false)
static void cfgRequestBoot(bool lactancia)
{
  cfgPrefs.putUChar("boot", lactancia ? 1 : 0);
}

// Guarda una cadena solo si cambia (menos escrituras en flash). Si no cabe, se ignora.
static void cfgSetStr(const char *key, char *dst, size_t n, const char *val)
{
  if (val == nullptr) val = "";
  if (strlen(val) >= n || strcmp(dst, val) == 0) return;
  strlcpy(dst, val, n);
  if (val[0]) cfgPrefs.putString(key, val); else cfgPrefs.remove(key);
}

static void cfgStatusJson(char *out, size_t n, const char *note, int mine)
{
  int wifi = 0;
  for (int i = 0; i < CFG_MAX_WIFI; i++) if (cfgWifi[i].ssid[0]) wifi++;
  int w = snprintf(out, n, "{\"t\":\"cfg\",\"lact\":%d,\"api\":%d,\"tok\":%d,\"wifi\":%d,\"poll\":%u,\"lock\":%d,\"keys\":%d,\"mine\":%d,\"hide\":%d",
                   lactEnabled ? 1 : 0, cfgApi[0] ? 1 : 0, cfgTok[0] ? 1 : 0, wifi,
                   (unsigned)(cfgPollMs / 1000UL), cfgLock ? 1 : 0, cfgKeyCount(), mine ? 1 : 0, cfgHideBtn ? 1 : 0);
  if (w > 0 && (size_t)w < n) {
    if (note && note[0]) snprintf(out + w, n - w, ",\"note\":\"%s\"}\n", note);
    else                 snprintf(out + w, n - w, "}\n");
  }
}

// Mensaje {"t":"cfg",...} recibido del movil (se llama desde loop())
static void cfgHandleJson(JsonDocument &doc)
{
  const uint32_t now = millis();
  const char *k = doc["k"] | "";
  const int mine = cfgKeyMatch(k) ? 1 : 0;

  // 1) Sin clave siempre se puede consultar el estado y emparejarse
  if ((int)(doc["get"] | 0)) { cfgSendStatus(nullptr, mine); return; }

  if ((int)(doc["pair"] | 0)) {                          // el movil pide emparejarse: codigo en la pantalla del ESP32
    if (cfgBefore(pairLockUntil) || (pairLastReq != 0 && (uint32_t)(now - pairLastReq) < 8000UL)) {
      cfgSendStatus("wait", mine); return;
    }
    pairLastReq = now ? now : 1;
    snprintf(pairCode, sizeof(pairCode), "%06u", (unsigned)(esp_random() % 1000000UL));
    pairUntil = now + 90000UL; if (pairUntil == 0) pairUntil = 1;
    pairTries = 0;
    cfgShowPairCode(pairCode);
    cfgSendStatus("show", mine);
    return;
  }

  const char *code = doc["code"] | "";
  if (code[0]) {                                         // el movil devuelve el codigo y la clave que quiere usar
    const char *nk = doc["nk"] | "";
    if (!cfgBefore(pairUntil) || !pairCode[0]) { cfgSendStatus("expired", mine); return; }
    if (!cfgHexKeyOk(nk))                      { cfgSendStatus("bad", mine); return; }
    if (!cfgCtEq(code, pairCode)) {
      if (++pairTries >= 3) {                            // demasiados fallos: se anula el codigo y se espera un minuto
        pairCode[0] = 0; pairUntil = 0;
        pairLockUntil = now + 60000UL; if (pairLockUntil == 0) pairLockUntil = 1;
        cfgHidePairCode();
      }
      cfgSendStatus("wrongcode", mine); return;
    }
    cfgAddKey(nk);                                       // se añade a los moviles autorizados y se activa el bloqueo
    pairCode[0] = 0; pairUntil = 0;
    cfgHidePairCode();
    cfgSendStatus("paired", 1);                          // aviso explicito: solo se manda al aceptar el codigo
    return;
  }

  // 2) Con el bloqueo activo, todo lo demas exige la clave de un movil emparejado
  if (cfgLock && !mine) { cfgSendStatus("auth", 0); return; }

  if (!doc["lock"].isNull() && ((int)(doc["lock"] | 1)) == 0) cfgUnlock();     // volver a "abierto" (solo se puede quitar, no poner)

  if ((int)(doc["reset"] | 0)) {
    cfgPrefs.remove("api"); cfgPrefs.remove("tok"); cfgPrefs.remove("poll");
    cfgApi[0] = 0; cfgTok[0] = 0; cfgPollMs = 0;
    for (int i = 0; i < CFG_MAX_WIFI; i++) {
      char kk[8];
      snprintf(kk, sizeof(kk), "ws%d", i); cfgPrefs.remove(kk);
      snprintf(kk, sizeof(kk), "wp%d", i); cfgPrefs.remove(kk);
      cfgWifi[i].ssid[0] = 0; cfgWifi[i].pass[0] = 0;
    }
  }
  if (!doc["lact"].isNull()) {
    bool v = ((int)(doc["lact"] | 0)) != 0;
    if (cfgPrefs.getBool("lact", false) != v) cfgPrefs.putBool("lact", v);
    lactEnabled = (ENABLE_LACTANCIA) && v;
  }
  if (!doc["hidebtn"].isNull()) {
    bool v = ((int)(doc["hidebtn"] | 0)) != 0;
    if (cfgPrefs.getBool("hidebtn", false) != v) cfgPrefs.putBool("hidebtn", v);
    cfgHideBtn = v;
  }
  if (!doc["api"].isNull()) {
    const char *u = doc["api"] | "";
    // Solo HTTPS (o vacio = la del firmware): con http:// el token viajaria sin cifrar
    if (u[0] && strncmp(u, "https://", 8) != 0) { cfgSendStatus("bad", mine); return; }
    cfgSetStr("api", cfgApi, sizeof(cfgApi), u);
  }
  if (!doc["tok"].isNull()) cfgSetStr("tok", cfgTok, sizeof(cfgTok), doc["tok"] | "");
  if (!doc["poll"].isNull()) {
    uint32_t s = doc["poll"] | 0;
    if (s != 0) s = constrain(s, (uint32_t)CFG_POLL_MIN_S, (uint32_t)CFG_POLL_MAX_S);
    if (cfgPollMs != s * 1000UL) {
      cfgPollMs = s * 1000UL;
      if (s) cfgPrefs.putUInt("poll", s); else cfgPrefs.remove("poll");
    }
  }
  if (!doc["wi"].isNull()) {
    int i = doc["wi"] | -1;
    if (i >= 0 && i < CFG_MAX_WIFI) {
      char kk[8];
      snprintf(kk, sizeof(kk), "ws%d", i); cfgSetStr(kk, cfgWifi[i].ssid, sizeof(cfgWifi[i].ssid), doc["s"] | "");
      snprintf(kk, sizeof(kk), "wp%d", i); cfgSetStr(kk, cfgWifi[i].pass, sizeof(cfgWifi[i].pass), doc["p"] | "");
    }
  }
  cfgApplied();                                      // el .ino actualiza botones/indicadores si hace falta
  cfgSendStatus(nullptr, cfgKeyMatch(k) ? 1 : 0);   // confirma al movil
}
