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
let portalBookingServices;
let portalBookingAddOns;
let portalHasDraft;
let portalActiveVersion;
let portalSessionExpired;
let portalBookingEnabled = true;
let portalHandoffs = [];

function resetPortalFixture() {
  portalSessionExpired = false;
  portalBookingEnabled = true;
  portalHandoffs = [{ id: "ticket-1", reason: "詢問營業時間", created_at: "2026-09-18T02:00:00Z" }, { id: "ticket-2", reason: "詢問商品", created_at: "2026-09-18T03:00:00Z" }];
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
  portalBookingAddOns = [{
    id: "add-on-1",
    tenant_id: "tenant-demo",
    name: "護髮",
    description: "增加保濕護理",
    duration_minutes: 60,
    price_amount: 500,
    active: true,
  }];
  portalBookingServices = [{
    id: "service-1",
    tenant_id: "tenant-demo",
    name: "洗頭",
    description: "基礎洗髮服務",
    duration_minutes: 60,
    price_amount: 300,
    active: true,
    add_ons: [portalBookingAddOns[0]],
  }];
}

resetPortalFixture();

async function portalApi(url, request, response) {
  const pathname = url.pathname;
  if (!pathname.startsWith("/portal/api/")) return false;
  const endpoint = pathname.slice("/portal/api".length);
  const authenticated = (request.headers.cookie || "").includes("portal-e2e=1");
  const tenant = {
    id: "tenant-demo",
    name: "暖心咖啡",
    slug: "demo",
    timezone: "Asia/Taipei",
    slot_minutes: 60,
    booking_enabled: portalBookingEnabled,
  };

  if (endpoint === "/line-session" && request.method === "POST") {
    resetPortalFixture();
    sendJson(response, 200, {
      authenticated: true,
      csrf_token: "portal-csrf",
      tenant,
    }, { "Set-Cookie": "portal-e2e=1; Path=/; HttpOnly; SameSite=Lax" });
    return true;
  }
  if (endpoint === "/test/expire-session" && request.method === "POST") {
    portalSessionExpired = true;
    sendEmpty(response);
    return true;
  }
  if (endpoint === "/session" && request.method === "GET") {
    sendJson(response, 200, authenticated && !portalSessionExpired
      ? { authenticated: true, csrf_token: "portal-csrf", tenant }
      : { authenticated: false });
    return true;
  }
  if (endpoint === "/session" && request.method === "DELETE") {
    sendEmpty(response);
    return true;
  }
  if (portalSessionExpired && request.method !== "GET") {
    sendJson(response, 403, { detail: "Invalid CSRF token" });
    return true;
  }
  if (!authenticated || portalSessionExpired) {
    sendJson(response, 401, { detail: "請先登入商家工作台" });
    return true;
  }
  if (endpoint === "/handoffs") {
    sendJson(response, 200, portalHandoffs);
    return true;
  }
  if (endpoint.startsWith("/handoffs/") && endpoint.endsWith("/close") && request.method === "POST") {
    portalHandoffs = portalHandoffs.filter((item) => item.id !== endpoint.split("/")[2]);
    sendEmpty(response);
    return true;
  }
  if (endpoint === "/features" && request.method === "PUT") {
    const data = await readJson(request);
    portalBookingEnabled = data.booking_enabled;
    sendJson(response, 200, { ...tenant, booking_enabled: portalBookingEnabled });
    return true;
  }
  if (endpoint === "/reservations") {
    sendJson(response, 200, [{ id: "legacy-reservation", service_name: "洗髮", customer_name: "王小姐", starts_at: "2026-10-01T02:00:00Z", status: "CONFIRMED" }]);
    return true;
  }
  if (endpoint === "/overview") {
    sendJson(response, 200, {
      tenant,
      has_reservations: true,
      open_handoff_count: portalHandoffs.length,
      line_channel: {
        configured: true,
        enabled: true,
        webhook_url: "https://example.test/webhooks/line/demo",
      },
      business_hours: [{ active: true }],
      booking_services: portalBookingServices,
      booking_add_ons: portalBookingAddOns,
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
  const ownedAddOn = endpoint.match(/^\/booking-services\/([^/]+)\/add-ons(?:\/([^/]+))?$/);
  if (ownedAddOn && ["POST", "PUT"].includes(request.method)) {
    readJson(request).then((body) => {
      const service = portalBookingServices.find((item) => item.id === ownedAddOn[1]);
      if (!service) return sendJson(response, 404, { detail: "Service not found" });
      if (request.method === "POST") {
        const addOn = { id: `add-on-${portalBookingAddOns.length + 1}`, ...body, active: true };
        portalBookingAddOns.push(addOn);
        service.add_ons.push(addOn);
        sendJson(response, 201, addOn);
      } else {
        const addOn = service.add_ons.find((item) => item.id === ownedAddOn[2]);
        if (!addOn) return sendJson(response, 404, { detail: "Add-on not found" });
        Object.assign(addOn, body);
        sendJson(response, 200, addOn);
      }
    });
    return true;
  }
  if (endpoint === "/booking-add-ons" && request.method === "POST") {
    readJson(request).then((body) => {
      const addOn = {
        id: `add-on-${portalBookingAddOns.length + 1}`,
        tenant_id: tenant.id,
        ...body,
        active: true,
      };
      portalBookingAddOns.push(addOn);
      sendJson(response, 201, addOn);
    });
    return true;
  }
  if (endpoint.startsWith("/booking-add-ons/") && request.method === "PUT") {
    readJson(request).then((body) => {
      const addOn = portalBookingAddOns.find(
        (item) => item.id === endpoint.slice("/booking-add-ons/".length)
      );
      Object.assign(addOn, body);
      sendJson(response, 200, addOn);
    });
    return true;
  }
  if (endpoint === "/booking-services" && request.method === "POST") {
    readJson(request).then((body) => {
      const service = {
        id: `service-${portalBookingServices.length + 1}`,
        tenant_id: tenant.id,
        name: body.name,
        description: body.description,
        duration_minutes: body.duration_minutes,
        price_amount: body.price_amount,
        active: true,
        add_ons: portalBookingAddOns.filter((item) => body.add_on_ids.includes(item.id)),
      };
      portalBookingServices.push(service);
      sendJson(response, 201, service);
    });
    return true;
  }
  if (endpoint.startsWith("/booking-services/") && request.method === "PUT") {
    readJson(request).then((body) => {
      const service = portalBookingServices.find(
        (item) => item.id === endpoint.slice("/booking-services/".length)
      );
      Object.assign(service, body, {
        add_ons: portalBookingAddOns.filter((item) => body.add_on_ids.includes(item.id)),
      });
      delete service.add_on_ids;
      sendJson(response, 200, service);
    });
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
      currency: "TWD",
      services: [{
        id: "service-1",
        name: "洗頭",
        description: "基礎洗髮服務",
        duration_minutes: 60,
        price_amount: 300,
        add_ons: [{
          id: "add-on-1",
          name: "護髮",
          description: "增加保濕護理",
          duration_minutes: 60,
          price_amount: 500,
        }],
      }],
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
      add_on_ids: url.searchParams.getAll("add_on_ids"),
      duration_minutes: url.searchParams.has("add_on_ids") ? 120 : 60,
      total_price_amount: url.searchParams.has("add_on_ids") ? 800 : 300,
      slots: [{ starts_at: slotFor(date), available: true }],
    });
    return true;
  }
  if (endpoint === "/reservations" && request.method === "POST") {
    readJson(request).then((body) => sendJson(response, 201, {
      id: "reservation-1",
      starts_at: body.starts_at,
      customer_name: body.customer_name,
      service_name: "洗頭",
      add_ons: body.add_on_ids?.includes("add-on-1")
        ? [{ name: "護髮", duration_minutes: 60, price_amount: 500 }]
        : [],
      total_duration_minutes: body.add_on_ids?.includes("add-on-1") ? 120 : 60,
      total_price_amount: body.add_on_ids?.includes("add-on-1") ? 800 : 300,
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
          service_name: "洗頭",
          add_ons: [{ name: "護髮", duration_minutes: 60, price_amount: 500 }],
          total_duration_minutes: 120,
          total_price_amount: 800,
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
  if (await portalApi(url, request, response)) return;
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
