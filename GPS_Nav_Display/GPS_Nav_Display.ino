/*
  ============================================================================
  GPS Nav Display para ESP32-C3 1.69" ST7789 (LVGL)
  ============================================================================
  Recibe indicaciones de navegación del móvil a través de Gadgetbridge
  (Android, código abierto, sin nube) haciéndose pasar por un reloj
  "Bangle.js", que es uno de los tipos de dispositivo que Gadgetbridge sabe
  hablar de forma nativa por Bluetooth LE (Nordic UART Service).

  Todo el tráfico es Bluetooth LE local: el teléfono nunca sube tus datos
  a ningún servidor para esto.

  CONFIGURACIÓN EN EL MÓVIL (Gadgetbridge):
  1) Instala Gadgetbridge (F-Droid o Play Store) y dale permiso de acceso
     a notificaciones y (si quieres velocidad) permiso de ubicación.
  2) Enciende este ESP32, ábrele el Bluetooth al móvil.
  3) En Gadgetbridge: engranaje (Ajustes) > activa "Discover unsupported
     devices" (Descubrir dispositivos no compatibles).
  4) Vuelve a la pantalla principal, botón "+" para buscar dispositivos,
     espera a que aparezca este ESP32 (nombre "GPS-Nav-ESP32").
  5) Mantén pulsado sobre él > "Añadir dispositivo de prueba" (o similar
     según versión) > elige "Bangle.js" como tipo de dispositivo > Aceptar.
  6) Entra en los ajustes de ese dispositivo dentro de Gadgetbridge y activa
     "Enviar notificaciones de navegación" / "Reenviar GPS del teléfono"
     (el nombre exacto varía algo entre versiones de Gadgetbridge).
  7) En Notificaciones, permite que Google Maps / Waze / OsmAnd puedan
     enviar notificaciones (y que Gadgetbridge las reenvíe).
  8) Inicia una ruta en tu app de mapas favorita. Las indicaciones deberían
     empezar a llegar al ESP32.

  LIMITACIONES A TENER EN CUENTA:
  - Gadgetbridge solo entrega la maniobra genérica (girar izquierda/derecha,
    rotonda, etc.), la distancia restante y la calle. No entrega el número
    de carril ni la salida exacta de la rotonda.
  - La "velocidad" que se muestra es la velocidad actual del GPS del móvil,
    NO el límite de velocidad de la vía (Gadgetbridge no proporciona eso).
  - El retroiluminado de esta placa concreta va cableado directo a 3.3V:
    no hay un pin de control de brillo por hardware. El "brillo" de este
    programa se simula oscureciendo la imagen con una capa semitransparente
    encima (funciona bien visualmente, pero no ahorra batería como el PWM
    real lo haría).
  - El sensor de batería es OPCIONAL y está pensado para si en el futuro
    añades una LiPo con un divisor de tensión. Por defecto está desactivado
    (ENABLE_BATTERY 0) porque tu placa actual no lleva batería.
  ============================================================================
*/

#define LGFX_USE_V1
#include <Arduino.h>
#include <lvgl.h>
#include <LovyanGFX.hpp>
#include <ArduinoJson.h>
#include <math.h>

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <BLESecurity.h>

#include "CST816D.h"

// ---------------------------------------------------------------------------
// 0) TIPOS PROPIOS
// ---------------------------------------------------------------------------
// Declarado aqui, al principio del todo, a proposito: el IDE de Arduino
// genera automaticamente los prototipos de todas las funciones y los mete
// justo despues de los #include, ANTES del resto del codigo. Si este enum
// se definiera mas abajo (por ejemplo junto a classifyManeuver()), el
// prototipo autogenerado de esa funcion lo usaria antes de que existiera,
// dando el error "'ManeuverType' does not name a type".
enum ManeuverType {
  MNV_STRAIGHT,
  MNV_SLIGHT_RIGHT, MNV_RIGHT, MNV_SHARP_RIGHT,
  MNV_SLIGHT_LEFT,  MNV_LEFT,  MNV_SHARP_LEFT,
  MNV_UTURN_RIGHT,  MNV_UTURN_LEFT,
  MNV_ROUNDABOUT_RIGHT, MNV_ROUNDABOUT_LEFT, MNV_ROUNDABOUT_STRAIGHT
};

// Tipos/constantes de los iconos de flecha (aqui por el mismo motivo de arriba).
#define ICON_STROKE   12   // grosor del trazo
#define ICON_HEAD_LEN 26   // largo de la punta
#define ICON_HEAD_HALF 19  // media anchura de la base de la punta
#define ICON_MAXPTS   48
#define SUN_ICON_SIZE 24   // icono de sol del boton de brillo

struct IconPath {
  float x[ICON_MAXPTS];
  float y[ICON_MAXPTS];
  int   n;
  float headBaseX, headBaseY, headAngle; // base de la punta y rumbo (0=arriba, + = derecha)
};

// ---------------------------------------------------------------------------
// 1) PINES (idénticos a pin_config.h del ejemplo "Display-Touch version")
// ---------------------------------------------------------------------------
#define PIN_LCD_RS   2   // DC
#define PIN_LCD_CS   4
#define PIN_LCD_SCK  5
#define PIN_LCD_SDA  6   // MOSI
#define PIN_LCD_RES  8   // RST

#define I2C_SCL      7
#define I2C_SDA      11
#define TP_RST       10
#define TP_INT       9

#define BUZZER_PIN   1   // altavoz pasivo ya montado en la placa

// Pin ADC OPCIONAL para leer el voltaje de una futura batería LiPo a través
// de un divisor resistivo (por ejemplo 100k + 100k). GPIO0 y GPIO3 están
// libres en esta placa (no se usan en pin_config.h ni son pines de arranque
// en el ESP32-C3), así que son buena opción si añades una batería.
#define ENABLE_BATTERY 0
#define BATTERY_ADC_PIN 0

// Pon esto a 0 para desactivar todo el bloque Bluetooth y comprobar si el
// reinicio en bucle ("Interrupt wdt timeout") desaparece. Es el primer paso
// de diagnostico si el dispositivo se reinicia solo.
#define ENABLE_BLE 1

