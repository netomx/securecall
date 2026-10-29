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
| `Kalloq/SelfCheck.swift` | Los vectores de referencia, para mostrarlos en pantalla |
| `Kalloq/ContentView.swift` | Pantalla provisional: estado de la capa de cifrado y lo que falta |

## Compatibilidad con Android

Cada valor del handshake tiene que salir byte por byte igual que en Android, o un iPhone y un
Android no pueden negociar una llamada. Los vectores son los de
[`tools/kalloq_crypto.py`](../../tools/kalloq_crypto.py), los mismos que verifica
`apps/android/app/src/test/java/com/cim/securecall/crypto/CryptoTest.kt`, y están en
`KalloqCore/Tests`. **Cualquier cambio en la capa de cifrado se valida ahí antes de seguir.**

CryptoKit no convierte Ed25519 a X25519, así que esa conversión (el mapa de Edwards a Montgomery de
`crypto_sign_ed25519_pk_to_curve25519`) está hecha a mano en `Field25519.swift`, sin libsodium.

## Estado

Hecho el paso 1 de `IOS_ARRANQUE.md`: proyecto y capa de cifrado. Falta el cliente del protocolo y
las pantallas de cuenta (paso 2), la llamada con WebRTC + CallKit (3), APNs y PushKit (4) y
Universal Links, verificación por QR, ES/EN y TestFlight (5).

No hay cuenta de Apple Developer configurada todavía: la app compila y corre en el simulador sin
firmar. CallKit y PushKit solo se pueden probar en un iPhone físico.
