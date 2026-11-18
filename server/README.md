# Servidor JuntosPlay (prototipo)

## Ejecutar para pruebas locales
Requiere Node.js 20 o superior.

```bash
npm install
npm start
```

Health check: `http://localhost:8080/health`

WebSocket local: `ws://localhost:8080`

## Para conectar Estados Unidos con República Dominicana
Despliega este servicio en un hosting público que admita WebSockets y TLS. Después usa la dirección segura `wss://tu-dominio` en la app Android.

No uses `ws://` sin TLS en producción. Configura HTTPS/WSS en el proveedor o mediante un proxy TLS.

## Activar Trakt en los televisores

Registra una aplicación con acceso de inicio de sesión en [Trakt Developer](https://developer.trakt.tv/docs/create-an-app). Configura dos variables de entorno en el servicio de TogetherPlay:

- `TRAKT_CLIENT_ID`: ID público de la aplicación registrada.
- `TRAKT_REDIRECT_URI`: URL HTTPS autorizada para esa aplicación. Respeta la dirección exacta que registraste en Trakt.

El endpoint `/trakt/config` entrega solo esos valores públicos. Los televisores solicitan el código a Trakt y guardan la autorización en el equipo que se vincula. El servidor de salas no recibe los tokens de las cuentas.

## Limitaciones actuales
- Las salas viven en memoria; se pierden al reiniciar el servidor.
- Código de sala aleatorio, sin cuentas ni autenticación todavía.
- La sala estándar admite dos equipos y permite compartir la captura de pantalla.
- Multisala admite hasta cinco equipos en total para compartir canales, películas y series.
  Para usarla, despliega el servidor actualizado en la dirección configurada en TogetherPlay.
- Solo retransmite eventos de control; no transmite video.
- No usar con contenido protegido/DRM para intentar eludir restricciones.
- Añadir límites de frecuencia, expiración de salas, autenticación, autorización y observabilidad antes de producción.
