# Guía de desarrollo — miliMusic (Windows)

Esta guía describe cómo configurar un entorno local para compilar, probar y empaquetar **miliMusic** en Windows x64.

## Requisitos previos

- **JDK 21** (por ejemplo, Eclipse Temurin 21).
- **Visual Studio Build Tools / MSVC x64** (para compilar el helper de inicio de sesión WebView2 y la DLL del puente SMTC).
- **WiX Toolset 3.x** (necesario únicamente para generar paquetes `.msi` y `.exe`).
- **7-Zip** (`7z`) si deseas extraer libVLC automáticamente siguiendo el mismo flujo del CI.

## Estructura de módulos

- `:innertube`: Biblioteca Kotlin JVM para comunicación con YouTube Music.
- `:desktop`: Aplicación Windows con Compose Multiplatform, SQLite, libVLC y helpers nativos en `desktop/native/`.

## Compilación de helpers nativos (opcional en desarrollo, requerida para empaquetado completo)

Los binarios nativos se colocan en `desktop/resources/windows/` (carpeta ignorada por Git y empaquetada automáticamente por Compose Desktop):

- `milimusic-login.exe` y `WebView2Loader.dll` (desde `desktop/native/webview2-login/main.cpp` y el paquete NuGet `Microsoft.Web.WebView2`).
- `milimusic_smtc.dll` (desde `desktop/native/smtc-bridge/smtc_bridge.cpp`).
- `vlc/` con `libvlc.dll`, `libvlccore.dll` y plugins de audio de libVLC 3.0.21 win64.

El workflow `.github/workflows/build-windows.yml` contiene los comandos exactos de PowerShell y `cl` para generar todos estos recursos automáticamente.

## Pruebas y ejecución

```powershell
# Ejecutar pruebas unitarias de ambos módulos
./gradlew :innertube:test :desktop:test

# Iniciar la aplicación en local
./gradlew :desktop:run
```

## Generación de paquetes de Windows

```powershell
# Instalador EXE
./gradlew :desktop:packageExe

# Instalador MSI
./gradlew :desktop:packageMsi

# Carpeta distribuible (para versión portable ZIP)
./gradlew :desktop:createDistributable
```

Los artefactos generados se ubican en `desktop/build/compose/binaries/main/`.
