// Minimal Source RCON client for the local dev server so tests can run
// commands without touching the server console.
//
//   node bridge/dev/rcon.js "bridge update"
//
// Reads host/port/password from env (RCON_HOST/RCON_PORT/RCON_PASSWORD) with
// defaults matching the local server.properties.
const net = require("net");

const HOST = process.env.RCON_HOST || "127.0.0.1";
const PORT = Number(process.env.RCON_PORT || 25575);
const PASSWORD = process.env.RCON_PASSWORD || "miaucraft";
const command = process.argv.slice(2).join(" ");

if (!command) {
  console.error("usage: node rcon.js <command>");
  process.exit(2);
}

const TYPE_RESPONSE = 0;
const TYPE_COMMAND = 2;
const TYPE_AUTH = 3;

function packet(id, type, body) {
  const bodyBuf = Buffer.from(body, "utf8");
  const buf = Buffer.allocUnsafe(bodyBuf.length + 14);
  buf.writeInt32LE(bodyBuf.length + 10, 0);
  buf.writeInt32LE(id, 4);
  buf.writeInt32LE(type, 8);
  bodyBuf.copy(buf, 12);
  buf.writeInt16LE(0, 12 + bodyBuf.length);
  return buf;
}

const socket = net.connect(PORT, HOST);
let buffer = Buffer.alloc(0);
let authed = false;
let output = "";
let done = false;

function finish(code) {
  if (done) return;
  done = true;
  socket.destroy();
  if (output.trim()) process.stdout.write(output.replace(/\u0000/g, ""));
  process.exit(code);
}

socket.on("connect", () => {
  socket.write(packet(1, TYPE_AUTH, PASSWORD));
});

socket.on("data", (chunk) => {
  buffer = Buffer.concat([buffer, chunk]);
  while (buffer.length >= 4) {
    const len = buffer.readInt32LE(0);
    if (buffer.length < len + 4) break;
    const id = buffer.readInt32LE(4);
    const type = buffer.readInt32LE(8);
    const body = buffer.slice(12, 4 + len - 2).toString("utf8");
    buffer = buffer.slice(4 + len);

    if (!authed && (type === TYPE_RESPONSE || id === 1 || id === -1)) {
      if (id === -1) {
        console.error("rcon: auth failed");
        finish(1);
      }
      authed = true;
      socket.write(packet(2, TYPE_COMMAND, command));
      continue;
    }
    if (authed) {
      output += body;
      // Minecraft terminates a command's output with an empty response packet.
      if (body === "" && output.length > 0) {
        setTimeout(() => finish(0), 50);
      }
    }
  }
});

socket.on("error", (err) => {
  console.error("rcon: " + err.message);
  finish(1);
});

setTimeout(() => finish(output ? 0 : 1), 5000);
