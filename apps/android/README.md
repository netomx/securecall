# Kalloq — Android

Kotlin + Jetpack Compose, minSdk 26, paquete `com.cim.securecall`.

```bash
# JDK 21 (el de Android Studio)
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

| Archivo | Qué hace |
|---|---|
| `net/ServerClient.kt` | Cliente WebSocket del [protocolo](../../docs/PROTOCOLO.md): petición/respuesta por `id` y flujo de eventos |
| `data/LocalStore.kt` | Token de sesión y semilla de identidad cifrados con AES-256-GCM (llave en Android Keystore); alias y llaves fijadas |
| `data/Identity.kt` | Llave de identidad Ed25519 (Tink), huella, parseo de IDs e invitaciones |
| `AppController.kt` | Vive con el proceso (no con la pantalla): sesión, reconexión con backoff, eventos, amigos, TOFU de llaves y el flujo de llamada |
| `crypto/Noise.kt` | `Noise_KKpsk2_25519_AESGCM_SHA256` y transporte con nonce explícito |
| `crypto/Keys.kt` | Ed25519 → X25519, HKDF, HMAC |
| `crypto/CallCrypto.kt` | Handshake por llamada, id del PSK, código de seguridad |
| `crypto/Verification.kt` | QR de verificación en persona y PSK del par |
| `ui/VerifyScreen.kt` | Verificar en persona: mostrar y escanear QR, código a comparar |
| `push/Push.kt` | Token FCM y servicio que despierta la app cuando llega una llamada |
| `call/CallEngine.kt` | WebRTC solo audio (Opus, DTLS-SRTP), ICE por goteo, cola de candidatos remotos |
| `call/AudioRouter.kt` | Modo comunicación, foco de audio, auricular / altavoz / Bluetooth (cambia solo al conectar o desconectar el audífono), sensor de proximidad |
| `call/CallService.kt` | Servicio en primer plano (`phoneCall` mientras suena, `+microphone` en llamada), notificación de llamada entrante con Contestar/Rechazar, en curso y perdida |
| `call/CallTones.kt` | Timbre + vibración al recibir, tono de llamada al marcar |
| `ui/` | Pantallas Compose: acceso, código de recuperación, contactos, agregar amigo (QR), contacto, ajustes |

- Sin respaldo en la nube (`allowBackup=false`, `data_extraction_rules.xml`): llaves y token nunca salen del teléfono.
- La pantalla del código de recuperación usa `FLAG_SECURE` (no se puede capturar).
- Idioma por app (ES/EN) con `AppCompatDelegate.setApplicationLocales`.
- Invitaciones: `https://call.l8.nz/a/<uuid>` y `kalloq://add/<uuid>`.

## Push

Para recibir llamadas con la app cerrada, poner el `google-services.json` del proyecto Firebase en
`app/` antes de compilar (ver `deploy/README.md`). Sin él la app compila y funciona, y Ajustes →
"Recibir llamadas" dice "no disponible".
