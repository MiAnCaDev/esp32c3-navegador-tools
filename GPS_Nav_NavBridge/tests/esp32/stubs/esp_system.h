#pragma once
extern uint32_t g_rand;
inline uint32_t esp_random() { return g_rand; }
