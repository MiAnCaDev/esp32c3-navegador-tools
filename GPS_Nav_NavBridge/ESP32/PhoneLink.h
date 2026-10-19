#pragma once
/*
  PhoneLink: receptor de tramas binarias del móvil (app NavBridge) por Nordic UART.

  Trama:  0xA5 | tipo | largo_lo | largo_hi | payload... | checksum
          checksum = XOR de tipo, largo_lo, largo_hi y todo el payload.
  Tipos:  0x01 = JSON (texto UTF-8)
          0x02 = ICONO: slot, ancho, alto, bpp(=4), píxeles 4 bits (nibble alto primero)

  Para añadir un tipo nuevo: define PL_T_xxx, añade un callback y un "else if" en phoneLinkPoll().
*/
#include <Arduino.h>
#include <string>

#define PL_SYNC        0xA5
#define PL_T_JSON      0x01
#define PL_T_ICON      0x02
#define PL_MAX_PAYLOAD 9000

extern SemaphoreHandle_t rxMutex;   // definido en el .ino

static std::string plIncoming;      // lo que escribe el callback BLE
static bool        plResetReq = false;

typedef void (*PhoneJsonCb)(const String &json);
typedef void (*PhoneIconCb)(uint8_t slot, uint8_t w, uint8_t h, const uint8_t *data, size_t len);

// Llamar desde el callback BLE onWrite (otro hilo).
inline void phoneLinkPush(const uint8_t *d, size_t n)
{
  if (xSemaphoreTake(rxMutex, portMAX_DELAY)) {
    if (plIncoming.size() + n < 20000) plIncoming.append((const char *)d, n);
    xSemaphoreGive(rxMutex);
  }
}

// Descarta lo pendiente (al desconectar o cambiar de modo).
inline void phoneLinkReset()
{
  if (xSemaphoreTake(rxMutex, portMAX_DELAY)) {
    plIncoming.clear();
    plResetReq = true;
    xSemaphoreGive(rxMutex);
  }
}

// Llamar desde loop(): extrae y despacha las tramas completas.
inline void phoneLinkPoll(PhoneJsonCb onJson, PhoneIconCb onIcon)
{
  static std::string work;

  if (xSemaphoreTake(rxMutex, portMAX_DELAY)) {
    if (plResetReq) { work.clear(); plResetReq = false; }
    if (!plIncoming.empty()) { work.append(plIncoming); plIncoming.clear(); }
    xSemaphoreGive(rxMutex);
  }
  if (work.empty()) return;

  size_t pos = 0;
  for (;;) {
    while (pos < work.size() && (uint8_t)work[pos] != PL_SYNC) pos++;   // buscar sincronismo
    size_t avail = work.size() - pos;
    if (avail < 5) break;

    uint8_t  type = (uint8_t)work[pos + 1];
    uint8_t  lo   = (uint8_t)work[pos + 2];
    uint8_t  hi   = (uint8_t)work[pos + 3];
    uint16_t len  = lo | ((uint16_t)hi << 8);
    if (len > PL_MAX_PAYLOAD) { pos++; continue; }
    if (avail < (size_t)5 + len) break;                                // trama incompleta: esperar

    const uint8_t *pl = (const uint8_t *)work.data() + pos + 4;
    uint8_t chk = type ^ lo ^ hi;
    for (uint16_t i = 0; i < len; i++) chk ^= pl[i];
    if (chk != pl[len]) { pos++; continue; }                           // corrupta: resincronizar

    if (type == PL_T_JSON) {
      String s;
      s.reserve(len + 1);
      s.concat((const char *)pl, len);
      if (onJson) onJson(s);
    } else if (type == PL_T_ICON) {
      if (len >= 4 && pl[3] == 4 && onIcon) onIcon(pl[0], pl[1], pl[2], pl + 4, len - 4);
    }
    // else if (type == PL_T_xxx) { ... }

    pos += 5 + len;
  }
  work.erase(0, pos);
}
