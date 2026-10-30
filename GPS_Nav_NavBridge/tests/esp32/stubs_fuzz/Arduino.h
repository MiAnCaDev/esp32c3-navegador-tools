#pragma once
#include <string>
#include <cstring>
#include <cstdio>
#include <cstdint>
#include <cstdlib>
typedef int SemaphoreHandle_t;
#define portMAX_DELAY 0
inline bool xSemaphoreTake(SemaphoreHandle_t, int) { return true; }
inline void xSemaphoreGive(SemaphoreHandle_t) {}
class String {
public:
  std::string s;
  void reserve(size_t n) { s.reserve(n); }
  void concat(const char *p, size_t n) { s.append(p, n); }
};
