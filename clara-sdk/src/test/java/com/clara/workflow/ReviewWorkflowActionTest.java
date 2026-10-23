package com.clara.workflow;

import com.clara.config.ModelRegistry;
import com.clara.core.skill.IPromptProvider;
import com.clara.dto.workflow.ExecutionScope;
import com.clara.dto.workflow.WorkflowActionSpec;
import com.clara.kernel.http.HttpRequest;
import com.clara.workflow.runtime.ReviewNodeOptions;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link ReviewWorkflowAction} 的执行测试：多轮多角色发言 + 主持人汇总、变量注入、
 * 空回复与失败状态的传播。
 */
public class ReviewWorkflowActionTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private ModelRegistry registry;

    @Before
    public void setUp() throws Exception {
        Path config = WorkflowActionTestSupport.providerConfig(folder.getRoot().toPath(), "alpha");
        registry = new ModelRegistry(Collections.singletonList(config), Collections.<String, String>emptyMap());
        registry.select(config);
    }

    @After
    public void tearDown() {
        if (registry != null) {
            registry.close();
        }
    }

    @Test
    public void discussesRolesAndModeratorThenReturnsConclusion() {
        WorkflowActionTestSupport.FakeModel model = WorkflowActionTestSupport.orchestrator(
                WorkflowActionTestSupport.response("发言内容", 5, 3));
        Map<String, Object> inputs = new HashMap<>();
        inputs.put("topic", "选型");
        inputs.put("instanceId", "i1");

        Map<String, Object> output = action(model, spec(Arrays.asList("alpha"), "alpha", 2, "${topic}"))
                .execute(new ExecutionScope(inputs));

        assertEquals("发言内容", output.get("output"));
        // 2 轮 × 1 角色 + 主持人 1 次
        assertEquals(3, model.requests().size());
        HttpRequest first = (HttpRequest) model.requests().get(0);
        HttpRequest last = (HttpRequest) model.requests().get(2);
        assertTrue(WorkflowActionTestSupport.userInput(first).contains("## topic\n选型"));
        assertTrue(WorkflowActionTestSupport.userInput(first).contains("## role\nalpha"));
        assertTrue(WorkflowActionTestSupport.userInput(first).contains("## discussion\n（暂无）"));
        assertTrue(WorkflowActionTestSupport.userInput(first).contains("## isFinal\nfalse"));
        assertTrue(WorkflowActionTestSupport.userInput(last).contains("## isFinal\ntrue"));
        assertTrue(WorkflowActionTestSupport.userInput(last).contains("### alpha（第 1 轮）"));
    }

    @Test
    public void missingTopicFallsBackToEmptyString() {
        WorkflowActionTestSupport.FakeModel model = WorkflowActionTestSupport.orchestrator(
                WorkflowActionTestSupport.response("ok", 1, 1));

        action(model, spec(Collections.singletonList("alpha"), "alpha", 1, null)).execute(new ExecutionScope(new HashMap<String, Object>()));

        assertTrue(WorkflowActionTestSupport.userInput((HttpRequest) model.requests().get(0)).contains("## topic\n"));
        assertFalse(WorkflowActionTestSupport.userInput((HttpRequest) model.requests().get(0)).contains("${"));
    }

    @Test
    public void blankStatementFailsWithRoleAndRound() {
        WorkflowActionTestSupport.FakeModel model = WorkflowActionTestSupport.orchestrator(
                WorkflowActionTestSupport.response("  ", 1, 1));
        try {
            action(model, spec(Collections.singletonList("alpha"), "alpha", 1, null)).execute(new ExecutionScope(new HashMap<String, Object>()));
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("角色 alpha"));
            assertTrue(expected.getMessage().contains("第 1 轮"));
        }
    }

    @Test
    public void failedStatementPropagates() {
        WorkflowActionTestSupport.FakeModel model = WorkflowActionTestSupport.orchestrator(null);
        try {
            action(model, spec(Collections.singletonList("alpha"), "alpha", 1, null)).execute(new ExecutionScope(new HashMap<String, Object>()));
            fail("expected IllegalStateException");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("未成功"));
        }
    }

    @Test
    public void constructorAndScopeValidation() {
        WorkflowActionTestSupport.FakeModel model = WorkflowActionTestSupport.orchestrator(
                WorkflowActionTestSupport.response("ok", 1, 1));
        IPromptProvider prompts = WorkflowActionTestSupport.prompts("P");
        ReviewNodeOptions options = ReviewNodeOptions.fromSpec(
                spec(Collections.singletonList("alpha"), "alpha", 1, null));

        assertIllegalArgument(() -> new ReviewWorkflowAction(null, prompts, registry, "n1", options));
        assertIllegalArgument(() -> new ReviewWorkflowAction(model.orchestrator(), null, registry, "n1", options));
        assertIllegalArgument(() -> new ReviewWorkflowAction(model.orchestrator(), prompts, null, "n1", options));
        assertIllegalArgument(() -> new ReviewWorkflowAction(model.orchestrator(), prompts, registry, " ", options));
        assertIllegalArgument(() -> new ReviewWorkflowAction(model.orchestrator(), prompts, registry, "n1", null));
        assertIllegalArgument(() -> new ReviewWorkflowAction(model.orchestrator(), prompts, registry, "n1", options)
                .execute(null));
    }

    private ReviewWorkflowAction action(WorkflowActionTestSupport.FakeModel model, WorkflowActionSpec spec) {
        IPromptProvider prompts = WorkflowActionTestSupport.prompts("讨论提示词");
        return new ReviewWorkflowAction(model.orchestrator(), prompts, registry, spec.getNodeId(),
                ReviewNodeOptions.fromSpec(spec));
    }

    private static WorkflowActionSpec spec(java.util.List<String> roles, String moderator, int rounds, String topic) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("prompt", "review");
        attributes.put("roles", roles);
        attributes.put("moderator", moderator);
        attributes.put("rounds", rounds);
        if (topic != null) {
            attributes.put("topic", topic);
        }
        return new WorkflowActionSpec("n1", "REVIEW", attributes);
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