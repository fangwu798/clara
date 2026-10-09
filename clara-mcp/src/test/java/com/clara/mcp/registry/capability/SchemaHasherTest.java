package com.clara.mcp.registry.capability;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * {@link SchemaHasher} 的稳定性与规范化测试：结构相同则哈希相同，结构不同则哈希不同。
 */
public class SchemaHasherTest {
    private final SchemaHasher hasher = new SchemaHasher();

    @Test
    public void sameSchemaDifferentKeyOrderProducesSameHash() {
        Map<String, Object> first = new LinkedHashMap<String, Object>();
        first.put("b", 2);
        first.put("a", 1);
        Map<String, Object> second = new LinkedHashMap<String, Object>();
        second.put("a", 1);
        second.put("b", 2);

        assertEquals(hasher.hash(first), hasher.hash(second));
    }

    @Test
    public void nestedStructureIsCanonicalized() {
        Map<String, Object> firstInner = new LinkedHashMap<String, Object>();
        firstInner.put("y", "2");
        firstInner.put("x", "1");
        Map<String, Object> first = new LinkedHashMap<String, Object>();
        first.put("props", firstInner);
        first.put("required", Arrays.asList("x", "y"));

        Map<String, Object> secondInner = new LinkedHashMap<String, Object>();
        secondInner.put("x", "1");
        secondInner.put("y", "2");
        Map<String, Object> second = new LinkedHashMap<String, Object>();
        second.put("required", Arrays.asList("x", "y"));
        second.put("props", secondInner);

        assertEquals(hasher.hash(first), hasher.hash(second));
    }

    @Test
    public void differentValuesProduceDifferentHash() {
        Map<String, Object> first = new LinkedHashMap<String, Object>();
        first.put("type", "object");
        Map<String, Object> second = new LinkedHashMap<String, Object>();
        second.put("type", "array");

        assertNotEquals(hasher.hash(first), hasher.hash(second));
    }

    @Test
    public void listOrderAffectsHash() {
        assertNotEquals(hasher.hash(Arrays.<Object>asList("x", "y")),
                hasher.hash(Arrays.<Object>asList("y", "x")));
    }

    @Test
    public void scalarTypesAreDistinguished() {
        assertNotEquals(hasher.hash("1"), hasher.hash(1));
        assertNotEquals(hasher.hash(Boolean.TRUE), hasher.hash("true"));
        assertEquals(hasher.hash(null), hasher.hash(null));
    }

    @Test
    public void hashIsLowercaseHexSha256() {
        String hash = hasher.hash(new LinkedHashMap<String, Object>());

        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
    }

    @Test
    public void unsupportedValueRejected() {
        try {
            hasher.hash(new Object());
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
    }

    @Test
    public void deeplyNestedValueRejected() {
        Object value = "leaf";
        for (int depth = 0; depth < 120; depth++) {
            List<Object> wrapper = new ArrayList<Object>();
            wrapper.add(value);
            value = wrapper;
        }
        try {
            hasher.hash(value);
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            assertTrue(true);
        }
    }
}
