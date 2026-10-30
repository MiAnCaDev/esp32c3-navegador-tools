// Prueba en el PC (sin placa) la logica de ESP32/DeviceConfig.h: ajustes guardados, modo abierto/bloqueado, emparejamiento por codigo,
// varios moviles, limites anti-fuerza-bruta, caducidades, persistencia (NVS simulada), ocultar el boton de lactancia...
// Arduino, Preferences (NVS) y esp_random() estan simulados en stubs/. Ver tests/README.md.
#include "Arduino.h"
#define ENABLE_LACTANCIA true
#include "Preferences.h"
#include "ArduinoJson.h"
#include "esp_system.h"
#include "../../ESP32/DeviceConfig.h"
#include <vector>
uint32_t g_millis = 1000, g_rand = 123456;
static std::vector<std::string> out; static std::string shown; static bool hidden = false;
void cfgSendStatus(const char *note, int mine) { char b[200]; cfgStatusJson(b, sizeof(b), note, mine); out.push_back(b); }
void cfgShowPairCode(const char *c) { shown = c; hidden = false; }
void cfgHidePairCode() { hidden = true; }
static int applied = 0;
void cfgApplied() { applied++; }
static int fails = 0;
#define CHECK(c, m) do { if (!(c)) { printf("FALLO: %s\n", m); fails++; } else printf("ok: %s\n", m); } while (0)
static bool has(const char *t) { return !out.empty() && out.back().find(t) != std::string::npos; }
static void send(const std::string &json) { StaticJsonDocument<768> d; deserializeJson(d, json); out.clear(); cfgHandleJson(d); }
static void advance(uint32_t ms) { const uint32_t step = 3600000u; while (ms > step) { g_millis += step; ms -= step; cfgTick(); } g_millis += ms; cfgTick(); }
static const std::string KA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", KB = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", KC = "cccccccccccccccccccccccccccccccc",
  KD = "dddddddddddddddddddddddddddddddd", KE = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";
