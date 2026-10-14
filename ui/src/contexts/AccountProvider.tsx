import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import { getCurrentAccount, loginAccount, logoutAccount, type AccountUser, type AuthResponse } from "../api/accounts.ts";
import { AuthenticationError } from "../api/http.ts";
import { authSnapshot, onAuthExpired, setAuthToken } from "../api/authSession.ts";
import { AccountContext, type AccountState } from "./accountState.ts";

export default function AccountProvider({ children }: { children: ReactNode }) {
  const navigate = useNavigate();
  const [user, setUser] = useState<AccountUser | null>(null);
  const [status, setStatus] = useState<AccountState["status"]>("loading");
  const [error, setError] = useState("");
  const [sessionVersion, setSessionVersion] = useState(0);
  const currentUser = useRef<AccountUser | null>(null);

  const clear = useCallback(() => {
    currentUser.current = null;
    setUser(null);
    setSessionVersion(setAuthToken(null));
    setStatus("signedOut");
    setError("");
    navigate("/", { replace: true, state: null });
  }, [navigate]);

  const acceptSession = useCallback((result: AuthResponse) => {
      const previous = currentUser.current;
      if (previous?.id !== result.user.id || previous?.role !== result.user.role
        || authSnapshot().csrfToken !== result.csrfToken) {
        setSessionVersion(setAuthToken(result.csrfToken));
      }
      currentUser.current = result.user;
      setUser(result.user);
      setError("");
      setStatus("signedIn");
  }, []);

  const rejectSession = useCallback((failure: unknown) => {
      if (failure instanceof DOMException && failure.name === "AbortError") return;
      if (failure instanceof AuthenticationError) {
        clear();
      } else if (!currentUser.current) {
        setError(failure instanceof Error ? failure.message : "暂时无法连接，请稍后重试");
        setStatus("error");
      }
  }, [clear]);

  const refresh = useCallback(() => getCurrentAccount().then(acceptSession).catch(rejectSession), [acceptSession, rejectSession]);

  useEffect(() => {
    const unsubscribe = onAuthExpired(clear);
    void getCurrentAccount().then(acceptSession).catch(rejectSession);
    const checkVisibleAccount = () => {
      if (document.visibilityState === "visible" && currentUser.current) void refresh();
    };
    window.addEventListener("focus", checkVisibleAccount);
    document.addEventListener("visibilitychange", checkVisibleAccount);
    return () => {
      unsubscribe();
      window.removeEventListener("focus", checkVisibleAccount);
      document.removeEventListener("visibilitychange", checkVisibleAccount);
    };
  }, [refresh, clear, acceptSession, rejectSession]);

  const login = async (username: string, password: string) => {
    const result = await loginAccount(username, password);
    setSessionVersion(setAuthToken(result.csrfToken));
    currentUser.current = result.user;
    setUser(result.user);
    setError("");
    setStatus("signedIn");
    navigate("/chat", { replace: true, state: null });
  };

  const logout = async () => {
    try {
      await logoutAccount();
    } catch (failure) {
      if (!(failure instanceof AuthenticationError)) throw failure;
    }
    clear();
  };

  return (
    <AccountContext.Provider value={{ user, status, error, sessionVersion, login, logout, refresh, clear }}>
      {children}
    </AccountContext.Provider>
  );
}