// ---------------------------------------------------------------------------
// 2) PANTALLA (LovyanGFX) - copiado tal cual del proyecto original
// ---------------------------------------------------------------------------
class LGFX : public lgfx::LGFX_Device
{
  lgfx::Panel_ST7789 _panel_instance;
  lgfx::Bus_SPI _bus_instance;

public:
  LGFX(void)
  {
    {
      auto cfg = _bus_instance.config();
      cfg.spi_host = SPI2_HOST;
      cfg.spi_mode = 2;
      cfg.freq_write = 20000000; // bajado de 40MHz a 20MHz: mas margen al combinar con BLE
      cfg.freq_read  = 20000000;
      cfg.spi_3wire  = true;
      cfg.use_lock   = true;
      cfg.dma_channel = SPI_DMA_CH_AUTO;
      cfg.pin_sclk = PIN_LCD_SCK;
      cfg.pin_mosi = PIN_LCD_SDA;
      cfg.pin_miso = -1;
      cfg.pin_dc   = PIN_LCD_RS;
      _bus_instance.config(cfg);
      _panel_instance.setBus(&_bus_instance);
    }
    {
      auto cfg = _panel_instance.config();
      cfg.pin_cs   = PIN_LCD_CS;
      cfg.pin_rst  = PIN_LCD_RES;
      cfg.pin_busy = -1;
      cfg.memory_width  = 240;
      cfg.memory_height = 280;
      cfg.panel_width   = 240;
      cfg.panel_height  = 280;
      cfg.offset_x = 0;
      cfg.offset_y = 20;
      cfg.offset_rotation = 1;
      cfg.dummy_read_pixel = 8;
      cfg.dummy_read_bits  = 1;
      cfg.readable  = true;
      cfg.invert    = true;
      cfg.rgb_order = false;
      cfg.dlen_16bit = false;
      cfg.bus_shared = false;
      _panel_instance.config(cfg);
    }
    setPanel(&_panel_instance);
  }
};

LGFX tft;
CST816D touch(I2C_SDA, I2C_SCL, TP_RST, TP_INT);

static const uint32_t screenWidth  = 280;
static const uint32_t screenHeight = 240;
// #define BUF_ROWS 120
#define BUF_ROWS 80 // Reducido ligeramente para liberar memoria RAM/SRAM al ESP32-C3
static lv_disp_draw_buf_t draw_buf;
static lv_color_t lvgl_buf[screenWidth * BUF_ROWS]; // FIX: antes tenía "240" fijo,
                                                     // pero screenWidth es 280 (pantalla
                                                     // rotada). Eso hacía que LVGL
                                                     // escribiera 3200 píxeles más allá
                                                     // del array en cada refresco completo
                                                     // -> corrupción de memoria -> los
                                                     // "Guru Meditation Error" y reinicios.

// ---------------------------------------------------------------------------
// 3) UUIDs del servicio "Nordic UART" que Gadgetbridge usa para Bangle.js
// ---------------------------------------------------------------------------
#define NUS_SERVICE_UUID   "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
#define NUS_CHAR_RX_UUID   "6E400002-B5A3-F393-E0A9-E50E24DCCA9E" // Gadgetbridge -> ESP32
#define NUS_CHAR_TX_UUID   "6E400003-B5A3-F393-E0A9-E50E24DCCA9E" // ESP32 -> Gadgetbridge (no usado, pero forma parte del estándar)

BLEServer *bleServer = nullptr;
BLECharacteristic *charRX = nullptr;
BLECharacteristic *charTX = nullptr;
volatile bool bleClientConnected = false;
String rxBuffer = ""; // acumula bytes hasta encontrar '\n'
SemaphoreHandle_t rxMutex;

// ---------------------------------------------------------------------------
// 4) ESTADO DE NAVEGACION
// ---------------------------------------------------------------------------
String  navInstr        = "";     // texto de la maniobra actual (calle, etc.)
String  navAction       = "";     // "turn-left", "roundabout-right", ...
int     navDistanceM    = -1;     // metros restantes, -1 = sin dato
bool    navActive       = false;

String  lastInstrKey     = "";
int     firstSeenDistance = -1;
bool    alert1kmFired    = false;
bool    alert100mFired   = false;

float   speedKmh   = -1;
unsigned long lastGpsMillis = 0;

int     batteryPct = -1; // -1 = sin batería / sin lectura válida

int     brightnessPct = 100; // 100=brillo normal, se simula oscureciendo

// ---------------------------------------------------------------------------
// 5) OBJETOS LVGL DE LA INTERFAZ
// ---------------------------------------------------------------------------
lv_obj_t *arrowCanvas   = nullptr;
lv_color_t *arrowCanvasBuf = nullptr;
#define ARROW_CANVAS_SIZE 132

lv_obj_t *distanceLabel = nullptr;
lv_obj_t *instrLabel    = nullptr;
lv_obj_t *speedLabel    = nullptr;
lv_obj_t *batteryLabel  = nullptr;
lv_obj_t *dimOverlay    = nullptr;
lv_obj_t *brightBtn     = nullptr;
lv_obj_t *toastLabel    = nullptr;
lv_timer_t *toastTimer  = nullptr;

// ===========================================================================
// FLUJO DE PANTALLA / LVGL
// ===========================================================================
void my_disp_flush(lv_disp_drv_t *disp, const lv_area_t *area, lv_color_t *color_p)
{
  if (tft.getStartCount() == 0) tft.endWrite();
  tft.pushImageDMA(area->x1, area->y1, area->x2 - area->x1 + 1, area->y2 - area->y1 + 1,
                    (lgfx::swap565_t *)&color_p->full);
  lv_disp_flush_ready(disp);
}

void my_touchpad_read(lv_indev_drv_t *indev_driver, lv_indev_data_t *data)
{
  uint16_t touchX, touchY;
  uint8_t gesture;
  bool touched = touch.getTouch(&touchX, &touchY, &gesture);
  if (touched) {
    // El panel tactil es nativamente vertical (240x280) pero aqui se usa
    // girado 90 grados para mostrarlo en horizontal (280x240). El chip
    // tactil no conoce ese giro, asi que hay que transformar sus
    // coordenadas nativas al sistema de coordenadas de LVGL:
    //   pantalla_x = tactil_y
    //   pantalla_y = 239 - tactil_x
    // (formula calibrada tocando las esquinas reales de la pantalla)
    int dispX = (int)touchY;
    int dispY = 239 - (int)touchX;
    if (dispX < 0) dispX = 0;
    if (dispX > 279) dispX = 279;
    if (dispY < 0) dispY = 0;
    if (dispY > 239) dispY = 239;

    data->state = LV_INDEV_STATE_PR;
    data->point.x = dispX;
    data->point.y = dispY;
  } else {
    data->state = LV_INDEV_STATE_REL;
  }
}

