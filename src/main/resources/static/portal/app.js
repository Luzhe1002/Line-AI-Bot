const state = {
  csrfToken: null,
  tenant: null,
  overview: null,
  documents: [],
  staff: [],
  selectedDatasetId: null,
  editingDocumentId: null,
  activeView: "overview",
};

const $ = (selector) => document.querySelector(selector);
const $$ = (selector) => [...document.querySelectorAll(selector)];

async function refreshSessionForRetry() {
  try {
    const response = await fetch("/portal/api/session", { credentials: "same-origin" });
    if (!response.ok) return false;
    const session = await response.json();
    if (!session.authenticated || !session.csrf_token) return false;
    state.csrfToken = session.csrf_token;
    state.tenant = session.tenant;
    return true;
  } catch (_) {
    return false;
  }
}

function expirePortalSession() {
  state.csrfToken = null;
  state.tenant = null;
  showAuth();
}

async function api(path, options = {}, retryCsrf = true) {
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
    if (response.status === 403
        && message === "Invalid CSRF token"
        && retryCsrf
        && options.method
        && options.method !== "GET") {
      if (await refreshSessionForRetry()) return api(path, options, false);
      expirePortalSession();
      throw new Error("登入狀態已過期，請重新登入後再操作");
    }
    throw new Error(message);
  }
  if (response.status === 204 || response.headers.get("content-length") === "0") return null;
  return response.json();
}

