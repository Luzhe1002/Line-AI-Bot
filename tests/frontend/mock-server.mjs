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

function sendJson(response, status, body, headers = {}) {
  response.writeHead(status, { "Content-Type": mimeTypes[".json"], ...headers });
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

let portalDocuments;
let portalStaff;
let portalHasDraft;
let portalActiveVersion;

function resetPortalFixture() {
  portalHasDraft = false;
  portalActiveVersion = 3;
  portalDocuments = [{
    id: "document-1",
    dataset_id: "dataset-active",
    title: "預約與取消政策",
    content: "顧客可透過 LINE 預約，若需要取消請提前聯絡店家。",
    source_url: null,
    index_status: "READY",
  }];
  portalStaff = [{
    id: "staff-owner",
    display_name: "王店長",
    role: "OWNER",
    status: "ACTIVE",
    notify_new_booking: true,
    notify_cancellation: true,
    daily_summary_enabled: true,
    daily_summary_time: "08:00:00",
    created_at: "2026-07-30T01:00:00Z",
  }, {
    id: "staff-manager",
    display_name: "林主管",
    role: "MANAGER",
    status: "ACTIVE",
    notify_new_booking: true,
    notify_cancellation: true,
    daily_summary_enabled: false,
    daily_summary_time: "08:00:00",
    created_at: "2026-07-30T02:00:00Z",
  }];
}

resetPortalFixture();

function portalApi(url, request, response) {
  const pathname = url.pathname;
  if (!pathname.startsWith("/portal/api/")) return false;
  const endpoint = pathname.slice("/portal/api".length);
  const authenticated = (request.headers.cookie || "").includes("portal-e2e=1");
  const tenant = { id: "tenant-demo", name: "暖心咖啡", slug: "demo" };

  if (endpoint === "/line-session" && request.method === "POST") {
    resetPortalFixture();
    sendJson(response, 200, {
      authenticated: true,
      csrf_token: "portal-csrf",
      tenant,
    }, { "Set-Cookie": "portal-e2e=1; Path=/; HttpOnly; SameSite=Lax" });
    return true;
  }
  if (endpoint === "/session" && request.method === "GET") {
    sendJson(response, 200, authenticated
      ? { authenticated: true, csrf_token: "portal-csrf", tenant }
      : { authenticated: false });
    return true;
  }
  if (endpoint === "/session" && request.method === "DELETE") {
    sendEmpty(response);
    return true;
  }
  if (!authenticated) {
    sendJson(response, 401, { detail: "請先登入商家工作台" });
    return true;
  }
  if (endpoint === "/overview") {
    sendJson(response, 200, {
      tenant,
      line_channel: {
        configured: true,
        enabled: true,
        webhook_url: "https://example.test/webhooks/line/demo",
      },
      business_hours: [{ active: true }],
      datasets: portalHasDraft
        ? [{
          id: "dataset-draft",
          name: "正式客服知識",
          version: 4,
          status: "DRAFT",
          published_at: null,
        }, {
          id: "dataset-active",
          name: "正式客服知識",
          version: portalActiveVersion,
          status: "ACTIVE",
          published_at: "2026-07-31T08:00:00Z",
        }]
        : [{
          id: "dataset-active",
          name: "正式客服知識",
          version: portalActiveVersion,
          status: "ACTIVE",
          published_at: "2026-07-31T08:00:00Z",
        }],
    });
    return true;
  }
  if (endpoint === "/datasets/draft" && request.method === "POST") {
    portalHasDraft = true;
    portalDocuments = portalDocuments.map((document, index) => ({
      ...document,
      id: `draft-copy-${index + 1}`,
      dataset_id: "dataset-draft",
    }));
    sendJson(response, 201, {
      id: "dataset-draft",
      name: "正式客服知識",
      version: 4,
      status: "DRAFT",
      published_at: null,
    });
    return true;
  }
  if (endpoint === "/datasets/publish" && request.method === "POST") {
    if (!portalHasDraft || url.searchParams.get("datasetId") !== "dataset-draft") {
      sendJson(response, 409, { detail: "只能發布目前的草稿" });
      return true;
    }
    portalHasDraft = false;
    portalActiveVersion = 4;
    portalDocuments = portalDocuments.map((document, index) => ({
      ...document,
      id: `active-copy-${index + 1}`,
      dataset_id: "dataset-active",
    }));
    sendJson(response, 200, {
      id: "dataset-active",
      name: "正式客服知識",
      version: 4,
      status: "ACTIVE",
      published_at: "2026-07-31T09:00:00Z",
    });
    return true;
  }
  if (endpoint === "/datasets/reindex" && request.method === "POST") {
    if (!portalHasDraft || url.searchParams.get("datasetId") !== "dataset-draft") {
      sendJson(response, 409, { detail: "請先建立新版草稿再重新索引" });
      return true;
    }
    sendJson(response, 200, { indexed: portalDocuments.length, failed: 0, errors: [] });
    return true;
  }
  if (endpoint === "/documents" && request.method === "GET") {
    sendJson(response, 200, portalDocuments);
    return true;
  }
  if (endpoint === "/documents" && request.method === "POST") {
    readJson(request).then((body) => {
      const document = {
        id: `document-${portalDocuments.length + 1}`,
        dataset_id: url.searchParams.get("datasetId"),
        title: body.title,
        content: body.content,
        source_url: body.source_url,
        index_status: "READY",
      };
      portalDocuments.push(document);
      sendJson(response, 201, document);
    });
    return true;
  }
  if (endpoint === "/documents" && request.method === "PUT") {
    readJson(request).then((body) => {
      const document = portalDocuments.find(
        (item) => item.id === url.searchParams.get("documentId")
      );
      Object.assign(document, body, { index_status: "READY" });
      sendJson(response, 200, document);
    });
    return true;
  }
  if (endpoint === "/documents" && request.method === "DELETE") {
    portalDocuments = portalDocuments.filter(
      (item) => item.id !== url.searchParams.get("documentId")
    );
    sendEmpty(response);
    return true;
  }
  if (endpoint === "/staff" && request.method === "GET") {
    sendJson(response, 200, portalStaff);
    return true;
  }
  if (endpoint.startsWith("/staff/") && request.method === "DELETE") {
    const staffId = endpoint.slice("/staff/".length);
    const staff = portalStaff.find((item) => item.id === staffId);
    if (staff?.role === "OWNER"
        && portalStaff.filter((item) => item.role === "OWNER").length <= 1) {
      sendJson(response, 409, { detail: "至少需要保留一位擁有者" });
      return true;
    }
    portalStaff = portalStaff.filter((item) => item.id !== staffId);
    sendEmpty(response);
    return true;
  }
  if (endpoint.startsWith("/staff/") && request.method === "PUT") {
    readJson(request).then((body) => {
      const staff = portalStaff.find(
        (item) => item.id === endpoint.slice("/staff/".length)
      );
      Object.assign(staff, body);
      sendJson(response, 200, staff);
    });
    return true;
  }
  sendJson(response, 404, { detail: "找不到商家工作台測試端點" });
  return true;
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
  if (portalApi(url, request, response)) return;
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
