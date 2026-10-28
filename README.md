# securecall

Llamadas de voz 1 a 1 cifradas de extremo a extremo, entre teléfonos.
*End-to-end encrypted 1:1 voice calls between phones.*

- Sin correo ni número de teléfono: usuario + contraseña + código de recuperación.
- Amigos solo por UUID (QR o enlace).
- Llave por contacto automática, o verificada en persona escaneando un QR.
- El servidor nunca puede escuchar las llamadas.

| Carpeta | Contenido |
|---|---|
| `core/` | Criptografía compartida en C++ (Android vía JNI, iOS en el futuro) |
| `server/` | Servidor de señalización en C++ para Debian 12 |
| `android/` | App Android (Kotlin, Jetpack Compose) |
| `deploy/` | Configuración de nginx, coturn y systemd |
| `docs/` | [Arquitectura](docs/ARQUITECTURA.md) |

Estado: diseño (fase 0).

## Licencia

[AGPL-3.0](LICENSE)
