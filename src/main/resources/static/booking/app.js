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
  const state = {
    bootstrap: null,
    service: null,
    addOnIds: new Set(),
    date: "",
    slot: null,
    lastQuote: null,
  };
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
        ...(options.headers || {}),
      },
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
      timeZone: state.bootstrap.timezone,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    }).format(new Date(iso));
  }

  function localTime(iso) {
    return new Intl.DateTimeFormat("zh-TW", {
      timeZone: state.bootstrap.timezone,
      hour: "2-digit",
      minute: "2-digit",
      hour12: false,
    }).format(new Date(iso));
  }

  function money(amount) {
    return new Intl.NumberFormat("zh-TW", {
      style: "currency",
      currency: state.bootstrap.currency || "TWD",
      maximumFractionDigits: 0,
    }).format(amount || 0);
  }

  function selectedAddOns() {
    return (state.service?.add_ons || []).filter((item) => state.addOnIds.has(item.id));
  }

  function selectionQuote() {
    return selectedAddOns().reduce((quote, item) => ({
      duration: quote.duration + item.duration_minutes,
      price: quote.price + item.price_amount,
    }), {
      duration: state.service?.duration_minutes || 0,
      price: state.service?.price_amount || 0,
    });
  }

  function updateSelectionQuote() {
    const quote = selectionQuote();
    document.querySelector("#selection-duration").textContent = `${quote.duration} 分鐘`;
    document.querySelector("#selection-price").textContent = money(quote.price);
  }

  function availabilityPath() {
    const params = new URLSearchParams({
      service_id: state.service.id,
      local_date: state.date,
    });
    state.addOnIds.forEach((id) => params.append("add_on_ids", id));
    return `/availability?${params}`;
  }

  async function loadSlots() {
    const container = document.querySelector("#slots");
    const requestId = slotRequests.begin();
    const requestedDate = state.date;
    const serviceId = state.service.id;
    const addOnKey = [...state.addOnIds].join(",");
    container.setAttribute("aria-busy", "true");
    renderMessage(container, "正在查詢可預約時段…");
    try {
      const result = await api(availabilityPath());
      if (!slotRequests.isLatest(requestId)
          || state.date !== requestedDate
          || state.service.id !== serviceId
          || [...state.addOnIds].join(",") !== addOnKey) return;
      state.lastQuote = result;
      container.replaceChildren();
      if (!result.slots.length) {
        renderMessage(container, "這天目前沒有足夠的連續時段，請選擇其他日期。");
        return;
      }
      result.slots.forEach((slot) => {
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
    const addOns = selectedAddOns();
    const quote = state.lastQuote || {
      duration_minutes: selectionQuote().duration,
      total_price_amount: selectionQuote().price,
    };
    document.querySelector("#summary-service").textContent = state.service.name;
    document.querySelector("#summary-add-ons").textContent = addOns.length
      ? addOns.map((item) => item.name).join("、")
      : "無";
    document.querySelector("#summary-date").textContent = localDate(slot.starts_at);
    document.querySelector("#summary-time").textContent = localTime(slot.starts_at);
    document.querySelector("#summary-duration").textContent = `${quote.duration_minutes} 分鐘`;
    document.querySelector("#summary-price").textContent = money(quote.total_price_amount);
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
          add_on_ids: [...state.addOnIds],
          starts_at: state.slot.starts_at,
          customer_name: name,
          idempotency_key: `web:${crypto.randomUUID()}`,
        }),
      });
      document.querySelector("#success-message").textContent =
        `${localDate(reservation.starts_at)} ${localTime(reservation.starts_at)} 的「${reservation.service_name}」已預約成功。`;
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

  function renderAddOns(service) {
    const container = document.querySelector("#add-ons");
    container.replaceChildren();
    if (!service.add_ons.length) {
      renderMessage(container, "這項服務目前沒有加購項目。直接繼續選擇時間即可。");
      document.querySelector("#add-on-help").textContent = "此服務無加購項目。";
    } else {
      document.querySelector("#add-on-help").textContent = "可複選，也可以不加購。";
      service.add_ons.forEach((addOn) => {
        const label = document.createElement("label");
        label.className = "add-on-choice";
        const checkbox = document.createElement("input");
        checkbox.type = "checkbox";
        checkbox.value = addOn.id;
        checkbox.addEventListener("change", () => {
          if (checkbox.checked) state.addOnIds.add(addOn.id);
          else state.addOnIds.delete(addOn.id);
          label.classList.toggle("selected", checkbox.checked);
          state.slot = null;
          state.lastQuote = null;
          updateSelectionQuote();
        });
        const copy = document.createElement("span");
        const title = document.createElement("strong");
        title.textContent = addOn.name;
        const detail = document.createElement("small");
        detail.textContent = `${addOn.duration_minutes ? `+${addOn.duration_minutes} 分鐘` : "不增加時間"} · +${money(addOn.price_amount)}`;
        copy.append(title, detail);
        label.append(checkbox, copy);
        container.appendChild(label);
      });
    }
    document.querySelector("#add-on-section").hidden = false;
    updateSelectionQuote();
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
    state.bootstrap.services.forEach((service) => {
      const button = document.createElement("button");
      button.className = "service";
      button.type = "button";
      button.setAttribute("aria-pressed", "false");
      const name = document.createElement("strong");
      name.textContent = service.name;
      const description = document.createElement("small");
      description.textContent = [
        service.description,
        `${service.duration_minutes} 分鐘 · ${money(service.price_amount)}`,
      ].filter(Boolean).join("｜");
      button.append(name, description);
      button.addEventListener("click", () => {
        document.querySelectorAll(".service").forEach((item) => {
          item.classList.remove("selected");
          item.setAttribute("aria-pressed", "false");
        });
        button.classList.add("selected");
        button.setAttribute("aria-pressed", "true");
        state.service = service;
        state.addOnIds.clear();
        state.slot = null;
        state.lastQuote = null;
        document.querySelector("#selected-service").textContent = `已選擇：${service.name}`;
        const dateInput = document.querySelector("#booking-date");
        const today = new Intl.DateTimeFormat("en-CA", {
          timeZone: state.bootstrap.timezone,
        }).format(new Date());
        dateInput.min = today;
        dateInput.value = today;
        state.date = today;
        renderAddOns(service);
        document.querySelector("#add-on-section").scrollIntoView({ block: "nearest" });
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
  document.querySelectorAll(".back").forEach((button) =>
    button.addEventListener("click", () => showStep(Number(button.dataset.back))));
  document.querySelector("#continue-to-time").addEventListener("click", () => {
    if (!state.service) return;
    showStep(1);
    void loadSlots();
  });
  document.querySelector("#confirm").addEventListener("click", confirmBooking);
  document.querySelector("#customer-name").addEventListener("input", (event) => {
    if (event.currentTarget.value.trim()) {
      event.currentTarget.removeAttribute("aria-invalid");
      if (notice.textContent === "請輸入預約姓名。") showNotice("");
    }
  });

  start().catch((error) => {
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
