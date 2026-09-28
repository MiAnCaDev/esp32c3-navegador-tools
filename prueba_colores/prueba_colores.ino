// Prueba mínima de pantalla ST7789 (ESP32-C3, 1.69" 240x280)
// Sin LVGL, sin WiFi, sin touch: solo para confirmar que la pantalla enciende.
// Requiere la librería LovyanGFX copiada en Arduino/libraries.

#define LGFX_USE_V1
#include <LovyanGFX.hpp>

// --- Pines según pin_config.h del proyecto original ---
#define PIN_LCD_RS   2   // DC
#define PIN_LCD_CS   4
#define PIN_LCD_SCK  5
#define PIN_LCD_SDA  6   // MOSI
#define PIN_LCD_RES  8   // RST

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
      cfg.freq_write = 40000000;
      cfg.freq_read  = 40000000;
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

void setup()
{
  Serial.begin(115200);
  delay(300);
  Serial.println("Iniciando pantalla...");

  bool ok = tft.init();
  Serial.printf("tft.init() devolvio: %s\n", ok ? "OK" : "FALLO");

  tft.setRotation(0);
  tft.setTextColor(TFT_WHITE, TFT_BLACK);
  tft.setTextSize(2);
}

void loop()
{
  tft.fillScreen(TFT_RED);
  tft.setCursor(10, 10);
  tft.println("ROJO");
  delay(1000);

  tft.fillScreen(TFT_GREEN);
  tft.setCursor(10, 10);
  tft.println("VERDE");
  delay(1000);

  tft.fillScreen(TFT_BLUE);
  tft.setCursor(10, 10);
  tft.println("AZUL");
  delay(1000);
}
