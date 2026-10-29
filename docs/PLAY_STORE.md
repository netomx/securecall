# Ficha de Google Play

Material listo para copiar en Play Console. Imágenes en `dist/play-store/` (no van al repo; las fuentes SVG están en `dist/play-store/fuente/`).

| Archivo | Uso en Play Console |
|---|---|
| `icono-512.png` | Ícono de la app (512×512, sin transparencia; Play redondea las esquinas) |
| `imagen-destacada-1024x500.png` | Imagen destacada, español |
| `feature-graphic-en-1024x500.png` | Imagen destacada, inglés |
| `capturas/captura-1…6.png` | Capturas de teléfono (1080×1920) |

## Datos de la ficha

- **Nombre:** Kalloq
- **Categoría:** Comunicación
- **Correo de contacto:** appstore@catalogomty.com
- **Sitio web:** https://www.catalogomty.com
- **Política de privacidad:** https://call.l8.nz/privacy
- **Eliminación de cuenta:** https://call.l8.nz/privacy#eliminar
- **Anuncios:** no contiene anuncios.

## Español (es-419) — idioma predeterminado

**Descripción breve** (máx. 80):

> Llamadas de voz privadas, cifradas de extremo a extremo. Sin número ni correo.

**Descripción completa:**

> Kalloq es una app de llamadas de voz 1 a 1 hecha para la privacidad. Tus llamadas van cifradas de extremo a extremo: solo tú y la persona con la que hablas pueden escucharlas, ni siquiera nuestro servidor.
>
> SIN NÚMERO DE TELÉFONO NI CORREO
> Para crear tu cuenta solo eliges un nombre de usuario y una contraseña. Tu nombre de usuario es privado: tus contactos te agregan con tu código QR o tu enlace, nunca con tu número.
>
> CIFRADO DE EXTREMO A EXTREMO
> Cada llamada usa llaves nuevas (Noise + DTLS-SRTP), autenticadas con la llave de identidad de cada persona. Ambas pantallas muestran un código de seguridad para comprobar de viva voz que nadie está en medio.
>
> VERIFICA EN PERSONA
> Escanea el código QR de tu contacto cuando estén juntos y Kalloq sabrá que es realmente esa persona. Si su llave cambia, te avisa.
>
> SE COMPORTA COMO UNA LLAMADA NORMAL
> • Suena con el teléfono bloqueado y la app cerrada.
> • Contesta desde el auto, el reloj o tus audífonos Bluetooth.
> • Si entra una llamada telefónica, Kalloq queda en espera y la retomas después.
> • Altavoz, silencio e indicador de calidad de la llamada.
>
> PRIVACIDAD POR DISEÑO
> • El servidor no guarda historial de llamadas.
> • Sin anuncios, sin rastreadores, sin analítica.
> • Los nombres que les pones a tus contactos solo se guardan en tu teléfono.
> • La app bloquea capturas de pantalla.
> • Recupera tu cuenta con un código que solo tú tienes.
> • Borra tu cuenta en cualquier momento desde Ajustes.
>
> CÓDIGO ABIERTO
> Kalloq es software libre (AGPL-3.0). Puedes revisar el código en github.com/netomx/securecall.

## English (en-US)

**Short description** (max. 80):

> Private voice calls, end-to-end encrypted. No phone number or email needed.

**Full description:**

> Kalloq is a one-to-one voice calling app built for privacy. Your calls are end-to-end encrypted: only you and the person you are talking to can hear them, not even our server.
>
> NO PHONE NUMBER OR EMAIL
> To create an account you only pick a username and a password. Your username stays private: contacts add you with your QR code or link, never with your number.
>
> END-TO-END ENCRYPTED
> Every call uses fresh keys (Noise + DTLS-SRTP), authenticated with each person's identity key. Both screens show a security code you can compare out loud to make sure no one is in the middle.
>
> VERIFY IN PERSON
> Scan your contact's QR code when you are together and Kalloq knows it is really them. If their key changes, you are warned.
>
> WORKS LIKE A REGULAR CALL
> • Rings with the phone locked and the app closed.
> • Answer from your car, watch or Bluetooth headphones.
> • If a phone call comes in, Kalloq goes on hold and you resume it afterwards.
> • Speaker, mute and a call quality indicator.
>
> PRIVATE BY DESIGN
> • The server keeps no call history.
> • No ads, no trackers, no analytics.
> • The names you give your contacts are stored only on your phone.
> • The app blocks screenshots.
> • Recover your account with a code only you have.
> • Delete your account at any time from Settings.
>
> OPEN SOURCE
> Kalloq is free software (AGPL-3.0). Review the code at github.com/netomx/securecall.

## Respuestas para Play Console

**Seguridad de los datos**
- ¿Recopila o comparte datos? Sí, recopila; no comparte (Firebase Cloud Messaging es un proveedor de servicio, no se cuenta como "compartir").
- Datos recopilados:
  - *Información personal → Identificadores de usuario* (nombre de usuario e ID): obligatorio, funcionalidad de la app y administración de la cuenta.
  - *Identificadores del dispositivo u otros* (token de notificaciones): opcional, funcionalidad de la app.
- No se recopila: audio, mensajes, contactos de la agenda, ubicación, fotos, información financiera, actividad en la app ni diagnósticos.
- Los datos se cifran en tránsito: sí. El usuario puede pedir que se borren: sí (en la app y por correo).

**Declaraciones de permisos**
- Servicio en primer plano `phoneCall` y `microphone`: llamadas de voz VoIP iniciadas o contestadas por el usuario; el servicio dura lo que dura la llamada.
- Intención de pantalla completa (`USE_FULL_SCREEN_INTENT`): mostrar llamadas entrantes con la pantalla bloqueada. Es una app de llamadas, uso permitido.

**Clasificación de contenido:** categoría "Comunicación"; permite que los usuarios se comuniquen entre sí (voz), no comparte ubicación, no hay compras digitales.

**Público objetivo:** 18 años o más.

**Acceso a la app:** se requiere iniciar sesión. Dar a Google una cuenta de prueba (usuario y contraseña) con al menos un contacto que pueda recibir llamadas.
