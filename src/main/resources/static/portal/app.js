const state = {
  csrfToken: null,
  tenant: null,
  overview: null,
  documents: [],
  staff: [],
  editingDocumentId: null,
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
  resetDocumentForm();
  renderDocuments();
}

function selectedDataset() {
  const datasetId = $("#dataset-select").value;
  return state.overview?.datasets?.find((item) => item.id === datasetId) || null;
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
  $("#dataset-select").value = draft.id;
  if (selectedDataset()?.id !== draft.id) {
    await loadDocuments(draft.id);
  }
  return draft.id;
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
  const editable = selectedDataset()?.status === "DRAFT";
  $("#publish-button").disabled = !editable;
  $("#publish-button").title = editable ? "" : "正式版不需要再次發布";
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
      <div class="document-actions">
        ${editable ? `
          <button class="text-button" type="button" data-document-action="edit" data-document-id="${item.id}">編輯</button>
          <button class="text-button danger" type="button" data-document-action="delete" data-document-id="${item.id}">刪除</button>
        ` : `<small>正式版為唯讀；修改時會建立新版草稿。</small>`}
      </div>
    </article>`).join("");
}

function resetDocumentForm() {
  const form = $("#document-form");
  state.editingDocumentId = null;
  form.reset();
  $("#document-form-eyebrow").textContent = "NEW SOURCE";
  $("#document-form-title").textContent = "新增知識文件";
  $("#document-submit").textContent = "加入草稿並索引";
  $("#document-submit").dataset.defaultLabel = "加入草稿並索引";
  $("#cancel-document-edit").classList.add("hidden");
}

function editDocument(documentId) {
  const document = state.documents.find((item) => item.id === documentId);
  if (!document) return;
  state.editingDocumentId = document.id;
  const form = $("#document-form");
  form.elements.title.value = document.title;
  form.elements.content.value = document.content;
  form.elements.source_url.value = document.source_url || "";
  $("#document-form-eyebrow").textContent = "EDIT SOURCE";
  $("#document-form-title").textContent = "編輯知識文件";
  $("#document-submit").textContent = "儲存修改並重新索引";
  $("#document-submit").dataset.defaultLabel = "儲存修改並重新索引";
  $("#cancel-document-edit").classList.remove("hidden");
  form.scrollIntoView({ behavior: "smooth", block: "start" });
}

function renderSettings() {
  const line = state.overview.line_channel;
  $("#webhook-url").textContent = line.webhook_url;
  $("#line-status").textContent = line.configured
    ? `已設定，${line.enabled ? "目前啟用中" : "目前停用"}`
    : "尚未連接 LINE Channel";
}

async function loadStaff() {
  state.staff = await api("/staff");
  renderStaff();
}

function renderStaff() {
  const list = $("#staff-list");
  if (!state.staff.length) {
    list.innerHTML = `<div class="empty-state"><strong>尚未綁定店家人員</strong><p>先產生擁有者綁定碼，再到 LINE 完成綁定。</p></div>`;
    return;
  }
  list.innerHTML = state.staff.map((staff) => `
    <article class="staff-item" data-staff-id="${staff.id}">
      <div class="staff-item-head">
        <div><strong>${escapeHtml(staff.display_name)}</strong><br><small>${new Date(staff.created_at).toLocaleString("zh-TW")}</small></div>
        <span class="badge ${staff.status === "ACTIVE" ? "" : "failed"}">${staff.status}</span>
      </div>
      <div class="staff-fields">
        <label>顯示名稱<input data-staff-field="display_name" maxlength="160" value="${escapeHtml(staff.display_name)}"></label>
        <label>權限
          <select data-staff-field="role">
            <option value="OWNER" ${staff.role === "OWNER" ? "selected" : ""}>擁有者</option>
            <option value="MANAGER" ${staff.role === "MANAGER" ? "selected" : ""}>管理員</option>
            <option value="VIEWER" ${staff.role === "VIEWER" ? "selected" : ""}>檢視者</option>
          </select>
        </label>
        <label>狀態
          <select data-staff-field="status">
            <option value="ACTIVE" ${staff.status === "ACTIVE" ? "selected" : ""}>啟用</option>
            <option value="DISABLED" ${staff.status === "DISABLED" ? "selected" : ""}>停用</option>
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
    </article>`).join("");
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
    overview: ["MERCHANT OVERVIEW", "今天，讓客服再可靠一點。"],
    knowledge: ["KNOWLEDGE STUDIO", "把經驗整理成可信的知識。"],
    tester: ["ANSWER LAB", "每次發布前，都先問一次。"],
    staff: ["MERCHANT STAFF", "把日常預約管理留在 LINE。"],
    settings: ["CHANNEL SETUP", "把 LINE 接到商家的服務流程。"],
  };
  $("#page-eyebrow").textContent = titles[name][0];
  $("#page-title").textContent = titles[name][1];
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
        status: field("status").value,
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

$("#dataset-select").addEventListener("change", (event) => loadDocuments(event.target.value));

$("#document-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const data = formData(form);
  const editingDocumentId = state.editingDocumentId;
  setSubmitting(form, true, editingDocumentId ? "儲存並索引中…" : "加入並索引中…");
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
    toast(editingDocumentId ? "文件已更新並完成索引" : "文件已加入並完成索引");
  } catch (error) {
    toast(error.message, true);
  } finally {
    setSubmitting(form, false, editingDocumentId ? "儲存並索引中…" : "加入並索引中…");
  }
});

$("#cancel-document-edit").addEventListener("click", resetDocumentForm);

$("#document-list").addEventListener("click", async (event) => {
  const button = event.target.closest("[data-document-action]");
  if (!button) return;
  const documentId = button.dataset.documentId;
  if (button.dataset.documentAction === "edit") {
    editDocument(documentId);
    return;
  }
  const document = state.documents.find((item) => item.id === documentId);
  if (!document || !window.confirm(`確定刪除「${document.title}」？此變更會在發布草稿後生效。`)) return;
  try {
    const datasetId = await ensureEditableDataset();
    await api(`/documents?${new URLSearchParams({ datasetId, documentId })}`, {
      method: "DELETE",
    });
    await loadDocuments(datasetId);
    renderOverview();
    toast("文件已從草稿刪除");
  } catch (error) {
    toast(error.message, true);
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
    toast("檔案已上傳並完成索引");
  } catch (error) { toast(error.message, true); }
});

$("#answer-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const form = event.currentTarget;
  const data = formData(form);
  const datasetId = $("#dataset-select").value;
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

async function initialize() {
  if (!(await exchangeLineOwnerToken())) await restoreSession();
}

initialize();
