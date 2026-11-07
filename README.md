# dron-mavicair-telemetry

App Android (Kotlin, DJI Mobile SDK 4.18) para el **DJI Mavic Air (WM230)**: video en vivo,
telemetría, Virtual Stick, navegación GPS, misiones Waypoint y envío de todo a la PC
(proyecto separado **drone-ground-station**, FastAPI).

```
Mavic Air ──► Control remoto ──USB──► esta app ──Wi-Fi──► PC (FastAPI + panel web)
```

## Estructura

```
com.diegorivas.drone/
├── DroneApplication.kt            Helper.install() (sin cambios)
├── MainActivity.kt                Sólo interfaz: botones, estados, diálogos
├── data/
│   ├── model/                     RoutePoint + FlightRoute, TelemetryData, FlightEvent, FlightSession
│   └── repository/
│       ├── RouteRepository.kt     Ruta A→B→A desde la posición real del dron / ruta del panel
│       └── FlightRepository.kt    Un evento = CSV local + servidor PC
├── dji/
│   ├── DjiConnectionManager.kt    Registro y conexión (lógica igual que antes)
│   ├── TelemetryManager.kt        KeyManager: GPS, altitud, velocidad, rumbo, batería, satélites, modo
│   ├── FlightControllerManager.kt Despegue, aterrizaje, modo de navegación (VS vs Waypoint)
│   ├── VirtualStickController.kt  Comandos 100 ms, movimientos por tiempo, goTo GPS
│   ├── WaypointMissionManager.kt  Builder → loadMission → uploadMission → startMission
│   └── VideoManager.kt            VideoFeeder → pantalla + archivo + PC
├── network/
│   ├── ApiClient.kt               HTTP (HttpURLConnection, sin librerías extra)
│   ├── TelemetryApi.kt            Telemetría 2 Hz + eventos, con cola si no hay Wi-Fi
│   ├── VideoStreamClient.kt       Video en vivo por bloques al panel
│   └── RecorridoClient.kt         Reserva el tramo (REC) y lo sube a la PC (STOP)
├── recording/
│   ├── TelemetryLogger.kt         CSV local (mismo formato)
│   ├── TramoRecorder.kt           Carpeta de cada tramo (video + telemetría + meta)
│   └── VideoRecorder.kt           .h264 local (respaldo)
└── utils/
    ├── GeoUtils.kt                Distancia, rumbo, destino, error N/E, llegada, distancia recorrida
    └── FlightValidator.kt         Batería, GPS, límites de ruta
```

## Qué se conservó tal cual

- `Helper.install()` en `DroneApplication`.
- Registro DJI → `startConnectionToProduct()`, fallback del `FlightController`.
- Despegue: `startTakeoff()` → esperar 0.9 m (timeout 15 s) → estabilizar 2 s → comando 0.
- Virtual Stick: `VELOCITY / VELOCITY / ANGULAR_VELOCITY / BODY`, envío cada 100 ms, `TEST_SPEED = 0.25`.
- Pruebas 2 a 6, STOP (cinco comandos 0), formato CSV de telemetría, log en pantalla y `dji_errors.log`.
- API Key y versiones de AGP / SDK / dependencias.

## Novedades

| Botón | Qué hace |
|---|---|
| **ATERRIZAR** | Ya existía en el XML pero no tenía código. `startLanding()` + `confirmLanding()` automático cerca del suelo. Pide confirmación. |
| **1. ACTIVAR CONTROL** | Ahora también **desactiva** (devuelve el control al mando). |
| **7. GPS A → B → A (3 m)** | Virtual Stick con corrección por GPS del dron: va a 3 m en la dirección del morro y vuelve. Aborta y queda en hover si pierde GPS, si tarda > 30 s o si se **aleja** del objetivo. |
| **WP: CARGAR A→B→A (5 m)** | Misión Waypoint de prueba desde la posición actual (3 m de altura, 1 m/s). |
| **WP: CARGAR RUTA DEL PANEL** | Descarga la ruta activa dibujada en el panel web. |
| **WP: SUBIR / INICIAR / DETENER** | Flujo DJI. Antes de iniciar se desactiva Virtual Stick. Al terminar queda en hover (`NO_ACTION`). |
| **● REC / ■ STOP** | Un **tramo** del recorrido abierto en la PC: carpeta con `video.h264`, `telemetria.csv` (formato de inspeccion-dron) y `meta.json`. Al pulsar STOP se sube sola a la PC. Durante la grabación también se ve en vivo en el panel. |
| **REENVIAR TRAMOS PENDIENTES** | Tramos que no llegaron a la PC (sin Wi-Fi o sin recorrido abierto). |
| **PC: …** | IP del servidor (se guarda). |

Telemetría en pantalla: ahora incluye satélites, nivel GPS y modo de vuelo.
Si el despegue falla, el log muestra `code`, descripción y el estado del dron (modo, motores, GPS, batería…).

**Virtual Stick y Waypoint nunca a la vez:** `FlightControllerManager` guarda el modo activo
(`IDLE / VIRTUAL_STICK / WAYPOINT`) y rechaza el otro.

## ⚠ Verificar antes de usar la navegación GPS (botón 7)

En `VirtualStickController.kt` hay `VELOCITY_AXES_SWAPPED = false` (igual que tu código actual:
`pitch` = adelante). Varios desarrolladores reportan que en MSDK 4 con modo `VELOCITY` los ejes van cruzados.

1. Despega y pulsa **4. AVANZAR 1 s**.
2. Si el dron va **hacia adelante** → está bien.
3. Si se mueve **de lado** → cambia a `VELOCITY_AXES_SWAPPED = true` y recompila.

La protección de divergencia detiene el botón 7 si el mapeo es incorrecto, pero haz esta prueba primero.

## Orden de pruebas recomendado

1. Sin hélices: conexión, telemetría, video, PC conectada, REC.
2. Zona abierta y autorizada, control remoto en mano: activar control → despegar → STOP → aterrizar.
3. Pruebas 4/5/6 por tiempo (y verificar el mapeo de ejes).
4. Con buen GPS (≥ 8 satélites, nivel ≥ 3): botón 7.
5. Waypoint: cargar → subir → iniciar → observar → STOP si hace falta → aterrizar.

## Compilar

Abrir en Android Studio (**File → Open**) o:

```
gradlew assembleDebug
gradlew testDebugUnitTest      # tests de GeoUtils y FlightValidator
```

El plugin `org.jetbrains.kotlin.android` sigue comentado a propósito: AGP 9 compila Kotlin de forma integrada.

## Archivos en el teléfono

`Android/data/com.diegorivas.drone/files/`
- `telemetry/vuelo_YYYYMMDD_HHMMSS.csv`
- `tramos/tramo_YYYYMMDD_HHMMSS/` → `video.h264`, `telemetria.csv`, `meta.json`, `estado.json`
- `dji_errors.log`
