const http = require("http");
const { WebSocketServer } = require("ws");
const crypto = require("crypto");

const PORT = Number(process.env.PORT || 8080);
const STANDARD_CAPACITY = 2;
const MULTI_CAPACITY = 5;

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

function newRoom(multisala) {
  return {
    members: new Set(),
    capacity: multisala === true ? MULTI_CAPACITY : STANDARD_CAPACITY,
    relay: null,
    lastActive: Date.now(),
  };
}

function roomStatus(room) {
  return { members: room.members.size, capacity: room.capacity, relay_snapshot: true };
}

function leave(ws) {
  const roomCode = clients.get(ws);
  if (!roomCode) return;
  clients.delete(ws);
  const room = rooms.get(roomCode);
  if (!room) return;
  room.members.delete(ws);
  const relayEnded = room.relay?.owner === ws;
  if (relayEnded) room.relay = null;
  for (const peer of room.members) {
    if (relayEnded) send(peer, { type: "relay_stop" });
    send(peer, { type: "peer_left", ...roomStatus(room) });
  }
  room.lastActive = Date.now();
}

function addMember(ws, room, roomCode) {
  leave(ws);
  room.members.add(ws);
  room.lastActive = Date.now();
  clients.set(ws, roomCode);
}

wss.on("connection", (ws) => {
  const sender = crypto.randomUUID();
  send(ws, { type: "connected", message: "TogetherPlay server ready", max_capacity: MULTI_CAPACITY });
  ws.on("message", (raw) => {
    let msg;
    try { msg = JSON.parse(raw.toString()); }
    catch { return send(ws, { type: "error", message: "JSON inválido" }); }
    if (msg == null || typeof msg !== "object" || Array.isArray(msg)) {
      return send(ws, { type: "error", message: "Mensaje inválido" });
    }

    if (msg.type === "create_room") {
      let roomCode;
      do { roomCode = code(); } while (rooms.has(roomCode));
      const room = newRoom(msg.multisala);
      rooms.set(roomCode, room);
      addMember(ws, room, roomCode);
      return send(ws, { type: "room_created", code: roomCode, ...roomStatus(room) });
    }
    if (msg.type === "join_room") {
      const roomCode = String(msg.code || "").trim().toUpperCase();
      if (!rooms.has(roomCode) && msg.create_if_missing === true && /^[0-9A-F]{6}$/.test(roomCode)) {
        rooms.set(roomCode, newRoom(msg.multisala));
      }
      const room = rooms.get(roomCode);
      if (!room) return send(ws, { type: "error", message: "La sala no existe o se perdió tras reiniciar el servidor." });
      if (room.members.has(ws)) return send(ws, { type: "room_joined", code: roomCode, ...roomStatus(room) });
      if (room.members.size >= room.capacity) return send(ws, { type: "error", message: "La sala ya está llena." });
      addMember(ws, room, roomCode);
      send(ws, { type: "room_joined", code: roomCode, ...roomStatus(room) });
      if (room.relay) send(ws, room.relay.event);
      for (const peer of room.members) {
        if (peer !== ws) send(peer, { type: "peer_joined", ...roomStatus(room) });
      }
      return;
    }
    // WebRTC signaling: forward offers, answers, and ICE candidates only
    // to the other member of the sender's room. Media itself is not sent here.
    if (["webrtc_offer", "webrtc_answer", "webrtc_ice"].includes(msg.type)) {
      const roomCode = clients.get(ws);
      const room = roomCode && rooms.get(roomCode);
      const payload = msg.payload;
      if (payload == null || JSON.stringify(payload).length > 100000) {
        return send(ws, { type: "error", message: "Señal WebRTC no válida o demasiado grande." });
      }
      const tunneledControl = Boolean(payload.togetherplay_relay);
      if (!room || (!tunneledControl && (room.capacity !== STANDARD_CAPACITY || room.members.size !== 2))) {
        return send(ws, { type: "error", message: "Se necesitan dos dispositivos conectados a la sala estándar." });
      }
      for (const peer of room.members) {
        if (peer !== ws) send(peer, { type: msg.type, payload });
      }
      return;
    }

    // El servidor anuncia la dirección. Cada invitado recibe el video directamente del anfitrión.
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
      if (msg.type === "relay_share") room.relay = { owner: ws, event };
      else if (room.relay?.owner === ws) room.relay = null;
      else if (room.relay) return;
      for (const peer of room.members) if (peer !== ws) send(peer, event);
      return;
    }

    if (msg.type === "playback") {
      const roomCode = clients.get(ws);
      const room = roomCode && rooms.get(roomCode);
      if (!room || room.members.size === 0) return send(ws, { type: "error", message: "Primero crea o únete a una sala." });
      const event = { type: "playback", action: String(msg.action || ""), positionMs: Number(msg.positionMs || 0),
        sender, sentAt: Date.now() };
      if (room.relay?.owner === ws && room.relay.event.kind === "vod" &&
          ["tick", "play", "pause", "seek"].includes(event.action) && Number.isFinite(event.positionMs)) {
        try {
          const url = new URL(room.relay.event.url);
          url.searchParams.set("resume_ms", String(Math.max(0, Math.trunc(event.positionMs))));
          room.relay.event.url = url.toString();
        } catch { /* la dirección original sigue disponible */ }
      }
      for (const peer of room.members) if (peer !== ws) send(peer, event);
      return;
    }
    send(ws, { type: "error", message: "Tipo de mensaje desconocido." });
  });
  ws.on("close", () => leave(ws));
  ws.on("error", () => leave(ws));
});

server.listen(PORT, process.env.HOST || "0.0.0.0",
  () => console.log(`TogetherPlay server listening on port ${server.address().port}`));
