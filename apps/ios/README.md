# Kalloq — iOS

Swift + SwiftUI, iOS 16 o más nuevo, identificador `com.cim.securecall`. Plan completo en
[`docs/IOS_ARRANQUE.md`](../../docs/IOS_ARRANQUE.md); la app de [Android](../android) es la
referencia de comportamiento.

```bash
# Pruebas de la capa de cifrado (corren en la Mac, sin simulador)
cd KalloqCore && swift test

# Compilar y abrir en el simulador
xcodebuild -project Kalloq.xcodeproj -scheme Kalloq \
  -destination 'platform=iOS Simulator,name=iPhone 16e' build
```

| Archivo | Qué hace |
|---|---|
| `KalloqCore/` | Paquete Swift sin UIKit ni CallKit, para que `swift test` corra los vectores en la Mac |
| `KalloqCore/.../Noise.swift` | `Noise_KKpsk2_25519_AESGCM_SHA256` y transporte con nonce explícito |
| `KalloqCore/.../Keys.swift` | Ed25519 → X25519, DH, HKDF, HMAC, base64url |
| `KalloqCore/.../Field25519.swift` | Aritmética módulo 2²⁵⁵−19, solo para convertir la llave Ed25519 a X25519 |
| `KalloqCore/.../CallCrypto.swift` | Handshake por llamada, id del PSK, código de seguridad |
| `KalloqCore/.../Verification.swift` | QR de verificación en persona y PSK del par |
| `KalloqCore/.../ServerClient.swift` | Cliente WebSocket del [protocolo](../../docs/PROTOCOLO.md): petición/respuesta por `id` y flujo de eventos |
| `KalloqCore/.../JSONValue.swift` | Los mensajes del protocolo, sueltos como en el servidor |
| `KalloqCore/.../Identity.swift` | Llave Ed25519, huella, parseo de IDs e invitaciones |
| `Kalloq/Store.swift` | Token y semilla en el Keychain; alias y llaves fijadas en un archivo protegido |
| `Kalloq/AppController.swift` | Vive con la app: sesión, reconexión con backoff, eventos, contactos y TOFU de llaves |
| `Kalloq/AuthView.swift` | Acceso, creación de cuenta y recuperación con el código offline |
| `Kalloq/RecoveryCodeView.swift` | El código de recuperación, que solo se muestra una vez |
| `Kalloq/ContactsView.swift` | Solicitudes, contactos y estado de la conexión |
| `Kalloq/ContactDetailView.swift` | Nombre local, ID, huella, aviso de llave cambiada, eliminar y bloquear |
| `Kalloq/AddContactView.swift` | Tu QR y tu enlace; agregar pegando un ID o enlace |
| `Kalloq/SettingsView.swift` | Cuenta, seguridad, cerrar sesión y eliminar cuenta |
| `Kalloq/CallEngine.swift` | WebRTC solo audio (Opus, DTLS-SRTP), ICE por goteo, cola de candidatos remotos |
| `Kalloq/SystemCall.swift` | CallKit: pantalla de llamada del sistema, espera, y el audio que solo arranca en `didActivate` |
| `Kalloq/CallController.swift` | Una llamada a la vez: handshake Noise, ICE cifrado, silenciar, altavoz, colgar |
| `Kalloq/CallView.swift` | Pantalla de llamada con el código de seguridad de 6 dígitos |
| `Kalloq/SelfCheck.swift` | Los vectores de referencia, para comprobarlos desde Ajustes |

## Compatibilidad con Android

Cada valor del handshake tiene que salir byte por byte igual que en Android, o un iPhone y un
Android no pueden negociar una llamada. Los vectores son los de
[`tools/kalloq_crypto.py`](../../tools/kalloq_crypto.py), los mismos que verifica
`apps/android/app/src/test/java/com/cim/securecall/crypto/CryptoTest.kt`, y están en
`KalloqCore/Tests`. **Cualquier cambio en la capa de cifrado se valida ahí antes de seguir.**

CryptoKit no convierte Ed25519 a X25519, así que esa conversión (el mapa de Edwards a Montgomery de
`crypto_sign_ed25519_pk_to_curve25519`) está hecha a mano en `Field25519.swift`, sin libsodium.

## Probar contra un servidor que no sea producción

La app usa `wss://call.l8.nz/ws` salvo que se lance con otro servidor:

```bash
xcrun simctl launch <udid> com.cim.securecall -serverURL "ws://127.0.0.1:18080/ws"
```

Es un argumento de lanzamiento, no un ajuste: solo se puede poner desde Xcode o `simctl`, así que
la app instalada siempre habla con producción. Ajustes → Acerca de muestra con cuál está hablando.

## Compilar para un iPhone

El proyecto no lleva el Team ID dentro; se pasa al compilar:

```bash
xcodebuild -project Kalloq.xcodeproj -scheme Kalloq \
  -destination 'platform=iOS,id=<udid>' \
  DEVELOPMENT_TEAM=<TeamID> -allowProvisioningUpdates build
xcrun devicectl device install app --device <device-id> <ruta>/Kalloq.app
```

## Estado

Hechos los pasos 1 a 3 de `IOS_ARRANQUE.md`: proyecto, capa de cifrado, cliente del protocolo,
pantallas de cuenta y contactos, y la llamada de voz con WebRTC y CallKit. Faltan APNs y PushKit (4)
y Universal Links, verificación por QR, ES/EN y TestFlight (5).

Pendientes conocidos dentro de lo ya hecho: leer el QR con la cámara (se agrega junto con la
verificación en persona), los textos en inglés (toda la interfaz está en español por ahora), y el
indicador de calidad de la llamada (el RTT que Android muestra).

**El simulador no tiene telefonía:** CallKit resetea su proveedor en cuanto se reporta una llamada
entrante, así que la app se queda con la llamada en su propia pantalla y arranca el audio ella misma
(`SystemCall.activateAudioDirectly`). Eso es justo lo que pasaría en un teléfono si CallKit rechazara
la llamada, pero el comportamiento real —pantalla del sistema, llamada en espera por una llamada
normal, coche y audífonos— solo se puede comprobar en un iPhone físico.
