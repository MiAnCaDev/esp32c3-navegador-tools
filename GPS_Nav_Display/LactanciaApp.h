/*
  LactanciaApp.h  -  "App" de lactancia para el display (sustituye a "Proximamente")

  Copia este archivo junto a GPS_Nav_Display.ino (misma carpeta -> nueva pestana).
  - Al entrar en la app: babyEnter()  -> WiFi ON, consulta el estado
  - Al salir:            babyExit()   -> WiFi OFF
  Toda la red va en una tarea FreeRTOS aparte para no congelar LVGL.

  Adaptado al endpoint real esp32.php:
    GET  esp32.php?token=XXX&action=estado
    POST esp32.php?token=XXX   body: action=toma&tipo=Izquierdo|Derecho&accion=Inicio|Fin
    POST esp32.php?token=XXX   body: action=dormir&accion=Inicio|Fin
    POST esp32.php?token=XXX   body: action=panal&tipo=Pis|Caca

  Las respuestas de POST solo confirman ('codError'=>0 si ok), no devuelven el
  estado completo, asi que tras cada POST se hace un GET de estado para
  refrescar la pantalla con el dato real del servidor.
*/
#pragma once
#include <Arduino.h>
#include <lvgl.h>
#include <WiFi.h>
#include <WiFiClientSecure.h>
#include <HTTPClient.h>
#include <ArduinoJson.h>

// ------------------------- CONFIGURACION -----------------------------------
// Redes WiFi, en orden de preferencia: { "NOMBRE_RED", "CONTRASEÑA" }
// (solo 2,4 GHz). Para una red abierta deja la clave vacía: { "Cafeteria", "" }
struct WifiCred { const char *ssid; const char *pass; };
static const WifiCred WIFI_LIST[] = {
  { "TU_WIFI", "TU_CLAVE" },
  { "OtraRed",            "otraClave"    },
  { "MovilMiki",          "claveMovil"   },
};
#define API_BASE   "https://control-panel.legioagro.com/app_bebe/lactancia/api/esp32.php"
#define API_TOKEN  "e21a521a09cbb0cd95095c9esp32xxx"   // el mismo que valida verificarAutenticacion()
#define POLL_MS    60000                 // cada cuanto se consulta el estado en reposo
// ---------------------------------------------------------------------------

void showToast(const char *text);   // definida en el .ino

enum NetStatus : uint8_t { NET_OFF, NET_CONNECTING, NET_ONLINE, NET_ERROR };
enum CmdType : uint8_t {
  CMD_TOMA_IZQ_INI, CMD_TOMA_DER_INI, CMD_TOMA_FIN,
  CMD_DORMIR_INI,   CMD_DORMIR_FIN,
  CMD_PANAL_PIS,    CMD_PANAL_CACA
};

struct BabyState {
  bool     tomaActiva   = false;
  char     tomaTipo[12] = "";      // "Izquierdo" | "Derecho" | "Biberon"
  int32_t  tomaHace     = -1;      // segundos

  bool     suenoActiva  = false;
  int32_t  suenoHace    = -1;

  char     panialTipo[8] = "";     // "pis" | "caca"
  int32_t  panialHace    = -1;

  uint32_t rxMs  = 0;               // millis() en que llego el dato
  bool     valid = false;
};

static BabyState          baby;
static SemaphoreHandle_t  babyMutex = nullptr;
static QueueHandle_t      cmdQueue  = nullptr;
static volatile bool      netWanted = false;
static volatile NetStatus netStatus = NET_OFF;
static volatile bool      babyDirty = true;

// Objetos LVGL
static lv_obj_t *bbCont, *bbStatus;
static lv_obj_t *bbIzqBtn, *bbIzqLbl, *bbDerBtn, *bbDerLbl, *bbTomaFinBtn, *bbTomaInfo;
static lv_obj_t *bbSuenoBtn, *bbSuenoLbl, *bbSuenoInfo;
static lv_obj_t *bbPisBtn, *bbCacaBtn, *bbPanialInfo;

