package com.kama.jchatmind.agent;

/** A malformed provider answer must not become a successful user message. */
public final class InvalidAnswerFormatException extends RuntimeException {
    public InvalidAnswerFormatException() {
        super("Model answer contains an invalid tool parameter boundary");
    }
}
