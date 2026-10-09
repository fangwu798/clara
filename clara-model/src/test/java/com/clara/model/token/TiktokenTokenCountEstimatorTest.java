package com.clara.model.token;

import com.clara.core.message.IContent;
import com.clara.core.memory.ITokenCountEstimator;
import com.clara.kernel.message.delta.UserContent;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link TiktokenTokenCountEstimator} 的单元测试：验证 jtokkit 内置词表可直接使用，
 * 对已知文本给出真实 BPE token 数。
 */
public class TiktokenTokenCountEstimatorTest {

    /**
     * {@code cl100k_base} 下 "hello world" 为 2 个 token（{@code hello} / {@code  world}）。
     */
    @Test
    public void estimatesKnownTextWithCl100kBase() {
        ITokenCountEstimator estimator = new TiktokenTokenCountEstimator(TiktokenTokenCountEstimator.DEFAULT_ENCODING);
        List<IContent> messages = Collections.<IContent>singletonList(new UserContent("hello world"));
        int tokens = estimator.estimate(messages);
        assertTrue("hello world 的 token 数应大于 0，实际=" + tokens, tokens > 0);
        assertTrue("hello world 的 token 数应约为 2-3，实际=" + tokens, tokens >= 2 && tokens <= 3);
    }

    /**
     * 未知编码名应在构造期被拒绝。
     */
    @Test
    public void rejectsUnknownEncodingName() {
        try {
            new TiktokenTokenCountEstimator("no-such-encoding");
            fail("unknown encoding name should be rejected");
        } catch (IllegalArgumentException expected) {
            // 预期：未知编码名不可用
        }
    }
}
