const state = {
  csrfToken: null,
  tenant: null,
  overview: null,
  documents: [],
  activeView: "overview",
};

const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => [...document.querySelectorAll(selector)];

async function api(path, options = {}) {
  const headers = { ...(options.headers || {}) };
  if (options.body && !(options.body instanceof FormData)) headers["Content-Type"] = "application/json";
  if (state.csrfToken && options.method && options.method !== "GET") {
    headers["X-CSRF-Token"] = state.csrfToken;
  }
  const response = await fetch(`/portal/api${path}`, {
    credentials: "same-origin",
    ...options,
    headers,
  });
  if (!response.ok) {
    let message = `操作失敗（${response.status}）`;
    try {
      const error = await response.json();
      message = error.detail || message;
    } catch (_) {}
    throw new Error(message);
  }
  if (response.status === 204 || response.headers.get("content-length") === "0") return null;
  return response.json();
}

function toast(message, error = false) {
  const element = $("#toast");
  element.textContent = message;
  element.className = `toast show${error ? " error" : ""}`;
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { element.className = "toast"; }, 3200);
}

function formData(form) {
  return Object.fromEntries(new FormData(form).entries());
}

function setSubmitting(form, submitting, pendingLabel) {
  const button = form.querySelector('button[type="submit"]');
  if (!button.dataset.defaultLabel) button.dataset.defaultLabel = button.textContent;
  button.disabled = submitting;
  button.textContent = submitting ? pendingLabel : button.dataset.defaultLabel;
}

function showTenantApiKey(apiKey, tenantId) {
  if (!apiKey) return;
  $("#tenant-login-id").textContent = tenantId;
  $("#tenant-api-key").textContent = apiKey;
  $("#tenant-key-notice").classList.remove("hidden");
}

async function restoreSession() {
  try {
    const session = await api("/session");
    if (session.authenticated) {
      state.csrfToken = session.csrf_token;
      state.tenant = session.tenant;
      await enterApp();
    }
  } catch (_) {
    showAuth();
  }
}

function showAuth() {
  $("#auth-view").classList.remove("hidden");
  $("#app-view").classList.add("hidden");
}

async function enterApp() {
  $("#auth-view").classList.add("hidden");
  $("#app-view").classList.remove("hidden");
  $("#merchant-mini").innerHTML = `<strong>${escapeHtml(state.tenant.name)}</strong><br><small>${escapeHtml(state.tenant.slug)}</small>`;
  await refreshOverview();
}

async function refreshOverview() {
  state.overview = await api("/overview");
  state.tenant = state.overview.tenant;
  const datasets = state.overview.datasets || [];
  const preferred = datasets.find((item) => item.status === "DRAFT")
    || datasets.find((item) => item.status === "ACTIVE")
    || datasets[0];

  const select = $("#dataset-select");
  select.innerHTML = datasets.map((item) =>
    `<option value="${item.id}" ${item.id === preferred?.id ? "selected" : ""}>${escapeHtml(item.name)} v${item.version} · ${item.status}</option>`
  ).join("");

  if (preferred) await loadDocuments(preferred.id);
  renderOverview();
  renderSettings();
}

async function loadDocuments(datasetId) {
  state.documents = datasetId ? await api(`/documents?datasetId=${encodeURIComponent(datasetId)}`) : [];
  renderDocuments();
}

function renderOverview() {
  const overview = state.overview;
  const hasLine = overview.line_channel.configured;
  const hasHours = overview.business_hours.some((item) => item.active);
  const hasKnowledge = state.documents.some((item) => item.index_status === "READY");
  const activeDataset = overview.datasets.find((item) => item.status === "ACTIVE");
  const checks = [
    [true, "商家空間", "基本資料與租戶隔離已建立"],
    [hasLine, "LINE 官方帳號", hasLine ? "Channel 已安全連接" : "加入 Secret 與 Access Token"],
    [hasHours, "營業與預約", hasHours ? "已有可用營業時間" : "設定服務時間"],
    [hasKnowledge, "可信知識", hasKnowledge ? "已有完成索引的文件" : "加入第一份客服資料"],
  ];
  const progress = Math.round(checks.filter(([done]) => done).length / checks.length * 100);
  $("#progress-number").textContent = `${progress}%`;
  $("#progress-ring").style.background = `conic-gradient(var(--green) ${progress}%, #e5e7e2 ${progress}%)`;
  $("#document-count").textContent = state.documents.length;
  $("#publish-state").textContent = activeDataset ? "已發布" : "草稿";
  $("#publish-time").textContent = activeDataset?.published_at
    ? new Date(activeDataset.published_at).toLocaleString("zh-TW")
    : "尚未發布";
  $("#checklist").innerHTML = checks.map(([done, title, copy], index) => `
    <article class="check-item ${done ? "done" : ""}">
      <span class="check-icon">${done ? "✓" : index + 1}</span>
      <strong>${title}</strong><p>${copy}</p>
    </article>`).join("");
  $("#system-pill").textContent = activeDataset ? "客服知識已上線" : "尚未發布知識";
}

