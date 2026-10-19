package com.clara.mcp.registry.capability;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link McpToolDescriptor} 的 Schema 哈希稳定性、不可变性与构造校验测试。
 */
public class McpToolDescriptorTest {

    @Test
    public void schemaHashStableAcrossKeyOrder() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("type", "object");
        Map<String, Object> firstProps = new LinkedHashMap<>();
        firstProps.put("b", "2");
        firstProps.put("a", "1");
        first.put("properties", firstProps);

        Map<String, Object> secondProps = new LinkedHashMap<>();
        secondProps.put("a", "1");
        secondProps.put("b", "2");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("properties", secondProps);
        second.put("type", "object");

        assertEquals(new McpToolDescriptor("echo", "d1", first).getSchemaHash(),
                new McpToolDescriptor("echo", "d2", second).getSchemaHash());
    }

    @Test
    public void schemaHashIndependentOfDescription() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        assertEquals(new McpToolDescriptor("echo", "first", schema).getSchemaHash(),
                new McpToolDescriptor("echo", "second", schema).getSchemaHash());
    }

    @Test
    public void schemaChangeChangesHash() {
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("type", "object");
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("type", "array");

        assertNotEquals(new McpToolDescriptor("echo", "d", first).getSchemaHash(),
                new McpToolDescriptor("echo", "d", second).getSchemaHash());
    }

    @Test
    public void inputSchemaIsReadOnlyDefensiveCopy() {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("type", "object");
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("city", "string");
        source.put("properties", nested);

        McpToolDescriptor descriptor = new McpToolDescriptor("echo", "d", source);
        source.put("type", "changed");
        assertEquals("object", descriptor.getInputSchema().get("type"));

        try {
            descriptor.getInputSchema().put("x", "y");
            fail("schema must be read-only");
        } catch (UnsupportedOperationException expected) {
            assertTrue(true);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> nestedView = (Map<String, Object>) descriptor.getInputSchema().get("properties");
        try {
            nestedView.put("x", "y");
            fail("nested schema must be read-only");
        } catch (UnsupportedOperationException expected) {
            assertTrue(true);
        }
    }

    @Test
    public void nullSchemaBecomesEmptyReadOnlyMap() {
        McpToolDescriptor descriptor = new McpToolDescriptor("echo", "d", null);

        assertTrue(descriptor.getInputSchema().isEmpty());
        assertFalse(descriptor.getSchemaHash().isEmpty());
    }

    @Test
    public void blankNameRejected() {
        try {
            new McpToolDescriptor("  ", "d", null);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
    }
}