// ===========================================================================
// RED (tarea en segundo plano)
// ===========================================================================
static void applyState(JsonDocument &d)
{
  BabyState s;
  s.tomaActiva  = d["toma"]["activa"] | false;
  const char *tt = d["toma"]["tipo"]  | "";
  strlcpy(s.tomaTipo, tt, sizeof(s.tomaTipo));
  s.tomaHace    = d["toma"]["hace"]   | -1;

  s.suenoActiva = d["dormir"]["activa"] | false;
  s.suenoHace   = d["dormir"]["hace"]   | -1;

  const char *pt = d["panial"]["tipo"] | "";
  strlcpy(s.panialTipo, pt, sizeof(s.panialTipo));
  s.panialHace  = d["panial"]["hace"]  | -1;

  s.rxMs  = millis();
  s.valid = true;

  xSemaphoreTake(babyMutex, portMAX_DELAY);
  baby = s;
  xSemaphoreGive(babyMutex);
  babyDirty = true;
}

// GET del estado completo
static bool httpGetEstado()
{
  String url = String(API_BASE) + "?token=" + API_TOKEN + "&action=estado";

  WiFiClientSecure secure;
  secure.setInsecure();   // ver nota al final del fichero
  HTTPClient http;
  if (!http.begin(secure, url)) return false;
  http.setTimeout(6000);

  int code = http.GET();
  bool ok = false;
  if (code == 200) {
    DynamicJsonDocument doc(512);
    if (!deserializeJson(doc, http.getString())) {
      applyState(doc);
      ok = true;
    }
  } else {
    Serial.printf("[NET] GET estado HTTP %d\n", code);
  }
  http.end();
  return ok;
}

// POST de una accion. body va en el cuerpo, el token en la URL (query).
static bool httpPostAccion(const char *body)
{
  String url = String(API_BASE) + "?token=" + API_TOKEN;

  WiFiClientSecure secure;
  secure.setInsecure();
  HTTPClient http;
  if (!http.begin(secure, url)) return false;
  http.setTimeout(6000);
  http.addHeader("Content-Type", "application/x-www-form-urlencoded");

  int code = http.POST(String(body));
  bool ok = false;
  if (code == 200) {
    DynamicJsonDocument doc(512);
    if (!deserializeJson(doc, http.getString())) {
      int codError = doc["codError"] | -1;
      ok = (codError == 0);
      if (!ok) Serial.printf("[NET] codError=%d: %s\n", codError, (const char*)(doc["cadError"] | ""));
    }
  } else {
    Serial.printf("[NET] POST HTTP %d\n", code);
  }
  http.end();
  return ok;
}

static const char *cmdBody(CmdType c)
{
  switch (c) {
    case CMD_TOMA_IZQ_INI: return "action=toma&tipo=Izquierdo&accion=Inicio";
    case CMD_TOMA_DER_INI: return "action=toma&tipo=Derecho&accion=Inicio";
    case CMD_TOMA_FIN:     return "action=toma&accion=Fin";
    case CMD_DORMIR_INI:   return "action=dormir&accion=Inicio";
    case CMD_DORMIR_FIN:   return "action=dormir&accion=Fin";
    case CMD_PANAL_PIS:    return "action=panal&tipo=Pis";
    case CMD_PANAL_CACA:   return "action=panal&tipo=Caca";
  }
  return "";
}


// ------------------- REDES WIFI (lista WIFI_LIST de arriba) ----------------
#define MAX_WIFIS      6
#define WIFI_TRY_MS    8000     // tiempo maximo por red antes de pasar a la siguiente

struct WifiNet { char ssid[33]; char pass[65]; };
static WifiNet wifiNets[MAX_WIFIS];
static int     wifiCount  = 0;
static int     wifiLastOk = 0;  // ultima red que funciono: se prueba primero

static void wifiLoadConfig()   // llamar una vez, desde babyInit()
{
  wifiCount = 0;
  const int total = sizeof(WIFI_LIST) / sizeof(WIFI_LIST[0]);
  for (int i = 0; i < total && wifiCount < MAX_WIFIS; i++) {
    if (!WIFI_LIST[i].ssid || !WIFI_LIST[i].ssid[0]) continue;
    strlcpy(wifiNets[wifiCount].ssid, WIFI_LIST[i].ssid, sizeof(wifiNets[0].ssid));
    strlcpy(wifiNets[wifiCount].pass, WIFI_LIST[i].pass ? WIFI_LIST[i].pass : "",
            sizeof(wifiNets[0].pass));
    wifiCount++;
  }
  Serial.printf("[WIFI] %d red(es) configurada(s)\n", wifiCount);
  for (int i = 0; i < wifiCount; i++) Serial.printf("   %d: %s\n", i + 1, wifiNets[i].ssid);
}

