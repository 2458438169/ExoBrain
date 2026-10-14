import { get, patch, post } from "./http.ts";

export type AccountRole = "ADMIN" | "USER";

export interface AccountUser {
  id: string;
  username: string;
  displayName: string;
  role: AccountRole;
  enabled: boolean;
  createdAt?: string;
}

export interface AuthResponse {
  user: AccountUser;
  csrfToken: string;
}

export interface UserRequest {
  username: string;
  displayName: string;
  password: string;
  role: AccountRole;
  enabled: boolean;
}

export const getCurrentAccount = () => get<AuthResponse>("/auth/me", undefined, { silent: true });
export const loginAccount = (username: string, password: string) =>
  post<AuthResponse>("/auth/login", { username, password }, { silent: true });
export const logoutAccount = () => post<void>("/auth/logout", undefined, { silent: true });
export const changePassword = (currentPassword: string, newPassword: string) =>
  patch<void>("/auth/password", { currentPassword, newPassword }, { silent: true });
export const getAccountUsers = () => get<{ users: AccountUser[] }>("/admin/users", undefined, { silent: true });
export const createAccountUser = (body: UserRequest) =>
  post<{ user: AccountUser }>("/admin/users", body, { silent: true });
export const updateAccountUser = (id: string, body: Partial<Omit<UserRequest, "username">>) =>
  patch<{ user: AccountUser }>(`/admin/users/${encodeURIComponent(id)}`, body, { silent: true });
