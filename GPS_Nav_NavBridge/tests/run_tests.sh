#!/usr/bin/env bash
# Lanza los tests del proyecto.   Uso:  ./run_tests.sh [esp32 | kotlin | all]      (por defecto: all)
#   esp32  -> logica del firmware compilada y ejecutada en el PC con g++ (no necesita la placa)
#   kotlin -> tests JUnit de la app, con Gradle (necesita el SDK de Android, como Android Studio)
# Sale con codigo distinto de 0 si alguno falla.
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$HERE/.."
what="${1:-all}"
rc=0

run_esp32() {
  cd "$HERE/esp32" || return 1
  command -v g++ >/dev/null 2>&1 || { echo "Falta g++ (Linux/macOS: instalar build-essential/Xcode CLT; Windows: MSYS2/MinGW o WSL)"; return 1; }
  if [ ! -f ArduinoJson.h ]; then
    if [ -n "${ARDUINOJSON_H:-}" ] && [ -f "$ARDUINOJSON_H" ]; then
      cp "$ARDUINOJSON_H" ArduinoJson.h           # p. ej. ARDUINOJSON_H=~/Arduino/libraries/ArduinoJson/src/ArduinoJson.h
    else
      echo "Descargando ArduinoJson 6.21.2 (cabecera unica)..."
      curl -fsSL -o ArduinoJson.h https://github.com/bblanchon/ArduinoJson/releases/download/v6.21.2/ArduinoJson-v6.21.2.h \
        || { echo "No se pudo descargar ArduinoJson.h. Copialo en tests/esp32/ o define ARDUINOJSON_H=/ruta/ArduinoJson.h"; return 1; }
    fi
  fi
  mkdir -p build
  echo "=== firmware: DeviceConfig.h (ajustes, emparejamiento, bloqueo) ==="
  g++ -std=c++17 -Wall -Wno-unused-function -Istubs -I. -o build/test_deviceconfig test_deviceconfig.cpp || return 1
  out="$(build/test_deviceconfig)"; st=$?
  echo "$out" | grep -v '^ok'
  echo "$(echo "$out" | grep -c '^ok') comprobaciones correctas"
  [ $st -ne 0 ] && return 1
  echo
  echo "=== firmware: PhoneLink.h (parser de tramas Bluetooth, fuzzing) ==="
  if g++ -std=c++17 -fsanitize=address,undefined -g -Istubs_fuzz -o build/fuzz_phonelink fuzz_phonelink.cpp 2>/dev/null; then
    echo "(compilado con AddressSanitizer + UBSan)"
  else
    echo "(sin sanitizers: este compilador no los soporta; la prueba es menos estricta)"
    g++ -std=c++17 -g -Istubs_fuzz -o build/fuzz_phonelink fuzz_phonelink.cpp || return 1
  fi
  build/fuzz_phonelink || return 1
}

run_kotlin() {
  cd "$ROOT" || return 1
  [ -x ./gradlew ] || chmod +x ./gradlew 2>/dev/null
  echo "=== app: tests JUnit (inclinacion, curvas, viajes, GPX/CSV) ==="
  ./gradlew :app:testDebugUnitTest --console=plain
}

case "$what" in
  esp32)  run_esp32 || rc=1 ;;
  kotlin) run_kotlin || rc=1 ;;
  all)    run_esp32 || rc=1; echo; run_kotlin || rc=1 ;;
  *) echo "Uso: $0 [esp32|kotlin|all]"; exit 2 ;;
esac
echo
[ $rc -eq 0 ] && echo "TODOS LOS TESTS OK" || echo "HAY TESTS QUE FALLAN"
exit $rc
