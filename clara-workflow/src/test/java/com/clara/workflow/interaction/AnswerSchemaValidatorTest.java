package com.clara.workflow.interaction;

import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * {@link AnswerSchemaValidator} 的单元测试：符合 / 不符合 Schema，空 Schema 视为无约束，非法 Schema 容忍。
 */
public class AnswerSchemaValidatorTest {

    private final AnswerSchemaValidator validator = new AnswerSchemaValidator();

    @Test
    public void acceptsWhenSchemaMissing() {
        assertTrue(validator.isValid(null, Collections.<String, Object>emptyMap()));
        assertTrue(validator.isValid("   ", Collections.<String, Object>emptyMap()));
    }

    @Test
    public void acceptsWhenSchemaHasNoRequired() {
        assertTrue(validator.isValid("{\"type\":\"object\"}", Collections.<String, Object>emptyMap()));
    }

    @Test
    public void enforcesRequiredFields() {
        String schema = "{\"required\":[\"name\",\"age\"]}";

        Map<String, Object> partial = new LinkedHashMap<>();
        partial.put("name", "Alice");
        assertFalse(validator.isValid(schema, partial));

        Map<String, Object> complete = new LinkedHashMap<>();
        complete.put("name", "Alice");
        complete.put("age", Integer.valueOf(30));
        assertTrue(validator.isValid(schema, complete));
    }

    @Test
    public void rejectsNullAnswerForConstrainedSchema() {
        assertFalse(validator.isValid("{\"required\":[\"name\"]}", null));
    }

    @Test
    public void toleratesMalformedSchema() {
        assertTrue(validator.isValid("not-json",
                Collections.<String, Object>singletonMap("name", "Alice")));
    }
}
