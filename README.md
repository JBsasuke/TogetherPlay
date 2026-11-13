# JuntosPlay 0.2.0 — conexión remota inicial

Este ZIP incluye la app Android TV y un servidor WebSocket de salas.

## Qué funciona en esta etapa
- Cliente WebSocket en Android mediante OkHttp.
- Campo para introducir la dirección del servidor.
- Crear sala y recibir un código.
- Unirse con código desde otro dispositivo.
- El servidor retransmite eventos de pausa/reproducción/avance al otro miembro.
- Servidor Node.js incluido en `server/`.

## Qué NO está terminado
- El servidor aún no está desplegado públicamente.
- No hay transmisión de video ni archivos.
- Los botones de control son pruebas de mensajería; todavía no controlan Stremio, TiviMate ni otros reproductores.
- No hay autenticación, persistencia ni protección antiabuso para producción.

## Pasos para probar
1. Abre `JuntosPlay` en Android Studio.
2. Instala Node.js 20+ en una computadora o servidor.
3. Entra en la carpeta `server/`, ejecuta `npm install` y `npm start`.
4. Para prueba en la misma red, coloca `ws://IP-DEL-SERVIDOR:8080` en la app de ambos dispositivos.
5. Para Estados Unidos–República Dominicana, despliega el servidor en un hosting público con TLS/WebSocket y coloca la URL `wss://...` en ambos televisores.

**Importante:** la conexión internacional real solo funcionará cuando el servidor esté publicado en Internet y ambos televisores usen su dirección WSS pública. No se ha creado ni contratado hosting desde este proyecto.

## Abrir en Android Studio
Abre la carpeta que contiene `settings.gradle.kts`, sincroniza Gradle y ejecuta `app`.
