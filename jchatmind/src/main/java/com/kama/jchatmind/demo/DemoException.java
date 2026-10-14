package com.kama.jchatmind.demo;

public class DemoException extends RuntimeException {
    private final int status;

    public DemoException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() { return status; }
}
