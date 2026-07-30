(() => {
  const pathParts = location.pathname.split("/").filter(Boolean);
  const tenantSlug = pathParts[1] || "";
  const fragment = new URLSearchParams(location.hash.slice(1));
  const linkToken = fragment.get("token");
  if (linkToken) history.replaceState(null, "", location.pathname);

  const state = {
    csrfToken: null,
    bootstrap: null,
    agenda: null,
  };

  const $ = (selector) => document.querySelector(selector);

  async function api(path, options = {}) {
    const headers = { ...(options.headers || {}) };
    if (options.body) headers["Content-Type"] = "application/json";
    if (state.csrfToken && options.method && options.method !== "GET") {
      headers["X-CSRF-Token"] = state.csrfToken;
    }
    const response = await fetch(`/merchant-booking/api/${encodeURIComponent(tenantSlug)}${path}`, {
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
    toast.timer = setTimeout(() => { element.className = "toast"; }, 3000);
  }

  function localToday() {
    return new Intl.DateTimeFormat("en-CA", {
      timeZone: state.bootstrap?.timezone || "Asia/Taipei",
    }).format(new Date());
  }

  function addDays(dateString, days) {
    const date = new Date(`${dateString}T12:00:00Z`);
    date.setUTCDate(date.getUTCDate() + days);
    return date.toISOString().slice(0, 10);
  }

  function localDateTime(value) {
    return new Intl.DateTimeFormat("zh-TW", {
      timeZone: state.bootstrap.timezone,
      month: "2-digit",
      day: "2-digit",
      weekday: "short",
      hour: "2-digit",
      minute: "2-digit",
      hour12: false,
    }).format(new Date(value));
  }

  function timeOnly(value) {
    return new Intl.DateTimeFormat("zh-TW", {
      timeZone: state.bootstrap.timezone,
      hour: "2-digit",
      minute: "2-digit",
      hour12: false,
    }).format(new Date(value));
  }

  function escapeHtml(value = "") {
    return String(value).replace(/[&<>"']/g, (char) => ({
      "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#039;",
    })[char]);
  }

  async function establishSession() {
    if (linkToken) {
      const session = await api("/session", {
        method: "POST",
        headers: { Authorization: `Bearer ${linkToken}` },
      });
      state.csrfToken = session.csrf_token;
      return;
    }
    const session = await api("/session");
    if (!session.authenticated) throw new Error("管理連結已失效，請回到 LINE 重新開啟。");
    state.csrfToken = session.csrf_token;
  }

  async function loadAgenda() {
    const date = $("#agenda-date").value;
    const next = addDays(date, 1);
    state.agenda = await api(`/agenda/local?from_date=${encodeURIComponent(date)}&to_date=${encodeURIComponent(next)}`);
    renderAgenda();
  }

  function renderAgenda() {
    const reservations = state.agenda.reservations || [];
    const blocks = (state.agenda.blocks || []).filter((item) => item.active);
    $("#confirmed-count").textContent = reservations.filter((item) => item.status === "CONFIRMED").length;
    $("#cancelled-count").textContent = reservations.filter((item) => item.status === "CANCELLED").length;
    $("#blocked-count").textContent = blocks.length;
    $("#agenda-title").textContent = `${$("#agenda-date").value} 預約`;

    const canWrite = ["OWNER", "MANAGER"].includes(state.bootstrap.staff.role);
    $("#reservation-list").innerHTML = reservations.length
      ? reservations.map((item) => `
        <article class="booking-card ${item.status === "CANCELLED" ? "cancelled" : ""}">
          <div class="booking-head">
            <div>
              <span class="booking-time">${escapeHtml(localDateTime(item.starts_at))}</span>
              <h3>${escapeHtml(item.customer_name)}</h3>
              <p class="booking-meta">${escapeHtml(item.service_name)} · ${escapeHtml(item.id.slice(0, 8).toUpperCase())}</p>
            </div>
            <span class="status ${item.status === "CANCELLED" ? "cancelled" : ""}">${item.status === "CONFIRMED" ? "已確認" : "已取消"}</span>
          </div>
          ${canWrite && item.status === "CONFIRMED"
            ? `<div class="booking-actions"><button class="danger" data-cancel-id="${item.id}" type="button">取消預約</button></div>`
            : ""}
        </article>`).join("")
      : '<div class="empty">這一天沒有預約。</div>';

    $("#block-list").innerHTML = blocks.length
      ? blocks.map((item) => `
        <article class="booking-card">
          <div class="booking-head">
            <div><span class="booking-time">${escapeHtml(localDateTime(item.starts_at))}</span><h3>${escapeHtml(item.reason || "店家暫停開放")}</h3></div>
            <span class="status">已封鎖</span>
          </div>
          ${canWrite ? `<div class="booking-actions"><button class="secondary" data-release-block="${item.id}" type="button">解除封鎖</button></div>` : ""}
        </article>`).join("")
      : '<div class="empty">這一天沒有封鎖時段。</div>';
  }

  async function loadBlockSlots() {
    const date = $("#block-date").value;
    const service = state.bootstrap.services[0];
    const select = $("#block-slot");
    if (!date || !service) {
      select.innerHTML = '<option value="">沒有可用服務</option>';
      return;
    }
    try {
      const result = await api(`/availability?service_id=${encodeURIComponent(service.id)}&local_date=${encodeURIComponent(date)}`);
      select.innerHTML = result.slots.length
        ? '<option value="">請選擇</option>' + result.slots.map((slot) =>
          `<option value="${slot.starts_at}">${escapeHtml(timeOnly(slot.starts_at))}</option>`
        ).join("")
        : '<option value="">沒有可封鎖時段</option>';
    } catch (error) {
      select.innerHTML = '<option value="">讀取失敗</option>';
      toast(error.message, true);
    }
  }

  async function start() {
    if (!tenantSlug) throw new Error("管理連結缺少商家代稱。");
    await establishSession();
    state.bootstrap = await api("/bootstrap");
    $("#tenant-name").textContent = state.bootstrap.tenant_name;
    $("#staff-name").textContent = `${state.bootstrap.staff.display_name} · 店家預約管理`;
    $("#role-badge").textContent = state.bootstrap.staff.role;
    document.title = `${state.bootstrap.tenant_name}｜預約管理`;
    const today = localToday();
    $("#agenda-date").value = today;
    $("#block-date").value = today;
    const canWrite = ["OWNER", "MANAGER"].includes(state.bootstrap.staff.role);
    $("#block-section").classList.toggle("hidden", !canWrite);
    $("#app-content").classList.remove("hidden");
    await Promise.all([loadAgenda(), canWrite ? loadBlockSlots() : Promise.resolve()]);
  }

  $("#previous-day").addEventListener("click", async () => {
    $("#agenda-date").value = addDays($("#agenda-date").value, -1);
    await loadAgenda();
  });
  $("#next-day").addEventListener("click", async () => {
    $("#agenda-date").value = addDays($("#agenda-date").value, 1);
    await loadAgenda();
  });
  $("#today-button").addEventListener("click", async () => {
    $("#agenda-date").value = localToday();
    await loadAgenda();
  });
  $("#agenda-date").addEventListener("change", loadAgenda);
  $("#refresh-button").addEventListener("click", loadAgenda);
  $("#block-date").addEventListener("change", loadBlockSlots);

  $("#reservation-list").addEventListener("click", async (event) => {
    const button = event.target.closest("[data-cancel-id]");
    if (!button || !confirm("確定取消這筆預約？系統會通知顧客。")) return;
    button.disabled = true;
    try {
      await api(`/reservations/${encodeURIComponent(button.dataset.cancelId)}/cancel`, { method: "POST" });
      toast("預約已取消");
      await Promise.all([loadAgenda(), loadBlockSlots()]);
    } catch (error) {
      toast(error.message, true);
    } finally {
      button.disabled = false;
    }
  });

  $("#block-list").addEventListener("click", async (event) => {
    const button = event.target.closest("[data-release-block]");
    if (!button || !confirm("確定解除這個封鎖時段？")) return;
    button.disabled = true;
    try {
      await api(`/blocks/${encodeURIComponent(button.dataset.releaseBlock)}`, { method: "DELETE" });
      toast("已解除封鎖");
      await Promise.all([loadAgenda(), loadBlockSlots()]);
    } catch (error) {
      toast(error.message, true);
    } finally {
      button.disabled = false;
    }
  });

  $("#block-form").addEventListener("submit", async (event) => {
    event.preventDefault();
    const startsAt = $("#block-slot").value;
    if (!startsAt) return toast("請選擇要封鎖的時段", true);
    const button = event.currentTarget.querySelector('button[type="submit"]');
    button.disabled = true;
    try {
      await api("/blocks", {
        method: "POST",
        body: JSON.stringify({ starts_at: startsAt, reason: $("#block-reason").value.trim() || null }),
      });
      $("#block-reason").value = "";
      toast("時段已封鎖");
      $("#agenda-date").value = $("#block-date").value;
      await Promise.all([loadAgenda(), loadBlockSlots()]);
    } catch (error) {
      toast(error.message, true);
    } finally {
      button.disabled = false;
    }
  });

  start().catch((error) => {
    $("#error-message").textContent = error.message;
    $("#error-panel").classList.remove("hidden");
  });
})();
