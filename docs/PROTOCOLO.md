# Protocolo de señalización v3

WebSocket sobre TLS: `wss://call.l8.nz/ws`. Cada mensaje es un objeto JSON en un frame de texto (máx. 16 KiB).

## Formato

**Petición (cliente → servidor)**
```json
{"id": 7, "op": "login", "username": "alicia_01", "password": "..."}
```
`id` es un entero o string elegido por el cliente; la respuesta lo repite.

**Respuesta**
```json
{"id": 7, "ok": true, "user_id": "…", "token": "…"}
{"id": 7, "ok": false, "error": "invalid_credentials"}
```

**Evento (servidor → cliente, sin `id`)**
```json
{"ev": "friend_request", "user_id": "…"}
```

Al conectar, el servidor envía `{"ev":"hello","v":3,"server":"sc-server/<versión>"}`.

### Versiones

Un solo número para todo el protocolo. El servidor lo anuncia en `hello`; cada app manda el suyo dentro de la
oferta y la respuesta cifradas (`proto`), así cada lado sabe qué puede hacer el otro **en esa llamada** y lo dice
en pantalla ("Video no disponible: Carlos usa una versión anterior de Kalloq") en vez de fallar. Una app que no
manda `proto` cuenta como v2.

| Versión | Qué trajo | Servidor | Entre apps |
|---|---|---|---|
| 2 | Push FCM/APNs, `ts` en la oferta, espera (`hold`), ICE restart | `hello.v` = 2 | sin `proto` |
| 3 | Videollamadas y límite de calidad; `call_ice` hasta 8 KiB | `hello.v` = 3 | `proto: 3` |

Una función nueva sube el número y anota aquí desde qué versión existe (en Android, `VIDEO_SINCE` en
`AppController.kt`).

## Codificaciones

| Dato | Formato |
|---|---|
| `user_id` | UUIDv4 en minúsculas, `xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx` |
| `token` | 32 bytes aleatorios, base64url sin padding |
| `ik` (llave de identidad) | Llave pública **Ed25519** (32 bytes), base64url sin padding. La X25519 se deriva de ella (`crypto_sign_ed25519_pk_to_curve25519`) |
| `recovery_code` | 160 bits en Base32 Crockford, 8 grupos de 4: `7KQ2-…`. Al validarlo se ignoran guiones y espacios, y no distingue mayúsculas (`O`→`0`, `I`/`L`→`1`) |

## Reglas

- **username**: 6–32 caracteres `a-z 0-9 _ .`; se guarda en minúsculas. Es privado: nunca se muestra a otros usuarios.
- **password**: 10–256 bytes.
- **1 dispositivo por cuenta**: al iniciar sesión, autenticarse o recuperar la cuenta en una conexión nueva, la conexión anterior recibe `session_revoked` y se cierra. Hacer `login` invalida el token anterior.

## Operaciones sin sesión

| op | Parámetros | Respuesta |
|---|---|---|
| `ping` | — | `{}` |
| `register` | `username`, `password` | `user_id`, `token`, `recovery_code` (**se muestra una sola vez**) |
| `login` | `username`, `password` | `user_id`, `token`, `key_version`, `has_key` |
| `auth` | `token` | `user_id`, `key_version`, `has_key` |
| `recover` | `username`, `recovery_code`, `new_password` | `user_id`, `token`, `recovery_code` (nuevo), `key_version`, `has_key` |

`recover` cambia la contraseña, invalida el código usado y cierra cualquier sesión anterior. La llave de identidad guardada **no** se borra: el nuevo dispositivo sube la suya con `set_key` y los amigos reciben `key_changed`.

## Operaciones con sesión

