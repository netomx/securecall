# Kalloq iOS — protocolo v3 y videollamadas

Instrucciones para la sesión de iOS (11 de noviembre de 2026). La referencia es Android 0.9.0 (commit
`aff13cc`); el protocolo está en `PROTOCOLO.md` (sección **Versiones** y **Llamadas**). Este documento dice qué
cambia en iOS y cómo probarlo.

## Estado

- **Servidor en producción ya es v3** (`hello.v = 3`): `call_ice` acepta hasta 8 KiB. No hace falta tocar el
  servidor.
- **Android 0.9.0** ya tiene videollamadas y protocolo v3. Todavía no está en Play.
- **iOS hoy es v2** (solo voz). Las llamadas con Android 0.9.0 siguen funcionando: Android ve que iOS no manda
  `proto` y muestra "Video no disponible: X usa una versión anterior de Kalloq".
- **Aviso de privacidad** ya actualizado: la cámara se usa en videollamadas en Android y en iPhone.
- **Decisión que cambia**: ya no es "solo voz". Ahora es voz y video, 1 a 1.

## Qué es el protocolo v3

Un solo número de versión. Cada app lo manda **dentro** de la oferta y la respuesta cifradas:

- Oferta (`call.msg`, Noise mensaje 1): `{"sdp", "psk", "ts", "proto": 3, "video": true|false}`.
- Respuesta (`call_answer.msg`, Noise mensaje 2): `{"sdp", "proto": 3}`.
- Sin `proto` = la otra app es v2. Cada función tiene una versión mínima; hoy solo video (`VIDEO_SINCE = 3`).
  Si el otro no la alcanza, la llamada sigue siendo de voz, la cámara queda desactivada y la pantalla lo dice.

En Android: `PROTOCOL`, `VIDEO_SINCE` y `peerProto()` al final de `AppController.kt`; `CallUi.peerVideo`.

## Tareas en iOS

### 1. Versión y video en la oferta y la respuesta
- Mandar `"proto": 3` en la oferta y la respuesta; leer el `proto` del otro (si falta, 2).
- Oferta: `"video": true` si quien llama empezó con cámara.
- Al recibir: `video` decide si suena como "Videollamada entrante".

### 2. Toda llamada lleva una sección de video
- En `CallEngine.swift`, agregar una pista de video **en toda llamada**, con `addTrack` (no `addTransceiver`)
  **antes** de crear la oferta y, del lado que contesta, **antes** de `setRemoteDescription`, para que tome la
  sección de video de la oferta. Una oferta sin video (app v2) la deja sin usar y la llamada es de voz.
- La pista empieza deshabilitada; la cámara solo captura cuando se prende. Prender o apagar **no renegocia**.
- Quitar las restricciones `OfferToReceiveVideo: false` (con Unified Plan deciden las pistas).
- Fábrica: ya usa `RTCDefaultVideoEncoderFactory` / `RTCDefaultVideoDecoderFactory`.
- Cámara: `RTCCameraVideoCapturer`, frontal por omisión, con botón para cambiar a la trasera.
- Video remoto: el `RTCVideoTrack` del receptor de video, en un `RTCMTLVideoView` (aspect fill).
- Referencia: `CallEngine.kt` (`open`, `setCamera`, `switchCamera`, `publishRemoteVideo`).

### 3. Mensajes cifrados nuevos en `call_ice`
Mismo canal que `hold` y `restart` (mismo cifrado de transporte):
- `{"camera": true|false, "video_max": bps}`.
  - `camera`: si mandamos imagen. Si el otro manda `false`, se muestra su inicial en vez del video.
  - `video_max`: lo más que queremos **recibir**. El otro limita su video a eso.
- Se mandan en cuanto hay transporte (después de contestar / al recibir la respuesta) y cada vez que cambian.
  No se mandan a una app v2. Android solo los manda si cambian (`sentVideo`).
- Al recibir `video_max`: acotarlo a 100000–1500000 y poner en el emisor de video
  `maxBitrateBps = min(calidad propia, video_max del otro)` (`RTCRtpSender.parameters.encodings`).
- Ignorar `camera`/`video_max` de una app que no es v3.
- Referencia: `applyVideo()` y `sendVideoState()` en `AppController.kt`.

