(() => {
  const pathParts = location.pathname.split("/").filter(Boolean);
  const tenantSlug = pathParts[1] || "";
  const fragment = new URLSearchParams(location.hash.slice(1));
  const tokenFromLink = fragment.get("token");
  if (tokenFromLink) {
    sessionStorage.setItem(`booking-token:${tenantSlug}`, tokenFromLink);
    history.replaceState(null, "", `${location.pathname}${location.search}`);
  }
  const token = sessionStorage.getItem(`booking-token:${tenantSlug}`);
  const state = { bootstrap: null, service: null, date: "", slot: null };
  const steps = [...document.querySelectorAll(".step")];
  const progress = [...document.querySelectorAll(".progress span")];
  const progressElement = document.querySelector(".progress");
  const notice = document.querySelector("#notice");
  const slotRequests = UiUtils.createLatestRequestGate();

  function showNotice(message, error = false) {
    notice.textContent = message;
    notice.setAttribute("role", error ? "alert" : "status");
    notice.setAttribute("aria-live", error ? "assertive" : "polite");
  }

  function renderMessage(container, message) {
    const paragraph = document.createElement("p");
    paragraph.className = "empty";
    paragraph.textContent = message;
    container.replaceChildren(paragraph);
  }

  function showStep(index, { focus = true } = {}) {
    steps.forEach((step, position) => {
      const active = position === index;
      step.classList.toggle("active", active);
      step.hidden = !active;
    });
    progress.forEach((bar, position) => bar.classList.toggle("active", position <= index));
    progressElement.setAttribute("aria-valuenow", String(index + 1));
    progressElement.setAttribute("aria-valuetext", `步驟 ${index + 1}，共 ${steps.length} 步`);
    showNotice("");
    if (focus) steps[index].querySelector("h2")?.focus({ preventScroll: true });
  }

  async function api(path, options = {}) {
    const response = await fetch(`/booking/api/${encodeURIComponent(tenantSlug)}${path}`, {
      ...options,
      headers: {
        "Authorization": `Bearer ${token || ""}`,
        "Content-Type": "application/json",
        ...(options.headers || {})
      }
    });
    const body = await response.json().catch(() => ({}));
    if (!response.ok) {
      const error = new Error(body.detail || "目前無法完成操作");
      error.status = response.status;
      throw error;
    }
    return body;
  }

  function localDate(iso) {
    return new Intl.DateTimeFormat("zh-TW", {
      timeZone: state.bootstrap.timezone, year: "numeric", month: "2-digit", day: "2-digit"
    }).format(new Date(iso));
  }

  function localTime(iso) {
    return new Intl.DateTimeFormat("zh-TW", {
      timeZone: state.bootstrap.timezone, hour: "2-digit", minute: "2-digit", hour12: false
    }).format(new Date(iso));
  }

  async function loadSlots() {
    const container = document.querySelector("#slots");
    const requestId = slotRequests.begin();
    const requestedDate = state.date;
    const serviceId = state.service.id;
    container.setAttribute("aria-busy", "true");
    renderMessage(container, "正在查詢可預約時段…");
    try {
      const result = await api(`/availability?service_id=${encodeURIComponent(serviceId)}&local_date=${requestedDate}`);
      if (!slotRequests.isLatest(requestId) || state.date !== requestedDate || state.service.id !== serviceId) return;
      container.replaceChildren();
      if (!result.slots.length) {
        renderMessage(container, "這天目前沒有可預約時段，請選擇其他日期。");
        return;
      }
      result.slots.forEach(slot => {
        const button = document.createElement("button");
        button.className = "slot";
        button.type = "button";
        button.textContent = localTime(slot.starts_at);
        button.addEventListener("click", () => selectSlot(slot));
        container.appendChild(button);
      });
    } catch (error) {
      if (slotRequests.isLatest(requestId)) renderMessage(container, error.message);
    } finally {
      if (slotRequests.isLatest(requestId)) container.setAttribute("aria-busy", "false");
    }
  }

  function selectSlot(slot) {
    state.slot = slot;
    document.querySelector("#summary-service").textContent = state.service.name;
    document.querySelector("#summary-date").textContent = localDate(slot.starts_at);
    document.querySelector("#summary-time").textContent = localTime(slot.starts_at);
    document.querySelector("#summary-duration").textContent = `${state.bootstrap.slot_minutes} 分鐘`;
    showStep(2);
  }

  async function confirmBooking() {
    const nameInput = document.querySelector("#customer-name");
    const name = nameInput.value.trim();
    if (!name) {
      nameInput.setAttribute("aria-invalid", "true");
      showNotice("請輸入預約姓名。", true);
      nameInput.focus();
      return;
    }
    nameInput.removeAttribute("aria-invalid");
    const button = document.querySelector("#confirm");
    button.disabled = true;
    button.textContent = "正在確認時段…";
    showNotice("");
    try {
      const reservation = await api("/reservations", {
        method: "POST",
        body: JSON.stringify({
          service_id: state.service.id,
          starts_at: state.slot.starts_at,
          customer_name: name,
          idempotency_key: `web:${crypto.randomUUID()}`
        })
      });
      document.querySelector("#success-message").textContent =
        `${localDate(reservation.starts_at)} ${localTime(reservation.starts_at)} 的「${state.service.name}」已預約成功。`;
      showStep(3);
    } catch (error) {
      if (error.status === 409) {
        showStep(1);
        showNotice("這個時段剛被預約，已為你重新載入當天可用時段。", true);
        await loadSlots();
      } else {
        showNotice(error.message, true);
      }
    } finally {
      button.disabled = false;
      button.textContent = "確認預約";
    }
  }

  async function start() {
    if (!tenantSlug || !token) throw new Error("連結缺少預約憑證");
    state.bootstrap = await api("/bootstrap");
    document.querySelector("#tenant-name").textContent = state.bootstrap.tenant_name;
    document.title = `${state.bootstrap.tenant_name}｜預約`;
    const services = document.querySelector("#services");
    if (!state.bootstrap.services.length) {
      renderMessage(services, "商家目前沒有開放預約服務。");
      return;
    }
    state.bootstrap.services.forEach(service => {
      const button = document.createElement("button");
      button.className = "service";
      button.type = "button";
      const name = document.createElement("strong");
      name.textContent = service.name;
      const description = document.createElement("small");
      description.textContent = service.description || `約 ${state.bootstrap.slot_minutes} 分鐘`;
      button.append(name, description);
      button.addEventListener("click", () => {
        state.service = service;
        document.querySelector("#selected-service").textContent =
          `已選擇：${service.name}（約 ${state.bootstrap.slot_minutes} 分鐘）`;
        const dateInput = document.querySelector("#booking-date");
        const today = new Intl.DateTimeFormat("en-CA", { timeZone: state.bootstrap.timezone }).format(new Date());
        dateInput.min = today;
        dateInput.value = today;
        state.date = today;
        state.slot = null;
        showStep(1);
        void loadSlots();
      });
      services.appendChild(button);
    });
  }

  function updateBookingDate(event) {
    if (event.target.value === state.date) return;
    state.date = event.target.value;
    state.slot = null;
    if (state.date) void loadSlots();
    else {
      slotRequests.invalidate();
      renderMessage(document.querySelector("#slots"), "請先選擇日期。");
    }
  }

  const bookingDate = document.querySelector("#booking-date");
  bookingDate.addEventListener("input", updateBookingDate);
  bookingDate.addEventListener("change", updateBookingDate);
  document.querySelectorAll(".back").forEach(button =>
    button.addEventListener("click", () => showStep(Number(button.dataset.back))));
  document.querySelector("#confirm").addEventListener("click", confirmBooking);
  document.querySelector("#customer-name").addEventListener("input", (event) => {
    if (event.currentTarget.value.trim()) {
      event.currentTarget.removeAttribute("aria-invalid");
      if (notice.textContent === "請輸入預約姓名。") showNotice("");
    }
  });

  start().catch(error => {
    slotRequests.invalidate();
    document.querySelector("header").hidden = true;
    steps.forEach((step) => {
      step.classList.remove("active");
      step.hidden = true;
    });
    const fatalError = document.querySelector("#fatal-error");
    fatalError.hidden = false;
    document.querySelector("#fatal-message").textContent =
      error.message === "連結缺少預約憑證"
        ? "預約連結不完整，請回到 LINE 重新輸入「預約」。"
        : "預約連結可能已失效，請回到 LINE 重新輸入「預約」。";
    fatalError.querySelector("h2").focus({ preventScroll: true });
  });
})();
