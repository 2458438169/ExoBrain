package com.kama.jchatmind.auth;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScopedToolCallbackTest {
    private static final String ALLOWED = "11111111-1111-4111-8111-111111111111";
    private static final String PRIVATE = "22222222-2222-4222-8222-222222222222";
    @Test void modelSuppliedForeignKnowledgeBaseIdsNeverReachTheUnderlyingTool() {
        ToolCallback underlying = mock(ToolCallback.class);
        when(underlying.getToolDefinition()).thenReturn(ToolDefinition.builder().name("KnowledgeTool")
                .description("Knowledge search").inputSchema("{}").build());
        Runnable authorize = mock(Runnable.class);
        var scoped = new ScopedToolCallback(underlying, Set.of(ALLOWED), authorize);
        String valid = "{\"kbsId\":\"" + ALLOWED + "\",\"query\":\"hello\"}";
        when(underlying.call(valid)).thenReturn("allowed result");
        assertEquals("allowed result", scoped.call(valid));
        assertThrows(AuthException.class, () -> scoped.call("{\"kbsId\":\"" + PRIVATE + "\"}"));
        assertThrows(AuthException.class, () -> scoped.call("{\"kbsId\":null}"));
        assertThrows(AuthException.class, () -> scoped.call("not-json"));
        verify(underlying).call(valid);
        verify(underlying, times(1)).call(anyString());
        verify(authorize, times(4)).run();
    }
    @Test void revokingTheActorStopsEvenAnAlreadyConstructedToolCallback() {
        ToolCallback underlying = mock(ToolCallback.class);
        Runnable authorize = mock(Runnable.class);
        doThrow(AuthException.unauthorized()).when(authorize).run();
        var scoped = new ScopedToolCallback(underlying, Set.of(), authorize);
        assertThrows(AuthException.class, () -> scoped.call("{}"));
        verifyNoInteractions(underlying);
    }
}
