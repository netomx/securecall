# Kalloq

Llamadas de voz 1 a 1 cifradas de extremo a extremo, entre teléfonos.
*End-to-end encrypted 1:1 voice calls between phones.*

- Sin correo ni número de teléfono: usuario + contraseña + código de recuperación.
- Amigos solo por UUID (QR o enlace).
- Llave por contacto automática, o verificada en persona escaneando un QR.
- El servidor nunca puede escuchar las llamadas.

| Carpeta | Contenido |
|---|---|
| `apps/core/` | Criptografía compartida en C++ (Android vía JNI, iOS en el futuro) |
| `apps/server/` | Servidor de señalización en C++ (`sc-server`) — [protocolo](docs/PROTOCOLO.md) |
| `apps/android/` | App Android (Kotlin, Jetpack Compose) |
| `deploy/` | Configuración de nginx, coturn y systemd |
| `docs/` | [Arquitectura](docs/ARQUITECTURA.md) |

Estado: fases 0 y 1 listas: nginx + TLS + coturn, y `sc-server` (cuentas, amigos, llaves) en producción en `wss://call.l8.nz/ws`.

## Licencia

[AGPL-3.0](LICENSE)