// Prueba las redes en orden (empezando por la ultima que funciono).
// Comprueba netWanted cada 250 ms para poder abortar si se sale de la app.
static bool wifiConnectAny()
{
  for (int k = 0; k < wifiCount; k++) {
    int i = (wifiLastOk + k) % wifiCount;
    if (!netWanted) return false;
    Serial.printf("[WIFI] probando '%s'...\n", wifiNets[i].ssid);
    WiFi.disconnect(false, false);
    WiFi.begin(wifiNets[i].ssid, wifiNets[i].pass[0] ? wifiNets[i].pass : nullptr);

    uint32_t t0 = millis();
    while (millis() - t0 < WIFI_TRY_MS) {
      if (!netWanted) return false;
      if (WiFi.status() == WL_CONNECTED) {
        wifiLastOk = i;
        Serial.printf("[WIFI] conectado a '%s'\n", wifiNets[i].ssid);
        return true;
      }
      vTaskDelay(pdMS_TO_TICKS(250));
    }
    Serial.printf("[WIFI] '%s' fallo, siguiente...\n", wifiNets[i].ssid);
  }
  return false;
}
// ---------------------------------------------------------------------------


static void netTask(void *)
{
  bool wifiOn = false;
  uint32_t lastPoll = 0;
  bool firstPoll = true;

  for (;;) {
    if (netWanted && !wifiOn) {
      WiFi.mode(WIFI_STA);
      // (quitado: WiFi.begin(WIFI_SSID, WIFI_PASS);)
      wifiOn = true;
      firstPoll = true;
      netStatus = NET_CONNECTING;
      babyDirty = true;
    }
    if (!netWanted && wifiOn) {
      xQueueReset(cmdQueue);
      WiFi.disconnect(true, false);
      WiFi.mode(WIFI_OFF);
      wifiOn = false;
      netStatus = NET_OFF;
    }

    if (wifiOn) {
      if (WiFi.status() != WL_CONNECTED) {
        if (netStatus != NET_CONNECTING) { netStatus = NET_CONNECTING; babyDirty = true; }
        if (!wifiConnectAny()) vTaskDelay(pdMS_TO_TICKS(5000));  // si todas fallan, reintenta en 5 s
        continue;
      }

      CmdType c;
      bool ok;
      if (xQueueReceive(cmdQueue, &c, 0) == pdTRUE) {
        ok = httpPostAccion(cmdBody(c));
        if (ok) ok = httpGetEstado();   // el POST no devuelve el estado completo
        lastPoll = millis();
      } else if (firstPoll || millis() - lastPoll >= POLL_MS) {
        ok = httpGetEstado();
        firstPoll = false;
        lastPoll = millis();
      } else {
        vTaskDelay(pdMS_TO_TICKS(100));
        continue;
      }
      NetStatus ns = ok ? NET_ONLINE : NET_ERROR;
      if (ns != netStatus) { netStatus = ns; babyDirty = true; }
    } else {
      vTaskDelay(pdMS_TO_TICKS(100));
    }
  }
}

// ===========================================================================
// INTERFAZ
// ===========================================================================
// Siempre "Xh Ym", como ha pedido el usuario (sin acortar a "<1m" etc.)
static void fmtHM(int32_t s, char *o, size_t n)
{
  if (s < 0) s = 0;
  int32_t h = s / 3600;
  int32_t m = (s % 3600) / 60;
  snprintf(o, n, "%ldh %ldm", (long)h, (long)m);
}

static const char *capitalize(const char *s, char *out, size_t n)
{
  strlcpy(out, s, n);
  if (out[0]) out[0] = toupper((unsigned char)out[0]);
  return out;
}