function toast(message, error = false) {
  const element = $("#toast");
  element.textContent = message;
  element.className = `toast show${error ? " error" : ""}`;
  element.setAttribute("role", error ? "alert" : "status");
  element.setAttribute("aria-live", error ? "assertive" : "polite");
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

async function exchangeLineOwnerToken() {
  const fragment = new URLSearchParams(window.location.hash.slice(1));
  const token = fragment.get("token");
  if (!token) return false;
  window.history.replaceState(null, "", `${window.location.pathname}${window.location.search}`);
  try {
    const session = await api("/line-session", {
      method: "POST",
      headers: { Authorization: `Bearer ${token}` },
    });
    state.csrfToken = session.csrf_token;
    state.tenant = session.tenant;
    await enterApp();
    toast("已透過店家擁有者 LINE 安全登入");
    return true;
  } catch (error) {
    showAuth();
    toast(error.message, true);
    return false;
  }
}

function showAuth() {
  $("#auth-view").classList.remove("hidden");
  $("#app-view").classList.add("hidden");
}

async function enterApp() {
  $("#portal-reservations").replaceChildren();
  $("#portal-handoffs").replaceChildren();
  $("#auth-view").classList.add("hidden");
  $("#app-view").classList.remove("hidden");
  $("#merchant-mini").innerHTML = `<strong>${escapeHtml(state.tenant.name)}</strong><br><small>${escapeHtml(state.tenant.slug)}</small>`;
  await refreshOverview();
  await loadStaff();
}

async function refreshOverview() {
  state.overview = await api("/overview");
  state.tenant = state.overview.tenant;
  const datasets = state.overview.datasets || [];
  const preferred = datasets.find((item) => item.status === "DRAFT")
    || datasets.find((item) => item.status === "ACTIVE")
    || datasets[0];
  state.selectedDatasetId = preferred?.id || null;
  if (preferred) await loadDocuments(preferred.id);
  else {
    state.documents = [];
    renderDocuments();
  }
  renderOverview();
  renderSettings();
}

async function loadDocuments(datasetId) {
  state.documents = datasetId ? await api(`/documents?datasetId=${encodeURIComponent(datasetId)}`) : [];
  resetDocumentForm();
  renderDocuments();
}

function selectedDataset() {
  return state.overview?.datasets?.find(
    (item) => item.id === state.selectedDatasetId
  ) || null;
}

async function ensureEditableDataset() {
  const selected = selectedDataset();
  if (!selected) throw new Error("請先建立資料集");
  if (selected.status === "DRAFT") return selected.id;

  toast("正在從正式版建立新版草稿…");
  const draft = await api(`/datasets/draft?datasetId=${encodeURIComponent(selected.id)}`, {
    method: "POST",
  });
  await refreshOverview();
  state.selectedDatasetId = draft.id;
  if (selectedDataset()?.id !== draft.id || state.documents[0]?.dataset_id !== draft.id) {
    await loadDocuments(draft.id);
  }
  return draft.id;
}

function renderOverview() {
  const overview = state.overview;
  const hasLine = overview.line_channel.configured;
  const hasKnowledge = state.documents.some((item) => item.index_status === "READY");
  const activeDataset = overview.datasets.find((item) => item.status === "ACTIVE");
  const checks = [
    { done: true, title: "商家空間", copy: "基本資料與租戶隔離已建立", view: "overview" },
    { done: hasLine, title: "LINE 官方帳號", copy: hasLine ? "Channel 已安全連接" : "加入 Secret 與 Access Token", view: "line" },
    { done: hasKnowledge, title: "可信知識", copy: hasKnowledge ? "已有完成索引的文件" : "加入第一份客服資料", view: "knowledge" },
    { done: Boolean(activeDataset), title: "發布客服知識", copy: activeDataset ? "顧客已能使用正式知識" : "測試後發布目前草稿", view: "knowledge" },
  ];
  const progress = Math.round(checks.filter((item) => item.done).length / checks.length * 100);
  const activeStaff = state.staff.filter((staff) => staff.status === "ACTIVE").length;
  const nextStep = checks.find((item) => !item.done);

  $("#progress-number").textContent = `${progress}%`;
  $("#progress-ring").style.background = `conic-gradient(var(--green) ${progress}%, #e5e7e2 ${progress}%)`;
  $("#document-count").textContent = state.documents.length;
  $("#staff-count").textContent = activeStaff;
  $("#staff-count-copy").textContent = state.staff.length
    ? `${activeStaff} 位啟用，共 ${state.staff.length} 位`
    : "尚未綁定 LINE 人員";
  $("#publish-state").textContent = activeDataset ? "已發布" : "草稿";
  $("#publish-time").textContent = activeDataset?.published_at
    ? new Date(activeDataset.published_at).toLocaleString("zh-TW")
    : "尚未發布";

  $("#line-metric-value").textContent = hasLine
    ? (overview.line_channel.enabled ? "已連線" : "已停用")
    : "未設定";
  $("#line-metric-copy").textContent = hasLine
    ? (overview.line_channel.enabled ? "目前可接收與回覆訊息" : "憑證已保存，回覆功能停用")
    : "尚未加入 Channel 憑證";
  $("#line-metric-dot").className = `metric-dot ${hasLine && overview.line_channel.enabled ? "ready" : "off"}`;

  $("#checklist").innerHTML = checks.map((item, index) => `
    <button class="check-item ${item.done ? "done" : ""}" data-go-view="${item.view}" type="button">
      <span class="check-icon">${item.done ? "✓" : index + 1}</span>
      <span class="check-copy"><strong>${item.title}</strong><p>${item.copy}</p></span>
      <span class="check-arrow" aria-hidden="true">›</span>
    </button>`).join("");

  const ready = hasLine && hasKnowledge && Boolean(activeDataset);
  const banner = $("#overview-banner");
  banner.classList.toggle("warning", !ready);
  $("#overview-status-title").textContent = ready ? "AI 客服已準備好服務顧客" : "還有一個重要步驟需要完成";
  $("#overview-status-copy").textContent = ready
    ? `LINE 已連線，${state.documents.length} 份知識可供顧客查詢。`
    : (nextStep?.copy || "請確認 LINE 與知識庫設定。");
  const primaryAction = $("#overview-primary-action");
  primaryAction.dataset.goView = nextStep?.view || "tester";
  primaryAction.textContent = nextStep ? `前往${nextStep.title}` : "測試 AI 回答";
  $("#system-pill").textContent = ready ? "營運準備完成" : `${progress}% 已完成`;
}

function renderDocuments() {
  const list = $("#document-list");
  const dataset = selectedDataset();
  const editable = dataset?.status === "DRAFT";
  const activeDocuments = state.documents.filter((item) => item.active !== false);
  const unreadyDocuments = activeDocuments.filter((item) => item.index_status !== "READY");
  const canPublish = editable && activeDocuments.length > 0 && unreadyDocuments.length === 0;
  const publishButton = $("#publish-button");
  publishButton.disabled = !canPublish;
  publishButton.title = canPublish
    ? ""
    : (!editable
      ? "修改內容或重新索引後，系統會建立可發布的新版草稿"
      : (activeDocuments.length === 0
        ? "至少新增一筆知識才能發布"
        : "請等待所有知識完成索引後再發布"));
  $("#document-total").textContent = `${state.documents.length} 筆`;
  const version = $("#knowledge-version");
  if (dataset) {
    const draft = dataset.status === "DRAFT";
    version.className = `knowledge-version ${draft ? "draft" : "published"}`;
    version.textContent = `${draft ? "待發布草稿" : "目前正式版"} · v${dataset.version}`;
  } else {
    version.className = "knowledge-version";
    version.textContent = "尚未建立版本";
  }
  if (!state.documents.length) {
    list.innerHTML = `<div class="empty-state"><strong>還沒有知識</strong><p>先新增顧客最常詢問的服務、價格或取消政策。</p><button class="primary compact" data-open-document-form type="button">新增第一筆知識</button></div>`;
    return;
  }
  list.innerHTML = state.documents.map((item) => `
    <article class="document-item">
      <div class="document-item-head">
        <strong>${escapeHtml(item.title)}</strong>
        <span class="badge ${item.index_status === "FAILED" ? "failed" : ""}">${UiUtils.knowledgeIndexStatusLabel(item.index_status)}</span>
      </div>
      <p>${escapeHtml(item.content.slice(0, 110))}${item.content.length > 110 ? "…" : ""}</p>
      <div class="document-actions">
        <button class="text-button" type="button" data-document-action="edit" data-document-id="${item.id}">編輯</button>
        <button class="text-button danger" type="button" data-document-action="delete" data-document-id="${item.id}">刪除</button>
        ${editable ? "" : "<small>修改時會自動建立草稿，不會立即影響顧客。</small>"}
      </div>
    </article>`).join("");
}

function resetDocumentForm(hide = true) {
  const form = $("#document-form");
  state.editingDocumentId = null;
  form.reset();
  $("#document-form-eyebrow").textContent = "NEW SOURCE";
  $("#document-form-title").textContent = "新增知識";
  $("#document-submit").textContent = "新增並自動索引";
  $("#document-submit").dataset.defaultLabel = "新增並自動索引";
  form.classList.toggle("hidden", hide);
}

function openDocumentForm() {
  resetDocumentForm(false);
  const form = $("#document-form");
  form.scrollIntoView({ behavior: "smooth", block: "start" });
  form.elements.title.focus({ preventScroll: true });
}

function sameDocumentContent(left, right) {
  return left.title === right.title
    && left.content === right.content
    && (left.source_url || "") === (right.source_url || "");
}

async function ensureEditableDocument(document) {
  if (selectedDataset()?.status !== "DRAFT") {
    await ensureEditableDataset();
  }
  const current = state.documents.find((item) => item.id === document.id);
  if (current) return current;
  const copied = state.documents.find((item) => sameDocumentContent(item, document));
  if (!copied) throw new Error("無法在新草稿中找到這筆知識，請重新整理後再試");
  return copied;
}

async function editDocument(documentId) {
  const original = state.documents.find((item) => item.id === documentId);
  if (!original) return;
  const document = await ensureEditableDocument(original);
  state.editingDocumentId = document.id;
  const form = $("#document-form");
  form.elements.title.value = document.title;
  form.elements.content.value = document.content;
  form.elements.source_url.value = document.source_url || "";
  $("#document-form-eyebrow").textContent = "EDIT SOURCE";
  $("#document-form-title").textContent = "編輯知識";
  $("#document-submit").textContent = "儲存修改";
  $("#document-submit").dataset.defaultLabel = "儲存修改";
  form.classList.remove("hidden");
  form.scrollIntoView({ behavior: "smooth", block: "start" });
  form.elements.title.focus({ preventScroll: true });
}

function bookingEnabled() { return state.tenant?.booking_enabled !== false; }

function renderFeatures() {
  const enabled = bookingEnabled();
  $("#features-form").elements.booking_enabled.checked = enabled;
  $$("[data-booking-only]").forEach((el) => el.classList.toggle("hidden", !enabled));
  $("#merchant-mode").textContent = enabled ? "客服＋預約" : "純客服";
  $("#handoff-count").textContent = state.overview.open_handoff_count || 0;
  $("#reservation-panel").classList.toggle("hidden", !enabled && !state.overview.has_reservations);
  $("#reservation-panel-title").textContent = enabled ? "預約管理" : "既有預約管理";
  $("#reservation-mode-copy").textContent = enabled ? "查看預約與處理取消。時段封鎖請從 LINE 開啟預約月曆。" : "已停止接受新預約；仍可查看與取消既有預約。";
  $("#manager-role-guide").textContent = enabled ? "顯示「預約管理」，可處理預約與封鎖時段。" : "顯示店家資訊與待處理客服案件數。";
  $("#viewer-role-guide").textContent = enabled ? "顯示「預約管理」，但只能查看行程。" : "可查看店家資訊與待處理客服案件數。";
  if (!enabled && state.activeView === "services") switchView("settings");
}

function renderSettings() {
  renderFeatures();
  $("#merchant-settings-summary").innerHTML = [
    ["商家名稱", state.tenant.name],
    ["網址代稱", state.tenant.slug],
    ["時區", state.tenant.timezone],
    ...(bookingEnabled() ? [["預約間隔", `${state.tenant.slot_minutes} 分鐘`]] : []),
  ].map(([label, value]) => `<dt>${escapeHtml(label)}</dt><dd>${escapeHtml(value)}</dd>`).join("");
  const line = state.overview.line_channel;
  const configured = Boolean(line.configured);
  const enabled = configured && Boolean(line.enabled);
  const setup = UiUtils.lineSetupState(configured, enabled);
  $$("[data-line-step]").forEach((step, index) => {
    const done = setup.completed[step.dataset.lineStep];
    const active = step.dataset.lineStep === setup.current;
    step.classList.toggle("done", done);
    step.classList.toggle("active", active);
    step.querySelector("span").textContent = done ? "✓" : String(index + 1);
    if (active) step.setAttribute("aria-current", "step");
    else step.removeAttribute("aria-current");
  });
  $("#line-setup-status").textContent = setup.message;
  $("#webhook-url").textContent = line.webhook_url;
  const health = $("#channel-health-card");
  health.classList.toggle("ready", enabled);
  health.classList.toggle("off", !enabled);
  $("#channel-health-title").textContent = configured
    ? (enabled ? "LINE Channel 已連線" : "LINE Channel 已停用")
    : "尚未連接 LINE Channel";
  $("#line-status").textContent = configured
    ? (enabled ? "Webhook 已建立，可接收與回覆顧客訊息。" : "憑證已保存，但目前不會回覆訊息。")
    : "完成左側憑證設定後，再把 Webhook URL 貼到 LINE Developers Console。";
  renderBookingSettings();
}

function formatMoney(amount) {
  return new Intl.NumberFormat("zh-TW", {
    style: "currency",
    currency: "TWD",
    maximumFractionDigits: 0,
  }).format(amount || 0);
}

function addOnFields(addOn = {}) {
  return `<label>加購名稱<input name="name" maxlength="160" required value="${escapeHtml(addOn.name || "")}"></label>
    <label>說明<textarea name="description" maxlength="2000">${escapeHtml(addOn.description || "")}</textarea></label>
    <div class="two-col">
      <label>增加時間（分鐘）<input name="duration_minutes" type="number" min="0" max="1440" step="${state.tenant.slot_minutes}" required value="${addOn.duration_minutes || 0}"></label>
      <label>增加費用（NT$）<input name="price_amount" type="number" min="0" required value="${addOn.price_amount || 0}"></label>
    </div>
    <label class="switch-row"><input name="active" type="checkbox" ${addOn.active ? "checked" : ""}><span>開放顧客加購</span></label>`;
}

function renderBookingSettings() {
  // Keep the single creation form alive when replacing a service card.
  const addOnForm = $("#add-on-form");
  $(".booking-catalog-grid").append(addOnForm);
  addOnForm.classList.add("hidden");
  const slot = state.tenant.slot_minutes;
  const form = $("#booking-service-form");
  form.elements.duration_minutes.min = String(slot);
  form.elements.duration_minutes.step = String(slot);
  if (!form.elements.duration_minutes.value) form.elements.duration_minutes.value = String(slot);
  addOnForm.elements.duration_minutes.step = String(slot);
  if (!addOnForm.elements.duration_minutes.value) addOnForm.elements.duration_minutes.value = "0";
  $("#booking-service-list").innerHTML = (state.overview.booking_services || []).map((service) => `
    <article class="catalog-card" data-service-id="${escapeHtml(service.id)}">
      <div class="catalog-item">
        <div class="catalog-heading">
          <div class="catalog-title"><strong>${escapeHtml(service.name)}</strong>
            <small>${service.duration_minutes} 分鐘 · ${escapeHtml(formatMoney(service.price_amount))}</small>
            <small>${service.add_ons.length} 個加購：${escapeHtml(service.add_ons.map((a) => a.name + (a.active ? "" : "（已停用）")).join("、") || "目前無加購，可直接預約")}</small>
          </div>
          <span class="catalog-status">${service.active ? "開放預約" : "已停用，顧客看不到"}</span>
        </div>
        <div class="catalog-edit hidden" data-service-editor>
          <label>服務名稱<input name="name" maxlength="160" required value="${escapeHtml(service.name)}"></label>
          <label>說明<textarea name="description" maxlength="2000">${escapeHtml(service.description || "")}</textarea></label>
          <div class="two-col">
            <label>基本時間（分鐘）<input name="duration_minutes" type="number" min="${slot}" max="1440" step="${slot}" required value="${service.duration_minutes}"></label>
            <label>基本價格（NT$）<input name="price_amount" type="number" min="0" required value="${service.price_amount}"></label>
          </div>
          <label class="switch-row"><input name="active" type="checkbox" ${service.active ? "checked" : ""}><span>開放顧客預約</span></label>
          <button class="primary" data-save-service type="button">儲存主服務</button>
          <button class="secondary" data-cancel-catalog-edit type="button">取消編輯</button>
        </div>
      </div>
      <div class="catalog-actions">
        <button class="secondary" data-edit-service type="button">編輯主服務</button>
        <button class="secondary" data-manage-addons type="button" aria-expanded="false">管理加購</button>
      </div>
      <section class="service-addons hidden" aria-label="${escapeHtml(service.name)}的加購">
        <h4>${escapeHtml(service.name)}的加購</h4>
        <p class="muted">只適用於這個主服務。儲存後自動提供給顧客，不需另外綁定。</p>
        ${service.add_ons.length ? service.add_ons.map((a) => `
          <details class="catalog-item" data-add-on-id="${escapeHtml(a.id)}">
            <summary><span class="catalog-title"><strong>${escapeHtml(a.name)}</strong><small>+${a.duration_minutes} 分鐘 · +${escapeHtml(formatMoney(a.price_amount))}</small></span><span class="catalog-status">${a.active ? "可加購" : "已停用"} · 編輯</span></summary>
            <div class="catalog-edit">${addOnFields(a)}
              <button class="primary" data-save-owned-addon type="button">儲存加購</button>
              <button class="secondary" data-cancel-catalog-edit type="button">取消編輯</button>
            </div>
          </details>`).join("") : '<p class="catalog-empty">目前無加購，可直接預約主服務。</p>'}
        <button class="secondary" data-new-owned-addon type="button">新增加購</button>
      </section>
    </article>`).join("") || '<p class="catalog-empty">尚無主服務，點選「新增主服務」開始設定。</p>';
}

function showServiceAddOns(id) {
  const card = $$(".catalog-card").find((item) => item.dataset.serviceId === id);
  if (!card) return;
  card.querySelector(".service-addons").classList.remove("hidden");
  card.querySelector("[data-manage-addons]").setAttribute("aria-expanded", "true");
  card.querySelector("[data-new-owned-addon]").focus();
}
$("#catalog-create").addEventListener("click", () => {
  $("#booking-service-form").classList.remove("hidden");
  $("#booking-service-form input").focus();
});
$$("[data-close-catalog]").forEach((button) => button.addEventListener("click", () => {
  const form = button.closest("form");
  form.classList.add("hidden");
  const card = form.closest(".catalog-card");
  (card?.querySelector("[data-new-owned-addon]") || $("#catalog-create")).focus();
}));
function catalogSaved(message, serviceId) {
  $("#booking-service-form").classList.add("hidden");
  const feedback = $("#catalog-feedback");
  feedback.replaceChildren();
  const copy = document.createElement("p");
  copy.textContent = message;
  const next = document.createElement("button");
  next.type = "button";
  next.className = "secondary";
  next.textContent = "管理此服務的加購";
  next.addEventListener("click", () => showServiceAddOns(serviceId));
  feedback.append(copy, next);
  feedback.classList.remove("hidden");
  $$(".catalog-card").forEach((card) => card.classList.toggle("catalog-updated", card.dataset.serviceId === serviceId));
  feedback.focus();
}
$("#booking-service-list").addEventListener("click", async (event) => {
  const card = event.target.closest(".catalog-card");
  if (!card) return;
  const serviceId = card.dataset.serviceId;
  if (event.target.closest("[data-edit-service]")) {
    const editor = card.querySelector("[data-service-editor]");
    editor.classList.remove("hidden");
    editor.querySelector("input").focus();
  }
  if (event.target.closest("[data-manage-addons]")) {
    const panel = card.querySelector(".service-addons");
    panel.classList.toggle("hidden");
    card.querySelector("[data-manage-addons]").setAttribute("aria-expanded", String(!panel.classList.contains("hidden")));
  }
  if (event.target.closest("[data-new-owned-addon]")) {
    const form = $("#add-on-form");
    form.reset();
    form.dataset.serviceId = serviceId;
    form.querySelector("h3").textContent = `新增「${state.overview.booking_services.find((s) => s.id === serviceId).name}」的加購`;
    form.elements.duration_minutes.value = "0";
    card.querySelector(".service-addons").append(form);
    form.classList.remove("hidden");
    form.querySelector("input").focus();
  }
  const cancel = event.target.closest("[data-cancel-catalog-edit]");
  if (cancel) {
    const editor = cancel.closest(".catalog-edit");
    editor.querySelectorAll("input, textarea").forEach((input) => {
      if (input.type === "checkbox") input.checked = input.defaultChecked;
      else input.value = input.defaultValue;
    });
    const details = editor.closest("details");
    if (details) { details.open = false; details.querySelector("summary").focus(); }
    else { editor.classList.add("hidden"); card.querySelector("[data-edit-service]").focus(); }
  }
  const save = event.target.closest("[data-save-owned-addon]");
  if (save) {
    const editor = save.closest(".catalog-edit");
    if (![...editor.querySelectorAll("input, textarea")].every((input) => input.reportValidity())) return;
    const field = (name) => editor.querySelector(`[name="${name}"]`);
    const addOnId = save.closest("[data-add-on-id]").dataset.addOnId;
    save.disabled = true;
    save.textContent = "儲存中…";
    try {
      await api(`/booking-services/${encodeURIComponent(serviceId)}/add-ons/${encodeURIComponent(addOnId)}`, {
        method: "PUT",
        body: JSON.stringify({ name: field("name").value.trim(), description: field("description").value.trim() || null,
          duration_minutes: Number(field("duration_minutes").value), price_amount: Number(field("price_amount").value),
          active: field("active").checked }),
      });
      await refreshOverview();
      catalogSaved("加購已儲存，只影響這個主服務。已成立的預約內容不變。", serviceId);
      showServiceAddOns(serviceId);
    } catch (error) { toast(error.message, true); }
    finally { save.disabled = false; save.textContent = "儲存加購"; }
  }
});

async function loadStaff() {
  state.staff = await api("/staff");
  renderStaff();
  if (state.overview) renderOverview();
}

function renderStaff() {
  const list = $("#staff-list");
  if (!state.staff.length) {
    list.innerHTML = `<div class="empty-state"><strong>尚未綁定店家人員</strong><p>先產生擁有者綁定碼，再到 LINE 完成綁定。</p></div>`;
    return;
  }
  const roleDescriptions = {
    OWNER: "個人選單顯示「管理後台」，可進入完整工作台。",
    MANAGER: "個人選單顯示「預約管理」，可取消預約與封鎖時段。",
    VIEWER: "個人選單顯示「預約管理」，僅能查看行程。",
  };
  list.innerHTML = state.staff.map((staff) => {
    const roleLabel = UiUtils.roleLabel(staff.role);
    const initial = escapeHtml((staff.display_name || "店").trim().slice(0, 1));
    return `
    <article class="staff-item" data-staff-id="${escapeHtml(staff.id)}">
      <div class="staff-item-head">
        <div class="staff-identity">
          <span class="staff-avatar" aria-hidden="true">${initial}</span>
          <div>
            <strong class="staff-name">${escapeHtml(staff.display_name)}</strong>
            <div class="staff-meta"><span class="staff-role">${escapeHtml(roleLabel)}</span><small>${new Date(staff.created_at).toLocaleString("zh-TW")}</small></div>
          </div>
        </div>
        <button class="text-button danger staff-remove-button" data-remove-staff type="button">移除綁定</button>
      </div>
      <p class="staff-menu-note">${escapeHtml(bookingEnabled() ? roleDescriptions[staff.role] : (staff.role === "OWNER" ? "個人選單顯示「管理後台」，可進入完整工作台。" : "個人選單提供店家資訊與客服案件數。"))}</p>
      <details class="staff-details">
        <summary>調整權限與通知</summary>
        <div class="staff-fields">
          <label>顯示名稱<input data-staff-field="display_name" maxlength="160" value="${escapeHtml(staff.display_name)}"></label>
          <label>權限
            <select data-staff-field="role">
              <option value="OWNER" ${staff.role === "OWNER" ? "selected" : ""}>擁有者</option>
              <option value="MANAGER" ${staff.role === "MANAGER" ? "selected" : ""}>管理員</option>
              <option value="VIEWER" ${staff.role === "VIEWER" ? "selected" : ""}>檢視者</option>
            </select>
          </label>
          <label class="${!bookingEnabled() && !state.overview.has_reservations ? 'hidden' : ''}">每日摘要時間<input data-staff-field="daily_summary_time" type="time" value="${escapeHtml((staff.daily_summary_time || "08:00").slice(0, 5))}"></label>
        </div>
        <div class="staff-checks ${!bookingEnabled() && !state.overview.has_reservations ? 'hidden' : ''}">
          <label class="${bookingEnabled() ? '' : 'hidden'}"><input data-staff-field="notify_new_booking" type="checkbox" ${staff.notify_new_booking ? "checked" : ""}>新預約通知</label>
          <label><input data-staff-field="notify_cancellation" type="checkbox" ${staff.notify_cancellation ? "checked" : ""}>取消預約通知</label>
          <label><input data-staff-field="daily_summary_enabled" type="checkbox" ${staff.daily_summary_enabled ? "checked" : ""}>每日預約摘要</label>
        </div>
        <div class="staff-item-actions"><button class="primary compact" data-save-staff type="button">儲存設定</button></div>
      </details>
    </article>`;
  }).join("");
}

function switchView(name) {
  if (name === "services" && !bookingEnabled()) name = "settings";
  state.activeView = name;
  $$(".view").forEach((view) => view.classList.add("hidden"));
  $(`#view-${name}`).classList.remove("hidden");
  $$(".nav-item").forEach((item) => {
    const active = item.dataset.view === name || (name === "line" && item.dataset.view === "settings");
    item.classList.toggle("active", active);
    if (active) item.setAttribute("aria-current", "page");
    else item.removeAttribute("aria-current");
  });
  const titles = {
    overview: ["MERCHANT OVERVIEW", "今天，讓客服再可靠一點。", "先看營運狀態，再處理最重要的下一步。"],
    knowledge: ["KNOWLEDGE STUDIO", "把經驗整理成可信的知識。", "編輯、索引與發布都集中在同一個工作區。"],
    tester: ["ANSWER LAB", "每次發布前，都先問一次。", "用顧客的角度確認回答內容、信心與引用來源。"],
    staff: ["MERCHANT STAFF", "店家人員與 LINE 管理入口", "設定角色、通知與每位人員會看到的中文管理入口。"],
    settings: ["MERCHANT SETTINGS", "商家設定", "查看基本資料與管理 LINE 串接。"],
    services: ["SERVICE CATALOG", "服務項目", "管理主服務、加購、時間、價格與開放狀態。"],
    line: ["LINE CONNECTION", "LINE 串接", "設定官方帳號憑證與確認連線狀態。"],
  };
  $("#page-eyebrow").textContent = titles[name][0];
  $("#page-title").textContent = titles[name][1];
  $("#page-context").textContent = titles[name][2];
  window.scrollTo({ top: 0, left: 0, behavior: "auto" });
  $("#page-title").focus({ preventScroll: true });
}

function escapeHtml(value = "") {
  return String(value).replace(/[&<>"']/g, (char) => ({
    "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#039;",
  })[char]);
}

