const http = require("http");
const { WebSocketServer } = require("ws");
const crypto = require("crypto");

const PORT = Number(process.env.PORT || 8080);
const server = http.createServer((req, res) => {
  if (req.url === "/health") {
    res.writeHead(200, { "content-type": "application/json" });
    return res.end(JSON.stringify({ ok: true, service: "TogetherPlay room server" }));
  }
  res.writeHead(404);
  res.end("Not found");
});
const wss = new WebSocketServer({ server });
const rooms = new Map();
const clients = new Map();

function code() { return crypto.randomBytes(3).toString("hex").toUpperCase(); }
function send(ws, payload) { if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(payload)); }

function leave(ws) {
  const roomCode = clients.get(ws);
  if (!roomCode) return;
  const room = rooms.get(roomCode);
  if (room) {
    room.members.delete(ws);
    for (const peer of room.members) send(peer, { type: "peer_left" });
    // Keep the room code reserved while empty so a saved client can rejoin.
    room.lastActive = Date.now();
  }
  clients.delete(ws);
}

wss.on("connection", (ws) => {
  send(ws, { type: "connected", message: "TogetherPlay server ready" });
  ws.on("message", (raw) => {
    let msg;
    try { msg = JSON.parse(raw.toString()); }
    catch { return send(ws, { type: "error", message: "JSON inválido" }); }

    if (msg.type === "create_room") {
      let roomCode;
      do { roomCode = code(); } while (rooms.has(roomCode));
      rooms.set(roomCode, { members: new Set([ws]), createdAt: Date.now(), lastActive: Date.now() });
      clients.set(ws, roomCode);
      return send(ws, { type: "room_created", code: roomCode });
    }
    if (msg.type === "join_room") {
      const roomCode = String(msg.code || "").trim().toUpperCase();
      // Al reconectar tras un reinicio del servidor, la app pide recrear la sala con el mismo código.
      if (!rooms.has(roomCode) && msg.create_if_missing === true && /^[0-9A-F]{6}$/.test(roomCode)) {
        rooms.set(roomCode, { members: new Set(), createdAt: Date.now(), lastActive: Date.now() });
      }
      const room = rooms.get(roomCode);
      if (!room) return send(ws, { type: "error", message: "La sala no existe o se perdió tras reiniciar el servidor." });
      if (room.members.size >= 2) return send(ws, { type: "error", message: "La sala ya está llena." });
      leave(ws);
      room.members.add(ws);
      room.lastActive = Date.now();
      clients.set(ws, roomCode);
      send(ws, { type: "room_joined", code: roomCode });
      for (const peer of room.members) if (peer !== ws) send(peer, { type: "peer_joined" });
      return;
    }
    // WebRTC signaling: forward offers, answers, and ICE candidates only
    // to the other member of the sender's room. Media itself is not sent here.
    if (["webrtc_offer", "webrtc_answer", "webrtc_ice"].includes(msg.type)) {
      const roomCode = clients.get(ws);
      const room = roomCode && rooms.get(roomCode);
      if (!room || room.members.size !== 2) {
        return send(ws, { type: "error", message: "Se necesitan dos dispositivos conectados a la sala." });
      }
      const payload = msg.payload;
      if (payload == null || JSON.stringify(payload).length > 100000) {
        return send(ws, { type: "error", message: "Señal WebRTC no válida o demasiado grande." });
      }
      for (const peer of room.members) {
        if (peer !== ws) send(peer, { type: msg.type, payload });
      }
      return;
    }

    // Relay de video: el anfitrión avisa al otro televisor qué URL abrir (su relay
    // por Tailscale). Por aquí solo viaja la dirección; el video va directo entre ambos.
    if (msg.type === "relay_share" || msg.type === "relay_stop") {
      const roomCode = clients.get(ws);
      const room = roomCode && rooms.get(roomCode);
      if (!room) return send(ws, { type: "error", message: "Primero crea o únete a una sala." });
      let event = { type: "relay_stop" };
      if (msg.type === "relay_share") {
        const url = String(msg.url || "");
        if (!/^https?:\/\/[^\s]{1,500}$/.test(url)) {
          return send(ws, { type: "error", message: "URL de relay no válida." });
        }
        event = {
          type: "relay_share",
          url,
          kind: msg.kind === "live" ? "live" : "vod",
          title: String(msg.title || "").slice(0, 200),
        };
      }
      for (const peer of room.members) if (peer !== ws) send(peer, event);
      return;
    }

    if (msg.type === "playback") {
      const roomCode = clients.get(ws);
      const room = roomCode && rooms.get(roomCode);
      if (!room || room.members.size === 0) return send(ws, { type: "error", message: "Primero crea o únete a una sala." });
      const event = { type: "playback", action: String(msg.action || ""), positionMs: Number(msg.positionMs || 0), sentAt: Date.now() };
      for (const peer of room.members) if (peer !== ws) send(peer, event);
      return;
    }
    send(ws, { type: "error", message: "Tipo de mensaje desconocido." });
  });
  ws.on("close", () => leave(ws));
  ws.on("error", () => leave(ws));
});

server.listen(PORT, "0.0.0.0", () => console.log(`TogetherPlay server listening on port ${PORT}`));
