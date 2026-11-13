# TogetherPlay (JuntosPlay) 0.3.0

App de Android TV para ver contenido en pareja a distancia, con un servidor WebSocket de salas.

## Qué hace
- **Salas:** un televisor crea la sala y el otro se une con el código. El servidor está en `server/`.
- **Ver juntos desde IPTV (Xtream Codes):** el anfitrión elige un canal, película o episodio.
  Su televisor abre el stream del proveedor **una sola vez** y lo reenvía al otro televisor
  (relay), así que una suscripción de una conexión alcanza para los dos.
- **Sincronización:** pausa y reproducción viajan por la sala en ambos sentidos; el anfitrión
  también mueve la posición en series y películas.

## Requisitos
- Los dos televisores con **Tailscale** conectado a la misma red (el video va directo entre
  ellos por Tailscale; el servidor de salas solo lleva los avisos).
- El servidor de salas desplegado con la versión de `server/server.js` de esta rama
  (mensajes `relay_share` y `relay_stop`).
- En el televisor anfitrión, espacio libre para la caché: una película o episodio ocupa lo
  que pesa el archivo mientras se comparte.

## Uso
1. En ambos televisores: conectar con el servidor y entrar en la misma sala.
2. En el anfitrión: **Compartir desde mi IPTV**, escribir la cuenta Xtream (solo la primera
   vez) y elegir qué ver.
3. El otro televisor abre el reproductor solo.

## Limitaciones
- Los canales en vivo deben ser MPEG-TS (`.ts`); HLS no está soportado.
- La cuenta Xtream se guarda sin cifrar en las preferencias del televisor anfitrión.
- El servidor de salas no tiene autenticación ni protección antiabuso.

## Compilar
Abrir la carpeta en Android Studio, sincronizar Gradle y ejecutar `app`.
Servidor: Node.js 20+, `npm install` y `npm start` dentro de `server/`.