// ===========================================================================
// DIBUJO DE LA FLECHA (rotable) EN UN CANVAS
// ===========================================================================
lv_point_t rotatePt(float x, float y, float angleDeg, float cx, float cy)
{
  float rad = angleDeg * (float)M_PI / 180.0f;
  float rx = x * cosf(rad) - y * sinf(rad);
  float ry = x * sinf(rad) + y * cosf(rad);
  lv_point_t p;
  p.x = (lv_coord_t)lroundf(cx + rx);
  p.y = (lv_coord_t)lroundf(cy + ry);
  return p;
}

// ---------------------------------------------------------------------------
// ICONOS MINIMALISTAS
// Todas las flechas se construyen igual: un trazo grueso de puntas redondas
// (recta + curva suave) terminado en una punta triangular. Se dibujan en
// coordenadas locales (sin girar = hacia arriba), se centran solas en el
// canvas y para giros a la izquierda se espeja en X.
// ---------------------------------------------------------------------------
// (constantes e IconPath declarados al principio del archivo, seccion 0)

static void pathAdd(IconPath &p, float x, float y)
{
  if (p.n < ICON_MAXPTS) { p.x[p.n] = x; p.y[p.n] = y; p.n++; }
}

// Trazo: recta vertical, arco de "sweepDeg" grados (radio r) hacia la derecha
// y un tramo recto final en la nueva direccion. sweepDeg = 0 -> solo recta.
static void buildTurnPath(IconPath &p, float straightLen, float sweepDeg, float r, float extLen)
{
  p.n = 0;
  float y0 = 0.0f;
  pathAdd(p, 0, y0 + straightLen);      // arranque (abajo)
  float yEnd = y0;                      // fin del tramo recto inicial
  pathAdd(p, 0, yEnd);

  float x = 0, y = yEnd, heading = 0;
  if (sweepDeg > 0.5f) {
    // Centro del arco a la derecha del final del tramo recto.
    float ccx = r, ccy = yEnd;
    int steps = (int)(sweepDeg / 8.0f) + 2;
    for (int i = 1; i <= steps; i++) {
      float phi = (180.0f + sweepDeg * i / steps) * (float)M_PI / 180.0f;
      x = ccx + r * cosf(phi);
      y = ccy + r * sinf(phi);
      pathAdd(p, x, y);
    }
    heading = sweepDeg;
  }
  float hr = heading * (float)M_PI / 180.0f;
  x += extLen * sinf(hr);
  y -= extLen * cosf(hr);
  pathAdd(p, x, y);

  p.headBaseX = x; p.headBaseY = y; p.headAngle = heading;
}

static void drawIconPath(const IconPath &p, bool mirror, lv_color_t color)
{
  // Puntos de la punta triangular
  float hr = p.headAngle * (float)M_PI / 180.0f;
  float dx = sinf(hr), dy = -cosf(hr);      // direccion de avance
  float px = cosf(hr), py = sinf(hr);       // perpendicular
  float tipX = p.headBaseX + dx * ICON_HEAD_LEN, tipY = p.headBaseY + dy * ICON_HEAD_LEN;
  float lX = p.headBaseX - px * ICON_HEAD_HALF,  lY = p.headBaseY - py * ICON_HEAD_HALF;
  float rX = p.headBaseX + px * ICON_HEAD_HALF,  rY = p.headBaseY + py * ICON_HEAD_HALF;

  // Caja que ocupa todo (para centrarlo en el canvas)
  float minX = tipX, maxX = tipX, minY = tipY, maxY = tipY;
  auto grow = [&](float x, float y) {
    if (x < minX) minX = x; if (x > maxX) maxX = x;
    if (y < minY) minY = y; if (y > maxY) maxY = y;
  };
  grow(lX, lY); grow(rX, rY);
  for (int i = 0; i < p.n; i++) grow(p.x[i], p.y[i]);
  float pad = ICON_STROKE / 2.0f;
  minX -= pad; maxX += pad; minY -= pad; maxY += pad;

  float offX = ARROW_CANVAS_SIZE / 2.0f - (minX + maxX) / 2.0f;
  float offY = ARROW_CANVAS_SIZE / 2.0f - (minY + maxY) / 2.0f;
  float c = ARROW_CANVAS_SIZE / 2.0f;

  auto tx = [&](float x) { float v = x + offX; if (mirror) v = 2 * c - v; return (lv_coord_t)lroundf(v); };
  auto ty = [&](float y) { return (lv_coord_t)lroundf(y + offY); };

  lv_point_t pts[ICON_MAXPTS];
  for (int i = 0; i < p.n; i++) { pts[i].x = tx(p.x[i]); pts[i].y = ty(p.y[i]); }

  lv_draw_line_dsc_t ldsc;
  lv_draw_line_dsc_init(&ldsc);
  ldsc.color = color;
  ldsc.width = ICON_STROKE;
  ldsc.round_start = true;
  ldsc.round_end   = true;
  lv_canvas_draw_line(arrowCanvas, pts, p.n, &ldsc);

  lv_point_t head[3] = {
    { tx(tipX), ty(tipY) }, { tx(lX), ty(lY) }, { tx(rX), ty(rY) }
  };
  lv_draw_rect_dsc_t rdsc;
  lv_draw_rect_dsc_init(&rdsc);
  rdsc.bg_color = color;
  rdsc.bg_opa   = LV_OPA_COVER;
  lv_canvas_draw_polygon(arrowCanvas, head, 3, &rdsc);
}

// Flecha para recto / giros / cambio de sentido.
static void drawManeuverIcon(float straightLen, float sweepDeg, float r, float extLen,
                             bool toRight, lv_color_t color)
{
  IconPath p;
  buildTurnPath(p, straightLen, sweepDeg, r, extLen);
  drawIconPath(p, !toRight, color);
}