const authTabs = $$("[data-auth-tab]");

function activateAuthTab(button) {
  authTabs.forEach((item) => {
    const active = item === button;
    item.classList.toggle("active", active);
    item.setAttribute("aria-selected", String(active));
    item.tabIndex = active ? 0 : -1;
  });
  $("#login-form").classList.toggle("hidden", button.dataset.authTab !== "login");
  $("#onboard-form").classList.toggle("hidden", button.dataset.authTab !== "onboard");
}

authTabs.forEach((button, index) => {
  button.addEventListener("click", () => activateAuthTab(button));
  button.addEventListener("keydown", (event) => {
    const targetIndex = UiUtils.tabIndexForKey(index, event.key, authTabs.length);
    if (targetIndex === index || !["ArrowLeft", "ArrowRight", "Home", "End"].includes(event.key)) return;
    event.preventDefault();
    authTabs[targetIndex].focus();
    activateAuthTab(authTabs[targetIndex]);
  });
});

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

$("#onboard-form").elements.booking_enabled.addEventListener("change", (event) => {
  $("#onboard-booking-options").classList.toggle("hidden", !event.target.checked);
});

$("#features-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  setSubmitting(form, true, "儲存中…");
  try {
    state.tenant = await api("/features", { method: "PUT", body: JSON.stringify({ booking_enabled: form.elements.booking_enabled.checked }) });
    await refreshOverview();
    renderStaff();
    toast("功能已儲存，LINE 選單將在背景同步");
  } catch (error) { toast(error.message, true); }
  finally { setSubmitting(form, false); }
});

