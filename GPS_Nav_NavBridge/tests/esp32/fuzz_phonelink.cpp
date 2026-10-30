// Fuzzing del parser de tramas Bluetooth (ESP32/PhoneLink.h), que recibe datos NO confiables: tramas validas troceadas al azar,
// basura aleatoria, longitudes enormes, checksums malos, cabeceras cortadas e iconos con campos hostiles. Si se compila con
// AddressSanitizer/UBSan (el script lo intenta), cualquier desbordamiento o comportamiento indefinido aborta la prueba.
#include <Arduino.h>
#include <vector>
#include <random>
SemaphoreHandle_t rxMutex = 0;
// Arduino.h falso para PhoneLink.h
#include "../../ESP32/PhoneLink.h"
static size_t jsons = 0, icons = 0;
static void onJson(const String &j) { jsons++; if (j.s.size() > PL_MAX_PAYLOAD) abort(); }
static void onIcon(uint8_t, uint8_t, uint8_t, const uint8_t *d, size_t n) { icons++; volatile uint8_t x = 0; for (size_t i = 0; i < n; i++) x ^= d[i]; (void)x; }
static std::vector<uint8_t> frame(uint8_t type, const std::vector<uint8_t> &pl) {
  std::vector<uint8_t> f{PL_SYNC, type, (uint8_t)(pl.size() & 255), (uint8_t)(pl.size() >> 8)};
  uint8_t chk = f[1] ^ f[2] ^ f[3]; for (auto b : pl) { f.push_back(b); chk ^= b; } f.push_back(chk); return f;
}
int main() {
  std::mt19937 rng(42);
  // 1) tramas validas, troceadas de forma aleatoria
  std::vector<uint8_t> all;
  for (int i = 0; i < 200; i++) { auto f = frame(PL_T_JSON, std::vector<uint8_t>(rng() % 300, 'a')); all.insert(all.end(), f.begin(), f.end()); }
  size_t sent = 0;
  while (sent < all.size()) { size_t n = std::min<size_t>(1 + rng() % 40, all.size() - sent); phoneLinkPush(all.data() + sent, n); sent += n; phoneLinkPoll(onJson, onIcon); }
  printf("tramas validas troceadas: %zu de 200 recibidas\n", jsons);
  // 2) basura aleatoria, longitudes enormes, checksums malos, cabeceras truncadas (bajo ASAN)
  for (int r = 0; r < 20000; r++) {
    std::vector<uint8_t> junk(rng() % 600); for (auto &b : junk) b = (rng() % 4 == 0) ? PL_SYNC : (uint8_t)rng();
    phoneLinkPush(junk.data(), junk.size()); phoneLinkPoll(onJson, onIcon);
    if (r % 50 == 0) phoneLinkReset();
  }
  // 3) iconos con campos hostiles (ancho/alto 255, len minima)
  for (int r = 0; r < 2000; r++) { std::vector<uint8_t> pl{(uint8_t)(rng() % 3), (uint8_t)rng(), (uint8_t)rng(), 4}; pl.resize(4 + rng() % 20, 1); auto f = frame(PL_T_ICON, pl); phoneLinkPush(f.data(), f.size()); phoneLinkPoll(onJson, onIcon); }
  printf("fuzz terminado sin fallos (json=%zu, iconos=%zu)\n", jsons, icons);
}