// Rotonda: anillo fino + entrada desde abajo + flecha de salida.
// exitAngle: rumbo de salida (0 = recto, + derecha, - izquierda).
static void drawRoundaboutIcon(float exitAngle, lv_color_t color)
{
  const float c = ARROW_CANVAS_SIZE / 2.0f;
  const float ringR = 24.0f;
  const float ringCx = c, ringCy = c - 14.0f;

  lv_draw_line_dsc_t ring;
  lv_draw_line_dsc_init(&ring);
  ring.color = color;
  ring.width = 5;
  ring.round_start = true;
  ring.round_end   = true;
  lv_point_t rp[33];
  for (int i = 0; i <= 32; i++) {
    float a = (float)i * 2.0f * (float)M_PI / 32.0f;
    rp[i].x = (lv_coord_t)lroundf(ringCx + ringR * cosf(a));
    rp[i].y = (lv_coord_t)lroundf(ringCy + ringR * sinf(a));
  }
  lv_canvas_draw_line(arrowCanvas, rp, 33, &ring);

  // Entrada desde abajo
  lv_draw_line_dsc_t st;
  lv_draw_line_dsc_init(&st);
  st.color = color;
  st.width = ICON_STROKE - 3;
  st.round_start = true;
  st.round_end   = true;
  lv_point_t entry[2] = {
    { (lv_coord_t)lroundf(ringCx), (lv_coord_t)(ARROW_CANVAS_SIZE - 10) },
    { (lv_coord_t)lroundf(ringCx), (lv_coord_t)lroundf(ringCy + ringR) }
  };
  lv_canvas_draw_line(arrowCanvas, entry, 2, &st);

  // Salida: sale del anillo en la direccion "exitAngle"
  float er = exitAngle * (float)M_PI / 180.0f;
  float dx = sinf(er), dy = -cosf(er);
  float sx = ringCx + dx * ringR, sy = ringCy + dy * ringR;   // punto en el anillo
  float bx = sx + dx * 12.0f,     by = sy + dy * 12.0f;       // base de la punta
  lv_point_t ex[2] = { { (lv_coord_t)lroundf(sx), (lv_coord_t)lroundf(sy) },
                       { (lv_coord_t)lroundf(bx), (lv_coord_t)lroundf(by) } };
  lv_canvas_draw_line(arrowCanvas, ex, 2, &st);

  const float hl = 20.0f, hh = 14.0f;
  float px = cosf(er), py = sinf(er);
  lv_point_t head[3] = {
    { (lv_coord_t)lroundf(bx + dx * hl), (lv_coord_t)lroundf(by + dy * hl) },
    { (lv_coord_t)lroundf(bx - px * hh), (lv_coord_t)lroundf(by - py * hh) },
    { (lv_coord_t)lroundf(bx + px * hh), (lv_coord_t)lroundf(by + py * hh) }
  };
  lv_draw_rect_dsc_t rdsc;
  lv_draw_rect_dsc_init(&rdsc);
  rdsc.bg_color = color;
  rdsc.bg_opa   = LV_OPA_COVER;
  lv_canvas_draw_polygon(arrowCanvas, head, 3, &rdsc);
}

// ---------------------------------------------------------------------------
// Clasificacion de la maniobra a partir del string de Gadgetbridge.
//
// IMPORTANTE: Gadgetbridge NO manda strings largos tipo "turn-left".
// En la práctica manda palabras sueltas como "left" o "right", o con guion
// bajo como "roundabout_right" (confirmado por logs reales). Por eso aquí
// buscamos palabras clave dentro del string en vez de comparar con "=="
// contra una lista cerrada: así funciona con "left", "turn-left",
// "turn_left", "roundabout_right", "LEFT", etc.
// (enum ManeuverType declarado al principio del archivo, ver seccion 0)
// ---------------------------------------------------------------------------
ManeuverType classifyManeuver(const String &actionIn)
{
  String a = actionIn;
  a.toLowerCase();

  bool right = a.indexOf("right") >= 0;
  bool left  = a.indexOf("left")  >= 0;

  if (a.indexOf("roundabout") >= 0 || a.indexOf("rotary") >= 0) {
    if (right) return MNV_ROUNDABOUT_RIGHT;
    if (left)  return MNV_ROUNDABOUT_LEFT;
    return MNV_ROUNDABOUT_STRAIGHT;
  }

  if (a.indexOf("uturn") >= 0 || a.indexOf("u-turn") >= 0 || a.indexOf("u_turn") >= 0) {
    return left ? MNV_UTURN_LEFT : MNV_UTURN_RIGHT; // por defecto, a la derecha
  }

  if (right) {
    if (a.indexOf("sharp")  >= 0) return MNV_SHARP_RIGHT;
    if (a.indexOf("slight") >= 0 || a.indexOf("keep") >= 0) return MNV_SLIGHT_RIGHT;
    return MNV_RIGHT;
  }
  if (left) {
    if (a.indexOf("sharp")  >= 0) return MNV_SHARP_LEFT;
    if (a.indexOf("slight") >= 0 || a.indexOf("keep") >= 0) return MNV_SLIGHT_LEFT;
    return MNV_LEFT;
  }

  // "continue", "straight", "depart", "arrive", "finish", vacío, o algo
  // que aun no conocemos. Lo avisamos por Serial para poder añadirlo.
  if (a.length() > 0 && a != "continue" && a != "straight" && a != "depart" &&
      a != "arrive" && a != "finish" && a != "destination") {
    Serial.print("  [AVISO] Accion de navegacion no reconocida: '");
    Serial.print(actionIn);
    Serial.println("' -> se dibuja como 'recto'");
  }
  return MNV_STRAIGHT;
}

void updateArrow()
{
  lv_canvas_fill_bg(arrowCanvas, lv_color_black(), LV_OPA_TRANSP);

  if (!navActive) {
    // Sin ruta activa: un guion horizontal como icono neutro.
    lv_point_t pts[4] = {
      { 30, ARROW_CANVAS_SIZE/2 - 8 }, { ARROW_CANVAS_SIZE-30, ARROW_CANVAS_SIZE/2 - 8 },
      { ARROW_CANVAS_SIZE-30, ARROW_CANVAS_SIZE/2 + 8 }, { 30, ARROW_CANVAS_SIZE/2 + 8 }
    };
    lv_draw_rect_dsc_t dsc;
    lv_draw_rect_dsc_init(&dsc);
    dsc.bg_color = lv_palette_main(LV_PALETTE_GREY);
    dsc.bg_opa = LV_OPA_COVER;
    lv_canvas_draw_polygon(arrowCanvas, pts, 4, &dsc);
    return;
  }

  lv_color_t col = lv_color_white();
  ManeuverType m = classifyManeuver(navAction);

  switch (m) {
    //                        recto  giro  radio  final  derecha
    case MNV_STRAIGHT:        drawManeuverIcon(64,   0,   0,   0, true,  col); break;
    case MNV_SLIGHT_RIGHT:    drawManeuverIcon(30,  35,  70,   4, true,  col); break;
    case MNV_SLIGHT_LEFT:     drawManeuverIcon(30,  35,  70,   4, false, col); break;
    case MNV_RIGHT:           drawManeuverIcon(36,  90,  26,  10, true,  col); break;
    case MNV_LEFT:            drawManeuverIcon(36,  90,  26,  10, false, col); break;
    case MNV_SHARP_RIGHT:     drawManeuverIcon(34, 135,  22,   8, true,  col); break;
    case MNV_SHARP_LEFT:      drawManeuverIcon(34, 135,  22,   8, false, col); break;
    case MNV_UTURN_RIGHT:     drawManeuverIcon(44, 180,  18,   6, true,  col); break;
    case MNV_UTURN_LEFT:      drawManeuverIcon(44, 180,  18,   6, false, col); break;
    case MNV_ROUNDABOUT_RIGHT:    drawRoundaboutIcon(  75, col); break;
    case MNV_ROUNDABOUT_LEFT:     drawRoundaboutIcon( -75, col); break;
    case MNV_ROUNDABOUT_STRAIGHT: drawRoundaboutIcon(   0, col); break;
  }
}

