import { createServer } from "node:http";
import { readFile } from "node:fs/promises";

const port = Number(process.env.FRONTEND_PORT ?? 4173);
const host = process.env.FRONTEND_HOST ?? "127.0.0.1";
const api = new URL(process.env.FOLIO_API_BASE ?? "http://127.0.0.1:8765");
if (!["http:", "https:"].includes(api.protocol) || api.username || api.password || api.search || api.hash) {
  throw new Error("FOLIO_API_BASE must be an HTTP or HTTPS URL without credentials, query or fragment.");
}
const apiBase = api.href.replace(/\/+$/, "");

const files = [
  ["/", "index.html", "text/html; charset=utf-8"],
  ["/workflow.html", "workflow.html", "text/html; charset=utf-8"],
  ["/app.js", "app.js", "text/javascript; charset=utf-8"],
  ["/styles.css", "styles.css", "text/css; charset=utf-8"]
];
const staticFiles = new Map();
for (const [route, file, type] of files) {
  staticFiles.set(route, { type, body: await readFile(new URL(file, import.meta.url)) });
}
staticFiles.set("/config.js", {
  type: "text/javascript; charset=utf-8",
  body: "window.FOLIO_API_BASE = " + JSON.stringify(apiBase) + ";"
});

createServer((request, response) => {
  response.setHeader("Cache-Control", "no-store");
  response.setHeader("X-Content-Type-Options", "nosniff");
  response.setHeader("Referrer-Policy", "no-referrer");
  response.setHeader("Content-Security-Policy",
    "default-src 'self'; connect-src " + api.origin +
    "; object-src 'none'; base-uri 'none'; frame-ancestors 'self'; form-action 'self'");
  if (request.method !== "GET" && request.method !== "HEAD") {
    response.writeHead(405, { Allow: "GET, HEAD" }).end();
    return;
  }
  const pathname = request.url.split("?", 1)[0];
  const file = staticFiles.get(pathname === "/index.html" ? "/" : pathname);
  if (!file) {
    response.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" }).end("Page not found.");
    return;
  }
  response.writeHead(200, { "Content-Type": file.type, "Content-Length": Buffer.byteLength(file.body) });
  response.end(request.method === "HEAD" ? undefined : file.body);
}).listen(port, host, () => console.log("Folio UI listening at http://" + host + ":" + port));