static void refreshBabyUI()
{
  BabyState s;
  xSemaphoreTake(babyMutex, portMAX_DELAY);
  s = baby;
  xSemaphoreGive(babyMutex);

  // Los "hace" que llegaron del servidor los vamos envejeciendo localmente
  // entre consulta y consulta para que el contador no se quede parado.
  int32_t extra = s.valid ? (int32_t)((millis() - s.rxMs) / 1000) : 0;
  auto adv = [&](int32_t v) { return v < 0 ? v : v + extra; };

  char hm[16], line[56], cap[12];

  // --- Habilitar los botones solo si el WiFi esta realmente conectado.
  // Con NET_OFF/NET_CONNECTING/NET_ERROR se ven deshabilitados (atenuados)
  // y no responden al toque, para no perder pulsaciones que no llegarian
  // al servidor.
  bool netOk = (netStatus == NET_ONLINE);
  lv_obj_t *babyBtns[] = { bbIzqBtn, bbDerBtn, bbTomaFinBtn, bbSuenoBtn, bbPisBtn, bbCacaBtn };
  for (lv_obj_t *b : babyBtns) {
    if (netOk) lv_obj_clear_state(b, LV_STATE_DISABLED);
    else       lv_obj_add_state(b, LV_STATE_DISABLED);
  }

  // --- Estado de red ---
  switch (netStatus) {
    case NET_CONNECTING:
      lv_label_set_text(bbStatus, "WiFi: conectando...");
      lv_obj_set_style_text_color(bbStatus, lv_palette_main(LV_PALETTE_ORANGE), 0); break;
    case NET_ONLINE:
      lv_label_set_text(bbStatus, "WiFi: OK");
      lv_obj_set_style_text_color(bbStatus, lv_palette_main(LV_PALETTE_GREEN), 0); break;
    case NET_ERROR:
      lv_label_set_text(bbStatus, "Error de servidor");
      lv_obj_set_style_text_color(bbStatus, lv_palette_main(LV_PALETTE_RED), 0); break;
    default:
      lv_label_set_text(bbStatus, "");
  }

  // --- Toma ---
  if (s.tomaActiva) {
    lv_obj_add_flag(bbIzqBtn, LV_OBJ_FLAG_HIDDEN);
    lv_obj_add_flag(bbDerBtn, LV_OBJ_FLAG_HIDDEN);
    lv_obj_clear_flag(bbTomaFinBtn, LV_OBJ_FLAG_HIDDEN);
    snprintf(line, sizeof(line), "Toma en curso (%s)", s.tomaTipo);
  } else {
    lv_obj_clear_flag(bbIzqBtn, LV_OBJ_FLAG_HIDDEN);
    lv_obj_clear_flag(bbDerBtn, LV_OBJ_FLAG_HIDDEN);
    lv_obj_add_flag(bbTomaFinBtn, LV_OBJ_FLAG_HIDDEN);
    if (s.tomaTipo[0]) {
      fmtHM(adv(s.tomaHace), hm, sizeof(hm));
      snprintf(line, sizeof(line), "Ultima toma (%s) %s", s.tomaTipo, hm);
    } else {
      strlcpy(line, "Sin tomas registradas", sizeof(line));
    }
  }
  lv_label_set_text(bbTomaInfo, line);

  // --- Dormir ---
  lv_label_set_text(bbSuenoLbl, s.suenoActiva ? "Fin dormir" : "Dormir");
  lv_obj_set_style_bg_color(bbSuenoBtn,
      lv_palette_main(s.suenoActiva ? LV_PALETTE_RED : LV_PALETTE_BLUE), 0);

  fmtHM(adv(s.suenoHace), hm, sizeof(hm));
  if (s.suenoActiva) snprintf(line, sizeof(line), "Dormido durante %s", hm);
  else               snprintf(line, sizeof(line), "Desperto hace %s", hm);
  lv_label_set_text(bbSuenoInfo, line);

  // --- Panal ---
  if (s.panialTipo[0]) {
    fmtHM(adv(s.panialHace), hm, sizeof(hm));
    capitalize(s.panialTipo, cap, sizeof(cap));
    snprintf(line, sizeof(line), "Panal hace %s (%s)", hm, cap);
  } else {
    strlcpy(line, "Sin panales registrados", sizeof(line));
  }
  lv_label_set_text(bbPanialInfo, line);
}