// ===========================================================================
// ETIQUETAS DE TEXTO
// ===========================================================================
void formatDistance(int meters, char *out, size_t outLen)
{
  if (meters < 0) {
    snprintf(out, outLen, "--");
  } else if (meters >= 1000) {
    snprintf(out, outLen, "%.1f km", meters / 1000.0f);
  } else {
    snprintf(out, outLen, "%d m", meters);
  }
}

void refreshDistanceLabel()
{
  char buf[16];
  formatDistance(navDistanceM, buf, sizeof(buf));
  lv_label_set_text(distanceLabel, navActive ? buf : "Sin ruta");
}

void refreshInstrLabel()
{
  if (!navActive || navInstr.length() == 0) {
    lv_label_set_text(instrLabel, "");
  } else {
    String s = navInstr;
    if (s.length() > 28) s = s.substring(0, 27) + "...";
    lv_label_set_text(instrLabel, s.c_str());
  }
}

void refreshSpeedLabel()
{
  // Si la app no manda velocidad, el campo se oculta del todo.
  if (speedKmh < 0) {
    lv_obj_add_flag(speedLabel, LV_OBJ_FLAG_HIDDEN);
    return;
  }
  char buf[16];
  snprintf(buf, sizeof(buf), "%d km/h", (int)lroundf(speedKmh));
  lv_label_set_text(speedLabel, buf);
  lv_obj_clear_flag(speedLabel, LV_OBJ_FLAG_HIDDEN);
}

void refreshBatteryLabel()
{
  if (batteryPct < 0) {
    lv_obj_add_flag(batteryLabel, LV_OBJ_FLAG_HIDDEN);
  } else {
    char buf[8];
    snprintf(buf, sizeof(buf), "%d%%", batteryPct);
    lv_label_set_text(batteryLabel, buf);
    lv_obj_clear_flag(batteryLabel, LV_OBJ_FLAG_HIDDEN);
  }
}


// Variables para delegar el sonido al loop() principal
volatile bool pendingProximityAlert = false;
volatile bool pendingCloseAlert = false;

// ===========================================================================
// SONIDO
// ===========================================================================
void beepTone(int freq, int ms)
{
  ledcWriteTone(0, freq);
  delay(ms);
  ledcWriteTone(0, 0);
}

void playProximityAlert()
{
  beepTone(1400, 130);
  delay(50);
  beepTone(1900, 160);
}

void playCloseAlert()
{
  beepTone(2200, 90);
  delay(40);
  beepTone(2200, 90);
  delay(40);
  beepTone(2200, 90);
}

void checkProximityAlerts()
{
  String key = navAction + "|" + navInstr;
  if (key != lastInstrKey) {
    lastInstrKey = key;
    firstSeenDistance = navDistanceM;
    alert1kmFired = false;
    alert100mFired = false;
  }

  if (!alert1kmFired && firstSeenDistance > 1000 &&
      navDistanceM > 0 && navDistanceM <= 1000) {
    pendingProximityAlert = true; // Activamos la bandera en lugar de bloquear
    alert1kmFired = true;
  }
  if (!alert100mFired && navDistanceM > 0 && navDistanceM <= 100) {
    pendingCloseAlert = true;    // Activamos la bandera en lugar de bloquear
    alert100mFired = true;
  }
}


// ===========================================================================
// BATERIA (opcional)
// ===========================================================================
void updateBatteryReading()
{
#if ENABLE_BATTERY
  // Divisor 100k/100k -> el ADC ve la mitad del voltaje real de la LiPo.
  int raw = analogRead(BATTERY_ADC_PIN);           // 0-4095 (12 bits)
  float vAdc = (raw / 4095.0f) * 3.3f;              // voltios en el pin
  float vBat = vAdc * 2.0f;                         // voltios reales de la batería

  if (vBat < 2.0f) {
    batteryPct = -1;                                // no hay nada conectado
  } else {
    float pct = (vBat - 3.3f) / (4.2f - 3.3f) * 100.0f; // 3.3V=0%, 4.2V=100%
    if (pct < 0) pct = 0;
    if (pct > 100) pct = 100;
    batteryPct = (int)lroundf(pct);
  }
#else
  batteryPct = -1;
#endif
}

// ===========================================================================
// BRILLO (simulado con una capa oscura encima de todo)
// ===========================================================================
void applyBrightness()
{
  // 100% brillo -> overlay totalmente transparente.
  // 30% brillo  -> overlay bastante opaco (pantalla se ve más oscura).
  lv_opa_t opa = (lv_opa_t)map(brightnessPct, 100, 30, 0, 190);
  lv_obj_set_style_bg_opa(dimOverlay, opa, 0);
}

void showToast(const char *text)
{
  lv_label_set_text(toastLabel, text);
  lv_obj_clear_flag(toastLabel, LV_OBJ_FLAG_HIDDEN);
  if (toastTimer) lv_timer_del(toastTimer);
  toastTimer = lv_timer_create([](lv_timer_t *t) {
    lv_obj_add_flag(toastLabel, LV_OBJ_FLAG_HIDDEN);
    lv_timer_del(t);
    toastTimer = nullptr;
  }, 1400, nullptr);
}

void brightBtnEventCb(lv_event_t *e)
{
  if (lv_event_get_code(e) != LV_EVENT_CLICKED) return;
  // Ciclo de presets: 100% -> 70% -> 40% -> 100% ...
  if (brightnessPct >= 100)      brightnessPct = 70;
  else if (brightnessPct >= 70)  brightnessPct = 40;
  else                            brightnessPct = 100;

  applyBrightness();
  char buf[24];
  snprintf(buf, sizeof(buf), "Brillo: %d%%", brightnessPct);
  showToast(buf);
}

