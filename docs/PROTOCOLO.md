# Protocolo de señalización v1

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

Al conectar, el servidor envía `{"ev":"hello","v":1,"server":"sc-server/<versión>"}`.

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

Los eventos solo llegan a quien está conectado. Al reconectar, el cliente llama `contacts` y compara con su estado local.

## Errores

`bad_request`, `unknown_op`, `unauthorized`, `already_authenticated`, `rate_limited`, `busy`, `invalid_username`, `weak_password`, `username_taken`, `invalid_credentials`, `invalid_token`, `invalid_key`, `not_found`, `blocked`, `internal`.

Si un cliente envía demasiados mensajes seguidos, el servidor cierra la conexión.
