package com.clara;

import com.clara.model.embedding.HashingEmbeddingClient;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 默认装配路径：验证缺省 MCP 客户端工厂（允许命令清单解析）与构造参数校验。
 */
public class ApplicationBootstrapDefaultFactoryTest {

    /**
     * 使用默认装配（含真实默认 MCP 工厂）时应能完成装配，并可从内部解析 stdio 允许清单。
     */
    @Test
    public void assemblesWithDefaultMcpFactory() {
        ApplicationBootstrap bootstrap = new ApplicationBootstrap();
        try {
            assertNotNull(bootstrap.mcpGateway());
            assertNotNull(bootstrap.embeddingClient());
            assertNotNull(bootstrap.externalToolCatalog());
            assertTrue(bootstrap.semanticMemoryDescription().length() > 0);
        } finally {
            bootstrap.close();
        }
    }

    /**
     * 非法的嵌入客户端与向量维度应在构造阶段被拒绝。
     */
    @Test
    public void rejectsInvalidConstructorArguments() {
        try {
            new ApplicationBootstrap(null, 8);
            fail("embeddingClient 为 null 时应抛异常");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("embeddingClient"));
        }
        try {
            new ApplicationBootstrap(new HashingEmbeddingClient(8), 0);
            fail("embeddingDimension 非正时应抛异常");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("embeddingDimension"));
        }
    }
}