async function loadHandoffs() {
  const tenantId = state.tenant.id;
  const items = await api("/handoffs");
  if (state.tenant?.id !== tenantId) return;
  $("#portal-handoffs").innerHTML = items.length ? items.map((item) => `
    <article class="reservation-row"><div><strong>${escapeHtml(item.reason)}</strong>
    <p>案件編號：${escapeHtml(item.id)}</p>
    <small>${escapeHtml(new Date(item.created_at).toLocaleString("zh-TW", { timeZone: state.tenant.timezone }))}</small></div>
    <button class="secondary" data-close-handoff="${escapeHtml(item.id)}" type="button">標記已處理</button></article>`).join("") : "<p>目前沒有待處理案件。</p>";
}
$("#load-handoffs").addEventListener("click", async (event) => {
  event.target.disabled = true;
  try { await loadHandoffs(); } catch (error) { toast(error.message, true); }
  finally { event.target.disabled = false; }
});
$("#portal-handoffs").addEventListener("click", async (event) => {
  const button = event.target.closest("[data-close-handoff]");
  if (!button) return;
  button.disabled = true;
  try {
    await api(`/handoffs/${encodeURIComponent(button.dataset.closeHandoff)}/close`, { method: "POST" });
    await Promise.all([loadHandoffs(), refreshOverview()]);
    toast("案件已標記處理完成");
  } catch (error) { toast(error.message, true); button.disabled = false; }
});

