/** 时间格式化：ISO → 本地 "YYYY-MM-DD HH:mm" */
export function formatDateTime(iso: string | null | undefined): string {
  if (!iso) return "-";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "-";
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** datetime-local 输入值 → UTC ISO（无时区后缀，按 UTC 解释） */
export function localInputToIso(value: string): string {
  return value ? new Date(value).toISOString() : value;
}

/** JWT payload 解码（仅提取 roles，不验签——验签由后端资源服务负责） */
export function decodeJwtRoles(token: string): string[] {
  try {
    const payload = token.split(".")[1];
    const normalized = payload.replace(/-/g, "+").replace(/_/g, "/");
    const json = decodeURIComponent(
      atob(normalized)
        .split("")
        .map((c) => "%" + ("00" + c.charCodeAt(0).toString(16)).slice(-2))
        .join(""),
    );
    const claims = JSON.parse(json);
    return Array.isArray(claims.roles) ? claims.roles : [];
  } catch {
    return [];
  }
}

/** 剩余时间的友好展示 */
export function formatRemaining(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  return `${h}时${m}分${s}秒`;
}
