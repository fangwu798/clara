package com.clara.orchestration.api;

import com.clara.exception.ClaraError;
import com.clara.enums.ErrorCategoryEnum;
import com.clara.exception.ErrorCodes;
import com.clara.kernel.message.delta.TokenUsage;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

/**
 * {@link ConversationResult} 单元测试：builder 字段透传。
 */
public class ConversationResultTest {

    @Test
    public void builderPopulatesAllFields() {
        TokenUsage usage = new TokenUsage(10, 5, 15, null);
        ClaraError error = ClaraError.builder(ErrorCodes.INVALID_ARGUMENT, ErrorCategoryEnum.VALIDATION)
                .safeMessage("boom")
                .build();

        ConversationResult result = ConversationResult.builder()
                .conversationId("c1")
                .content("answer")
                .status(OrchestrationStatusEnum.FAILED)
                .toolRoundTrips(2)
                .usage(usage)
                .error(error)
                .build();

        assertEquals("c1", result.getConversationId());
        assertEquals("answer", result.getContent());
        assertEquals(OrchestrationStatusEnum.FAILED, result.getStatus());
        assertEquals(2, result.getToolRoundTrips());
        assertSame(usage, result.getUsage());
        assertSame(error, result.getError());
    }

    @Test
    public void builderDefaultsAreEmpty() {
        ConversationResult result = ConversationResult.builder().build();

        assertNull(result.getConversationId());
        assertNull(result.getContent());
        assertNull(result.getStatus());
        assertNull(result.getUsage());
        assertNull(result.getError());
        assertEquals(0, result.getToolRoundTrips());
    }
}
