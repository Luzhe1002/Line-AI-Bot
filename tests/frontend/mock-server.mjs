import { createServer } from "node:http";
import { readFile, stat } from "node:fs/promises";
import { extname, resolve, sep } from "node:path";

const host = "127.0.0.1";
const port = Number(process.env.UI_E2E_PORT || 4173);
const staticRoot = resolve("src/main/resources/static");

const mimeTypes = {
  ".css": "text/css; charset=utf-8",
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
};

function sendJson(response, status, body) {
  response.writeHead(status, { "Content-Type": mimeTypes[".json"] });
  response.end(JSON.stringify(body));
}

function sendEmpty(response, status = 204) {
  response.writeHead(status);
  response.end();
}

function slotFor(date, hour = 2) {
  return new Date(`${date}T${String(hour).padStart(2, "0")}:00:00Z`).toISOString();
}

async function readJson(request) {
  const chunks = [];
  for await (const chunk of request) chunks.push(chunk);
  if (!chunks.length) return {};
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

function bookingApi(pathname, url, request, response) {
  const match = pathname.match(/^\/booking\/api\/([^/]+)(\/.*)$/);
  if (!match) return false;
  const [, slug, endpoint] = match;
  if (slug !== "demo") {
    sendJson(response, 401, { detail: "預約連結已失效" });
    return true;
  }
  if (endpoint === "/bootstrap") {
    sendJson(response, 200, {
      tenant_name: "測試店家",
      timezone: "Asia/Taipei",
      slot_minutes: 60,
      services: [{ id: "service-1", name: "基礎服務", description: "約 60 分鐘" }],
    });
    return true;
  }
  if (endpoint === "/availability") {
    const date = url.searchParams.get("local_date");
    if (date === "2099-12-31") {
      sendJson(response, 400, { detail: "<strong>時段錯誤</strong>" });
      return true;
    }
    sendJson(response, 200, {
      slots: [{ starts_at: slotFor(date), available: true }],
    });
    return true;
  }
  if (endpoint === "/reservations" && request.method === "POST") {
    readJson(request).then((body) => sendJson(response, 201, {
      id: "reservation-1",
      starts_at: body.starts_at,
      customer_name: body.customer_name,
      status: "CONFIRMED",
    }));
    return true;
  }
  sendJson(response, 404, { detail: "找不到預約測試端點" });
  return true;
}

function merchantApi(pathname, url, request, response) {
  const match = pathname.match(/^\/merchant-booking\/api\/([^/]+)(\/.*)$/);
  if (!match) return false;
  const [, slug, endpoint] = match;
  if (slug !== "demo") {
    sendJson(response, 401, { detail: "管理連結已失效" });
    return true;
  }
  if (endpoint === "/session") {
    sendJson(response, 200, request.method === "POST"
      ? { csrf_token: "csrf-token" }
      : { authenticated: true, csrf_token: "csrf-token" });
    return true;
  }
  if (endpoint === "/bootstrap") {
    sendJson(response, 200, {
      tenant_name: "測試店家",
      timezone: "Asia/Taipei",
      staff: { display_name: "王店長", role: "OWNER" },
      services: [{ id: "service-1", name: "基礎服務" }],
    });
    return true;
  }
  if (endpoint === "/agenda/local") {
    const date = url.searchParams.get("from_date");
    setTimeout(() => {
      if (date === "2099-12-31") {
        sendJson(response, 503, { detail: "暫時無法讀取預約" });
        return;
      }
      sendJson(response, 200, {
        reservations: [{
          id: "reservation-12345678",
          customer_name: `測試顧客 ${date}`,
          service_name: "基礎服務",
          starts_at: slotFor(date),
          status: "CONFIRMED",
        }],
        blocks: [],
      });
    }, 180);
    return true;
  }
  if (endpoint === "/availability") {
    const date = url.searchParams.get("local_date");
    sendJson(response, 200, { slots: [{ starts_at: slotFor(date, 3), available: true }] });
    return true;
  }
  if (endpoint === "/blocks" && request.method === "POST") {
    readJson(request).then(() => sendJson(response, 201, { id: "block-1" }));
    return true;
  }
  if ((endpoint.startsWith("/blocks/") && request.method === "DELETE")
    || (endpoint.endsWith("/cancel") && request.method === "POST")) {
    sendEmpty(response);
    return true;
  }
  sendJson(response, 404, { detail: "找不到店家管理測試端點" });
  return true;
}

async function serveStatic(pathname, response) {
  let relativePath = pathname.replace(/^\/+/, "");
  if (pathname === "/" || pathname === "/portal" || pathname === "/portal/") {
    relativePath = "portal/index.html";
  } else if (/^\/booking\/[^/.]+\/?$/.test(pathname) || pathname === "/booking/index.html") {
    relativePath = "booking/index.html";
  } else if (/^\/merchant-booking\/[^/.]+\/?$/.test(pathname)
      || pathname === "/merchant-booking/index.html") {
    relativePath = "merchant-booking/index.html";
  }

  const filePath = resolve(staticRoot, decodeURIComponent(relativePath));
  if (filePath !== staticRoot && !filePath.startsWith(`${staticRoot}${sep}`)) {
    response.writeHead(403);
    response.end("Forbidden");
    return;
  }
  try {
    const fileStat = await stat(filePath);
    if (!fileStat.isFile()) throw new Error("Not a file");
    const body = await readFile(filePath);
    response.writeHead(200, {
      "Cache-Control": "no-store",
      "Content-Type": mimeTypes[extname(filePath)] || "application/octet-stream",
    });
    response.end(body);
  } catch (_) {
    response.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
    response.end("Not found");
  }
}

const server = createServer(async (request, response) => {
  const url = new URL(request.url, `http://${request.headers.host || `${host}:${port}`}`);
  if (url.pathname === "/portal/api/session" && request.method === "GET") {
    sendJson(response, 200, { authenticated: false });
    return;
  }
  if (bookingApi(url.pathname, url, request, response)) return;
  if (merchantApi(url.pathname, url, request, response)) return;
  await serveStatic(url.pathname, response);
});

server.listen(port, host, () => {
  process.stdout.write(`UI E2E mock server listening on http://${host}:${port}\n`);
});

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => server.close(() => process.exit(0)));
}
