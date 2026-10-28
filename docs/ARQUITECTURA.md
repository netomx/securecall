# Kalloq — Arquitectura (v0.1)

App de llamadas de **voz 1 a 1** cifradas de extremo a extremo (E2E).
Cliente Android (iOS en el futuro) + servidor C++ en Debian 12. Host: `call.l8.nz`.
Licencia: **AGPL-3.0**. Paquete Android: `com.cim.securecall`. Nombre de la app: **Kalloq**.

---

## 1. Decisiones tomadas

| Tema | Decisión |
|---|---|
| Medios | Solo voz, 1 a 1, WebRTC (Opus). Licencia BSD-3, sin restricciones |
| Cifrado | DTLS-SRTP (WebRTC) **+** cifrado propio por frame con llave E2E del par |
| Cuentas | Usuario + contraseña. **Sin correo.** Recuperación con código offline |
| Amigos | Solo por UUID (QR o enlace), con solicitud y aceptación |
| Llaves | Automática (TOFU) por defecto. Verificada por QR en persona |
| Dispositivos | **1 dispositivo activo por cuenta.** Al iniciar sesión en otro se regeneran las llaves y los amigos ven "llave cambiada" |
| Push | FCM, mensaje vacío de alta prioridad solo para despertar la app |
| Escala | 1 servidor, < 1000 usuarios |
| Idioma | Español / Inglés, seleccionable en Ajustes |
| Compilación | Directo en Debian 12 |
| Usuario | Privado (solo para iniciar sesión, nadie más lo ve). 6–32 caracteres `a-z 0-9 _ .`, sin distinguir mayúsculas |

---

## 2. Componentes

```
 Android (Kotlin/Compose)                         Debian (l8.nz)
┌──────────────────────────┐   WSS (TLS 1.3)   ┌──────────────────────────┐
│ UI · Room/SQLCipher      │──────────────────▶│ call.l8.nz · nginx :443  │
│ libwebrtc                │                   │   └▶ sc-server (C++20)    │
│ core C++ (JNI, libsodium)│                   │        SQLite (WAL)       │
└───────────┬──────────────┘                   │        FCM HTTP v1 (curl)│
            │  SRTP + frame E2E (UDP)           │ coturn :3478/:5349       │
            └──── P2P directo, o vía TURN ─────▶│   relay 49152-65535/udp  │
                                                └──────────────────────────┘
```

- **`core/`**: biblioteca C++ con toda la criptografía (libsodium): handshake por llamada,
  cifrado de frames y formato de QR. Android la usa vía JNI y iOS la podrá reutilizar tal cual.
- **`server/` (`sc-server`)**: señalización, cuentas, amigos, entrega de llaves públicas,
  credenciales TURN temporales y push. **Nunca ve audio ni llaves privadas.**