| op | Parámetros | Respuesta |
|---|---|---|
| `whoami` | — | `user_id`, `key_version`, `has_key` |
| `logout` | — | `{}` y cierra la conexión |
| `change_password` | `password`, `new_password` | `{}` |
| `regen_recovery` | `password` | `recovery_code` |
| `delete_account` | `password` | `{}` y cierra la conexión; los amigos reciben `friend_removed` |
| `set_key` | `ik` | `key_version` (sube cada vez que la llave cambia) |
| `get_key` | `user_id` | `user_id`, `ik` (o `null`), `key_version`. Solo funciona con amigos |
| `regen_id` | — | `user_id` (nuevo). Los amigos reciben `friend_id_changed` |
| `friend_request` | `user_id` | `status`: `pending` o `friends`. Si el otro ya te había enviado solicitud, se aceptan las dos y además viene `ik` y `key_version` |
| `friend_respond` | `user_id`, `accept` (bool) | `status`: `friends` (+ `ik`, `key_version`) o `none` |
| `friend_remove` | `user_id` | `{}`. Sirve también para cancelar o rechazar una solicitud |
| `block` / `unblock` | `user_id` | `{}` |
| `contacts` | — | `friends: [{user_id, ik, key_version}]`, `incoming: [user_id]`, `outgoing: [user_id]`, `blocked: [user_id]` |
| `turn` | — | `urls`, `username`, `credential`, `ttl` (para `RTCIceServer`) |
| `set_push` | `token` (máx. 4096; `""` lo borra), `platform` opcional: `fcm` (por omisión), `apns` o `apns_sandbox` | `{}`. Se guarda con la sesión: `logout` o entrar en otro teléfono lo borra. Un mismo token solo pertenece a una cuenta. Un token APNs es hexadecimal, de 32 a 200 caracteres; `apns_sandbox` es para compilaciones de desarrollo, cuyos tokens solo valen en los servidores sandbox de Apple |

### Llamadas

El servidor solo reenvía `msg`, que es opaco para él (base64url): los mensajes Noise del handshake y los
candidatos ICE cifrados (ver ARQUITECTURA §4.4). Solo se puede llamar a amigos.

