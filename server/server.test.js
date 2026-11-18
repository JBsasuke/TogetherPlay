const { spawn } = require("node:child_process");
const assert = require("node:assert/strict");
const { test } = require("node:test");
const { once } = require("node:events");
const path = require("node:path");
const WebSocket = require("ws");

async function startServer(t, env = {}) {
  const server = spawn(process.execPath, [path.join(__dirname, "server.js")], {
    env: { ...process.env, HOST: "127.0.0.1", PORT: "0",
      TRAKT_CLIENT_ID: "", TRAKT_REDIRECT_URI: "", ...env },
  });
  t.after(() => server.kill());
  const [data] = await once(server.stdout, "data");
  const port = String(data).match(/port (\d+)/)?.[1];
  assert.ok(port);
  return port;
}

test("Trakt configuration is optional and exposes only public registration fields", { timeout: 10000 }, async t => {
  const unconfigured = await startServer(t);
  const absent = await fetch(`http://127.0.0.1:${unconfigured}/trakt/config`);
  assert.equal(absent.status, 503);
  assert.deepEqual(await absent.json(), { error: "trakt_not_configured" });

  const configured = await startServer(t, {
    TRAKT_CLIENT_ID: "example-client-id",
    TRAKT_REDIRECT_URI: "https://example.org/trakt/callback",
    TRAKT_CLIENT_SECRET: "must-not-appear-in-the-response",
  });
  const response = await fetch(`http://127.0.0.1:${configured}/trakt/config`);
  assert.equal(response.status, 200);
  assert.equal(response.headers.get("cache-control"), "no-store");
  assert.deepEqual(await response.json(), {
    client_id: "example-client-id",
    redirect_uri: "https://example.org/trakt/callback",
  });
});

test("Multisala admits five members and keeps the standard room at two", { timeout: 10000 }, async t => {
  const peers = [];
  t.after(() => peers.forEach(peer => peer.ws.terminate()));
  const port = await startServer(t);

  async function connect() {
    const ws = new WebSocket(`ws://127.0.0.1:${port}`);
    const inbox = [];
    let wake = null;
    ws.on("message", bytes => { inbox.push(JSON.parse(bytes)); wake?.(); });
    const peer = {
      ws, inbox,
      send(msg) { ws.send(JSON.stringify(msg)); },
      async next(type) {
        while (true) {
          const i = inbox.findIndex(msg => msg.type === type);
          if (i >= 0) return inbox.splice(i, 1)[0];
          await new Promise(resolve => { wake = resolve; });
          wake = null;
        }
      },
    };
    peers.push(peer);
    await once(ws, "open");
    assert.equal((await peer.next("connected")).max_capacity, 5);
    return peer;
  }

  const host = await connect();
  host.send({ type: "create_room", multisala: true });
  const room = await host.next("room_created");
  assert.equal(room.capacity, 5);

  const first = await connect();
  first.send({ type: "join_room", code: room.code });
  assert.equal((await first.next("room_joined")).members, 2);
  assert.equal((await host.next("peer_joined")).members, 2);
  host.send({ type: "relay_share", url: "https://example.org/video", kind: "vod" });
  await first.next("relay_share");
  host.send({ type: "playback", action: "tick", positionMs: 21000 });
  assert.equal((await first.next("playback")).positionMs, 21000);

  const others = [];
  for (let count = 3; count <= 5; count++) {
    const guest = await connect();
    guest.send({ type: "join_room", code: room.code });
    assert.equal((await guest.next("room_joined")).members, count);
    assert.equal(new URL((await guest.next("relay_share")).url).searchParams.get("resume_ms"), "21000");
    assert.equal((await host.next("peer_joined")).members, count);
    others.push(guest);
  }
  const overflow = await connect();
  overflow.send({ type: "join_room", code: room.code });
  assert.match((await overflow.next("error")).message, /llena/);
  first.send({ type: "join_room", code: room.code });
  assert.equal((await first.next("room_joined")).members, 5);

  host.send({ type: "playback", action: "pause", positionMs: 22000 });
  for (const guest of [first, ...others]) {
    const event = await guest.next("playback");
    assert.equal(event.action, "pause");
    assert.equal(event.positionMs, 22000);
    assert.ok(event.sender);
  }
  assert.ok(first.inbox.every(event => event.type !== "relay_share"));

  const left = once(others[0].ws, "close");
  others[0].ws.close();
  await left;
  assert.equal((await host.next("peer_left")).members, 4);
  overflow.send({ type: "join_room", code: room.code });
  assert.equal((await overflow.next("room_joined")).members, 5);

  host.ws.close();
  await first.next("relay_stop");

  overflow.send({ type: "create_room" });
  const standard = await overflow.next("room_created");
  assert.equal(standard.capacity, 2);
  first.send({ type: "join_room", code: standard.code });
  assert.equal((await first.next("room_joined")).members, 2);
  others[1].send({ type: "join_room", code: standard.code });
  assert.match((await others[1].next("error")).message, /llena/);
  first.send({ type: "webrtc_offer", payload: { sdp: "prueba de señalización" } });
  assert.equal((await overflow.next("webrtc_offer")).payload.sdp, "prueba de señalización");
});
