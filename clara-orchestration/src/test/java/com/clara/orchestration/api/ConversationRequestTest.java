package com.clara.orchestration.api;

import com.clara.dto.memory.ContextControlConfig;
import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.ModelProviderEnum;
import com.clara.kernel.stream.IStreamObserver;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

/**
 * {@link ConversationRequest} 单元测试：builder 字段透传与默认值。
 */
public class ConversationRequestTest {

    @Test
    public void builderPopulatesAllFields() {
        ContextControlConfig control = ContextControlConfig.defaults();
        IStreamObserver observer = event -> { };

        ConversationRequest request = ConversationRequest.builder()
                .userId("u1")
                .conversationId("c1")
                .provider(ModelProviderEnum.OPENAI)
                .model("m1")
                .capabilityKind(CapabilityKindEnum.IMAGE)
                .userInput("draw a cat")
                .control(control)
                .stream(true)
                .observer(observer)
                .maxToolRoundTrips(5)
                .build();

        assertEquals("u1", request.getUserId());
        assertEquals("c1", request.getConversationId());
        assertEquals(ModelProviderEnum.OPENAI, request.getProvider());
        assertEquals("m1", request.getModel());
        assertEquals(CapabilityKindEnum.IMAGE, request.getCapabilityKind());
        assertEquals("draw a cat", request.getUserInput());
        assertSame(control, request.getControl());
        assertTrue(request.isStream());
        assertSame(observer, request.getObserver());
        assertEquals(5, request.getMaxToolRoundTrips());
    }

    @Test
    public void builderDefaultsAreEmptyAndNonStreaming() {
        ConversationRequest request = ConversationRequest.builder().build();

        assertNull(request.getUserId());
        assertNull(request.getConversationId());
        assertNull(request.getProvider());
        assertNull(request.getModel());
        assertNull(request.getCapabilityKind());
        assertNull(request.getUserInput());
        assertNull(request.getControl());
        assertNull(request.getObserver());
        assertFalse(request.isStream());
        assertEquals(0, request.getMaxToolRoundTrips());
    }
}
