package com.clara.orchestration.intent;

import com.clara.dto.planner.ExecutionPlan;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * {@link ExecutionPlanValidator} 单元测试：合法计划、标识唯一、依赖存在且无环、步骤上限。
 */
public class ExecutionPlanValidatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ExecutionPlanValidator validator = new ExecutionPlanValidator(3);

    @Test
    public void acceptsValidPlanAndKeepsDependencies() throws Exception {
        ExecutionPlan plan = validator.validate(node("{\"goal\":\"实现登录\",\"steps\":["
                + "{\"id\":\"s1\",\"title\":\"读取现有登录代码\",\"dependencies\":[]},"
                + "{\"id\":\"s2\",\"title\":\"实现登录接口\",\"dependencies\":[\"s1\"],"
                + "\"expectedOutput\":\"接口可用\"}]}"));

        assertEquals("实现登录", plan.getGoal());
        assertEquals(2, plan.getSteps().size());
        assertEquals("s1", plan.getSteps().get(1).getDependencies().get(0));
        assertTrue(plan.toPrompt().contains("目标：实现登录"));
    }

    @Test
    public void acceptsNestedPlanObject() throws Exception {
        JsonNode root = node("{\"mode\":\"complex\",\"plan\":{\"goal\":\"修缺陷\","
                + "\"steps\":[{\"id\":\"s1\",\"title\":\"定位问题\"}]}}");

        ExecutionPlan plan = validator.validate(root);

        assertEquals("修缺陷", plan.getGoal());
        assertEquals(1, plan.getSteps().size());
    }

    @Test
    public void rejectsDuplicateStepIds() throws Exception {
        assertRejected("{\"goal\":\"g\",\"steps\":["
                + "{\"id\":\"s1\",\"title\":\"a\"},{\"id\":\"s1\",\"title\":\"b\"}]}", "重复");
    }

    @Test
    public void rejectsUnknownDependency() throws Exception {
        assertRejected("{\"goal\":\"g\",\"steps\":["
                + "{\"id\":\"s1\",\"title\":\"a\",\"dependencies\":[\"s9\"]}]}", "依赖不存在");
    }

    @Test
    public void rejectsSelfDependency() throws Exception {
        assertRejected("{\"goal\":\"g\",\"steps\":["
                + "{\"id\":\"s1\",\"title\":\"a\",\"dependencies\":[\"s1\"]}]}", "不能依赖自身");
    }

    @Test
    public void rejectsCycle() throws Exception {
        assertRejected("{\"goal\":\"g\",\"steps\":["
                + "{\"id\":\"s1\",\"title\":\"a\",\"dependencies\":[\"s2\"]},"
                + "{\"id\":\"s2\",\"title\":\"b\",\"dependencies\":[\"s1\"]}]}", "循环依赖");
    }

    @Test
    public void rejectsTooManySteps() throws Exception {
        assertRejected("{\"goal\":\"g\",\"steps\":["
                + "{\"id\":\"s1\",\"title\":\"a\"},{\"id\":\"s2\",\"title\":\"b\"},"
                + "{\"id\":\"s3\",\"title\":\"c\"},{\"id\":\"s4\",\"title\":\"d\"}]}", "超过上限");
    }

    @Test
    public void rejectsBlankGoal() throws Exception {
        assertRejected("{\"goal\":\" \",\"steps\":[{\"id\":\"s1\",\"title\":\"a\"}]}", "goal");
    }

    private void assertRejected(String json, String expectedMessage) throws Exception {
        try {
            validator.validate(node(json));
            throw new AssertionError("expected validation failure");
        } catch (IllegalArgumentException failure) {
            assertTrue(failure.getMessage(), failure.getMessage().contains(expectedMessage));
        }
    }

    private static JsonNode node(String json) throws Exception {
        return JSON.readTree(json);
    }
}