async function loadPortalReservations() {
  const tenantId = state.tenant.id;
  const items = await api("/reservations");
  if (state.tenant?.id !== tenantId) return;
  $("#portal-reservations").innerHTML = items.length ? items.map((item) => `
    <article class="reservation-row"><div><strong>${escapeHtml(item.service_name)}</strong>
    <p>${escapeHtml(item.customer_name || "未填姓名")} · ${escapeHtml(new Date(item.starts_at).toLocaleString("zh-TW", { timeZone: state.tenant.timezone }))}</p>
    <small>${item.status === "CONFIRMED" ? "已確認" : "已取消"}</small></div>
    ${item.status === "CONFIRMED" ? `<button class="secondary" data-cancel-reservation="${escapeHtml(item.id)}" type="button">取消預約</button>` : ""}</article>`).join("") : "<p>目前沒有預約。</p>";
}
$("#load-reservations").addEventListener("click", async (event) => {
  event.target.disabled = true;
  try { await loadPortalReservations(); } catch (error) { toast(error.message, true); }
  finally { event.target.disabled = false; }
});
$("#portal-reservations").addEventListener("click", async (event) => {
  const button = event.target.closest("[data-cancel-reservation]");
  if (!button || !confirm("確定取消這筆預約？系統會通知顧客。")) return;
  button.disabled = true;
  try {
    await api(`/reservations/${encodeURIComponent(button.dataset.cancelReservation)}/cancel`, { method: "POST" });
    await loadPortalReservations();
    toast("預約已取消");
  } catch (error) { toast(error.message, true); button.disabled = false; }
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
          booking_enabled: form.elements.booking_enabled.checked,
        },
      }),
    });
    state.csrfToken = session.csrf_token;
    state.tenant = session.tenant;
    form.reset();
    $("#onboard-booking-options").classList.add("hidden");
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

