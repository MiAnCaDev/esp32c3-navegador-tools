#pragma once
#include <string>
#include <map>
#include <cstring>
#include <cstdio>
#include <cstdint>
#include <cctype>
#include <cstdlib>
extern uint32_t g_millis;
inline uint32_t millis() { return g_millis; }
class String {
  std::string s;
public:
  String() {} String(const char *c) : s(c ? c : "") {} String(const std::string &x) : s(x) {}
  const char *c_str() const { return s.c_str(); }
  size_t length() const { return s.size(); }
};
static inline size_t strlcpy_stub(char *d, const char *s, size_t n) {
  size_t l = strlen(s); if (n) { size_t c = l >= n ? n - 1 : l; memcpy(d, s, c); d[c] = 0; } return l;
}
#define strlcpy strlcpy_stub
#define constrain(a, l, h) ((a) < (l) ? (l) : ((a) > (h) ? (h) : (a)))
