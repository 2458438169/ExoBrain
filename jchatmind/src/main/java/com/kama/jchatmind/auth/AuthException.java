package com.kama.jchatmind.auth;

public class AuthException extends RuntimeException {
    private final int status;
    public AuthException(int status, String message) { super(message); this.status = status; }
    public int getStatus() { return status; }
    public static AuthException unauthorized() { return new AuthException(401, "请先登录"); }
    public static AuthException forbidden() { return new AuthException(403, "没有操作权限"); }
    public static AuthException missing() { return new AuthException(404, "资源不存在"); }
}
