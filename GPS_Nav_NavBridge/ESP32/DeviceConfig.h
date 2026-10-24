#pragma once
/*
  AJUSTES GUARDADOS EN EL ESP32 (memoria NVS, sobreviven a reinicios y a reflashear el sketch)

  Los envia la app del movil por Bluetooth con mensajes {"t":"cfg",...}:
    {"t":"cfg","get":1}                      -> el ESP32 contesta con su estado (sin secretos)
    {"t":"cfg","lact":1}                     -> habilita (1) / deshabilita (0) el modo lactancia
    {"t":"cfg","api":"https://..."}          -> URL de la API   ("" = volver a la del firmware)
    {"t":"cfg","tok":"..."}                  -> token de la API ("" = volver al del firmware)
    {"t":"cfg","poll":60}                    -> segundos entre consultas (0 = el valor del firmware)
    {"t":"cfg","wi":0,"s":"red","p":"clave"} -> red WiFi n.º 0..2 (s vacio = borrar esa red)
    {"t":"cfg","reset":1}                    -> borra API, token, intervalo y redes: vuelven los valores por defecto

  Respuesta (notificacion, nunca incluye claves ni token):
    {"t":"cfg","lact":0,"api":0,"tok":0,"wifi":0,"poll":0}

  OJO: el modo lactancia se habilita desde la app y estos ajustes solo se aplican en el SIGUIENTE arranque en
  modo lactancia (en ese modo el Bluetooth esta apagado, asi que la configuracion se envia desde el modo GPS).
*/
#include <Arduino.h>
#include <Preferences.h>
#include <ArduinoJson.h>

#define CFG_MAX_WIFI   3
#define CFG_POLL_MIN_S 10
#define CFG_POLL_MAX_S 3600

struct CfgWifi { char ssid[33]; char pass[65]; };

static bool     lactEnabled = false;    // el boton de modo lactancia solo funciona si es true (por defecto false)
static bool     cfgBootLact = false;    // este arranque es en modo lactancia (BLE apagado desde el principio)
static char     cfgApi[200] = "";       // "" = usar API_BASE del firmware
static char     cfgTok[80]  = "";       // "" = usar API_TOKEN del firmware
static uint32_t cfgPollMs   = 0;        // 0  = usar POLL_MS del firmware
static CfgWifi  cfgWifi[CFG_MAX_WIFI];  // si hay alguna, sustituyen a WIFI_LIST
static Preferences cfgPrefs;

void cfgSendStatus();                   // definida en el .ino (notifica por Bluetooth)

static void cfgReadStr(const char *key, char *dst, size_t n)
{
  String v = cfgPrefs.getString(key, "");
  strlcpy(dst, v.c_str(), n);
}

// Llamar lo primero de setup(): decide si este arranque es en modo lactancia
static void cfgLoad()
{
  cfgPrefs.begin("navcfg", false);
  lactEnabled = (ENABLE_LACTANCIA) && cfgPrefs.getBool("lact", false);
  cfgReadStr("api", cfgApi, sizeof(cfgApi));
  cfgReadStr("tok", cfgTok, sizeof(cfgTok));
  cfgPollMs = cfgPrefs.getUInt("poll", 0) * 1000UL;
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

static void cfgStatusJson(char *out, size_t n)
{
  int wifi = 0;
  for (int i = 0; i < CFG_MAX_WIFI; i++) if (cfgWifi[i].ssid[0]) wifi++;
  snprintf(out, n, "{\"t\":\"cfg\",\"lact\":%d,\"api\":%d,\"tok\":%d,\"wifi\":%d,\"poll\":%u}\n",
           lactEnabled ? 1 : 0, cfgApi[0] ? 1 : 0, cfgTok[0] ? 1 : 0, wifi, (unsigned)(cfgPollMs / 1000UL));
}

// Mensaje {"t":"cfg",...} recibido del movil (se llama desde loop())
static void cfgHandleJson(JsonDocument &doc)
{
  if ((int)(doc["reset"] | 0)) {
    cfgPrefs.remove("api"); cfgPrefs.remove("tok"); cfgPrefs.remove("poll");
    cfgApi[0] = 0; cfgTok[0] = 0; cfgPollMs = 0;
    for (int i = 0; i < CFG_MAX_WIFI; i++) {
      char k[8];
      snprintf(k, sizeof(k), "ws%d", i); cfgPrefs.remove(k);
      snprintf(k, sizeof(k), "wp%d", i); cfgPrefs.remove(k);
      cfgWifi[i].ssid[0] = 0; cfgWifi[i].pass[0] = 0;
    }
  }
  if (!doc["lact"].isNull()) {
    bool v = ((int)(doc["lact"] | 0)) != 0;
    if (cfgPrefs.getBool("lact", false) != v) cfgPrefs.putBool("lact", v);
    lactEnabled = (ENABLE_LACTANCIA) && v;
  }
  if (!doc["api"].isNull()) cfgSetStr("api", cfgApi, sizeof(cfgApi), doc["api"] | "");
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
      char k[8];
      snprintf(k, sizeof(k), "ws%d", i); cfgSetStr(k, cfgWifi[i].ssid, sizeof(cfgWifi[i].ssid), doc["s"] | "");
      snprintf(k, sizeof(k), "wp%d", i); cfgSetStr(k, cfgWifi[i].pass, sizeof(cfgWifi[i].pass), doc["p"] | "");
    }
  }
  cfgSendStatus();      // confirma al movil (get tambien llega aqui)
}
