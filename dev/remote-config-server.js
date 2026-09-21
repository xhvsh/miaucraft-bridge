// Dev-only: serves the local test server's "remote" files so the plugin fetches
// them exactly like it does from GitHub in production:
//   /remote-config.json  -> bridge/remote-config.json
//   /plugin-update.json  -> bridge/dev/plugin-update.json
//   /jars/<file>         -> bridge/dev/jars/<file>
// Start with: powershell -File bridge/dev/start-remote-config.ps1
const http = require("http");
const fs = require("fs");
const path = require("path");

const root = path.join(__dirname, "..");
const devDir = __dirname;
const port = Number(process.env.PORT || 8099);

function send(res, status, type, body) {
  res.writeHead(status, {
    "Content-Type": type,
    "Cache-Control": "no-store, no-cache, must-revalidate",
    "Content-Length": Buffer.byteLength(body),
  });
  res.end(body);
}

function sendFile(res, file, type, notFound) {
  fs.readFile(file, (err, data) => {
    if (err) {
      send(res, 404, "text/plain", notFound);
      return;
    }
    send(res, 200, type, data);
  });
}

http
  .createServer((req, res) => {
    const url = req.url.split("?")[0];

    if (url === "/remote-config.json") {
      sendFile(res, path.join(root, "remote-config.json"), "application/json",
        "remote-config.json not found");
      return;
    }

    if (url === "/plugin-update.json") {
      sendFile(res, path.join(devDir, "plugin-update.json"), "application/json",
        "plugin-update.json not found - run publish-update.ps1");
      return;
    }

    if (url.startsWith("/jars/")) {
      const name = path.basename(decodeURIComponent(url.slice("/jars/".length)));
      const file = path.join(devDir, "jars", name);
      sendFile(res, file, "application/java-archive", "jar not found");
      return;
    }

    send(res, 404, "text/plain", "not found");
  })
  .listen(port, "127.0.0.1", () => {
    console.log("dev remote server: http://127.0.0.1:" + port +
      " (remote-config.json, plugin-update.json, jars/)");
  });
