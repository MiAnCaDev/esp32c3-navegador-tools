@echo off
rem Lanza los tests del proyecto en Windows.   Uso:  run_tests.bat [esp32 | kotlin | all]     (por defecto: all)
rem   esp32  -> necesita g++ en el PATH (MSYS2/MinGW) y curl (viene con Windows 10/11)
rem   kotlin -> necesita el SDK de Android (el que usa Android Studio)
setlocal
set HERE=%~dp0
set WHAT=%1
if "%WHAT%"=="" set WHAT=all
set RC=0
if /i "%WHAT%"=="esp32" goto esp32
if /i "%WHAT%"=="kotlin" goto kotlin
if /i "%WHAT%"=="all" goto esp32
echo Uso: %0 [esp32^|kotlin^|all]
exit /b 2

:esp32
pushd "%HERE%esp32"
where g++ >nul 2>nul
if errorlevel 1 ( echo Falta g++ en el PATH ^(instala MSYS2/MinGW^) & set RC=1 & popd & goto after_esp32 )
if not exist ArduinoJson.h (
  if defined ARDUINOJSON_H ( copy /y "%ARDUINOJSON_H%" ArduinoJson.h >nul ) else (
    echo Descargando ArduinoJson 6.21.2...
    curl -fsSL -o ArduinoJson.h https://github.com/bblanchon/ArduinoJson/releases/download/v6.21.2/ArduinoJson-v6.21.2.h
  )
)
if not exist ArduinoJson.h ( echo No se pudo obtener ArduinoJson.h: copialo en tests\esp32 o define ARDUINOJSON_H & set RC=1 & popd & goto after_esp32 )
if not exist build mkdir build
echo === firmware: DeviceConfig.h ^(ajustes, emparejamiento, bloqueo^) ===
g++ -std=c++17 -Wall -Wno-unused-function -Istubs -I. -o build\test_deviceconfig.exe test_deviceconfig.cpp
if errorlevel 1 ( set RC=1 & popd & goto after_esp32 )
build\test_deviceconfig.exe > build\deviceconfig.txt
set ST=%errorlevel%
findstr /v /b "ok:" build\deviceconfig.txt
if not "%ST%"=="0" set RC=1
echo.
echo === firmware: PhoneLink.h ^(parser de tramas Bluetooth, fuzzing^) ===
g++ -std=c++17 -fsanitize=address,undefined -g -Istubs_fuzz -o build\fuzz_phonelink.exe fuzz_phonelink.cpp 2>nul
if errorlevel 1 (
  echo ^(sin sanitizers: este compilador no los soporta; la prueba es menos estricta^)
  g++ -std=c++17 -g -Istubs_fuzz -o build\fuzz_phonelink.exe fuzz_phonelink.cpp
)
build\fuzz_phonelink.exe
if errorlevel 1 set RC=1
popd
:after_esp32
if /i "%WHAT%"=="esp32" goto fin

:kotlin
echo.
echo === app: tests JUnit ^(inclinacion, curvas, viajes, GPX/CSV^) ===
pushd "%HERE%.."
call gradlew.bat :app:testDebugUnitTest --console=plain
if errorlevel 1 set RC=1
popd

:fin
echo.
if "%RC%"=="0" ( echo TODOS LOS TESTS OK ) else ( echo HAY TESTS QUE FALLAN )
exit /b %RC%
