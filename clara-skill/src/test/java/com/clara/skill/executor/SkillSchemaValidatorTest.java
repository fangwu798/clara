package com.clara.skill.executor;

import com.clara.dto.skill.ToolMethod;
import com.clara.enums.skill.ScriptTypeEnum;
import com.clara.exception.ClaraException;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link SkillSchemaValidator} 的单元测试：入参 Schema 校验通过与拒绝、输出 JSON 校验。
 */
public class SkillSchemaValidatorTest {

    private final SkillSchemaValidator validator = new SkillSchemaValidator();

    private static ToolMethod methodWithSchema(String schemaJson) {
        return new ToolMethod("t1", "calc", ScriptTypeEnum.SHELL, "计算工具", schemaJson, "echo ok", null);
    }

    @Test
    public void validateInputAcceptsMatchingArguments() {
        String schema = "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}";
        validator.validateInput(methodWithSchema(schema), "{\"text\":\"hello\"}");
    }

    @Test(expected = ClaraException.class)
    public void validateInputRejectsMissingRequiredField() {
        String schema = "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}";
        validator.validateInput(methodWithSchema(schema), "{}");
    }

    @Test(expected = ClaraException.class)
    public void validateInputRejectsWrongType() {
        String schema = "{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}";
        validator.validateInput(methodWithSchema(schema), "{\"text\":123}");
    }

    @Test
    public void isValidReturnsTrueAndFalse() {
        String schema = "{\"type\":\"object\",\"properties\":{\"n\":{\"type\":\"integer\"}},\"required\":[\"n\"]}";
        assertTrue(validator.isValid(schema, "{\"n\":1}"));
        assertFalse(validator.isValid(schema, "{\"n\":\"x\"}"));
        assertFalse(validator.isValid(schema, "{}"));
    }

    @Test
    public void validateOutputAcceptsValidJson() {
        validator.validateOutput(methodWithSchema(null), "{\"ok\":true}");
    }

    @Test(expected = ClaraException.class)
    public void validateOutputRejectsInvalidJson() {
        validator.validateOutput(methodWithSchema(null), "not-json");
    }
}
