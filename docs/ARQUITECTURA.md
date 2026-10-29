# Kalloq — Arquitectura (v0.1)

App de llamadas de **voz 1 a 1** cifradas de extremo a extremo (E2E).
Cliente Android (iOS en el futuro) + servidor C++ en Ubuntu 24.04 LTS. Host: `call.l8.nz`.
Licencia: **AGPL-3.0**. Paquete Android: `com.cim.securecall`. Nombre de la app: **Kalloq**.

---

## 1. Decisiones tomadas

| Tema | Decisión |
|---|---|
| Medios | Solo voz, 1 a 1, WebRTC (Opus). Licencia BSD-3, sin restricciones |
| Cifrado | DTLS-SRTP (WebRTC) con las huellas DTLS autenticadas E2E por un handshake Noise entre las llaves de identidad |
| Cuentas | Usuario + contraseña. **Sin correo.** Recuperación con código offline |
| Amigos | Solo por UUID (QR o enlace), con solicitud y aceptación |
| Llaves | Automática (TOFU) por defecto. Verificada por QR en persona |
| Dispositivos | **1 dispositivo activo por cuenta.** Al iniciar sesión en otro se regeneran las llaves y los amigos ven "llave cambiada" |
| Push | FCM, mensaje vacío de alta prioridad solo para despertar la app |
| Escala | 1 servidor, < 1000 usuarios |
| Idioma | Español / Inglés, seleccionable en Ajustes |
| Compilación | Directo en el servidor (Ubuntu 24.04) |
| Usuario | Privado (solo para iniciar sesión, nadie más lo ve). 6–32 caracteres `a-z 0-9 _ .`, sin distinguir mayúsculas |

---

## 2. Componentes

```
 Android (Kotlin/Compose)                         Debian (l8.nz)
┌──────────────────────────┐   WSS (TLS 1.3)   ┌──────────────────────────┐
│ UI · Room/SQLCipher      │──────────────────▶│ call.l8.nz · nginx :443  │
│ libwebrtc                │                   │   └▶ sc-server (C++20)    │
│ core C++ (JNI, libsodium)│                   │        SQLite (WAL)       │
└───────────┬──────────────┘                   │        FCM HTTP v1 (Beast)│
            │  SRTP + frame E2E (UDP)           │ coturn :3478/:5349       │
            └──── P2P directo, o vía TURN ─────▶│   relay 49152-65535/udp  │
                                                └──────────────────────────┘
```

- **Capa E2E** (`apps/android/.../crypto/`, ~300 líneas de Kotlin): handshake Noise por llamada y
  verificación por QR. Referencia en Python en `tools/kalloq_crypto.py`, que interopera con la biblioteca
  `noiseprotocol`; los tests de Android usan sus vectores. iOS la reimplementará con CryptoKit contra esos vectores.
- **`apps/server/` (`sc-server`)**: señalización, cuentas, amigos, entrega de llaves públicas,
  credenciales TURN temporales y push. **Nunca ve audio ni llaves privadas.**