function renderDocuments() {
  const list = $("#document-list");
  if (!state.documents.length) {
    list.innerHTML = `<div class="empty-state"><strong>還沒有文件</strong><p>從左側加入第一份已確認的商家知識。</p></div>`;
    return;
  }
  list.innerHTML = state.documents.map((item) => `
    <article class="document-item">
      <div class="document-item-head">
        <strong>${escapeHtml(item.title)}</strong>
        <span class="badge ${item.index_status === "FAILED" ? "failed" : ""}">${item.index_status}</span>
      </div>
      <p>${escapeHtml(item.content.slice(0, 110))}${item.content.length > 110 ? "…" : ""}</p>
    </article>`).join("");
}

function renderSettings() {
  const line = state.overview.line_channel;
  $("#webhook-url").textContent = line.webhook_url;
  $("#line-status").textContent = line.configured
    ? `已設定，${line.enabled ? "目前啟用中" : "目前停用"}`
    : "尚未連接 LINE Channel";
}

function switchView(name) {
  state.activeView = name;
  $$(".view").forEach((view) => view.classList.add("hidden"));
  $(`#view-${name}`).classList.remove("hidden");
  $$(".nav-item").forEach((item) => item.classList.toggle("active", item.dataset.view === name));
  const titles = {
    overview: ["MERCHANT OVERVIEW", "今天，讓客服再可靠一點。"],
    knowledge: ["KNOWLEDGE STUDIO", "把經驗整理成可信的知識。"],
    tester: ["ANSWER LAB", "每次發布前，都先問一次。"],
    settings: ["CHANNEL SETUP", "把 LINE 接到商家的服務流程。"],
  };
  $("#page-eyebrow").textContent = titles[name][0];
  $("#page-title").textContent = titles[name][1];
}

function escapeHtml(value = "") {
  return String(value).replace(/[&<>"']/g, (char) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#039;",
  })[char]);
}

$$("[data-auth-tab]").forEach((button) => button.addEventListener("click", () => {
  $$("[data-auth-tab]").forEach((item) => item.classList.toggle("active", item === button));
  $("#login-form").classList.toggle("hidden", button.dataset.authTab !== "login");
  $("#onboard-form").classList.toggle("hidden", button.dataset.authTab !== "onboard");
}));

$("#login-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const data = formData(form);
  setSubmitting(form, true, "登入中…");
  try {
    const session = await api("/session", {
      method: "POST",
      body: JSON.stringify({ tenant_id: data.tenant_id, api_key: data.api_key }),
    });
    state.csrfToken = session.csrf_token;
    state.tenant = session.tenant;
    form.reset();
    await enterApp();
  } catch (error) {
    toast(error.message, true);
  } finally {
    setSubmitting(form, false, "登入中…");
  }
});

$("#onboard-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const data = formData(form);
  setSubmitting(form, true, "建立商家中…");
  try {
    const session = await api("/onboarding", {
      method: "POST",
      body: JSON.stringify({
        platform_admin_key: data.platform_key,
        tenant: {
          name: data.name,
          slug: data.slug,
          timezone: data.timezone,
          slot_minutes: Number(data.slot_minutes),
        },
      }),
    });
    state.csrfToken = session.csrf_token;
    state.tenant = session.tenant;
    form.reset();
    showTenantApiKey(session.tenant_api_key, session.tenant.id);
    await enterApp();
    toast("商家空間已建立");
  } catch (error) {
    toast(error.message, true);
  } finally {
    setSubmitting(form, false, "建立商家中…");
  }
});

$("#copy-tenant-key").addEventListener("click", async () => {
  try {
    await navigator.clipboard.writeText(
      `Tenant ID: ${$("#tenant-login-id").textContent}\nTenant Admin API Key: ${$("#tenant-api-key").textContent}`
    );
    toast("登入資料已複製");
  } catch (_) {
    toast("無法存取剪貼簿，請手動複製", true);
  }
});

