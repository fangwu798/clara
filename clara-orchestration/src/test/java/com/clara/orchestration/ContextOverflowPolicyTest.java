package com.clara.orchestration;

import com.clara.dto.memory.ContextControlConfig;
import com.clara.enums.ModelProviderEnum;
import com.clara.exception.ClaraException;
import com.clara.utils.ExceptionUtils;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ContextOverflowPolicy} 单元测试：超限识别、压缩强度递增与输出上限覆盖。
 */
public class ContextOverflowPolicyTest {

    @Test
    public void recognizesProviderContextLengthFailure() {
        ClaraException failure = ExceptionUtils.providerFailure(ModelProviderEnum.DEEPSEEK, 400,
                "This model's maximum context length is 131072 tokens.");

        assertTrue(ContextOverflowPolicy.matches(failure));
    }

    @Test
    public void ignoresUnrelatedBadRequest() {
        ClaraException failure = ExceptionUtils.providerFailure(ModelProviderEnum.DEEPSEEK, 400,
                "invalid parameter: temperature");

        assertFalse(ContextOverflowPolicy.matches(failure));
    }

    @Test
    public void recognizesLocalRequestTooLarge() {
        assertTrue(ContextOverflowPolicy.matches(ExceptionUtils.requestTooLarge()));
        assertTrue(ContextOverflowPolicy.matches(ExceptionUtils.responseTooLarge()));
    }

    @Test
    public void compactsWindowAndToolBudgetOnFirstAttempt() {
        ContextControlConfig compacted = ContextOverflowPolicy.compactControl(
                ContextControlConfig.defaults(), 1);

        assertEquals(24, compacted.getWindowMaxMessages());
        assertEquals(256L * 1024L, compacted.getWindowMaxSizeBytes());
        assertEquals(800, compacted.getToolOutputMaxChars());
        assertEquals(ContextControlConfig.defaults().getRetrievalCount(), compacted.getRetrievalCount());
    }

    @Test
    public void secondAttemptIsTighter() {
        ContextControlConfig compacted = ContextOverflowPolicy.compactControl(
                ContextControlConfig.defaults(), 2);

        assertEquals(12, compacted.getWindowMaxMessages());
        assertEquals(128L * 1024L, compacted.getWindowMaxSizeBytes());
        assertEquals(400, compacted.getToolOutputMaxChars());
    }

    @Test
    public void keepsStricterConfiguredLimits() {
        ContextControlConfig base = ContextControlConfig.builder()
                .contextWindowTokens(-1L)
                .windowMaxMessages(5)
                .windowMaxSizeBytes(1024L)
                .toolOutputMaxChars(300)
                .build();

        ContextControlConfig compacted = ContextOverflowPolicy.compactControl(base, 1);

        assertEquals(5, compacted.getWindowMaxMessages());
        assertEquals(1024L, compacted.getWindowMaxSizeBytes());
        assertEquals(300, compacted.getToolOutputMaxChars());
    }

    @Test
    public void outputTokensOnlyForResponseTooLarge() {
        ClaraException providerFailure = ExceptionUtils.providerFailure(ModelProviderEnum.DEEPSEEK, 400,
                "maximum context length exceeded");
        assertNull(ContextOverflowPolicy.outputTokens(providerFailure, 1));
        assertEquals(Integer.valueOf(32768), ContextOverflowPolicy.outputTokens(ExceptionUtils.responseTooLarge(), 1));
        assertEquals(Integer.valueOf(16384), ContextOverflowPolicy.outputTokens(ExceptionUtils.responseTooLarge(), 2));
    }

    @Test
    public void outputTokensNeverExceedPerCallCeiling() {
        assertEquals(Integer.valueOf(10000), ContextOverflowPolicy.outputTokens(
                ExceptionUtils.responseTooLarge(), 1, 10000));
        assertEquals(Integer.valueOf(10000), ContextOverflowPolicy.outputTokens(
                ExceptionUtils.responseTooLarge(), 2, 10000));
    }
}
