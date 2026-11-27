<div align="center">

<img src="desktop/src/main/resources/icons/milimusic.png" alt="miliMusic icon" width="160" />

# miliMusic

### Cliente de YouTube Music para Windows

</div>

**miliMusic** es un cliente de escritorio para YouTube Music desarrollado en Kotlin con Compose Multiplatform, enfocado en Windows x64, integración nativa del sistema y accesibilidad.

## Estructura del repositorio

- `desktop/`: Aplicación de escritorio para Windows (Compose Multiplatform, reproducción con libVLC, SQLite local, DataStore, accesibilidad, helpers nativos en C++ para inicio de sesión con WebView2 y controles multimedia SMTC).
- `innertube/`: Cliente JVM en Kotlin para la API de YouTube Music (InnerTube).
- `.github/workflows/build-windows.yml`: Pipeline de GitHub Actions para compilar los helpers nativos, empaquetar libVLC (solo audio) y generar los paquetes `.exe`, `.msi` y `.zip` portable.

## Características principales

- Reproducción de audio desde YouTube Music mediante libVLC con ecualizador paramétrico y gestión de descargas/caché local.
- Integración nativa con Windows: controles multimedia del sistema (SMTC), instancia única e inicio de sesión con cuenta de YouTube Music mediante WebView2.
- Biblioteca local en SQLite, sincronización de cuenta, historial, estadísticas y copias de seguridad/restauración.
- Letras sincronizadas, traducción y romanización de letras, y tarjetas de letras.
- Integraciones adicionales: Listen Together, reconocimiento de música, Last.fm y Discord Rich Presence.
- Tema Material 3 con soporte de colores dinámicos y accesibilidad para lectores de pantalla.

## Requisitos de desarrollo

- **JDK 21** (Temurin u otro OpenJDK 21).
- **Windows x64** con **MSVC (Build Tools / Visual Studio)** para compilar los helpers nativos (`desktop/native/webview2-login` y `desktop/native/smtc-bridge`).
- **WiX Toolset 3.x** para generar instaladores `.msi` y `.exe`.

## Comandos principales

```powershell
# Ejecutar pruebas
./gradlew :innertube:test :desktop:test

# Ejecutar la aplicación en modo desarrollo
./gradlew :desktop:run

# Empaquetar para Windows
./gradlew :desktop:packageExe
./gradlew :desktop:packageMsi
./gradlew :desktop:createDistributable
```

Consulta [development_guide.md](development_guide.md) para los pasos detallados de compilación de los binarios nativos y empaquetado local.

## Licencia

Distribuido bajo la licencia **GNU General Public License v3.0**. Consulta [LICENSE](LICENSE) para más detalles.