// Actualiza el estado local al instante (optimista); el GET tras el POST
// confirma o corrige el dato real que ha guardado el servidor.
static void queueCmd(CmdType c)
{
  if (netStatus != NET_ONLINE) { showToast("Sin conexion"); return; }

  xSemaphoreTake(babyMutex, portMAX_DELAY);
  baby.rxMs = millis();
  baby.valid = true;
  switch (c) {
    case CMD_TOMA_IZQ_INI: baby.tomaActiva = true;  strlcpy(baby.tomaTipo, "Izquierdo", sizeof(baby.tomaTipo)); baby.tomaHace = 0; break;
    case CMD_TOMA_DER_INI: baby.tomaActiva = true;  strlcpy(baby.tomaTipo, "Derecho", sizeof(baby.tomaTipo));   baby.tomaHace = 0; break;
    case CMD_TOMA_FIN:     baby.tomaActiva = false; baby.tomaHace = 0; break;
    case CMD_DORMIR_INI:   baby.suenoActiva = true;  baby.suenoHace = 0; break;
    case CMD_DORMIR_FIN:   baby.suenoActiva = false; baby.suenoHace = 0; break;
    case CMD_PANAL_PIS:    strlcpy(baby.panialTipo, "pis",  sizeof(baby.panialTipo)); baby.panialHace = 0; break;
    case CMD_PANAL_CACA:   strlcpy(baby.panialTipo, "caca", sizeof(baby.panialTipo)); baby.panialHace = 0; break;
  }
  xSemaphoreGive(babyMutex);

  xQueueSend(cmdQueue, &c, 0);
  babyDirty = true;
}

// ---------------------------------------------------------------------------
// Confirmacion antes de ejecutar cualquier accion (evita pulsaciones
// accidentales). Se muestra un cuadro modal con "Si"/"No"; solo si se
// confirma se llama a queueCmd() de verdad.
// ---------------------------------------------------------------------------
static lv_obj_t *bbConfirmBox = nullptr;
static CmdType   bbPendingCmd;

// Se ejecuta SIEMPRE que el cuadro se destruye (Si, No o la X)
static void confirmDeleteCb(lv_event_t *e)
{
  if (lv_event_get_target(e) == bbConfirmBox) bbConfirmBox = nullptr;
}

static void confirmEventCb(lv_event_t *e)
{
  lv_obj_t *mbox = lv_event_get_current_target(e);
  const char *txt = lv_msgbox_get_active_btn_text(mbox);
  if (txt && strcmp(txt, "Si") == 0) {
    queueCmd(bbPendingCmd);
  }
  lv_msgbox_close(mbox);   // el puntero se limpia en confirmDeleteCb
}

static void showConfirm(CmdType c, const char *msg)
{
  if (bbConfirmBox) return;
  bbPendingCmd = c;
  static const char *btns[] = {"Si", "No", ""};
  bbConfirmBox = lv_msgbox_create(NULL, "Confirmar", msg, btns, true);
  lv_obj_set_width(bbConfirmBox, 220);
  lv_obj_center(bbConfirmBox);
  lv_obj_add_event_cb(bbConfirmBox, confirmEventCb,  LV_EVENT_VALUE_CHANGED, nullptr);
  lv_obj_add_event_cb(bbConfirmBox, confirmDeleteCb, LV_EVENT_DELETE,        nullptr);
}

