package com.clara.workflow;

import com.clara.config.ModelRegistry;
import com.clara.core.skill.IPromptProvider;
import com.clara.dto.workflow.ExecutionScope;
import com.clara.dto.workflow.WorkflowActionSpec;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.kernel.http.HttpRequest;
import com.clara.media.VideoDownloader;
import com.clara.usage.CapabilityUsageLedger;
import com.clara.workflow.runtime.AgentNodeOptions;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link AgentWorkflowAction} 的执行测试：文本 / 图片产出整理、内联提示词与变量替换、
 * 多模态附件、失败状态传播，以及视频必须先人工批准的保护。
 */
public class AgentWorkflowActionTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private ModelRegistry registry;
    private ProviderConfiguration configuration;
    private CapabilityUsageLedger ledger;

    @Before
    public void setUp() throws Exception {
        Path config = WorkflowActionTestSupport.providerConfig(folder.getRoot().toPath(), "alpha");
        registry = new ModelRegistry(Collections.singletonList(config), Collections.<String, String>emptyMap());
        registry.select(config);
        configuration = registry.currentConfiguration();
        ledger = new CapabilityUsageLedger();
    }

    @After
    public void tearDown() {
        if (registry != null) {
            registry.close();
        }
    }

    @Test
    public void parsesFencedJsonIntoMapOutput() {
        WorkflowActionTestSupport.FakeModel model = WorkflowActionTestSupport.orchestrator(
                WorkflowActionTestSupport.response("```json\n{\"score\":1}\n```", 10, 5));

        Map<String, Object> output = action(model, spec("n1", attributes("prompt", "p"))).execute(scope());

        assertTrue(output.get("output") instanceof Map);
        assertEquals(1, ((Map<?, ?>) output.get("output")).get("score"));
    }

    @Test
    public void keepsPlainTextWhenNotJson() {
        WorkflowActionTestSupport.FakeModel model = WorkflowActionTestSupport.orchestrator(
                WorkflowActionTestSupport.response("hello", 1, 1));

        Map<String, Object> output = action(model, spec("n1", attributes("prompt", "p"))).execute(scope());

        assertEquals("hello", output.get("output"));
    }

    @Test
    public void blankReplyFails() {
        WorkflowActionTestSupport.FakeModel model = WorkflowActionTestSupport.orchestrator(
                WorkflowActionTestSupport.response("   ", 1, 1));
        try {
            action(model, spec("n1", attributes("prompt", "p"))).execute(scope());
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("回复为空"));
        }
    }

    @Test
    public void failedOrchestrationPropagates() {
        WorkflowActionTestSupport.FakeModel model = WorkflowActionTestSupport.orchestrator(null);
        try {
            action(model, spec("n1", attributes("prompt", "p"))).execute(scope());
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("未成功"));
        }
    }

    @Test
    public void imageOutputBecomesMediaReferencesAndIsRecorded() {
        com.clara.kernel.http.HttpResponse response = WorkflowActionTestSupport.response(null, 4, 0);
        response.setImages(Arrays.asList(image("https://cdn/a.png"), image("https://cdn/b.png")));
        response.setGeneratedImages(2);
        WorkflowActionTestSupport.FakeModel model = WorkflowActionTestSupport.orchestrator(response);
        Map<String, Object> attributes = attributes("prompt", "p");
        attributes.put("capability", "IMAGE");

        Map<String, Object> output = action(model, spec("n1", attributes)).execute(scope());

        @SuppressWarnings("unchecked")
        Map<String, Object> media = (Map<String, Object>) output.get("output");
        assertEquals("IMAGE", media.get("mediaType"));
        assertEquals(2, ((List<?>) media.get("references")).size());
        assertTrue(ledger.report().contains("图片：2 张"));
    }

    @Test
    public void imageGenerationFallsBackWhenPrimaryFails() throws Exception {
        registry.close();
        Path root = folder.getRoot().toPath();
        Path primary = imageProvider(root, "openai", "OPENAI", "gpt-image-1", "https://openai.test");
        Path fallback = imageProvider(root, "volcengine-response", "VOLCENGINE",
                "doubao-seedream", "https://volcengine.test");
        registry = new ModelRegistry(Arrays.asList(primary, fallback),
                Collections.<String, String>emptyMap());
        registry.select(primary);
        ledger = new CapabilityUsageLedger();

        com.clara.kernel.http.HttpResponse response = WorkflowActionTestSupport.response(null, 1, 1);
        response.setImages(Collections.singletonList(image("https://cdn/a.png")));
        WorkflowActionTestSupport.FakeModel model = WorkflowActionTestSupport.orchestrator(response);
        model.failOnce(new IllegalStateException("primary unavailable"));

        Map<String, Object> attributes = attributes("prompt", "p");
        attributes.put("capability", "IMAGE");
        attributes.put("model", "openai");
        attributes.put("fallbackModel", "volcengine-response");

        Map<String, Object> output = action(model, spec("n1", attributes)).execute(scope());

        @SuppressWarnings("unchecked")
        Map<String, Object> media = (Map<String, Object>) output.get("output");
        assertEquals("IMAGE", media.get("mediaType"));
        assertEquals(1, ((List<?>) media.get("references")).size());
        assertEquals("https://cdn/a.png", ((List<?>) media.get("references")).get(0));
        assertEquals(2, model.requests().size());
    }

    private static Path imageProvider(Path directory, String alias, String provider, String modelId,
            String origin) throws Exception {
        String yaml = "provider: " + provider + "\n"
                + "credential:\n"
                + "  secretRef: " + alias + "\n"
                + "  apiKey: test-key\n"
                + "transport:\n"
                + "  allowedOrigins:\n"
                + "    - " + origin + "\n"
                + "  connectTimeoutMillis: 10000\n"
                + "  readTimeoutMillis: 60000\n"
                + "  maxRequestBytes: 8388608\n"
                + "  maxResponseBytes: 33554432\n"
                + "  allowHttp: false\n"
                + "capabilities:\n"
                + "  IMAGE:\n"
                + "    endpoint: " + origin + "/v1/images/generations\n"
                + "    modelId: " + modelId + "\n"
                + "    apiProfile: images-v1\n"
                + "    parameters:\n"
                + "      maxPromptChars: 1000\n"
                + "      maxOutputs: 1\n";
        Path file = directory.resolve(alias + ".yml");
        Files.write(file, yaml.getBytes(StandardCharsets.UTF_8));
        return file;
    }
    @Test
    public void blankImageOutputFails() {
        WorkflowActionTestSupport.FakeModel model = WorkflowActionTestSupport.orchestrator(
                WorkflowActionTestSupport.response("  ", 1, 0));
        Map<String, Object> attributes = attributes("prompt", "p");
        attributes.put("capability", "IMAGE");
        try {
            action(model, spec("n1", attributes)).execute(scope());
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("媒体生成结果为空"));
        }
    }

    @Test
    public void imageNodeWithDeclaredAttachmentFailsWhenReferenceIsMissing() {
        WorkflowActionTestSupport.FakeModel model = WorkflowActionTestSupport.orchestrator(
                WorkflowActionTestSupport.response("ok", 1, 1));
        Map<String, Object> attributes = attributes("prompt", "p");
        attributes.put("capability", "IMAGE");
        attributes.put("attachments", Collections.singletonList("${reference}"));
        try {
            action(model, spec("edit-image", attributes))
                    .execute(new ExecutionScope(Collections.singletonMap("reference", "")));
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("缺少参考图"));
        }
    }

    @Test
    public void videoRequiresHumanApproval() {
        WorkflowActionTestSupport.FakeModel model = WorkflowActionTestSupport.orchestrator(
                WorkflowActionTestSupport.response("whatever", 1, 1));
        Map<String, Object> attributes = attributes("prompt", "p");
        attributes.put("capability", "VIDEO");
        try {
            action(model, spec("plan-video", attributes)).execute(scope());
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("人工确认"));
            assertTrue(expected.getMessage().contains("plan-video"));
        }
    }

    @Test
    public void inlinePromptSubstitutesVariablesAndKeepsAttachments() {
        WorkflowActionTestSupport.FakeModel model = WorkflowActionTestSupport.orchestrator(
                WorkflowActionTestSupport.response("ok", 1, 1));
        Map<String, Object> attributes = attributes("promptText", "请描述 ${topic}");
        attributes.put("attachments", Collections.singletonList("${image}"));
        attributes.put("role", "alpha");
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("topic", "${topic}");
        attributes.put("promptVars", vars);
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("topic", "cat");
        inputs.put("image", "https://cdn/cat.png");
        inputs.put("instanceId", "inst-1");

        action(model, spec("n1", attributes)).execute(new ExecutionScope(inputs));

        HttpRequest sent = (HttpRequest) model.requests().get(0);
        assertEquals("请描述 cat", WorkflowActionTestSupport.userInput(sent));
        Object last = sent.getMessages().get(sent.getMessages().size() - 1);
        assertTrue(last instanceof com.clara.kernel.message.delta.UserVisionContent);
        assertEquals(Collections.singletonList("https://cdn/cat.png"),
                ((com.clara.kernel.message.delta.UserVisionContent) last).getImages());
    }

    @Test
    public void unresolvedVariableIsAppendedAsSection() {
        WorkflowActionTestSupport.FakeModel model = WorkflowActionTestSupport.orchestrator(
                WorkflowActionTestSupport.response("ok", 1, 1));
        Map<String, Object> attributes = attributes("prompt", "p");
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("topic", "fixed");
        attributes.put("promptVars", vars);

        action(model, spec("n1", attributes)).execute(scope());

        assertEquals("PROMPT(p)\n## topic\nfixed",
                WorkflowActionTestSupport.userInput((HttpRequest) model.requests().get(0)));
    }

    @Test
    public void constructorRejectsInvalidDependencies() {
        AgentActionContext context = context(
                WorkflowActionTestSupport.orchestrator(WorkflowActionTestSupport.response("ok", 1, 1)));
        AgentNodeOptions options = AgentNodeOptions.fromSpec(spec("n1", attributes("prompt", "p")));
        assertIllegalArgument(() -> new AgentWorkflowAction(null, "n1", "p", null, options));
        assertIllegalArgument(() -> new AgentWorkflowAction(context, "n1", "p", null, null));
        assertIllegalArgument(() -> new AgentWorkflowAction(context, " ", "p", null, options));
        assertIllegalArgument(() -> new AgentWorkflowAction(context, "n1", null, "  ", null, options));
    }

    @Test
    public void executeRejectsNullScope() {
        WorkflowActionTestSupport.FakeModel model = WorkflowActionTestSupport.orchestrator(
                WorkflowActionTestSupport.response("ok", 1, 1));
        assertIllegalArgument(() -> action(model, spec("n1", attributes("prompt", "p"))).execute(null));
    }

    private AgentWorkflowAction action(WorkflowActionTestSupport.FakeModel model, WorkflowActionSpec spec) {
        Object promptText = spec.getAttribute("promptText");
        String inline = promptText == null ? null : String.valueOf(promptText);
        String promptId = inline == null ? "p" : null;
        return new AgentWorkflowAction(context(model), spec.getNodeId(), promptId, inline,
                vars(spec), AgentNodeOptions.fromSpec(spec));
    }

    private AgentActionContext context(WorkflowActionTestSupport.FakeModel model) {
        IPromptProvider prompts = WorkflowActionTestSupport.prompts("PROMPT");
        return new AgentActionContext(model.orchestrator(), prompts, registry, new VideoDownloader(), ledger);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> vars(WorkflowActionSpec spec) {
        Object value = spec.getAttribute("promptVars");
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<String, Object>();
    }

    private static com.clara.kernel.message.delta.ImageItem image(String url) {
        com.clara.kernel.message.delta.ImageItem image = new com.clara.kernel.message.delta.ImageItem();
        image.setUrl(url);
        return image;
    }

    private static ExecutionScope scope() {
        return new ExecutionScope(new HashMap<String, Object>());
    }

    private static WorkflowActionSpec spec(String nodeId, Map<String, Object> attributes) {
        return new WorkflowActionSpec(nodeId, "AGENT", attributes);
    }

    private static Map<String, Object> attributes(String key, String value) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(key, value);
        return attributes;
    }


    private static void assertIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}