- `call.msg` = Noise mensaje 1, cuyo contenido es `{"sdp": oferta, "psk": id del PSK o "none", "ts": ms Unix,
  "proto": 3, "video": true|false}`. `video` dice si quien llama empezó con la cámara (el otro ve "Videollamada
  entrante" y elige contestar con video o solo voz).
  El que recibe rechaza en silencio (`call_reject`) una oferta con más de 10 min de diferencia en `ts` o un `call_id`
  que ya atendió: el servidor podría repetir una oferta vieja, que descifra bien pero no debe volver a sonar.
- `call_answer.msg` = Noise mensaje 2, con `{"sdp": respuesta, "proto": 3}`.
- Desde v3 toda llamada lleva una sección de video en el SDP, aunque empiece sin cámara: prender o apagar la
  cámara no renegocia. El video va cifrado igual que el audio (DTLS-SRTP).
- `call_ice.msg` = nonce (8 bytes, big-endian) ‖ AES-GCM(`{candidate, sdpMid, sdpMLineIndex}`), o bien
  `{hold: true|false}` cuando el sistema pone la llamada en espera (entró una llamada SIM) o la reanuda, y
  `{restart: "offer"|"answer", sdp}` para rehacer la ruta del audio (ICE restart) tras un corte. Solo quien
  llamó manda `offer`, así nunca se cruzan dos. Con video ese SDP mide ~3.4 KB (~4.7 KiB en base64): por eso
  `call_ice` admite hasta 8 KiB desde v3.
- v3, también en `call_ice`: `{camera: true|false, video_max: bps}`. `camera` dice si mandamos imagen (si no, el
  otro muestra la inicial); `video_max` es lo más que queremos recibir (1500000 en calidad alta, 200000 en ahorro
  de datos) y el otro limita su video a eso o menos. Se mandan al conectar y cada vez que cambian (la cámara se
  pausa cuando la app no está en pantalla).

| op | Parámetros | Respuesta / errores |
|---|---|---|
| `call` | `user_id`, `call_id` (UUIDv4 elegido por quien llama), `msg` (máx. 14 KiB) | `{}` · `offline` (desconectado y sin push), `busy` (el otro está en otra llamada), `in_call` (tú ya estás en una), `not_found` |
| `call_answer` | `call_id`, `msg` (máx. 14 KiB) | `{}` (solo quien recibe la llamada) |
| `call_ice` | `call_id`, `msg` (máx. 8 KiB; 3 KiB antes de v3) | `{}` |
| `call_reject` | `call_id` | `{}` (solo mientras suena) |
| `call_end` | `call_id` | `{}` (colgar o cancelar) |

Si nadie contesta en 45 s, el servidor termina la llamada (`timeout`). Si uno de los dos se desconecta, el otro recibe `call_ended` con `disconnected`, salvo quien **recibe** una llamada que todavía suena: su teléfono puede estar reconectando o despertando por push, y al autenticarse (`auth`/`login`) recibe otra vez `call_incoming`.

Una llamada **contestada** espera 10 s a quien perdió la conexión: el otro recibe `call_reconnecting`, y si vuelve a autenticarse a tiempo los dos reciben `call_resumed` (quien llamó rehace la ruta del audio). Si no, el otro recibe `call_ended` con `disconnected`. Durante una llamada contestada el servidor manda un ping WebSocket cada 10 s y cierra la conexión tras 20 s sin respuesta; fuera de llamadas no hace ping, para no despertar la radio del teléfono.

Si quien recibe tiene token de push, el servidor le manda un push `{"t":"call"}` (sin más datos) al llamar, esté conectado o no: FCM de alta prioridad en Android, VoIP por APNs en iOS (tema `com.cim.securecall.voip`, caduca a los 40 s). En iOS la app tiene que mostrar una llamada de CallKit en cuanto llega el push, antes de saber quién llama; la actualiza al recibir `call_incoming`. Si el servicio de push de ese token no está configurado en el servidor, cuenta como sin push.

Si alguien te bloqueó, `friend_request` responde `pending` igualmente: no se revela el bloqueo.

## Eventos

| ev | Campos | Cuándo |
|---|---|---|
| `hello` | `v`, `server` | Al conectar |
| `session_revoked` | — | Otra conexión inició sesión con la cuenta. Luego se cierra la conexión |
| `friend_request` | `user_id` | Alguien te envió solicitud |
| `friend_accepted` | `user_id`, `ik`, `key_version` | Aceptaron tu solicitud |
| `friend_removed` | `user_id` | Un amigo te eliminó, canceló su solicitud o borró su cuenta |
| `key_changed` | `user_id`, `ik`, `key_version` | Un amigo cambió su llave de identidad: **la app debe avisar al usuario** |
| `friend_id_changed` | `old_id`, `new_id` | Un amigo regeneró su UUID |
| `call_incoming` | `call_id`, `user_id`, `msg` | Te están llamando |
| `call_answered` | `call_id`, `msg` | Contestaron tu llamada |
| `call_ice` | `call_id`, `msg` | Candidato ICE del otro lado |
| `call_ended` | `call_id`, `reason`: `hangup`, `rejected`, `timeout`, `disconnected` | La llamada terminó |
| `call_reconnecting` | `call_id` | El otro perdió la conexión; la llamada lo espera 10 s |
| `call_resumed` | `call_id` | Quien se había desconectado volvió (lo reciben los dos) |

Los eventos solo llegan a quien está conectado. Al reconectar, el cliente llama `contacts` y compara con su estado local.

## Errores

`bad_request`, `unknown_op`, `unauthorized`, `already_authenticated`, `rate_limited`, `busy`, `invalid_username`, `weak_password`, `username_taken`, `invalid_credentials`, `invalid_token`, `invalid_key`, `not_found`, `blocked`, `offline`, `busy`, `in_call`, `internal`.

Si un cliente envía demasiados mensajes seguidos, el servidor cierra la conexión.