### 4. Calidad de video (Ajustes)
- Tres opciones, mismos textos que Android:
  - **Automática**: "Alta en Wi-Fi, ahorro de datos en datos móviles".
  - **Alta**: "Hasta 720p, unos 1.5 Mbps". 1500000 bps, captura 1280×720 a 30 fps.
  - **Ahorro de datos**: "Imagen más pequeña, unos 256 kbps". 200000 bps de video, captura 640×360.
- Automática: `NWPathMonitor`; `path.isExpensive` (datos móviles o hotspot) = ahorro. Al cambiar de red a media
  llamada, recalcular y mandar el `video_max` nuevo.

### 5. Cámara y ciclo de vida
- Pausar la cámara cuando la app no está activa (iOS no deja usarla en segundo plano) y mandar `camera: false`;
  al volver, reanudar y mandar `camera: true`. Picture in picture puede venir después.
- Al contestar desde CarPlay, el reloj o los audífonos (CallKit), contestar **solo voz**; la cámara se prende
  desde la pantalla.
- En espera (`hold`): sin cámara y sin video del otro.

### 6. Audio
- Con la cámara encendida: altavoz (salvo Bluetooth o audífonos) y modo `.videoChat` en `AVAudioSession`; sin
  sensor de proximidad. Al apagar la cámara no se cambia la ruta.

### 7. CallKit
- `CXProviderConfiguration.supportsVideo = true` (hoy es `false` en `SystemCall.swift`).
- `CXCallUpdate.hasVideo` = el `video` de la oferta (entrantes) o el tipo de llamada (salientes).
- El push VoIP llega antes de saber si es video: reportar como voz y actualizar `hasVideo` con
  `reportCall(with:updated:)` cuando llegue `call_incoming` y se descifre la oferta.

### 8. Pantallas (igual que Android, ver `CallScreen.kt`)
- Contactos y detalle del contacto: botón **Videollamada** junto a **Llamar**.
- Entrante con video: **Rechazar · Solo voz · Con video**.
- En llamada: video del otro a pantalla completa; el nuestro en un recuadro arriba a la derecha (~104×156 pt,
  espejo con la cámara frontal) con botón de cambiar cámara; antes de que contesten, nuestra cámara a pantalla
  completa. Botones: Silenciar · Cámara · Altavoz · Bluetooth, y Colgar.
- Sobre video, nombre y estado alineados a la izquierda con sombra; el código de seguridad se oculta.
- Si el otro es v2: banda "Video no disponible: %@ usa una versión anterior de Kalloq" / "Video not available:
  %@ is using an older version of Kalloq", y el botón de cámara desactivado.
- `NSCameraUsageDescription`: mencionar videollamadas, p. ej. "Kalloq usa la cámara para las videollamadas y
  para leer los códigos QR de tus contactos."

### 9. Textos (ES / EN, como Android)
| Clave Android | Español | English |
|---|---|---|
| `video_call` | Videollamada | Video call |
| `call_incoming_video` | Videollamada entrante | Incoming video call |
| `call_answer_video` | Con video | With video |
| `call_answer_voice` | Solo voz | Voice only |
| `call_camera` | Cámara | Camera |
| `call_switch_camera` | Cambiar cámara | Switch camera |
| `err_camera` | Kalloq necesita la cámara para el video | Kalloq needs the camera for video |
| `video_quality` | Calidad de video | Video quality |

## Pruebas

1. iOS ↔ Android 0.9.0 (pedir el APK de prueba a la sesión de Windows): video en los dos sentidos, contestar
   con video y solo voz, cámara prendida/apagada, cambiar cámara, salir de la app y volver.
2. Calidad: en Ahorro de datos el otro debe recibir ~200 kbps de video; en Alta, hasta 1.5 Mbps.
3. Compatibilidad: iOS v3 con un Android 0.8.0 (v2) → banda de "versión anterior", llamada de voz normal.
4. Reconexión: cambiar de Wi-Fi a datos a media videollamada; el ICE restart con video mide ~4.7 KiB y
   el servidor ya lo acepta.
5. Push: con Kalloq cerrada, una videollamada entrante debe sonar (CallKit) y mostrar video al contestar.

No hay cambios de servidor en esta fase. Al terminar: commit en la Mac y avisar a la sesión de Windows.
