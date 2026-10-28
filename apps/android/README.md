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
| `AppViewModel.kt` | Sesión, reconexión con backoff, eventos del servidor, amigos, TOFU de llaves |
| `ui/` | Pantallas Compose: acceso, código de recuperación, contactos, agregar amigo (QR), contacto, ajustes |

- Sin respaldo en la nube (`allowBackup=false`, `data_extraction_rules.xml`): llaves y token nunca salen del teléfono.
- La pantalla del código de recuperación usa `FLAG_SECURE` (no se puede capturar).
- Idioma por app (ES/EN) con `AppCompatDelegate.setApplicationLocales`.
- Invitaciones: `https://call.l8.nz/a/<uuid>` y `kalloq://add/<uuid>`.
