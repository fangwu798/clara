package com.clara;

import com.clara.dto.record.CallRecord;
import com.clara.enums.ModelProviderEnum;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.kernel.http.HttpRequest;
import com.clara.kernel.http.HttpResponse;
import com.clara.mcp.api.IMcpClient;
import com.clara.mcp.api.IMcpClientFactory;
import com.clara.mcp.registry.McpServerRegistration;
import com.clara.model.configuration.ProviderYamlConfigurationLoader;
import com.clara.model.embedding.HashingEmbeddingClient;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ApplicationBootstrap} 的装配与只读接口测试：确认各协作者可取出、说明文本可用，
 * 以及压缩 / 用量统计在空历史与非法入参下的语义。
 */
public class ApplicationBootstrapBehaviorTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void exposesAssembledCollaborators() throws Exception {
        ApplicationBootstrap bootstrap = bootstrap();
        try {
            assertNotNull(bootstrap.orchestrator());
            assertNotNull(bootstrap.workflowEngine());
            assertNotNull(bootstrap.workflowDefinitionRegistry());
            assertNotNull(bootstrap.workflowRunner());
            assertNotNull(bootstrap.modelRegistry());
            assertNotNull(bootstrap.embeddingClient());
            assertNotNull(bootstrap.mcpGateway());
            assertNotNull(bootstrap.mcpCapabilityCatalog());
            assertNotNull(bootstrap.mcpServerRegistry());
            assertNotNull(bootstrap.mcpDiscoveryService());
            assertNotNull(bootstrap.externalToolCatalog());
            assertNotNull(bootstrap.codeGraphSettings());
            assertNotNull(bootstrap.callRecordSink());
            assertNotNull(bootstrap.capabilityUsage());
            assertNotNull(bootstrap.semanticMemoryDescription());
            assertTrue(bootstrap.conversationHistoryDescription().contains(".clara"));
        } finally {
            bootstrap.close();
        }
    }

    @Test
    public void describesToolInvocationForKnownUnknownAndBlankNames() {
        ApplicationBootstrap bootstrap = bootstrap();
        try {
            assertTrue(bootstrap.describeToolInvocation("run_command", "{\"command\":\"mvn test\"}").length() > 0);
            assertNull(bootstrap.describeToolInvocation("no-such-tool", "{}"));
            assertNull(bootstrap.describeToolInvocation(null, null));
            assertNull(bootstrap.describeToolInvocation("  ", null));
        } finally {
            bootstrap.close();
        }
    }

    @Test
    public void usageReportHandlesMissingBindingAndEmptyHistory() throws Exception {
        ApplicationBootstrap bootstrap = bootstrap();
        ProviderConfiguration configuration = loadProviderConfiguration();
        try {
            String unbound = bootstrap.conversationUsageReport("c1", null, null);
            assertTrue(unbound.contains("会话：c1"));
            assertTrue(unbound.contains("历史记录：未知"));
            assertTrue(unbound.contains("本次会话累计调用：0 次"));
            assertTrue(unbound.contains("按平台统计："));
            assertTrue(unbound.contains("（暂无模型调用）"));

            String bound = bootstrap.conversationUsageReport("c1", configuration, "alpha-model");
            assertTrue(bound.contains("历史记录：0 条"));
            assertTrue(bound.contains("估算 token：0"));
        } finally {
            configuration.close();
            bootstrap.close();
        }
    }

    /**
     * 用量报告按平台拆分：同一平台的调用合并，不同平台分别展示，并给出总量。
     */
    @Test
    public void usageReportSplitsTokensByProvider() throws Exception {
        ApplicationBootstrap bootstrap = bootstrap();
        ProviderConfiguration configuration = loadProviderConfiguration();
        try {
            bootstrap.callRecordSink().accept(record(ModelProviderEnum.DEEPSEEK, 10, 5, 15));
            bootstrap.callRecordSink().accept(record(ModelProviderEnum.OPENAI, 100, 50, 150));

            String report = bootstrap.conversationUsageReport("c1", configuration, "alpha-model");

            assertTrue(report.contains("本次会话累计调用：2 次"));
            assertTrue(report.contains("DEEPSEEK"));
            assertTrue(report.contains("OPENAI"));
            assertTrue(report.contains("合计 165 tokens"));
        } finally {
            configuration.close();
            bootstrap.close();
        }
    }

    @Test
    public void compressConversationRejectsInvalidInputAndEmptyHistory() throws Exception {
        ApplicationBootstrap bootstrap = bootstrap();
        ProviderConfiguration configuration = loadProviderConfiguration();
        try {
            assertEquals(0, bootstrap.compressConversation(" ", configuration, "m"));
            assertEquals(0, bootstrap.compressConversation("c1", null, "m"));
            assertEquals(0, bootstrap.compressConversation("c1", configuration, " "));
            assertEquals(0, bootstrap.compressConversation("c1", configuration, "alpha-model"));
        } finally {
            configuration.close();
            bootstrap.close();
        }
    }

    private static CallRecord record(ModelProviderEnum provider, int input, int output, Integer total) {
        HttpResponse response = new HttpResponse();
        response.setInputTokens(input);
        response.setOutputTokens(output);
        response.setTotalTokens(total);
        return new CallRecord(HttpRequest.builder().build(), response, provider, "alpha-model");
    }

    private ProviderConfiguration loadProviderConfiguration() throws Exception {
        Path file = folder.newFile("alpha.yml").toPath();
        String yaml = "provider: DEEPSEEK\n"
                + "credential:\n"
                + "  secretRef: deepseek\n"
                + "  environmentVariable: CLARA_DEEPSEEK_API_KEY\n"
                + "transport:\n"
                + "  allowedOrigins:\n"
                + "    - https://api.deepseek.com\n"
                + "  connectTimeoutMillis: 10000\n"
                + "  readTimeoutMillis: 180000\n"
                + "  maxRequestBytes: 8388608\n"
                + "  maxResponseBytes: 33554432\n"
                + "  allowHttp: false\n"
                + "capabilities:\n"
                + "  CHAT:\n"
                + "    endpoint: https://api.deepseek.com/responses\n"
                + "    modelId: alpha-model\n"
                + "    apiProfile: RESPONSE\n";
        Files.write(file, yaml.getBytes(StandardCharsets.UTF_8));
        return new ProviderYamlConfigurationLoader().load(file);
    }

    private static ApplicationBootstrap bootstrap() {
        IMcpClientFactory factory = new IMcpClientFactory() {
            @Override
            public IMcpClient create(McpServerRegistration registration) {
                throw new IllegalStateException("not used in this test");
            }
        };
        return new ApplicationBootstrap(new HashingEmbeddingClient(1024), 1024, factory);
    }
}