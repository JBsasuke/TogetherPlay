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

## Limitaciones actuales
- Las salas viven en memoria; se pierden al reiniciar el servidor.
- Código de sala aleatorio, sin cuentas ni autenticación todavía.
- Límite de dos dispositivos por sala.
- Solo retransmite eventos de control; no transmite video.
- No usar con contenido protegido/DRM para intentar eludir restricciones.
- Añadir límites de frecuencia, expiración de salas, autenticación, autorización y observabilidad antes de producción.
