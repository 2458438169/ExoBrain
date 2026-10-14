import { createContext, useContext } from "react";
import type { AccountUser } from "../api/accounts.ts";

export interface AccountState {
  user: AccountUser | null;
  status: "loading" | "signedOut" | "signedIn" | "error";
  error: string;
  sessionVersion: number;
  login: (username: string, password: string) => Promise<void>;
  logout: () => Promise<void>;
  refresh: () => Promise<void>;
  clear: () => void;
}

export const AccountContext = createContext<AccountState | null>(null);

export function useAccount() {
  return useContext(AccountContext);
}