- **coturn**: relay TURN para cuando no hay P2P. Solo ve paquetes cifrados.
- **nginx**: termina TLS (Let's Encrypt) y hace proxy del WebSocket a `sc-server` en localhost.

Un solo host: `call.l8.nz` para la señalización (443) y para coturn (3478/5349).

---

## 3. Cuentas

**Registro**
1. El cliente envía `username` + `password` por WSS.
2. El servidor guarda `argon2id(password)` y asigna `user_id` = UUIDv4 aleatorio.
3. El cliente genera un **código de recuperación** (160 bits, en Base32 o 15 palabras) y lo
   muestra **una sola vez** para que el usuario lo guarde. El servidor solo guarda `argon2id(código)`.

**Recuperación:** `username` + código + nueva contraseña. Se invalidan las sesiones y las llaves
del dispositivo anterior, y se emite un código de recuperación nuevo.

**Sesión:** token aleatorio de 256 bits. El servidor guarda su hash y el cliente lo guarda en el
Android Keystore. Un solo token activo por cuenta (= 1 dispositivo).

**Defensas:** límite de intentos por usuario e IP, retraso progresivo y lista de contraseñas comunes.

---

## 4. Llaves

Cada dispositivo genera al instalarse o iniciar sesión:
- `IK`: llave de identidad Ed25519 (firma) / X25519 (DH).
- La privada se guarda cifrada con una llave del Android Keystore y nunca sale del teléfono.

### 4.1 Llave automática (TOFU)
- Al aceptar la amistad, cada lado descarga el `IK` público del otro y lo **fija** (pin).
- `PSK_par = HKDF(X25519(IK_a, IK_b), "sc-pair-v1" ‖ uuid_a ‖ uuid_b)`.
- Si el `IK` del contacto cambia, se muestra **⚠ "La llave de X cambió"** y se pide confirmar.

### 4.2 Llave verificada por QR (en persona)
1. A muestra un QR con `uuid_A`, `fp(IK_A)` y 32 bytes aleatorios `r_A`. B lo escanea.
2. B muestra un QR con `uuid_B`, `fp(IK_B)` y `r_B`. A lo escanea.
3. Cada uno comprueba que la huella `fp` coincide con la llave fijada (si no coincide, hay un
   ataque MITM o la llave cambió).
4. `PSK_par = HKDF(X25519(IK_a, IK_b) ‖ r_A ‖ r_B, "sc-qr-v1")`. El contacto queda **✔ verificado**.

`r_A` y `r_B` nunca pasan por el servidor, así que aunque el servidor fuera malicioso no conoce el PSK.

### 4.3 Cambiar la llave
- "Regenerar llave con X": se repite la automática con un nuevo valor aleatorio, o se vuelve a escanear el QR.
- "Regenerar mi identidad": nuevo `IK` y todos los amigos ven el aviso.

### 4.4 Llaves por llamada (forward secrecy)
En cada llamada se hace un handshake **Noise `KKpsk2`** (X25519, ChaChaPoly, BLAKE2b) con:
- llaves estáticas = `IK` de ambos,
- `psk` = `PSK_par`,
- *prologue* = huellas DTLS de ambos SDP (amarra el DTLS de WebRTC a nuestras identidades).

Los mensajes del handshake viajan dentro de la oferta y la respuesta de señalización. El resultado
es una llave de sesión efímera que se usa en el **FrameEncryptor/FrameDecryptor** de libwebrtc
(XChaCha20-Poly1305 por frame Opus) y se borra al colgar.

---

## 5. Amigos

- El ID público es el `user_id` (UUIDv4). No hay búsqueda por nombre ni directorio.
- Compartir: QR o enlace `https://call.l8.nz/a/<uuid>` (abre la app con un deep link).
- Flujo: `friend_request(uuid)` → el otro acepta o rechaza → intercambio de `IK`.
- Límite de solicitudes por hora, bloquear y eliminar.
- **Regenerar mi UUID** (contra spam): se notifica el nuevo ID a los amigos actuales y el anterior deja de existir.
- El servidor guarda el grafo de amistades (necesario para enrutar). Los alias de los contactos
  solo se guardan en el teléfono.

---

## 6. Flujo de llamada

```
A                         sc-server                       B
│ call_offer{to, E2E(sdp+noise1)} ─▶│                     │
│                                   │─ (offline) FCM {} ─▶│ despierta
│                                   │◀── WSS connect ─────│
│                                   │── call_offer ──────▶│ suena (ConnectionService)
│◀── call_answer{E2E(sdp+noise2)} ──│◀────────────────────│
│◀════════ ICE: P2P o TURN (SRTP + frames E2E) ══════════▶│
```

- El SDP (que lleva IPs) va **cifrado E2E** con `PSK_par`. El servidor solo ve "A llama a B".
- La oferta expira en 45 s. Mensajes: `call_offer`, `call_answer`, `ice`, `call_reject`, `call_end`, `call_busy`.
- Credenciales TURN: `turn_credentials` con usuario y contraseña HMAC temporales (TTL 1 h) usando
  el secreto compartido de coturn (`use-auth-secret`).

---

## 7. Push (FCM)

- `data` message de alta prioridad, payload `{"v":1}`. **Sin nombre, sin ID de quien llama.**
- La app despierta, abre el WSS, recibe la `call_offer` pendiente y muestra una notificación de
  pantalla completa (`CallStyle`) con `ConnectionService` / Telecom.
- El servidor firma un JWT con la cuenta de servicio de Firebase y llama a la API HTTP v1 con libcurl.
- Sin Google Play Services no hay push (UnifiedPush como opción futura).

---

## 8. Stack

**Servidor (Debian):** C++20, CMake, Boost.Asio/Beast, libsodium, SQLite (WAL), nlohmann/json,
spdlog, libcurl, OpenSSL (JWT para FCM). Corre como servicio systemd con usuario sin privilegios,
detrás de nginx + certbot. coturn instalado desde apt.

**Android:** Kotlin, Jetpack Compose, minSdk 26, `io.github.webrtc-sdk:android`, `core` vía JNI
(NDK + CMake), Room + SQLCipher, Android Keystore, CameraX + ML Kit (leer QR), ZXing (generar QR),
Firebase Messaging, Telecom `ConnectionService`. Idioma por app con `AppCompatDelegate.setApplicationLocales`.

**Protocolo de señalización:** JSON sobre WSS, versionado (`"v":1`), fácil de reutilizar en iOS.

---

## 9. Estructura del repositorio

```
secure-call/
├── core/        # C++ cripto compartida (Android/iOS) + tests
├── server/      # sc-server (C++), esquema SQL, unidad systemd
├── android/     # proyecto Android Studio
├── deploy/      # nginx, coturn, certbot, scripts de instalación Debian
└── docs/        # arquitectura, protocolo, modelo de amenazas
```

---

## 10. Fases

| # | Entregable |
|---|---|
| 0 | Repo, CI básico, servidor Debian: nginx + certbot + coturn |
| 1 | `sc-server`: registro, login, recuperación, sesiones, amigos, llaves públicas |
| 2 | Android: login/registro, código de recuperación, amigos por QR/enlace, ES/EN |
| 3 | Llamadas WebRTC (señalización + TURN), sin capa E2E extra todavía |
| 4 | `core`: Noise KKpsk2 + cifrado de frames + verificación por QR |
| 5 | Push FCM + ConnectionService (recibir llamadas con la app cerrada) |
| 6 | Endurecimiento: rate limits, fuzzing del protocolo, revisión de seguridad, release |

---

## 11. Modelo de amenazas (resumen)

| Atacante | Qué ve / qué puede hacer |
|---|---|
| Servidor comprometido | Ve quién llama a quién y cuándo, IPs de conexión. **No** escucha. Puede intentar MITM con llaves falsas: lo detecta el QR o el aviso de llave cambiada |
| Red / ISP | Solo TLS/SRTP cifrado |
| Google (FCM) | Que la app recibió un push vacío |
| Robo de teléfono desbloqueado | Acceso a la cuenta. Mitigación: bloqueo de app con PIN/biometría (opcional) |
