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
    { done: hasLine, title: "LINE 官方帳號", copy: hasLine ? "Channel 已安全連接" : "加入 Secret 與 Access Token", view: "settings" },
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

function renderSettings() {
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
}

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
      <p class="staff-menu-note">${escapeHtml(roleDescriptions[staff.role] || "依角色顯示對應的 LINE 管理入口。")}</p>
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
          <label>每日摘要時間<input data-staff-field="daily_summary_time" type="time" value="${escapeHtml((staff.daily_summary_time || "08:00").slice(0, 5))}"></label>
        </div>
        <div class="staff-checks">
          <label><input data-staff-field="notify_new_booking" type="checkbox" ${staff.notify_new_booking ? "checked" : ""}>新預約通知</label>
          <label><input data-staff-field="notify_cancellation" type="checkbox" ${staff.notify_cancellation ? "checked" : ""}>取消預約通知</label>
          <label><input data-staff-field="daily_summary_enabled" type="checkbox" ${staff.daily_summary_enabled ? "checked" : ""}>每日預約摘要</label>
        </div>
        <div class="staff-item-actions"><button class="primary compact" data-save-staff type="button">儲存設定</button></div>
      </details>
    </article>`;
  }).join("");
}

function switchView(name) {
  state.activeView = name;
  $$(".view").forEach((view) => view.classList.add("hidden"));
  $(`#view-${name}`).classList.remove("hidden");
  $$(".nav-item").forEach((item) => {
    const active = item.dataset.view === name;
    item.classList.toggle("active", active);
    if (active) item.setAttribute("aria-current", "page");
    else item.removeAttribute("aria-current");
  });
  const titles = {
    overview: ["MERCHANT OVERVIEW", "今天，讓客服再可靠一點。", "先看營運狀態，再處理最重要的下一步。"],
    knowledge: ["KNOWLEDGE STUDIO", "把經驗整理成可信的知識。", "編輯、索引與發布都集中在同一個工作區。"],
    tester: ["ANSWER LAB", "每次發布前，都先問一次。", "用顧客的角度確認回答內容、信心與引用來源。"],
    staff: ["MERCHANT STAFF", "把日常預約管理留在 LINE。", "設定角色、通知與每位人員會看到的中文管理入口。"],
    settings: ["CHANNEL SETUP", "把 LINE 接到商家的服務流程。", "依序完成憑證、Webhook 與啟用狀態檢查。"],
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
