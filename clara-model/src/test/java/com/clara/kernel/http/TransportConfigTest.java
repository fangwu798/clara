package com.clara.kernel.http;

import com.clara.core.transport.HttpTransportPolicy;
import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertTrue;

/**
 * {@link TransportConfig} 单元测试：SSE 单行/单事件上限必须容纳平台的终端事件。
 */
public class TransportConfigTest {

    @Test
    public void sseAllowsTerminalEventWithFullInstructionsAndReasoning() {
        TransportConfig config = new TransportConfig(Collections.singleton("https://api.deepseek.com"),
                10_000, 600_000, 8 * 1024 * 1024, 32 * 1024 * 1024, false);

        HttpTransportPolicy policy = config.toHttpTransportPolicy();

        // 终端事件是一条包含完整 instructions 与思维链的 data 行，64KB 的旧上限会把正常响应判成超限。
        assertTrue(policy.getSseParserConfig().getMaxLineBytes() >= 1024 * 1024);
        assertTrue(policy.getSseParserConfig().getMaxEventBytes() >= 1024 * 1024);
    }
}
