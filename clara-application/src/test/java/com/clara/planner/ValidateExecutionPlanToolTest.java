package com.clara.planner;

import com.clara.orchestration.intent.ExecutionPlanValidator;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * {@link ValidateExecutionPlanTool} 单元测试：合法计划返回 ok，非法计划返回可修正的错误。
 */
public class ValidateExecutionPlanToolTest {

    private final ValidateExecutionPlanTool tool =
            new ValidateExecutionPlanTool(new ExecutionPlanValidator(3));

    @Test
    public void returnsOkForValidPlan() {
        String output = tool.execute("{\"plan\":{\"goal\":\"实现登录\",\"steps\":["
                + "{\"id\":\"s1\",\"title\":\"读取代码\"}]}}");

        assertTrue(output, output.contains("\"ok\":true"));
        assertTrue(output, output.contains("\"stepCount\":1"));
    }

    @Test
    public void returnsErrorForInvalidPlan() {
        String output = tool.execute("{\"plan\":{\"goal\":\"实现登录\",\"steps\":["
                + "{\"id\":\"s1\",\"title\":\"a\"},{\"id\":\"s1\",\"title\":\"b\"}]}}");

        assertTrue(output, output.contains("\"ok\":false"));
        assertTrue(output, output.contains("重复"));
    }

    @Test
    public void returnsErrorWhenPlanMissing() {
        String output = tool.execute("{}");

        assertTrue(output, output.contains("\"ok\":false"));
    }
}