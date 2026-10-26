#pragma once
/*
  VALORES POR DEFECTO DE LA APP DE LACTANCIA  (edita SOLO este fichero)

  Son los valores que usa el ESP32 si desde la app del movil no se ha guardado otros. Los de la app tienen
  prioridad y se guardan en la memoria del ESP32 (sobreviven a reinicios); "Restaurar valores por defecto"
  en la app los borra y vuelve a usar los de aqui.
*/
#include <Arduino.h>

// Redes WiFi, en orden de preferencia: { "NOMBRE_RED", "CONTRASEÑA" }
// (solo 2,4 GHz). Para una red abierta deja la clave vacía: { "Cafeteria", "" }
struct WifiCred { const char *ssid; const char *pass; };
static const WifiCred WIFI_LIST[] = {
  { "TU_WIFI",    "TU_CLAVE"   },
  { "OtraRed",    "otraClave"  },
  { "MovilMiki",  "claveMovil" },
};
#define API_BASE   "CAMBIA_ESTA_URL"
#define API_TOKEN  "CAMBIA_ESTE_TOKEN"
#define POLL_MS    60000                 // cada cuanto se consulta el estado en reposo

// Certificado RAIZ de tu servidor (formato PEM) para que el ESP32 COMPRUEBE que habla con él y no con alguien que
// controle la red WiFi. Vacio = cifra pero NO comprueba el certificado (menos seguro; es como estaba).
// Como obtenerlo: abre la URL de la API en un navegador > candado > certificado > ruta de certificacion > el de arriba
// > exportar como PEM. (Para Let's Encrypt es "ISRG Root X1".) Pegalo asi, linea a linea:
//   #define API_CA_CERT \
//     "-----BEGIN CERTIFICATE-----\n" \
//     "MIIFazCCA1OgAwIBAgIRAIIQz7DSQONZRGPgu2OCiwAwDQYJKoZIhvcNAQELBQAw\n" \
//     "...\n" \
//     "-----END CERTIFICATE-----\n"
// Con certificado, el ESP32 sincroniza la hora por internet (NTP) antes de conectar: la necesita para validar fechas.
#define API_CA_CERT ""
