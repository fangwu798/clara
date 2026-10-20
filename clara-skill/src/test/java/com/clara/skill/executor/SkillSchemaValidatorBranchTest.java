package com.clara.skill.executor;

import com.clara.dto.skill.ToolMethod;
import com.clara.enums.skill.ScriptTypeEnum;
import com.clara.exception.ClaraException;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Additional JSON Schema keyword coverage.
 */
public class SkillSchemaValidatorBranchTest {

    private final SkillSchemaValidator validator = new SkillSchemaValidator();

    @Test
    public void validatesEnumTypesAndAdditionalProperties() {
        assertTrue(validator.isValid("{\"enum\":[\"a\",\"b\"]}", "\"a\""));
        assertFalse(validator.isValid("{\"enum\":[\"a\",\"b\"]}", "\"c\""));

        assertTrue(validator.isValid("{\"type\":[\"string\",\"null\"]}", "\"x\""));
        assertTrue(validator.isValid("{\"type\":[\"string\",\"null\"]}", "null"));
        assertFalse(validator.isValid("{\"type\":[\"string\",\"null\"]}", "1"));
        assertFalse(validator.isValid("{\"type\":[\"string\",\"null\"]}", "{}"));

        assertTrue(validator.isValid("{\"type\":\"object\",\"properties\":{\"a\":{}},"
                + "\"additionalProperties\":false}", "{\"a\":1}"));
        assertFalse(validator.isValid("{\"type\":\"object\",\"properties\":{\"a\":{}},"
                + "\"additionalProperties\":false}", "{\"b\":1}"));
    }

    @Test
    public void validatesArraysAndScalarTypes() {
        assertTrue(validator.isValid("{\"type\":\"array\",\"items\":{\"type\":\"integer\"}}", "[1,2]"));
        assertFalse(validator.isValid("{\"type\":\"array\",\"items\":{\"type\":\"integer\"}}", "[1,\"x\"]"));
        assertTrue(validator.isValid("{\"type\":\"array\"}", "[1,\"x\"]"));
        assertTrue(validator.isValid("{\"items\":{\"type\":\"string\"}}", "[\"a\"]"));

        assertTrue(validator.isValid("{\"type\":\"boolean\"}", "true"));
        assertFalse(validator.isValid("{\"type\":\"boolean\"}", "\"true\""));
        assertTrue(validator.isValid("{\"type\":\"number\"}", "1.5"));
        assertTrue(validator.isValid("{\"type\":\"integer\"}", "2"));
        assertFalse(validator.isValid("{\"type\":\"integer\"}", "2.5"));
        assertTrue(validator.isValid("{\"type\":\"object\"}", "{}"));
        assertFalse(validator.isValid("{\"type\":\"object\"}", "[]"));
        assertTrue(validator.isValid("{\"type\":\"null\"}", "null"));
        assertFalse(validator.isValid("{\"type\":\"null\"}", "0"));
        assertTrue(validator.isValid("{\"type\":\"unknown\"}", "0"));
    }

    @Test
    public void validatesNestedProperties() {
        String schema = "{\"type\":\"object\",\"properties\":{\"user\":{\"type\":\"object\","
                + "\"properties\":{\"name\":{\"type\":\"string\"}},\"required\":[\"name\"]}}}";
        assertTrue(validator.isValid(schema, "{\"user\":{\"name\":\"a\"}}"));
        assertFalse(validator.isValid(schema, "{\"user\":{}}"));
        assertFalse(validator.isValid(schema, "{\"user\":{\"name\":1}}"));
    }

    @Test
    public void handlesInvalidJsonAndBlankValues() {
        assertFalse(validator.isValid("{\"type\":\"object\"}", "not-json"));
        assertFalse(validator.isValid("{\"type\":\"object\"}", null));

        try {
            validator.validateInput(method("{not-json"), "{}");
            fail("expected ClaraException");
        } catch (ClaraException expected) {
            assertTrue(expected.getError().getSafeMessage().contains("Schema"));
        }

        try {
            validator.validateOutput(method(null), null);
            fail("expected ClaraException");
        } catch (ClaraException expected) {
            assertTrue(expected.getError().getSafeMessage().contains("输出"));
        }
        try {
            validator.validateOutput(method(null), "  ");
            fail("expected ClaraException");
        } catch (ClaraException expected) {
            // expected
        }
    }

    @Test
    public void acceptsNullArgumentsAsEmptyObject() {
        validator.validateInput(method(null), null);
        validator.validateInput(method("{\"type\":\"object\"}"), null);
    }

    private static ToolMethod method(String schema) {
        return new ToolMethod("t", "t", ScriptTypeEnum.SHELL, "d", schema, "echo", null);
    }
}
