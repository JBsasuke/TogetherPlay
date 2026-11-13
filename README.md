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
- Red para el video (se elige en la pantalla principal del equipo que comparte):
  - **Wi-Fi local:** los dos equipos en la misma red de casa; sirve para probar.
  - **Tailscale:** entre casas distintas, con Tailscale conectado en los dos.
  - **Automática:** Tailscale si está conectado; si no, la red de casa.
- El servidor de salas: funciona con la versión anterior (los avisos viajan por la
  señalización WebRTC que ya reenvía), pero lo ideal es desplegar `server/server.js` de
  esta versión, que entiende `relay_share` y `relay_stop` directamente.
- En el televisor anfitrión, espacio libre para la caché: una película o episodio ocupa lo
  que pesa el archivo mientras se comparte.

## Uso
1. En ambos televisores: conectar con el servidor y entrar en la misma sala.
2. En el anfitrión: **Compartir desde mi IPTV**, escribir la cuenta Xtream (solo la primera
   vez) y elegir qué ver.
3. El otro televisor abre el reproductor solo.

## Formatos
La app abre el contenido una vez para ver cómo lo entrega el proveedor y lo comparte así:
- **HLS (m3u8):** las listas se reescriben para pasar por el relay; cada segmento se pide
  una sola vez aunque lo vean los dos equipos.
- **TS en vivo** y **archivos** (.mkv/.mp4), como antes.

## Conexión con el servidor
Si se corta, la app se reconecta sola y vuelve a entrar a la sala. Si el servidor se
reinició y perdió la sala, la recrea con el mismo código (necesita el `server.js` de esta
versión; con el anterior hay que crear o unirse a una sala nueva).

## Limitaciones
- La cuenta Xtream se guarda sin cifrar en las preferencias del televisor anfitrión.
- El servidor de salas no tiene autenticación ni protección antiabuso.

## Compilar
Abrir la carpeta en Android Studio, sincronizar Gradle y ejecutar `app`.
Servidor: Node.js 20+, `npm install` y `npm start` dentro de `server/`.
