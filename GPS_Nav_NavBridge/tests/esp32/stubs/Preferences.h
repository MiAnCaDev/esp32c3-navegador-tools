#pragma once
#include "Arduino.h"
class Preferences {
public:
  static std::map<std::string, std::string> &db() { static std::map<std::string, std::string> d; return d; }
  bool begin(const char *, bool) { return true; }
  bool getBool(const char *k, bool d) { auto i = db().find(k); return i == db().end() ? d : i->second == "1"; }
  void putBool(const char *k, bool v) { db()[k] = v ? "1" : "0"; }
  String getString(const char *k, const char *d) { auto i = db().find(k); return String(i == db().end() ? d : i->second.c_str()); }
  void putString(const char *k, const char *v) { db()[k] = v; }
  uint32_t getUInt(const char *k, uint32_t d) { auto i = db().find(k); return i == db().end() ? d : (uint32_t)atoll(i->second.c_str()); }
  void putUInt(const char *k, uint32_t v) { db()[k] = std::to_string(v); }
  uint8_t getUChar(const char *k, uint8_t d) { return (uint8_t)getUInt(k, d); }
  void putUChar(const char *k, uint8_t v) { putUInt(k, v); }
  void remove(const char *k) { db().erase(k); }
};
