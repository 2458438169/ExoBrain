package com.kama.jchatmind.event;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class ChatEvent {
    private final String agentId;
    private final String sessionId;
    private final String userInput;
    private final String actorId;
    private final int actorAuthVersion;
    private final String actorSessionHash;
    private final String triggeringMessageId;
    private final String historySnapshot;
}
