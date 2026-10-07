import type { ApiResponse, PageData } from "../types/api";
import { useAuthStore } from "../stores/auth";

/** 业务错误：携带稳定错误码（docs/api.md 错误码注册表） */
export class ApiError extends Error {
  readonly code: string;
  readonly status: number;

  constructor(code: string, message: string, status: number) {
    super(message);
    this.code = code;
    this.status = status;
  }
}

interface RequestOptions {
  method?: string;
  /** JSON 请求体（自动序列化并设置 Content-Type） */
  json?: unknown;
  query?: Record<string, string | number | boolean | undefined>;
  signal?: AbortSignal;
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { method, json, query, signal } = options;
  const url = new URL(path, window.location.origin);
  if (query) {
    for (const [k, v] of Object.entries(query)) {
      if (v !== undefined) url.searchParams.set(k, String(v));
    }
  }

  const headers: Record<string, string> = {};
  const token = useAuthStore.getState().token;
  if (token) headers.Authorization = `Bearer ${token}`;
  if (json !== undefined) headers["Content-Type"] = "application/json";

  const res = await fetch(url.pathname + url.search, {
    method: method ?? (json !== undefined ? "POST" : "GET"),
    headers,
    body: json !== undefined ? JSON.stringify(json) : undefined,
    signal,
  });

  const envelope = (await res.json().catch(() => null)) as ApiResponse<T> | null;

  if (res.status === 401 && useAuthStore.getState().token) {
    useAuthStore.getState().clear();
    window.location.href = "/login";
  }

  if (!res.ok || !envelope || envelope.error) {
    throw new ApiError(
      envelope?.error?.code ?? "HTTP_ERROR",
      envelope?.error?.message ?? `请求失败（${res.status}）`,
      res.status,
    );
  }
  return envelope.data;
}

/** GET 分页列表 */
export function getPage<T>(path: string, query?: RequestOptions["query"], signal?: AbortSignal) {
  return request<PageData<T>>(path, { query, signal });
}

/** GET 单体 */
export function getData<T>(path: string, signal?: AbortSignal) {
  return request<T>(path, { signal });
}

/** POST/DELETE 等，返回 data 或 null */
export function sendData<T>(path: string, json?: unknown, method = "POST", signal?: AbortSignal) {
  return request<T>(path, { json, method, signal });
}

export { request };