- **coturn**: relay TURN para cuando no hay P2P. Solo ve paquetes cifrados.
- **nginx**: termina TLS (Let's Encrypt) y hace proxy del WebSocket a `sc-server` en localhost.

Un solo host: `call.l8.nz` para la señalización (443) y para coturn (3478/5349).

---

## 3. Cuentas

**Registro**
1. El cliente envía `username` + `password` por WSS.
2. El servidor guarda `argon2id(password)` y asigna `user_id` = UUIDv4 aleatorio.
3. El servidor genera un **código de recuperación** (160 bits, Base32 Crockford en 8 grupos de 4),
   lo devuelve **una sola vez** y solo guarda `argon2id(código)`. La app lo muestra para que el usuario lo guarde.

**Recuperación:** `username` + código + nueva contraseña. Se invalidan las sesiones y las llaves
del dispositivo anterior, y se emite un código de recuperación nuevo.

**Sesión:** token aleatorio de 256 bits. El servidor guarda su hash y el cliente lo guarda en el
Android Keystore. Un solo token activo por cuenta (= 1 dispositivo).

**Defensas:** límite de intentos por usuario e IP, retraso progresivo y lista de contraseñas comunes.

---

## 4. Llaves

Cada dispositivo genera al instalarse o iniciar sesión:
- `IK`: llave de identidad Ed25519 (firma). Su forma X25519 (DH) se deriva de ella, así que el servidor solo guarda la pública Ed25519.
- La privada se guarda cifrada con una llave del Android Keystore y nunca sale del teléfono.

### 4.1 Llave automática (TOFU)
- Al aceptar la amistad, cada lado descarga el `IK` público del otro y lo **fija** (pin).
- Sin verificación en persona el `psk` de Noise son 32 bytes en cero: la seguridad descansa en las llaves fijadas.
- Si el `IK` del contacto cambia, se muestra **⚠ "La llave de X cambió"** y se pide confirmar.

### 4.2 Llave verificada por QR (en persona)
1. A muestra un QR `kalloq-verify:1:<uuid_A>:<IK_A>:<r_A>` (`r_A` = 32 bytes aleatorios). B lo escanea.
2. B muestra el suyo con `uuid_B`, `IK_B` y `r_B`. A lo escanea.
3. Cada uno comprueba que la llave del QR es la que tiene fijada (si no, hay un MITM o la llave cambió).
4. `PSK_par = HKDF-SHA256(X25519(IK_a, IK_b) ‖ IK_1 ‖ r_1 ‖ IK_2 ‖ r_2, info "kalloq/qr/1")`, con 1 y 2
   ordenados por llave. El contacto queda **✔ verificado** y ambos teléfonos muestran un código de 9 dígitos
   derivado del PSK que debe coincidir.
5. Cada `r` se guarda (cifrado) hasta que se reinicia la verificación, así el orden de escaneo no importa.
   Si el PSK no coincide en una llamada, quien recibe la rechaza y avisa "verifiquen de nuevo".

`r_A` y `r_B` nunca pasan por el servidor, así que aunque el servidor fuera malicioso no conoce el PSK.

### 4.3 Cambiar la llave
- "Empezar de nuevo" en la verificación: nuevos `r` y se vuelve a escanear el QR. "Quitar verificación" vuelve a TOFU.
- Al confirmar una llave cambiada se borra la verificación anterior.
- "Regenerar mi identidad": nuevo `IK` y todos los amigos ven el aviso.

### 4.4 Llaves por llamada (forward secrecy)
En cada llamada se hace un handshake **`Noise_KKpsk2_25519_AESGCM_SHA256`** con:
- llaves estáticas = forma X25519 de los `IK` fijados,
- `psk` = `PSK_par` (o ceros sin verificación),
- *prologue* = `"kalloq/call/1\0" ‖ call_id` (una oferta no se puede reutilizar en otra llamada).

El mensaje 1 lleva cifrado `{sdp: oferta, psk: id}` y el mensaje 2 `{sdp: respuesta}`. Como el SDP va
cifrado y autenticado por las dos identidades, las **huellas DTLS** que contiene son las reales: WebRTC
verifica el certificado DTLS contra ellas, así que las llaves SRTP del audio solo las tienen los dos
teléfonos (ni el servidor ni TURN pueden descifrar ni hacer MITM). Después del handshake, los candidatos
ICE viajan cifrados con las llaves de transporte de Noise (nonce explícito de 8 bytes, sin repetición).

Ambas pantallas muestran un **código de seguridad** de 6 dígitos derivado del hash del handshake;
si coincide al compararlo de viva voz, no hay nadie en medio.

**Cifrado por frame (descartado por ahora):** en el SDK WebRTC de Android, `FrameEncryptor` solo acepta
un objeto C++ nativo enlazado contra libwebrtc, que no expone su ABI de C++. Con las huellas DTLS
autenticadas E2E, DTLS-SRTP ya da cifrado de extremo a extremo; el cifrado por frame sería defensa en
profundidad y requiere compilar libwebrtc propio.

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
│                                   │─ FCM {"t":"call"} ─▶│ despierta
│                                   │◀── WSS connect ─────│
│                                   │── call_incoming ───▶│ suena (servicio phoneCall + CallStyle)
│◀── call_answer{E2E(sdp+noise2)} ──│◀────────────────────│
│◀════════ ICE: P2P o TURN (SRTP + frames E2E) ══════════▶│
```

- El SDP y los candidatos ICE (que llevan IPs) van **cifrados E2E** (Noise). El servidor solo ve "A llama a B".
- La oferta expira en 45 s. Mensajes: `call_offer`, `call_answer`, `ice`, `call_reject`, `call_end`, `call_busy`.
- Credenciales TURN: `turn_credentials` con usuario y contraseña HMAC temporales (TTL 1 h) usando
  el secreto compartido de coturn (`use-auth-secret`).

---

## 7. Push (FCM)

- `data` message de alta prioridad (TTL 40 s), payload `{"t":"call"}`. **Sin nombre, sin ID de quien
  llama, sin ID de llamada.** Google solo sabe que a ese teléfono le llegó "una llamada".
- El token FCM va con la sesión (`set_push`): cerrar sesión o entrar en otro teléfono lo borra.
- El push se manda aunque el teléfono parezca conectado (su socket puede estar muerto sin que el
  servidor lo sepa todavía); la app comprueba su conexión con un `ping` y reconecta si hace falta.
- Mientras suena, la llamada sobrevive a que el socket de quien recibe se caiga: al volver a
  autenticarse recibe `call_incoming` otra vez. Una vez contestada, perder el socket la termina.
- La app arranca un servicio en primer plano de tipo `phoneCall` cuyo aviso es la notificación de
  llamada entrante (`CallStyle`, pantalla completa con el teléfono bloqueado, Contestar/Rechazar);
  al contestar pasa a `phoneCall|microphone`. Si nadie contesta: aviso de llamada perdida.
- El servidor firma un JWT RS256 con la cuenta de servicio de Firebase (`/etc/kalloq/fcm.json`),
  lo cambia por un token OAuth (cache 55 min) y llama a la API HTTP v1, con Boost.Beast + OpenSSL.
- Sin `google-services.json` en la compilación, o sin Google Play Services, no hay push: la app
  lo indica en Ajustes y solo recibe llamadas mientras está viva (UnifiedPush como opción futura).
- **ConnectionService / Telecom: pendiente.** Integrarse con Telecom cambia quién controla el audio
  (altavoz/Bluetooth) y se hará por separado, cuando lo actual esté probado en teléfonos reales.

---

## 8. Stack

**Servidor (Debian):** C++20, CMake, Boost.Asio/Beast, libsodium, SQLite (WAL), nlohmann/json,
spdlog, OpenSSL (TLS y JWT para FCM). Corre como servicio systemd con usuario sin privilegios,
detrás de nginx + certbot. coturn instalado desde apt.

**Android:** Kotlin, Jetpack Compose, minSdk 26, `io.github.webrtc-sdk:android`, Tink (Ed25519, X25519),
AES-GCM de la plataforma, Android Keystore, ZXing (generar y leer QR),
Firebase Messaging (sin el plugin de Google Services: `build.gradle.kts` lee `google-services.json`). Idioma por app con `AppCompatDelegate.setApplicationLocales`.

**Protocolo de señalización:** JSON sobre WSS, versionado (`"v":1`), fácil de reutilizar en iOS.

---

## 9. Estructura del repositorio

```
secure-call/
├── apps/
│   ├── server/  # sc-server (C++) + pruebas de integración
│   └── android/ # proyecto Android Studio
├── deploy/      # nginx, coturn, certbot, scripts de instalación Debian
├── tools/       # referencia Python de la capa E2E y peer de pruebas (aiortc)
└── docs/        # arquitectura, protocolo, modelo de amenazas
```

---

## 10. Fases

| # | Entregable |
|---|---|
| 0 | ✅ Repo, servidor: nginx + certbot + coturn |
| 1 | ✅ `sc-server`: registro, login, recuperación, sesiones, amigos, llaves públicas ([protocolo](PROTOCOLO.md)) |
| 2 | ✅ Android: login/registro, código de recuperación, amigos por QR/enlace, ES/EN (`apps/android`) |
| 3 | ✅ Llamadas de voz WebRTC (señalización + TURN), silenciar, altavoz, Bluetooth, servicio en primer plano. Sin capa E2E extra todavía: hoy el audio va cifrado con DTLS-SRTP entre los teléfonos, pero las huellas DTLS pasan por el servidor sin verificar |
| 4 | ✅ E2E: Noise KKpsk2 por llamada (SDP e ICE cifrados, huellas DTLS autenticadas), código de seguridad en llamada, verificación en persona por QR con PSK |
| 5 | ✅ Push FCM (recibir llamadas con la app cerrada), notificación de llamada con Contestar/Rechazar, llamadas perdidas, audio no capturable por otras apps · pendiente: ConnectionService/Telecom |
| 6 | ✅ Endurecimiento: límites por cuenta e IP (llamadas, cambios de llave/ID, conexiones), cola de envío acotada, login obligatorio en 60 s, fuzzer del protocolo, revisión de seguridad de la app (renombres sin reemplazar llaves, ofertas con fecha y no repetibles, Contestar solo desde la notificación, FLAG_SECURE en toda la app), respaldos diarios, APK release firmado y App Links verificados |

---

## 11. Modelo de amenazas (resumen)

| Atacante | Qué ve / qué puede hacer |
|---|---|
| Servidor comprometido | Ve quién llama a quién y cuándo, IPs de conexión. **No** escucha. Puede intentar MITM con llaves falsas: lo detecta el QR o el aviso de llave cambiada |
| Red / ISP | Solo TLS/SRTP cifrado |
| Google (FCM) | Que la app recibió un push vacío |
| Robo de teléfono desbloqueado | Acceso a la cuenta. Mitigación: bloqueo de app con PIN/biometría (opcional) |
