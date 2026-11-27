import { copyFile, mkdir, readFile, writeFile } from "node:fs/promises";

const configured = process.env.FOLIO_API_BASE?.trim();
if (process.env.VERCEL && !configured) {
  throw new Error("Set FOLIO_API_BASE to the public HTTPS API origin in the Vercel project.");
}
const api = new URL(configured || "http://127.0.0.1:8765");
const loopback = ["localhost", "127.0.0.1", "[::1]"].includes(api.hostname);
if ((api.protocol !== "https:" && !(api.protocol === "http:" && loopback && !process.env.VERCEL)) ||
    api.username || api.password || api.pathname !== "/" || api.search || api.hash ||
    (process.env.VERCEL && loopback)) {
  throw new Error("FOLIO_API_BASE must be an HTTPS origin. Local builds also allow HTTP loopback.");
}

const output = new URL("./dist/", import.meta.url);
await mkdir(output, { recursive: true });
const policy = "default-src 'self'; connect-src " + api.origin + "; object-src 'none'; base-uri 'none'; form-action 'self'";
const index = await readFile(new URL("./index.html", import.meta.url), "utf8");

await Promise.all([
  writeFile(new URL("index.html", output), index.replace("<head>", '<head>\n  <meta http-equiv="Content-Security-Policy" content="' + policy + '">')),
  writeFile(new URL("config.js", output), "window.FOLIO_API_BASE = " + JSON.stringify(api.origin) + ";\n"),
  ...["app.js", "styles.css", "workflow.html"].map(file => copyFile(new URL(file, import.meta.url), new URL(file, output)))
]);