$("#app-view").addEventListener("click", (event) => {
  const button = event.target.closest("[data-go-view]");
  if (!button) return;
  switchView(button.dataset.goView);
});

$$("[data-toggle-secret]").forEach((button) => {
  button.addEventListener("click", () => {
    const input = document.getElementById(button.dataset.toggleSecret);
    const visible = input.type === "text";
    input.type = visible ? "password" : "text";
    button.textContent = visible ? "顯示" : "隱藏";
    button.setAttribute("aria-pressed", String(!visible));
    input.focus({ preventScroll: true });
  });
});

$("#staff-link-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const data = formData(form);
  setSubmitting(form, true, "產生中…");
  try {
    const link = await api("/staff-links", {
      method: "POST",
      body: JSON.stringify({ display_name: data.display_name, role: data.role }),
    });
    const command = `綁定 ${link.code}`;
    $("#staff-link-command").textContent = command;
    $("#staff-link-expiry").textContent = `有效期限：${new Date(link.expires_at).toLocaleString("zh-TW")}`;
    $("#staff-link-result").classList.remove("hidden");
    toast("綁定碼已產生");
  } catch (error) {
    toast(error.message, true);
  } finally {
    setSubmitting(form, false, "產生中…");
  }
});

$("#copy-staff-link").addEventListener("click", async () => {
  try {
    await navigator.clipboard.writeText($("#staff-link-command").textContent);
    toast("LINE 綁定指令已複製");
  } catch (_) {
    toast("無法存取剪貼簿，請手動複製", true);
  }
});