static void onIzqClick(lv_event_t *e)
{
  if (lv_event_get_code(e) != LV_EVENT_CLICKED) return;
  showConfirm(CMD_TOMA_IZQ_INI, "Iniciar toma del\npecho izquierdo?");
}
static void onDerClick(lv_event_t *e)
{
  if (lv_event_get_code(e) != LV_EVENT_CLICKED) return;
  showConfirm(CMD_TOMA_DER_INI, "Iniciar toma del\npecho derecho?");
}
static void onTomaFinClick(lv_event_t *e)
{
  if (lv_event_get_code(e) != LV_EVENT_CLICKED) return;
  char tipo[12], msg[48];
  xSemaphoreTake(babyMutex, portMAX_DELAY);
  strlcpy(tipo, baby.tomaTipo, sizeof(tipo));
  xSemaphoreGive(babyMutex);
  snprintf(msg, sizeof(msg), "Finalizar toma\n(%s)?", tipo[0] ? tipo : "en curso");
  showConfirm(CMD_TOMA_FIN, msg);
}
static void onSuenoClick(lv_event_t *e)
{
  if (lv_event_get_code(e) != LV_EVENT_CLICKED) return;
  xSemaphoreTake(babyMutex, portMAX_DELAY);
  bool act = baby.suenoActiva;
  xSemaphoreGive(babyMutex);
  showConfirm(act ? CMD_DORMIR_FIN : CMD_DORMIR_INI,
              act ? "Marcar que se\nha despertado?" : "Marcar que se\nha dormido?");
}
static void onPisClick(lv_event_t *e)
{
  if (lv_event_get_code(e) != LV_EVENT_CLICKED) return;
  showConfirm(CMD_PANAL_PIS, "Registrar cambio\nde panal (Pis)?");
}
static void onCacaClick(lv_event_t *e)
{
  if (lv_event_get_code(e) != LV_EVENT_CLICKED) return;
  showConfirm(CMD_PANAL_CACA, "Registrar cambio\nde panal (Caca)?");
}

static lv_obj_t *makeBabyBtn(lv_obj_t *parent, int x, int y, int w, int h,
                             lv_color_t color, const char *text,
                             lv_event_cb_t cb, lv_obj_t **outLabel)
{
  lv_obj_t *b = lv_btn_create(parent);
  lv_obj_set_pos(b, x, y);
  lv_obj_set_size(b, w, h);
  lv_obj_set_style_bg_color(b, color, 0);
  lv_obj_set_style_radius(b, 8, 0);
  // Aspecto claramente atenuado cuando el boton esta deshabilitado (sin WiFi).
  lv_obj_set_style_bg_color(b, lv_palette_main(LV_PALETTE_GREY), LV_STATE_DISABLED);
  lv_obj_set_style_bg_opa(b, LV_OPA_50, LV_STATE_DISABLED);
  lv_obj_set_style_text_opa(b, LV_OPA_60, LV_STATE_DISABLED);
  lv_obj_add_event_cb(b, cb, LV_EVENT_CLICKED, nullptr);
  lv_obj_t *l = lv_label_create(b);
  lv_label_set_text(l, text);
  lv_obj_set_style_text_font(l, &lv_font_montserrat_14, 0);
  lv_obj_set_style_text_color(l, lv_color_white(), 0);
  lv_obj_center(l);
  if (outLabel) *outLabel = l;
  return b;
}

static lv_obj_t *makeInfo(lv_obj_t *parent, int y)
{
  lv_obj_t *l = lv_label_create(parent);
  lv_obj_set_style_text_font(l, &lv_font_montserrat_14, 0);
  lv_obj_set_style_text_color(l, lv_palette_main(LV_PALETTE_GREY), 0);
  lv_obj_set_pos(l, 2, y);
  lv_label_set_text(l, "");
  return l;
}

