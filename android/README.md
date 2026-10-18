# Sotspart para Android

Navegador sencillo (basado en el motor Chromium del teléfono) que abre **sotspart.lat**.

## Instalar
1. En el teléfono abre la página *Releases* del repositorio y descarga `Sotspart.apk`
   (etiqueta `apk-latest`).
2. Ábrelo y permite "Instalar apps de origen desconocido" cuando Android lo pida.

## Botones (arriba a la derecha)
| Botón | Qué hace |
|---|---|
| ⟲ | Gira la pantalla (vertical ↔ horizontal) y la deja fija |
| 🔓 / 🔒 | Libera el giro automático / fija la orientación actual |
| ✋ | Bloquea los toques (el video sigue). Para desbloquear, toca la pantalla y **mantén presionado** el candado |
| 🌐 | Traduce la página a Español o Inglés (Google Translate) |
| CC | Elige subtítulos del video: Español, Inglés, apagados. Incluye la guía para subtítulos en vivo |
| ⋮ | Recargar, inicio, pantalla completa, bloqueo de anuncios, fijar app (anclar), cambiar dirección |
| ✕ | Oculta la barra (☰ la vuelve a mostrar) |

## Audio en inglés con subtítulos en español
- Si el video trae pista de subtítulos en español, la app la activa sola (CC → Español).
- Si no la trae, usa **Subtítulos instantáneos (Live Caption)** de Android con la opción
  **Traducir → Español** (botón de volumen → ícono de subtítulos). La traducción en vivo
  depende del teléfono (p. ej. Pixel 6 o más nuevo).

## Compilar
GitHub Actions (`.github/workflows/android-apk.yml`) genera el APK en cada cambio dentro de `android/`.
Localmente: `cd android && gradle assembleRelease` (requiere Android SDK).