// ===========================================================================
// CONSTRUCCION DE LA INTERFAZ
// ===========================================================================
void buildUI()
{
  lv_obj_t *scr = lv_scr_act();
  lv_obj_set_style_bg_color(scr, lv_color_black(), 0);

  // --- Batería (arriba a la izquierda) ---
  batteryLabel = lv_label_create(scr);
  lv_obj_set_style_text_color(batteryLabel, lv_color_white(), 0);
  lv_obj_set_style_text_font(batteryLabel, &lv_font_montserrat_14, 0);
  lv_obj_align(batteryLabel, LV_ALIGN_TOP_LEFT, 6, 4);
  lv_label_set_text(batteryLabel, "");
  lv_obj_add_flag(batteryLabel, LV_OBJ_FLAG_HIDDEN);

  // --- Flecha central (canvas) ---
  arrowCanvasBuf = (lv_color_t *)malloc(LV_CANVAS_BUF_SIZE_TRUE_COLOR(ARROW_CANVAS_SIZE, ARROW_CANVAS_SIZE));
  if (arrowCanvasBuf == NULL) {
      Serial.println("Error: Memoria insuficiente para arrowCanvas");
  }
  arrowCanvas = lv_canvas_create(scr);
  lv_canvas_set_buffer(arrowCanvas, arrowCanvasBuf, ARROW_CANVAS_SIZE, ARROW_CANVAS_SIZE, LV_IMG_CF_TRUE_COLOR);
  lv_obj_align(arrowCanvas, LV_ALIGN_TOP_MID, 0, 26);

  // --- Distancia (grande, bajo la flecha) ---
  distanceLabel = lv_label_create(scr);
  lv_obj_set_style_text_color(distanceLabel, lv_color_white(), 0);
  lv_obj_set_style_text_font(distanceLabel, &lv_font_montserrat_24, 0);
  lv_obj_align(distanceLabel, LV_ALIGN_TOP_MID, 0, 162);
  lv_label_set_text(distanceLabel, "Sin ruta");

  // --- Instrucción / calle (pequeño, bajo la distancia) ---
  instrLabel = lv_label_create(scr);
  lv_obj_set_style_text_color(instrLabel, lv_palette_main(LV_PALETTE_GREY), 0);
  lv_obj_set_style_text_font(instrLabel, &lv_font_montserrat_14, 0);
  lv_obj_align(instrLabel, LV_ALIGN_TOP_MID, 0, 195);
  lv_label_set_text(instrLabel, "");

  // --- Velocidad (abajo a la derecha, pequeña) ---
  speedLabel = lv_label_create(scr);
  lv_obj_set_style_text_color(speedLabel, lv_palette_main(LV_PALETTE_GREY), 0);
  lv_obj_set_style_text_font(speedLabel, &lv_font_montserrat_14, 0);
  lv_obj_align(speedLabel, LV_ALIGN_BOTTOM_RIGHT, -6, -6);
  lv_label_set_text(speedLabel, "");
  lv_obj_add_flag(speedLabel, LV_OBJ_FLAG_HIDDEN); // oculto hasta que llegue velocidad

  // --- Boton de brillo (abajo a la izquierda) ---
  brightBtn = lv_btn_create(scr);
  lv_obj_set_size(brightBtn, 46, 30);
  lv_obj_align(brightBtn, LV_ALIGN_BOTTOM_LEFT, 4, -4);
  lv_obj_set_style_bg_color(brightBtn, lv_palette_darken(LV_PALETTE_GREY, 3), 0);
  lv_obj_set_style_bg_opa(brightBtn, LV_OPA_60, 0);
  lv_obj_add_event_cb(brightBtn, brightBtnEventCb, LV_EVENT_CLICKED, nullptr);
  // Icono de sol dibujado a mano (LVGL no trae simbolo de sol/bombilla)
  static lv_color_t sunBuf[SUN_ICON_SIZE * SUN_ICON_SIZE];
  lv_obj_t *sunCanvas = lv_canvas_create(brightBtn);
  lv_canvas_set_buffer(sunCanvas, sunBuf, SUN_ICON_SIZE, SUN_ICON_SIZE, LV_IMG_CF_TRUE_COLOR);
  lv_obj_clear_flag(sunCanvas, LV_OBJ_FLAG_CLICKABLE);
  lv_obj_center(sunCanvas);
  lv_canvas_fill_bg(sunCanvas, lv_color_black(), LV_OPA_TRANSP);
  {
    const int c = SUN_ICON_SIZE / 2;
    lv_draw_rect_dsc_t rd;
    lv_draw_rect_dsc_init(&rd);
    rd.bg_color = lv_color_white();
    rd.bg_opa   = LV_OPA_COVER;
    rd.radius   = LV_RADIUS_CIRCLE;
    lv_canvas_draw_rect(sunCanvas, c - 4, c - 4, 9, 9, &rd);   // disco central

    lv_draw_line_dsc_t ld;
    lv_draw_line_dsc_init(&ld);
    ld.color = lv_color_white();
    ld.width = 2;
    ld.round_start = true;
    ld.round_end   = true;
    for (int i = 0; i < 8; i++) {                               // 8 rayos
      float a = i * (float)M_PI / 4.0f;
      lv_point_t ray[2] = {
        { (lv_coord_t)lroundf(c + 7.0f  * cosf(a)), (lv_coord_t)lroundf(c + 7.0f  * sinf(a)) },
        { (lv_coord_t)lroundf(c + 10.0f * cosf(a)), (lv_coord_t)lroundf(c + 10.0f * sinf(a)) }
      };
      lv_canvas_draw_line(sunCanvas, ray, 2, &ld);
    }
  }

  // --- Toast (mensaje temporal, oculto por defecto) ---
  toastLabel = lv_label_create(scr);
  lv_obj_set_style_text_color(toastLabel, lv_color_white(), 0);
  lv_obj_set_style_bg_color(toastLabel, lv_color_black(), 0);
  lv_obj_set_style_bg_opa(toastLabel, LV_OPA_70, 0);
  lv_obj_set_style_pad_all(toastLabel, 6, 0);
  lv_obj_align(toastLabel, LV_ALIGN_CENTER, 0, 0);
  lv_obj_add_flag(toastLabel, LV_OBJ_FLAG_HIDDEN);

  // --- Capa de "brillo" (siempre la última, para quedar por encima) ---
  dimOverlay = lv_obj_create(scr);
  lv_obj_remove_style_all(dimOverlay);
  lv_obj_set_size(dimOverlay, screenWidth, screenHeight);
  lv_obj_set_pos(dimOverlay, 0, 0);
  lv_obj_set_style_bg_color(dimOverlay, lv_color_black(), 0);
  lv_obj_set_style_bg_opa(dimOverlay, 0, 0);
  lv_obj_clear_flag(dimOverlay, LV_OBJ_FLAG_CLICKABLE);

  updateArrow();
}