// Llamar desde buildUI() en lugar de crear el label "Proximamente".
// Layout pensado para el area libre a la derecha de los botones fijos
// (columna 0-32 reservada para appBtn/brightBtn), en 246 x 240 px.
void buildBabyUI(lv_obj_t *scr)
{
  // Mas separado del icono de app (arriba izq.) y del de brillo (abajo izq.),
  // que ocupan aprox. x:2-26. Dejamos el contenido a partir de x=44 y con un
  // margen vertical de 6px arriba/abajo para que tampoco roce esos iconos.
  bbCont = lv_obj_create(scr);
  lv_obj_remove_style_all(bbCont);
  lv_obj_set_pos(bbCont, 44, 6);
  lv_obj_set_size(bbCont, 232, 228);
  lv_obj_clear_flag(bbCont, LV_OBJ_FLAG_SCROLLABLE);

  bbStatus = lv_label_create(bbCont);
  lv_obj_set_style_text_font(bbStatus, &lv_font_montserrat_14, 0);
  lv_obj_set_pos(bbStatus, 0, 0);
  lv_label_set_text(bbStatus, "");

  // Botones mas pequenos (34px de alto en vez de 40) para que quepan con
  // holgura en el ancho reducido.
  const int BW  = 232;
  const int BH  = 34;
  const int GAP = 6;
  const int HW  = (BW - GAP) / 2;  // ancho de cada botn en fila de dos

  // Fila 1: toma. En reposo se ven los dos botones de pecho lado a lado;
  // en cuanto hay una toma en curso se ocultan y aparece solo "Fin toma"
  // a ancho completo (ver refreshBabyUI).
  bbIzqBtn = makeBabyBtn(bbCont, 0,        16, HW, BH, lv_palette_main(LV_PALETTE_PINK),
                         "Pecho Izq.", onIzqClick, &bbIzqLbl);
  bbDerBtn = makeBabyBtn(bbCont, HW + GAP, 16, HW, BH, lv_palette_main(LV_PALETTE_PINK),
                         "Pecho Der.", onDerClick, &bbDerLbl);
  bbTomaFinBtn = makeBabyBtn(bbCont, 0, 16, BW, BH, lv_palette_main(LV_PALETTE_RED),
                             "Fin toma", onTomaFinClick, nullptr);
  lv_obj_add_flag(bbTomaFinBtn, LV_OBJ_FLAG_HIDDEN);   // oculto hasta que haya una toma activa
  bbTomaInfo = makeInfo(bbCont, 16 + BH + 4);

  // Fila 2: dormir (ancho completo) + info
  int y2 = 16 + BH + 4 + 18 + 10;
  bbSuenoBtn  = makeBabyBtn(bbCont, 0, y2, BW, BH, lv_palette_main(LV_PALETTE_BLUE),
                            "Dormir", onSuenoClick, &bbSuenoLbl);
  bbSuenoInfo = makeInfo(bbCont, y2 + BH + 4);

  // Fila 3: panal (dos botones lado a lado) + info
  int y3 = y2 + BH + 4 + 18 + 10;
  bbPisBtn  = makeBabyBtn(bbCont, 0,        y3, HW, BH, lv_palette_main(LV_PALETTE_AMBER),
                          "Pis", onPisClick, nullptr);
  bbCacaBtn = makeBabyBtn(bbCont, HW + GAP, y3, HW, BH, lv_palette_main(LV_PALETTE_BROWN),
                          "Caca", onCacaClick, nullptr);
  bbPanialInfo = makeInfo(bbCont, y3 + BH + 4);

  lv_obj_add_flag(bbCont, LV_OBJ_FLAG_HIDDEN);
}

// ===========================================================================
// API PUBLICA (llamar desde el .ino)
// ===========================================================================
void babyInit()   // en setup(), antes de buildUI()
{
  wifiLoadConfig();
  babyMutex = xSemaphoreCreateMutex();
  cmdQueue  = xQueueCreate(8, sizeof(CmdType));
  xTaskCreate(netTask, "net", 10240, nullptr, 1, nullptr);   // TLS necesita pila grande
}

void babyEnter()  // al entrar en la app: WiFi ON
{
  netStatus = NET_CONNECTING;
  netWanted = true;
  babyDirty = true;
  lv_obj_clear_flag(bbCont, LV_OBJ_FLAG_HIDDEN);
  refreshBabyUI();
}

void babyExit()   // al salir de la app: WiFi OFF
{
  lv_obj_add_flag(bbCont, LV_OBJ_FLAG_HIDDEN);
  netWanted = false;
  // espera acotada a que la tarea apague el WiFi antes de levantar BLE
  uint32_t t0 = millis();
  while (netStatus != NET_OFF && millis() - t0 < 1500) delay(20);
}

void babyLoop()   // en loop(), solo cuando appMode == true
{
  static uint32_t last = 0;
  if (babyDirty || millis() - last >= 1000) {
    babyDirty = false;
    last = millis();
    refreshBabyUI();
  }
}

/*
  NOTA sobre HTTPS: setInsecure() cifra pero no comprueba el certificado del
  servidor. Es razonable para un dispositivo casero protegido por token; si
  quieres validar el certificado, usa secure.setCACert(...) con el certificado
  raiz de control-panel.legioagro.com.

  NOTA sobre "dormir": el endpoint ignora el campo 'accion' y simplemente
  alterna el estado en el servidor (si habia un sueno abierto lo cierra, si no
  lo abre). Aqui se envia 'accion' igualmente por claridad y por si el server
  cambia en el futuro, pero quien decide el resultado final es el GET de
  estado que se hace justo despues.
*/