$("#dismiss-tenant-key").addEventListener("click", () => {
  $("#tenant-login-id").textContent = "";
  $("#tenant-api-key").textContent = "";
  $("#tenant-key-notice").classList.add("hidden");
});

$("#logout-button").addEventListener("click", async () => {
  await api("/session", { method: "DELETE" });
  state.csrfToken = null;
  state.tenant = null;
  showAuth();
});

$$(".nav-item").forEach((button) => button.addEventListener("click", () => switchView(button.dataset.view)));

$("#dataset-select").addEventListener("change", (event) => loadDocuments(event.target.value));

$("#document-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const data = formData(form);
  const datasetId = $("#dataset-select").value;
  if (!datasetId) return toast("請先建立資料集", true);
  setSubmitting(form, true, "加入並索引中…");
  try {
    await api(`/documents?datasetId=${encodeURIComponent(datasetId)}`, {
      method: "POST",
      body: JSON.stringify({
        title: data.title,
        content: data.content,
        source_url: data.source_url || null,
      }),
    });
    form.reset();
    await loadDocuments(datasetId);
    renderOverview();
    toast("文件已加入並完成索引");
  } catch (error) {
    toast(error.message, true);
  } finally {
    setSubmitting(form, false, "加入並索引中…");
  }
});

$("#upload-button").addEventListener("click", async () => {
  const input = $("#knowledge-file");
  const datasetId = $("#dataset-select").value;
  if (!datasetId) return toast("請先建立資料集", true);
  if (!input.files.length) return toast("請先選擇檔案", true);
  const body = new FormData();
  body.append("file", input.files[0]);
  try {
    await api(`/documents/upload?datasetId=${encodeURIComponent(datasetId)}`, {
      method: "POST",
      body,
    });
    input.value = "";
    await loadDocuments(datasetId);
    renderOverview();
    toast("檔案已上傳並完成索引");
  } catch (error) { toast(error.message, true); }
});

$("#answer-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const data = formData(form);
  setSubmitting(form, true, "產生回答中…");
  try {
    const result = await api("/answer", {
      method: "POST",
      body: JSON.stringify({ question: data.question }),
    });
    const citations = (result.citations || []).map((item) =>
      `<div class="citation"><strong>${escapeHtml(item.title)}</strong><br>${escapeHtml(item.snippet)}</div>`
    ).join("");
    $("#answer-result").classList.remove("empty");
    $("#answer-result").innerHTML = `
      <p class="eyebrow">AI RESPONSE · 信心 ${Math.round(result.confidence * 100)}%</p>
      <p class="answer-text">${escapeHtml(result.answer)}</p>
      ${citations || '<div class="citation">沒有引用來源，系統已採取保守回覆。</div>'}`;
  } catch (error) {
    toast(error.message, true);
  } finally {
    setSubmitting(form, false, "產生回答中…");
  }
});

$("#line-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const data = formData(form);
  setSubmitting(form, true, "儲存中…");
  try {
    await api("/line-channel", {
      method: "PUT",
      body: JSON.stringify({
        channel_secret: data.channel_secret,
        channel_access_token: data.channel_access_token,
        enabled: data.enabled === "on",
      }),
    });
    form.reset();
    await refreshOverview();
    toast("LINE Channel 已安全保存");
  } catch (error) {
    toast(error.message, true);
  } finally {
    setSubmitting(form, false, "儲存中…");
  }
});

$("#publish-button").addEventListener("click", async () => {
  const datasetId = $("#dataset-select").value;
  if (!datasetId) return toast("沒有可發布的資料集", true);
  try {
    await api(`/datasets/publish?datasetId=${encodeURIComponent(datasetId)}`, { method: "POST" });
    await refreshOverview();
    toast("知識庫已發布");
  } catch (error) { toast(error.message, true); }
});

$("#reindex-button").addEventListener("click", async () => {
  const datasetId = $("#dataset-select").value;
  if (!datasetId) return toast("沒有可索引的資料集", true);
  try {
    const result = await api(`/datasets/reindex?datasetId=${encodeURIComponent(datasetId)}`, { method: "POST" });
    await loadDocuments(datasetId);
    toast(`重新索引完成：${result.indexed} 成功，${result.failed} 失敗`);
  } catch (error) { toast(error.message, true); }
});

$("#copy-webhook").addEventListener("click", async () => {
  try {
    await navigator.clipboard.writeText($("#webhook-url").textContent);
    toast("Webhook URL 已複製");
  } catch (_) { toast("無法存取剪貼簿，請手動複製", true); }
});

restoreSession();