static std::string msg(const char *body, const std::string &k) { return std::string("{\"t\":\"cfg\",") + body + (k.empty() ? "" : ",\"k\":\"" + k + "\"") + "}"; }
static void pairPhone(const std::string &k) {          // emparejar un movil: pedir codigo y devolverlo
  advance(9000); g_rand = 100000 + (g_millis % 800000);
  send("{\"t\":\"cfg\",\"pair\":1}"); std::string code = shown;
  send("{\"t\":\"cfg\",\"code\":\"" + code + "\",\"nk\":\"" + k + "\"}");
}
int main() {
  Preferences::db()["ckey"] = "0123456789abcdef0123456789abcdef";           // formato antiguo de la v15
  cfgLoad();
  CHECK(!cfgLock && cfgKeyCount() == 0 && Preferences::db().count("ckey") == 0, "arranca ABIERTO y descarta el formato antiguo");
  // --- 1) modo abierto: sin clave todo vale
  send("{\"t\":\"cfg\",\"get\":1}"); CHECK(has("\"lock\":0") && has("\"keys\":0") && !has("note"), "get: lock 0, keys 0");
  send(msg("\"lact\":1", "")); CHECK(lactEnabled && !has("note"), "ABIERTO: habilitar lactancia sin clave");
  send(msg("\"api\":\"https://mi.servidor/api.php\"", "")); CHECK(strcmp(cfgApi, "https://mi.servidor/api.php") == 0, "ABIERTO: cambiar la URL sin clave");
  send(msg("\"api\":\"http://sin-cifrar\"", "")); CHECK(has("\"note\":\"bad\"") && strcmp(cfgApi, "https://mi.servidor/api.php") == 0, "http:// se rechaza tambien abierto");
  send(msg("\"lock\":1", "")); CHECK(!cfgLock, "lock:1 NO activa el bloqueo (solo se activa emparejando)");
  send(msg("\"wi\":0,\"s\":\"CasaWiFi\",\"p\":\"clave\"", "")); CHECK(strcmp(cfgWifi[0].ssid, "CasaWiFi") == 0, "ABIERTO: guardar WiFi sin clave");
  // --- 1b) ocultar el boton de lactancia
  CHECK(!cfgHideBtn, "por defecto el boton NO se oculta");
  applied = 0; send(msg("\"hidebtn\":1", "")); CHECK(cfgHideBtn && has("\"hide\":1") && applied == 1 && Preferences::db()["hidebtn"] == "1", "ABIERTO: ocultar el boton (estado hide:1, persistido, y se avisa al .ino)");
  cfgHideBtn = false; cfgLoad(); CHECK(cfgHideBtn, "tras reiniciar sigue oculto");
  send(msg("\"lact\":0,\"hidebtn\":0", "")); CHECK(!cfgHideBtn && has("\"hide\":0"), "mostrarlo otra vez");
  send(msg("\"hidebtn\":1", "")); send(msg("\"lact\":1", ""));      // se deja la lactancia habilitada para el resto de casos
  // --- 2) el movil A se empareja: se activa el bloqueo
  pairPhone(KA); CHECK(has("\"note\":\"paired\"") && has("\"lock\":1") && has("\"keys\":1") && has("\"mine\":1") && cfgLock, "movil A emparejado -> bloqueo ON, 1 clave");
  send(msg("\"lact\":0", "")); CHECK(has("\"note\":\"auth\"") && lactEnabled, "BLOQUEADO: sin clave -> auth, no cambia nada");
  send(msg("\"lact\":0", "ffffffffffffffffffffffffffffffff")); CHECK(has("auth") && lactEnabled, "BLOQUEADO: clave ajena -> auth");
  applied = 0; send(msg("\"hidebtn\":0", "")); CHECK(has("auth") && cfgHideBtn && applied == 0, "BLOQUEADO: sin clave no se puede mostrar/ocultar el boton");
  send(msg("\"hidebtn\":0", KA)); CHECK(!cfgHideBtn && applied == 1, "BLOQUEADO: con clave si");
  send(msg("\"hidebtn\":1", KA));
  send(msg("\"lact\":0", KA)); CHECK(!has("note") && !lactEnabled, "BLOQUEADO: clave de A -> ok");
  send(msg("\"get\":1", KA)); CHECK(has("\"mine\":1"), "get con la clave de A -> mine:1");
  send(msg("\"get\":1", KB)); CHECK(has("\"mine\":0"), "get con clave desconocida -> mine:0");
  // --- 3) DOS MOVILES: el movil B se empareja tambien y ambos funcionan
  pairPhone(KB); CHECK(has("\"keys\":2") && has("paired"), "movil B emparejado -> 2 claves");
  send(msg("\"lact\":1", KA)); CHECK(!has("note") && lactEnabled, "A sigue funcionando");
  send(msg("\"lact\":0", KB)); CHECK(!has("note") && !lactEnabled, "B tambien funciona");
  pairPhone(KB); CHECK(has("\"keys\":2"), "volver a emparejar el mismo movil no duplica la clave");
  // --- 4) maximo 4 y el 5.º sustituye al mas antiguo
  pairPhone(KC); pairPhone(KD); CHECK(has("\"keys\":4"), "4 moviles emparejados");
  pairPhone(KE); CHECK(has("\"keys\":4"), "el 5.º no pasa de 4 claves");
  send(msg("\"lact\":1", KA)); CHECK(has("auth"), "A (la mas antigua) ha sido sustituida");
  send(msg("\"lact\":1", KE)); CHECK(!has("note") && lactEnabled, "E funciona");
  send(msg("\"lact\":0", KB)); CHECK(!has("note"), "B, C, D siguen funcionando");
  // --- 5) persistencia
  cfgLock = false; for (int i = 0; i < 4; i++) cfgKeys[i][0] = 0; cfgLoad();
  CHECK(cfgLock && cfgKeyCount() == 4 && cfgKeyMatch(KE.c_str()) && !cfgKeyMatch(KA.c_str()), "tras reiniciar se conservan bloqueo y las 4 claves");
  // --- 6) quitar el bloqueo
  send(msg("\"lock\":0", "")); CHECK(has("auth") && cfgLock, "BLOQUEADO: no se puede quitar sin clave");
  send(msg("\"lock\":0", KC)); CHECK(!cfgLock && cfgKeyCount() == 0 && has("\"lock\":0") && has("\"keys\":0"), "con clave valida se quita el bloqueo y se olvidan las claves");
  send(msg("\"lact\":1", "")); CHECK(!has("note") && lactEnabled, "otra vez ABIERTO: sin clave vale");
  cfgLoad(); CHECK(!cfgLock && cfgKeyCount() == 0, "y asi se queda tras reiniciar");
  // --- 7) recuperacion: bloqueado y sin ningun movil valido -> con el codigo de la pantalla se vuelve a entrar
  pairPhone(KA); send(msg("\"lact\":0", KE)); CHECK(has("auth"), "bloqueado de nuevo; E ya no vale");
  pairPhone(KE); send(msg("\"lact\":0", KE)); CHECK(!has("note"), "recuperado emparejando con el codigo de la pantalla");
  // --- 8) emparejamiento: limites
  advance(9000); g_rand = 777777; send("{\"t\":\"cfg\",\"pair\":1}"); advance(3000); send("{\"t\":\"cfg\",\"pair\":1}"); CHECK(has("wait"), "peticiones de emparejamiento: minimo 8 s");
  send("{\"t\":\"cfg\",\"code\":\"000000\",\"nk\":\"" + KD + "\"}"); send("{\"t\":\"cfg\",\"code\":\"111111\",\"nk\":\"" + KD + "\"}"); send("{\"t\":\"cfg\",\"code\":\"222222\",\"nk\":\"" + KD + "\"}");
  CHECK(has("wrongcode") && hidden, "3 codigos malos anulan el codigo");
  send("{\"t\":\"cfg\",\"code\":\"777777\",\"nk\":\"" + KD + "\"}"); CHECK(has("expired") && !cfgKeyMatch(KD.c_str()), "el codigo correcto ya no vale tras 3 fallos");
  advance(9000); send("{\"t\":\"cfg\",\"pair\":1}"); CHECK(has("wait"), "60 s de espera tras 3 fallos");
  advance(61000); send("{\"t\":\"cfg\",\"pair\":1}"); CHECK(has("show"), "pasado el minuto se puede pedir otro codigo");
  advance(91000); send("{\"t\":\"cfg\",\"code\":\"" + shown + "\",\"nk\":\"" + KD + "\"}"); CHECK(has("expired"), "el codigo caduca a los 90 s");
  send("{\"t\":\"cfg\",\"code\":\"123456\",\"nk\":\"corta\"}"); CHECK(has("expired") || has("bad"), "clave de formato incorrecto no se acepta");
  // --- 9) datos hostiles
  std::string longp(70, 'a'); send(msg(("\"wi\":2,\"s\":\"R\",\"p\":\"" + longp + "\"").c_str(), KE)); CHECK(cfgWifi[2].pass[0] == 0, "clave wifi demasiado larga se ignora");
  send(msg("\"wi\":9,\"s\":\"x\"", KE)); CHECK(!has("note"), "indice de wifi fuera de rango ignorado");
  send(msg("\"poll\":5", KE)); CHECK(has("\"poll\":10"), "poll con minimo de 10 s");
  // --- 10) desbordamiento de millis() con claves en uso
  advance(0xFFFFFFF0u - g_millis); pairPhone(KD); CHECK(has("paired"), "emparejar al desbordar millis()");
  // --- 11) arranque en lactancia
  Preferences::db()["lact"] = "0"; Preferences::db()["boot"] = "1"; cfgLoad();
  CHECK(!cfgBootLact && Preferences::db()["boot"] == "0", "con lactancia DESHABILITADA la marca de arranque se descarta (no arranca en lactancia)");
  Preferences::db()["lact"] = "1"; Preferences::db()["boot"] = "1"; cfgLoad();
  CHECK(cfgBootLact && Preferences::db()["boot"] == "0", "con lactancia habilitada arranca en lactancia y la marca se consume");
  cfgLoad(); CHECK(!cfgBootLact, "y no se repite");
  printf("\n%s (%d fallos)\n", fails ? "HAY FALLOS" : "TODO OK", fails);
  return fails != 0;
}