// ===========================================================================
// PROCESADO DE MENSAJES JSON DE GADGETBRIDGE
// ===========================================================================
// Función auxiliar para convertir textos como "40m", "0m" o "1.5km" a metros enteros
int parseDistanceMeters(JsonVariant distVar)
{
  if (distVar.isNull()) return -1;
  if (distVar.is<int>()) return distVar.as<int>();
  if (distVar.is<float>()) return (int)distVar.as<float>();

  String s = distVar.as<String>();
  s.toLowerCase();

  // toFloat() extrae automáticamente los primeros dígitos (ej: "40m" -> 40.0)
  float val = s.toFloat();

  if (s.indexOf("km") >= 0) {
    return (int)(val * 1000.0f);
  }
  return (int)val;
}

void handleNavJson(JsonDocument &doc)
{
  if (!doc.containsKey("instr") && !doc.containsKey("action")) {
    // {"t":"nav"} sin más datos = navegación detenida.
    navActive = false;
    navInstr = "";
    navAction = "";
    navDistanceM = -1;
    lastInstrKey = "";
  } else {
    navActive = true;
    navInstr = stripNonAscii(String((const char *)(doc["instr"] | "")));
    navAction = doc["action"] | "";
    navDistanceM = parseDistanceMeters(doc["distance"]);
    checkProximityAlerts();
  }
  updateArrow();
  refreshDistanceLabel();
  refreshInstrLabel();
}

void handleGpsJson(JsonDocument &doc)
{
  if (doc.containsKey("speed")) {
    speedKmh = doc["speed"];
    lastGpsMillis = millis();
    refreshSpeedLabel();
  }
}

// El movil a veces manda letras acentuadas (nombres de calle) escapadas en
// un formato que NO es JSON valido: "\xE1" (estilo Python/C, un byte
// Latin-1) en vez de "\u00E1" (unicode, que es lo unico que admite JSON).
// ArduinoJson rechaza "\x.." con el error "InvalidInput" y se pierde el
// mensaje entero. Aqui convertimos "\xHH" -> "\u00HH" antes de parsear.
String fixInvalidHexEscapes(const String &in)
{
  String out;
  out.reserve(in.length() + 8);
  int i = 0;
  int n = in.length();
  while (i < n) {
    if (in[i] == '\\' && i + 3 < n && (in[i + 1] == 'x' || in[i + 1] == 'X') &&
        isxdigit((unsigned char)in[i + 2]) && isxdigit((unsigned char)in[i + 3])) {
      out += "\\u00";
      out += in[i + 2];
      out += in[i + 3];
      i += 4;
    } else {
      out += in[i];
      i++;
    }
  }
  return out;
}

// Deja solo caracteres ASCII imprimibles: la fuente de la pantalla no tiene
// tildes/ñ y pintaba un cuadrado vacio. Los caracteres especiales se quitan.
String stripNonAscii(const String &in)
{
  String out;
  out.reserve(in.length());
  for (unsigned int i = 0; i < in.length(); i++) {
    unsigned char ch = (unsigned char)in[i];
    if (ch >= 32 && ch <= 126) out += (char)ch;
  }
  return out;
}

void processLine(String line)
{
  line.trim();
  if (line.length() == 0) return;

  Serial.print("[BLE RECIBIDO] ");
  Serial.println(line);

  // Busca los límites del objeto JSON {...} ignorando prefijos como \x10GB( y sufijos como );
  int jsonStart = line.indexOf('{');
  int jsonEnd   = line.lastIndexOf('}');

  if (jsonStart < 0 || jsonEnd <= jsonStart) {
    Serial.println("  └─> [Info] Ignorado: No contiene un objeto JSON valido { ... }.");
    return;
  }

  // Extrae únicamente la cadena JSON limpia
  String jsonStr = line.substring(jsonStart, jsonEnd + 1);
  jsonStr = fixInvalidHexEscapes(jsonStr);

  StaticJsonDocument<768> doc;
  DeserializationError err = deserializeJson(doc, jsonStr);
  if (err) {
    Serial.print("  └─> [Error] Error al procesar el JSON: ");
    Serial.println(err.c_str());
    return;
  }

  const char *t = doc["t"] | "desconocido";
  Serial.print("  └─> Tipo de comando ('t'): ");
  Serial.println(t);

  if (strcmp(t, "nav") == 0) {
    Serial.println("  └─> ¡PAQUETE DE NAVEGACIÓN PROCESADO CON ÉXITO!");
    handleNavJson(doc);
  } else if (strcmp(t, "gps") == 0) {
    Serial.println("  └─> ¡Datos de velocidad/GPS recibidos!");
    handleGpsJson(doc);
  } else {
    Serial.printf("  └─> El comando '%s' no es de navegacion (se ignora).\n", t);
  }
}






// ===========================================================================
// BLE (Nordic UART Service, se anuncia como reloj Bangle.js compatible)
// ===========================================================================
class ServerCallbacks : public BLEServerCallbacks
{
  void onConnect(BLEServer *s) override
  {
    bleClientConnected = true;
    Serial.println("Movil conectado por BLE");
  }
  void onDisconnect(BLEServer *s) override
  {
    bleClientConnected = false;
    Serial.println("Movil desconectado, reanudando anuncio BLE");
    delay(100);
    s->startAdvertising();
  }
};

// Añade esta bandera global justo encima de los callbacks
volatile bool bleDataPending = false;

class RxCallbacks : public BLECharacteristicCallbacks
{
  void onWrite(BLECharacteristic *c) override
  {
    std::string v = c->getValue();
    if (v.empty()) return;

    // Bloqueamos el Mutex antes de modificar la variable compartida
    if (xSemaphoreTake(rxMutex, portMAX_DELAY)) {
      rxBuffer += v.c_str();
      bleDataPending = true;
      xSemaphoreGive(rxMutex);
    }
  }
};

