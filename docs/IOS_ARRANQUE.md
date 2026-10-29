# Kalloq iOS — arranque

Documento para empezar la app de iOS en una Mac con Claude Code. Lee también `ARQUITECTURA.md` y
`PROTOCOLO.md`; la app de Android (`apps/android`) es la referencia de comportamiento.

## Estado del proyecto (28 de octubre de 2026)

- Android 0.8.0 en pruebas internas de Google Play: cuentas, contactos, llamadas E2E, push FCM,
  integración con Telecom (llamada en espera, coche/Bluetooth), endurecimiento.
- Servidor `sc-server` en producción en `call.l8.nz` (Ubuntu 24.04). Fuente en `apps/server`; en el host
  vive en `/opt/kalloq/src/server` (no es checkout de git: se copian archivos y se compila ahí).
- Política de privacidad: `https://call.l8.nz/privacy` (`deploy/www/privacy.html`).

## Decisiones ya tomadas (no volver a preguntar)

- Todo en español: respuestas, documentación y commits descriptivos. La interfaz es ES/EN, elegible en la app.
- En la interfaz se dice **"contacto"**, nunca "amigo".
- Sin correo electrónico: la recuperación es solo con el código offline.
- Un dispositivo por cuenta (entrar en otro teléfono cierra la sesión anterior).
- Solo voz, 1 a 1. Licencia AGPL-3.0.
- Estructura `apps/<nombre>`: la app va en **`apps/ios`**.
- Identificador propuesto: `com.cim.securecall` (igual que Android), nombre visible "Kalloq".
- Nunca pegar secretos en el chat (llaves, `.p8`, contraseñas): se copian directo al servidor o a archivos locales ignorados por git.

## Stack de iOS

| Parte | Elección |
|---|---|
| Lenguaje / UI | Swift, SwiftUI. iOS 16 o más nuevo |
| Audio | WebRTC para iOS (paquete SPM, p. ej. `stasel/WebRTC`), solo audio, Opus |
| Llamadas del sistema | **CallKit** (`CXProvider`, `CXCallController`): equivalente a `SystemCall.kt` |
| Push de llamadas | **PushKit** (VoIP) por APNs |
| Cripto | CryptoKit: `Curve25519.Signing` (Ed25519), `Curve25519.KeyAgreement` (X25519), `AES.GCM`, `SHA256`, `HKDF` |
| Almacenamiento | Keychain para la identidad y el token; archivos cifrados con protección de datos para alias/verificaciones |
| QR | AVFoundation (leer) y CoreImage (generar) |
| Red | `URLSessionWebSocketTask` contra `wss://call.l8.nz/ws` |

## La capa E2E debe ser compatible byte por byte

- Handshake `Noise_KKpsk2_25519_AESGCM_SHA256`, prologue `"kalloq/call/1\0" ‖ call_id`.
- Referencia: `tools/kalloq_crypto.py` y la implementación Kotlin en `apps/android/.../crypto/`
  (`Noise.kt`, `CallCrypto.kt`, `Keys.kt`, `Verification.kt`). Los tests de Android
  (`apps/android/app/src/test/.../CryptoTest.kt`) tienen los vectores: los tests de iOS deben pasar los mismos.
- **Ojo:** la identidad (`IK`) es Ed25519 y Noise usa su forma X25519. CryptoKit no convierte
  Ed25519→X25519; hay que implementar la conversión (mapa de Edwards a Montgomery, como
  `crypto_sign_ed25519_pk_to_curve25519` de libsodium) o usar libsodium (`swift-sodium`). Verificar contra los vectores.
- Oferta: payload `{"sdp","psk","ts"}`; rechazar ofertas de más de 10 min y `call_id` ya vistos
  (últimos 200), igual que Android.
- Código de seguridad de 6 dígitos y QR de verificación `kalloq-verify:1:<uuid>:<IK>:<r>` iguales a Android.
- Mensajes seguros por `call_ice`: candidatos ICE y `{hold: true|false}` (llamada en espera).

## Cambios necesarios en el servidor

1. **APNs:** hoy `set_push` solo guarda un token FCM. Agregar plataforma (p. ej. `set_push {token, platform: "fcm"|"apns"}`
   sin romper Android) y un envío VoIP por APNs HTTP/2 con JWT ES256 (llave `.p8`, Key ID, Team ID),
   `apns-push-type: voip`, tema `com.cim.securecall.voip`. Payload sin datos de quién llama, igual que FCM.
   Documentarlo en `PROTOCOLO.md` y agregar tests en `apps/server/tests`.
2. **Universal Links:** publicar `deploy/www/.well-known/apple-app-site-association`
   (`<TeamID>.com.cim.securecall`, rutas `/a/*`) y servirlo en nginx como JSON.
3. Política de privacidad: mencionar iOS y APNs.

## Condiciones de Apple que marcan el diseño

- **Cada push VoIP debe reportarse a CallKit de inmediato** (`reportNewIncomingCall`) o iOS deja de
  entregar pushes a la app. Como el push no dice quién llama, se reporta una llamada genérica "Kalloq",
  se conecta el WebSocket, y al llegar `call_incoming` se actualiza el nombre. Si la llamada ya no existe, se termina al instante.
- El audio se activa solo en `provider(_:didActivate:)` de CallKit (configurar `RTCAudioSession` en modo manual).
- La llamada en espera llega por CallKit (`CXSetHeldCallAction`): mandar `{hold:true}` como en Android.
- El simulador no sirve para PushKit ni CallKit real: probar en un iPhone físico.

## Lo que pone el usuario (cuenta Apple Developer)

- Team ID.
- Llave APNs `.p8` + Key ID → se copia al servidor (p. ej. `/etc/kalloq/apns.p8`, permisos como `fcm.json`), nunca al repo ni al chat.
- App ID `com.cim.securecall` con Push Notifications y Associated Domains.

## Orden sugerido

1. Proyecto Xcode en `apps/ios` + `.gitignore` de Xcode; capa cripto con los vectores de Android.
2. Cliente del protocolo (registro, login, recuperación, contactos) y pantallas.
3. Llamada con WebRTC + CallKit en primer plano (sin push), probada contra Android y `tools/test_peer.py`.
4. Servidor: APNs + `set_push` con plataforma. App: PushKit.
5. Universal Links, verificación por QR, ES/EN, TestFlight.