$("#refresh-staff").addEventListener("click", async () => {
  try {
    await loadStaff();
    toast("人員清單已更新");
  } catch (error) {
    toast(error.message, true);
  }
});

$("#staff-list").addEventListener("click", async (event) => {
  const removeButton = event.target.closest("[data-remove-staff]");
  if (removeButton) {
    const item = removeButton.closest("[data-staff-id]");
    const staff = state.staff.find((entry) => entry.id === item.dataset.staffId);
    if (!staff || !window.confirm(
      `確定移除「${staff.display_name}」的 LINE 管理綁定？\n\n移除後會立即失去管理權限，個人圖文選單將在背景解除。`
    )) return;
    removeButton.disabled = true;
    try {
      await api(`/staff/${encodeURIComponent(item.dataset.staffId)}`, {
        method: "DELETE",
      });
      await loadStaff();
      toast("人員綁定已移除");
    } catch (error) {
      toast(error.message, true);
      removeButton.disabled = false;
    }
    return;
  }

  const button = event.target.closest("[data-save-staff]");
  if (!button) return;
  const item = button.closest("[data-staff-id]");
  const field = (name) => item.querySelector(`[data-staff-field="${name}"]`);
  button.disabled = true;
  try {
    await api(`/staff/${encodeURIComponent(item.dataset.staffId)}`, {
      method: "PUT",
      body: JSON.stringify({
        display_name: field("display_name").value.trim(),
        role: field("role").value,
        notify_new_booking: field("notify_new_booking").checked,
        notify_cancellation: field("notify_cancellation").checked,
        daily_summary_enabled: field("daily_summary_enabled").checked,
        daily_summary_time: field("daily_summary_time").value,
      }),
    });
    await loadStaff();
    toast("店家人員設定已儲存");
  } catch (error) {
    toast(error.message, true);
  } finally {
    button.disabled = false;
  }
});

$("#open-document-form").addEventListener("click", openDocumentForm);

$("#document-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const data = formData(form);
  const editingDocumentId = state.editingDocumentId;
  setSubmitting(form, true, editingDocumentId ? "儲存中…" : "新增中…");
  try {
    const datasetId = await ensureEditableDataset();
    const query = new URLSearchParams({ datasetId });
    if (editingDocumentId) query.set("documentId", editingDocumentId);
    await api(`/documents?${query}`, {
      method: editingDocumentId ? "PUT" : "POST",
      body: JSON.stringify({
        title: data.title,
        content: data.content,
        source_url: data.source_url || null,
      }),
    });
    await loadDocuments(datasetId);
    renderOverview();
    toast(editingDocumentId ? "知識已更新並完成索引" : "知識已新增並完成索引");
  } catch (error) {
    toast(error.message, true);
  } finally {
    setSubmitting(form, false, editingDocumentId ? "儲存中…" : "新增中…");
  }
});

$("#cancel-document-edit").addEventListener("click", () => resetDocumentForm());

$("#document-list").addEventListener("click", async (event) => {
  if (event.target.closest("[data-open-document-form]")) {
    openDocumentForm();
    return;
  }
  const button = event.target.closest("[data-document-action]");
  if (!button) return;
  const documentId = button.dataset.documentId;
  if (button.dataset.documentAction === "edit") {
    button.disabled = true;
    try {
      await editDocument(documentId);
    } catch (error) {
      toast(error.message, true);
    } finally {
      button.disabled = false;
    }
    return;
  }
  const document = state.documents.find((item) => item.id === documentId);
  if (!document || !window.confirm(
    `確定刪除「${document.title}」？\n\n刪除會先保存在草稿，發布更新後才會影響顧客。`
  )) return;
  button.disabled = true;
  try {
    const datasetId = await ensureEditableDataset();
    const editableDocument = await ensureEditableDocument(document);
    await api(`/documents?${new URLSearchParams({
      datasetId,
      documentId: editableDocument.id,
    })}`, {
      method: "DELETE",
    });
    await loadDocuments(datasetId);
    renderOverview();
    toast("知識已從草稿移除");
  } catch (error) {
    toast(error.message, true);
    button.disabled = false;
  }
});

