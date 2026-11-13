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

## Desde otras apps
TogetherPlay se registra como reproductor de video. En la app de origen hay que elegirlo
como reproductor externo (o en «Abrir con»):
- **TiviMate:** Ajustes → Reproducción → Reproductor → Reproductor externo → TogetherPlay.
- **Stremio / Nuvio:** Ajustes → Reproductor → Reproductor externo.
- **WUPlay / M3UFY:** opción de reproductor externo de cada app.
- **Kodi:** copiar `docs/kodi-playercorefactory.xml` como indica el propio archivo.

La app detecta el formato (HLS, TS en vivo o archivo), respeta las cabeceras que mande la
app de origen y también comparte archivos locales (content:// y file://).

## Compartir pantalla
Para lo que no se puede mandar como video. Usa WebRTC: el equipo que comparte transmite
la pantalla (hasta 720p) y, en Android 10 o más, el sonido de las apps que lo permiten.
Funciona entre casas con Tailscale en los dos equipos, o directamente si los routers lo
permiten. Algunas apps (Netflix, etc.) bloquean la captura y se ven en negro.

## Conexión con el servidor
Si se corta, la app se reconecta sola y vuelve a entrar a la sala. Si el servidor se
reinició y perdió la sala, la recrea con el mismo código (necesita el `server.js` de esta
versión; con el anterior hay que crear o unirse a una sala nueva).

## Limitaciones
- La cuenta Xtream se guarda sin cifrar en las preferencias del televisor anfitrión.
- El servidor de salas no tiene autenticación ni protección antiabuso.

## Servidor de salas
La versión de GitHub no trae servidor: en la app se escribe la dirección `wss://...` del
servidor propio. El servidor está en `server/` (Node.js 20+) y se puede desplegar gratis
en Render.

## Compilar en Android Studio
1. Baja `tsbridge.aar` (Tailscale integrado) de la última versión en **Releases** y cópialo
   en `app/libs/`. Sin él, la app compila igual pero sin Tailscale integrado.
2. Para que tu compilación traiga tu servidor: copia `togetherplay.properties.example` como
   `togetherplay.properties` y pon tu dirección. Ese archivo no se sube a GitHub.
3. Para firmar con tu llave fija: pon `togetherplay.jks` y un `keystore.properties` en la
   carpeta del proyecto (tampoco se suben).
4. Abre la carpeta en Android Studio, sincroniza Gradle y ejecuta `app`.

En GitHub Actions la app se firma con los secretos `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD` y
`KEY_ALIAS`, y cada compilación de `main` se publica en **Releases** con su APK y el
`tsbridge.aar`.
