// Prueba mínima y aislada del táctil CST816D, sin LVGL ni pantalla.
// Solo imprime por el monitor serie lo que detecta el chip táctil.
// Usa los mismos pines I2C que el proyecto principal.
//
// Necesitas tener CST816D.h y CST816D.cpp en la misma carpeta que este
// sketch (cópialos junto a este .ino, como ya tienes en el proyecto GPS).

#include <Wire.h>
#include "CST816D.h"

#define I2C_SCL 7
#define I2C_SDA 11
#define TP_RST  10
#define TP_INT  9

CST816D touch(I2C_SDA, I2C_SCL, TP_RST, TP_INT);

void setup()
{
  Serial.begin(115200);
  unsigned long t0 = millis();
  while (!Serial && millis() - t0 < 3000) { delay(10); }
  delay(200);

  Serial.println("Iniciando prueba tactil CST816D...");

  touch.begin();
  Serial.println("touch.begin() OK. Toca la pantalla...");
  Serial.println("(si no aparece nada al tocar, hay un problema de");
  Serial.println(" cableado/I2C/pines, no de LVGL ni del sketch principal)");
}

void loop()
{
  uint16_t x, y;
  uint8_t gesture;

  bool touched = touch.getTouch(&x, &y, &gesture);

  if (touched) {
    Serial.printf("TOCADO  x=%u  y=%u  gesture=0x%02X\n", x, y, gesture);
  }

  // También mostramos el nivel del pin INT cada segundo, por si acaso,
  // para ver si de verdad se pone en LOW al tocar (dato informativo).
  static unsigned long lastPrint = 0;
  if (millis() - lastPrint > 1000) {
    lastPrint = millis();
    Serial.printf("(estado TP_INT ahora mismo: %s)\n",
                  digitalRead(TP_INT) == LOW ? "LOW" : "HIGH");
  }

  delay(50);
}
