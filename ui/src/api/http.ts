import { notify } from "./feedback.ts";
import { IS_PUBLIC_DEMO } from "../config.ts";
import { authSnapshot, expireAuthSession } from "./authSession.ts";

// API 响应类型定义，匹配后端 ApiResponse 结构
export interface ApiResponse<T = unknown> {
  code: number;
  message: string;
  data: T;
}

// 请求配置选项
export interface RequestOptions extends RequestInit {
  params?: Record<string, string | number | boolean | null | undefined>;
  silent?: boolean;
}

class ReportedApiError extends Error {}
export class AuthenticationError extends Error {}

export function showRequestError(error: unknown, fallback: string): void {
  if (!(error instanceof ReportedApiError) && !(error instanceof AuthenticationError)
    && !(error instanceof DOMException && error.name === "AbortError")) {
    notify.error(error instanceof Error ? error.message : fallback);
  }
}

// 默认使用同源路由，生产环境由反向代理转发到后端。
export const BASE_URL = (
  import.meta.env.VITE_API_BASE_URL || (IS_PUBLIC_DEMO ? "/api/demo" : "/api")
).replace(/\/+$/, "");
export const SSE_BASE_URL = (
  import.meta.env.VITE_SSE_BASE_URL || (IS_PUBLIC_DEMO ? "/sse/demo" : "/sse")
).replace(/\/+$/, "");

/**
 * 构建完整的 URL（包含查询参数）
 */
function buildUrl(url: string, params?: Record<string, string | number | boolean | null | undefined>): string {
  const fullUrl = `${BASE_URL}${url}`;
  
  if (!params || Object.keys(params).length === 0) {
    return fullUrl;
  }

  const searchParams = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== null && value !== undefined) {
      searchParams.append(key, String(value));
    }
  });

  const queryString = searchParams.toString();
  return queryString ? `${fullUrl}?${queryString}` : fullUrl;
}

/**
 * 处理响应
 */
async function handleResponse<T>(response: Response, silent: boolean, requestRevision: number): Promise<ApiResponse<T>> {
  const data: ApiResponse<T> | null = await response.json().catch(() => null);
  if (!IS_PUBLIC_DEMO && requestRevision !== authSnapshot().revision) {
    throw new DOMException("账号已切换", "AbortError");
  }
  if (!IS_PUBLIC_DEMO && (response.status === 401 || data?.code === 401)) {
    expireAuthSession(requestRevision);
    throw new AuthenticationError(data?.message || "请登录后继续");
  }
  if (!response.ok) {
    const errorMessage = data?.message || `请求失败（HTTP ${response.status}）`;
    if (!silent) notify.error(errorMessage);
    throw silent ? new Error(errorMessage) : new ReportedApiError(errorMessage);
  }

  if (!data) {
    throw new Error("服务器返回了无效响应，请重试");
  }

  // 检查业务状态码
  if (data.code !== 200) {
    if (!silent) notify.error(data.message || "请求失败");
    throw silent ? new Error(data.message || "请求失败") : new ReportedApiError(data.message || "请求失败");
  }

  return data;
}

/**
 * 封装的 fetch 请求函数
 */
export async function request<T = unknown>(
  url: string,
  options: RequestOptions = {}
): Promise<T> {
  const { params, headers, silent = false, ...restOptions } = options;
  const session = authSnapshot();

  // 构建完整 URL
  const fullUrl = buildUrl(url, params);

  // 设置默认请求头
  const defaultHeaders = new Headers(headers);
  if (!(restOptions.body instanceof FormData)) defaultHeaders.set("Content-Type", "application/json");
  const isWrite = !["GET", "HEAD", "OPTIONS"].includes((restOptions.method || "GET").toUpperCase());
  if (!IS_PUBLIC_DEMO && isWrite && session.csrfToken) {
    defaultHeaders.set("X-CSRF-Token", session.csrfToken);
  }

  try {
    const response = await fetch(fullUrl, {
      credentials: "same-origin",
      cache: "no-store",
      ...restOptions,
      headers: defaultHeaders,
    });

    const apiResponse = await handleResponse<T>(response, silent, session.revision);
    return apiResponse.data;
  } catch (error) {
    // 统一错误处理
    if (error instanceof Error) {
      throw error;
    }
    throw new Error("网络请求失败");
  }
}

/**
 * GET 请求
 */
export function get<T = unknown>(
  url: string,
  params?: Record<string, string | number | boolean | null | undefined>,
  options?: Omit<RequestOptions, "method" | "body" | "params">
): Promise<T> {
  return request<T>(url, {
    ...options,
    method: "GET",
    params,
  });
}

/**
 * POST 请求
 */
export function post<T = unknown>(
  url: string,
  data?: unknown,
  options?: Omit<RequestOptions, "method" | "body">
): Promise<T> {
  return request<T>(url, {
    ...options,
    method: "POST",
    body: data ? JSON.stringify(data) : undefined,
  });
}

/**
 * PUT 请求
 */
export function put<T = unknown>(
  url: string,
  data?: unknown,
  options?: Omit<RequestOptions, "method" | "body">
): Promise<T> {
  return request<T>(url, {
    ...options,
    method: "PUT",
    body: data ? JSON.stringify(data) : undefined,
  });
}

/**
 * PATCH 请求
 */
export function patch<T = unknown>(
  url: string,
  data?: unknown,
  options?: Omit<RequestOptions, "method" | "body">
): Promise<T> {
  return request<T>(url, {
    ...options,
    method: "PATCH",
    body: data ? JSON.stringify(data) : undefined,
  });
}

/**
 * DELETE 请求
 */
export function del<T = unknown>(
  url: string,
  params?: Record<string, string | number | boolean | null | undefined>,
  options?: Omit<RequestOptions, "method" | "body" | "params">
): Promise<T> {
  return request<T>(url, {
    ...options,
    method: "DELETE",
    params,
  });
}

// 导出默认对象，方便使用
export default {
  get,
  post,
  put,
  patch,
  delete: del,
};