void setupBLE()
{
  Serial.println("  [BLE 1/9] BLEDevice::init...");
  BLEDevice::init("GPS-Nav-ESP32");
  Serial.println("  [BLE 2/9] setMTU...");
  BLEDevice::setMTU(500);

  // Configuracion de seguridad: aceptamos emparejamiento "Just Works"
  // (sin PIN, sin pantalla/teclado). Gadgetbridge intenta emparejar con
  // cifrado al tratarlo como un Bangle.js; sin esto, el ESP32 no sabe
  // responder a la negociacion de claves y la conexion se cae en bucle
  // con errores SMP/BTM.
  BLESecurity *pSecurity = new BLESecurity();
  pSecurity->setAuthenticationMode(ESP_LE_AUTH_BOND);
  pSecurity->setCapability(ESP_IO_CAP_NONE);
  pSecurity->setInitEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);
  pSecurity->setRespEncryptionKey(ESP_BLE_ENC_KEY_MASK | ESP_BLE_ID_KEY_MASK);

  Serial.println("  [BLE 3/9] createServer...");
  bleServer = BLEDevice::createServer();
  bleServer->setCallbacks(new ServerCallbacks());

  Serial.println("  [BLE 4/9] createService...");
  BLEService *service = bleServer->createService(NUS_SERVICE_UUID);

  Serial.println("  [BLE 5/9] createCharacteristic RX...");
  charRX = service->createCharacteristic(
      NUS_CHAR_RX_UUID,
      BLECharacteristic::PROPERTY_WRITE | BLECharacteristic::PROPERTY_WRITE_NR);
  charRX->setCallbacks(new RxCallbacks());

  Serial.println("  [BLE 6/9] createCharacteristic TX...");
  charTX = service->createCharacteristic(
      NUS_CHAR_TX_UUID,
      BLECharacteristic::PROPERTY_NOTIFY);
  charTX->addDescriptor(new BLE2902());

  Serial.println("  [BLE 7/9] service->start...");
  service->start();

  Serial.println("  [BLE 8/9] configurando advertising...");
  BLEAdvertising *adv = BLEDevice::getAdvertising();
  adv->addServiceUUID(NUS_SERVICE_UUID);
  adv->setScanResponse(true);

  Serial.println("  [BLE 9/9] startAdvertising...");
  BLEDevice::startAdvertising();

  Serial.println("BLE anunciando como 'GPS-Nav-ESP32'");
  Serial.printf("Heap libre tras BLE: %u bytes\n", ESP.getFreeHeap());
}

// ===========================================================================
// SETUP / LOOP
// ===========================================================================
void setup()
{
  Serial.begin(115200);
  // Espera activa a que el USB CDC enumere, para no perder los primeros
  // mensajes (con "USB CDC On Boot: Enabled" el puerto tarda un poco tras
  // el reset). Si no hay monitor conectado, sigue de todos modos a los 3s.
  unsigned long t0 = millis();
  while (!Serial && millis() - t0 < 3000) { delay(10); }
  delay(200);
  Serial.println("Iniciando GPS Nav Display...");
  Serial.printf("Heap libre al arrancar: %u bytes\n", ESP.getFreeHeap());

  rxMutex = xSemaphoreCreateMutex();										  

  Serial.println("[CP1] tft.init...");
  tft.init();
  delay(50); // deja que la pantalla/SPI se asiente antes de seguir
  Serial.println("[CP2] tft.init OK");

  Serial.println("[CP3] touch.begin...");
  touch.begin();
  delay(50); // deja que el bus I2C del tactil se asiente antes de seguir
  Serial.println("[CP4] touch.begin OK");

  pinMode(BUZZER_PIN, OUTPUT);
  ledcSetup(0, 2000, 8);
  ledcAttachPin(BUZZER_PIN, 0);

#if ENABLE_BATTERY
  analogReadResolution(12);
#endif

  Serial.println("[CP5] lv_init...");
  lv_init();
  lv_disp_draw_buf_init(&draw_buf, lvgl_buf, NULL, screenWidth * BUF_ROWS);

  static lv_disp_drv_t disp_drv;
  lv_disp_drv_init(&disp_drv);
  disp_drv.hor_res = screenWidth;
  disp_drv.ver_res = screenHeight;
  disp_drv.flush_cb = my_disp_flush;
  disp_drv.draw_buf = &draw_buf;
  lv_disp_drv_register(&disp_drv);

  static lv_indev_drv_t indev_drv;
  lv_indev_drv_init(&indev_drv);
  indev_drv.type = LV_INDEV_TYPE_POINTER;
  indev_drv.read_cb = my_touchpad_read;
  lv_indev_drv_register(&indev_drv);

  Serial.println("[CP6] buildUI...");
  buildUI();
  refreshSpeedLabel();
  refreshBatteryLabel();
  applyBrightness();
  Serial.println("[CP7] buildUI OK");

#if ENABLE_BLE
  delay(300); // deja que LVGL/pantalla terminen de asentarse antes de levantar BLE
  Serial.println("[CP8] setupBLE...");
  setupBLE();
  Serial.println("[CP9] setupBLE OK, setup() completo");
#else
  Serial.println("BLE DESACTIVADO (ENABLE_BLE 0) - modo diagnostico");
#endif
}

void loop()
{
  lv_timer_handler();
  delay(5);

  String localBuffer = "";

  // Capturamos el buffer rápidamente y lo vaciamos de forma segura
  if (bleDataPending) {
    if (xSemaphoreTake(rxMutex, portMAX_DELAY)) {
      localBuffer = rxBuffer;
      rxBuffer = "";
      bleDataPending = false;
      xSemaphoreGive(rxMutex);
    }
  }

  // Ahora procesamos el buffer local sin peligro
  if (localBuffer.length() > 0) {
    int nl;
    while ((nl = localBuffer.indexOf('\n')) >= 0) {
      String line = localBuffer.substring(0, nl);
      localBuffer = localBuffer.substring(nl + 1);
      processLine(line);
    }

    // Lo que quede en localBuffer (si algo queda) es un mensaje incompleto:
    // el móvil lo partió en varios paquetes BLE y el resto aún no ha
    // llegado. Antes se descartaba aquí, lo que corrompía el siguiente
    // mensaje (se veía como "[BLE RECIBIDO] m","action":"left"})" seguido
    // de "Ignorado: No contiene un objeto JSON valido"). Lo devolvemos al
    // buffer global para completarlo en cuanto llegue el resto.
    if (localBuffer.length() > 0) {
      if (xSemaphoreTake(rxMutex, portMAX_DELAY)) {
        rxBuffer = localBuffer + rxBuffer; // por si ya ha llegado algo mas mientras tanto
        if (rxBuffer.length() > 0) bleDataPending = true;
        xSemaphoreGive(rxMutex);
      }
    }
  }

  // Procesamos los sonidos fuera del callback de Bluetooth
  if (pendingProximityAlert) {
    pendingProximityAlert = false;
    playProximityAlert();
  }
  if (pendingCloseAlert) {
    pendingCloseAlert = false;
    playCloseAlert();
  }

  static unsigned long lastSlow = 0;
  if (millis() - lastSlow > 3000) {
    lastSlow = millis();

    updateBatteryReading();
    refreshBatteryLabel();

    if (speedKmh >= 0 && millis() - lastGpsMillis > 12000) {
      speedKmh = -1;
      refreshSpeedLabel();
    }
  }
}
