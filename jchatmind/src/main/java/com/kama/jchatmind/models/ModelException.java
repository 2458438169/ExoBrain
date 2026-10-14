package com.kama.jchatmind.models;

/** Only fixed, user-safe messages belong in this exception. */
public class ModelException extends RuntimeException {
    private final int status;

    public ModelException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int getStatus() { return status; }
}
