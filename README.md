# dron-mavicair-telemetry

Etapa 2: video en vivo del DJI Mavic Air (WM230) usando DJI Mobile SDK 4.18.

## Cómo usarlo

1. Descomprime este zip **sobre tu carpeta local del proyecto**, reemplazando lo existente
   (o como base si vas a crear el repo `dron-mavicair-telemetry` desde cero).
2. Abre la carpeta en Android Studio (**File → Open**, no "New Project").
3. En `app/src/main/AndroidManifest.xml`, reemplaza `TU_API_KEY_DJI_AQUI` con la misma
   API Key de developer.dji.com que ya usaste cuando lograste `REGISTRATION SUCCESS`.
4. Sincroniza Gradle. Si Android Studio sugiere otra versión de AGP/Kotlin al abrir,
   acepta el ajuste automático (están en `build.gradle.kts` raíz).
5. Conecta: teléfono → USB → control remoto → dron encendido.
6. Compila e instala:
   ```
   adb install -r -t app/build/outputs/apk/debug/app-debug.apk
   ```
7. Deberías ver en pantalla:
   - Arriba a la izquierda: `REGISTRATION SUCCESS` → `DRONE CONNECTED: ...`
   - En el resto de la pantalla: el video en vivo de la cámara del dron.

## Si algo no compila

`DJICodecManager` puede tener nombres de método ligeramente distintos según el build
exacto del SDK 4.18. Hay una nota en `MainActivity.kt` señalando dónde comparar contra
tu copia local de `VideoFeederView.java` (en el sample code de DJI que ya descargaste)
si Android Studio marca algún método en rojo.

## Siguiente paso sugerido

Una vez que el video se vea, el siguiente archivo que te paso agregará lectura de
telemetría básica (batería, GPS, altitud) en paralelo al video.
