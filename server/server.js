const http = require("http");
const { WebSocketServer } = require("ws");
const crypto = require("crypto");

const PORT = Number(process.env.PORT || 8080);
const server = http.createServer((req, res) => {
  if (req.url === "/health") {
    res.writeHead(200, { "content-type": "application/json" });
    return res.end(JSON.stringify({ ok: true, service: "JuntosPlay room server" }));
  }
  res.writeHead(404);
  res.end("Not found");
});

const wss = new WebSocketServer({ server });
const rooms = new Map();
const clients = new Map();

function code() {
  return crypto.randomBytes(3).toString("hex").toUpperCase();
}
function send(ws, payload) {
  if (ws.readyState === ws.OPEN) ws.send(JSON.stringify(payload));
}
function leave(ws) {
  const roomCode = clients.get(ws);
  if (!roomCode) return;
  const members = rooms.get(roomCode);
  if (members) {
    members.delete(ws);
    for (const peer of members) send(peer, { type: "peer_left" });
    if (members.size === 0) rooms.delete(roomCode);
  }
  clients.delete(ws);
}

wss.on("connection", (ws) => {
  send(ws, { type: "connected", message: "JuntosPlay server ready" });

  ws.on("message", (raw) => {
    let msg;
    try { msg = JSON.parse(raw.toString()); }
    catch { return send(ws, { type: "error", message: "JSON inválido" }); }

    if (msg.type === "create_room") {
      let roomCode;
      do { roomCode = code(); } while (rooms.has(roomCode));
      rooms.set(roomCode, new Set([ws]));
      clients.set(ws, roomCode);
      return send(ws, { type: "room_created", code: roomCode });
    }

    if (msg.type === "join_room") {
      const roomCode = String(msg.code || "").trim().toUpperCase();
      const members = rooms.get(roomCode);
      if (!members) return send(ws, { type: "error", message: "La sala no existe o ya terminó." });
      if (members.size >= 2) return send(ws, { type: "error", message: "La sala ya está llena." });
      leave(ws);
      members.add(ws);
      clients.set(ws, roomCode);
      send(ws, { type: "room_joined", code: roomCode });
      for (const peer of members) if (peer !== ws) send(peer, { type: "peer_joined" });
      return;
    }

    if (msg.type === "playback") {
      const roomCode = clients.get(ws);
      const members = roomCode && rooms.get(roomCode);
      if (!members) return send(ws, { type: "error", message: "Primero crea o únete a una sala." });
      const event = {
        type: "playback",
        action: String(msg.action || ""),
        positionMs: Number(msg.positionMs || 0),
        sentAt: Date.now()
      };
      for (const peer of members) if (peer !== ws) send(peer, event);
      return;
    }

    send(ws, { type: "error", message: "Tipo de mensaje desconocido." });
  });

  ws.on("close", () => leave(ws));
  ws.on("error", () => leave(ws));
});

server.listen(PORT, "0.0.0.0", () => {
  console.log(`JuntosPlay server listening on port ${PORT}`);
});
