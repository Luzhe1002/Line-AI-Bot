(function attachUiUtils(root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) {
    module.exports = api;
  } else {
    root.UiUtils = api;
  }
})(
  typeof window !== "undefined"
    ? window
    : typeof globalThis !== "undefined"
      ? globalThis
      : this,
  function createUiUtils() {
  function createLatestRequestGate() {
    let sequence = 0;
    return Object.freeze({
      begin() {
        sequence += 1;
        return sequence;
      },
      invalidate() {
        sequence += 1;
      },
      isLatest(requestId) {
        return requestId === sequence;
      },
    });
  }

  function tabIndexForKey(currentIndex, key, length) {
    if (!Number.isInteger(currentIndex) || length < 1) return currentIndex;
    if (key === "ArrowRight") return (currentIndex + 1) % length;
    if (key === "ArrowLeft") return (currentIndex - 1 + length) % length;
    if (key === "Home") return 0;
    if (key === "End") return length - 1;
    return currentIndex;
  }

  function withRecoveryMessage(message, recovery) {
    const detail = String(message || "").trim();
    const action = String(recovery || "").trim();
    if (!detail) return action;
    if (!action || detail.includes(action)) return detail;
    return `${detail} ${action}`;
  }

  function roleLabel(role) {
    return {
      OWNER: "擁有者",
      MANAGER: "管理員",
      VIEWER: "檢視者",
    }[role] || role || "未知權限";
  }

  return Object.freeze({
    createLatestRequestGate,
    roleLabel,
    tabIndexForKey,
    withRecoveryMessage,
  });
  },
);