$("#upload-button").addEventListener("click", async () => {
  const input = $("#knowledge-file");
  if (!input.files.length) return toast("請先選擇檔案", true);
  const body = new FormData();
  body.append("file", input.files[0]);
  try {
    const datasetId = await ensureEditableDataset();
    await api(`/documents/upload?datasetId=${encodeURIComponent(datasetId)}`, {
      method: "POST",
      body,
    });
    input.value = "";
    await loadDocuments(datasetId);
    renderOverview();
    resetDocumentForm();
    toast("檔案已上傳並完成索引");
  } catch (error) { toast(error.message, true); }
});

$("#answer-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const data = formData(form);
  const datasetId = state.selectedDatasetId;
  if (!datasetId) return toast("請先建立資料集", true);
  setSubmitting(form, true, "產生回答中…");
  try {
    const result = await api(`/answer?datasetId=${encodeURIComponent(datasetId)}`, {
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

$("#add-on-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const data = formData(form);
  setSubmitting(form, true, "新增中…");
  try {
    const created = await api(`/booking-services/${encodeURIComponent(form.dataset.serviceId)}/add-ons`, {
      method: "POST",
      body: JSON.stringify({
        name: data.name.trim(),
        description: data.description.trim() || null,
        duration_minutes: Number(data.duration_minutes),
        price_amount: Number(data.price_amount),
      }),
    });
    form.reset();
    await refreshOverview();
    toast("加購項目已新增");
    catalogSaved(`${created.name}已新增並綁定此主服務。主服務開放預約時，顧客即可選擇。`, form.dataset.serviceId);
    showServiceAddOns(form.dataset.serviceId);
  } catch (error) {
    toast(error.message, true);
  } finally {
    setSubmitting(form, false, "新增中…");
  }
});

$("#booking-service-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const data = formData(form);
  setSubmitting(form, true, "新增中…");
  try {
    const created = await api("/booking-services", {
      method: "POST",
      body: JSON.stringify({
        name: data.name.trim(),
        description: data.description.trim() || null,
        duration_minutes: Number(data.duration_minutes),
        price_amount: Number(data.price_amount),
        add_on_ids: [],
      }),
    });
    form.reset();
    await refreshOverview();
    toast("主服務已新增");
    catalogSaved(`${created.name}已開放預約。可在此服務下新增加購，也可直接提供主服務。`, created.id);
  } catch (error) {
    toast(error.message, true);
  } finally {
    setSubmitting(form, false, "新增中…");
  }
});

$("#booking-service-list").addEventListener("click", async (event) => {
  const button = event.target.closest("[data-save-service]");
  if (!button) return;
  const item = button.closest("[data-service-id]");
  const editor = button.closest(".catalog-edit");
  const invalid = [...editor.querySelectorAll("input, textarea")]
    .find((input) => !input.checkValidity());
  if (invalid) return invalid.reportValidity();
  const field = (name) => editor.querySelector(`[name="${name}"]`);
  button.disabled = true;
  try {
    await api(`/booking-services/${encodeURIComponent(item.dataset.serviceId)}`, {
      method: "PUT",
      body: JSON.stringify({
        name: field("name").value.trim(),
        description: field("description").value.trim() || null,
        duration_minutes: Number(field("duration_minutes").value),
        price_amount: Number(field("price_amount").value),
        active: field("active").checked,
        add_on_ids: state.overview.booking_services.find((service) => service.id === item.dataset.serviceId).add_ons.map((a) => a.id),
      }),
    });
    await refreshOverview();
    toast("主服務設定已儲存");
    catalogSaved(`${field("name").value.trim()}已儲存。${field("active").checked ? "顧客可選擇此主服務與已啟用的加購。" : "此服務已停用，顧客預約頁不會顯示。"}`, item.dataset.serviceId);
  } catch (error) {
    toast(error.message, true);
    button.disabled = false;
  }
});


$("#publish-button").addEventListener("click", async () => {
  const datasetId = state.selectedDatasetId;
  if (!datasetId) return toast("沒有可發布的資料集", true);
  const button = $("#publish-button");
  button.disabled = true;
  const label = button.textContent;
  button.textContent = "發布中…";
  try {
    await api(`/datasets/publish?datasetId=${encodeURIComponent(datasetId)}`, { method: "POST" });
    await refreshOverview();
    toast("知識更新已發布給顧客");
  } catch (error) {
    toast(error.message, true);
    button.disabled = false;
  } finally {
    button.textContent = label;
  }
});

$("#reindex-button").addEventListener("click", async () => {
  if (!state.selectedDatasetId) return toast("沒有可索引的資料集", true);
  const button = $("#reindex-button");
  button.disabled = true;
  button.textContent = "重建索引中…";
  try {
    const datasetId = await ensureEditableDataset();
    const result = await api(`/datasets/reindex?datasetId=${encodeURIComponent(datasetId)}`, { method: "POST" });
    await refreshOverview();
    toast(`重新索引完成：${result.indexed} 成功，${result.failed} 失敗`);
  } catch (error) {
    toast(error.message, true);
  } finally {
    button.disabled = false;
    button.textContent = "重新建立全部索引";
  }
});

$("#copy-webhook").addEventListener("click", async () => {
  try {
    await navigator.clipboard.writeText($("#webhook-url").textContent);
    toast("Webhook URL 已複製");
  } catch (_) { toast("無法存取剪貼簿，請手動複製", true); }
});

async function initialize() {
  if (!(await exchangeLineOwnerToken())) await restoreSession();
}

initialize();
