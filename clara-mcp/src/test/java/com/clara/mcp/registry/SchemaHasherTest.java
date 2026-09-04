package com.clara.mcp.registry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import com.clara.mcp.client.JsonRpcRequest;

import static org.junit.Assert.assertEquals;

public class SchemaHasherTest {
    @Test
    public void mapOrderDoesNotChangeHash() {
        Map<String, Object> first = new LinkedHashMap<String, Object>();
        first.put("type", "object"); first.put("required", Arrays.asList("query"));
        Map<String, Object> second = new LinkedHashMap<String, Object>();
        second.put("required", Arrays.asList("query")); second.put("type", "object");
        assertEquals(new SchemaHasher().hash(first), new SchemaHasher().hash(second));
    }

    @Test(expected = UnsupportedOperationException.class)
    @SuppressWarnings("unchecked")
    public void descriptorDeepCopiesNestedSchema() {
        List<Object> required = new ArrayList<Object>(); required.add("query");
        Map<String, Object> schema = new LinkedHashMap<String, Object>(); schema.put("required", required);
        McpToolDescriptor descriptor = new McpToolDescriptor("search", null, schema);
        required.add("limit");
        ((List<Object>) descriptor.getInputSchema().get("required")).add("other");
    }

    @Test(expected = UnsupportedOperationException.class)
    @SuppressWarnings("unchecked")
    public void jsonRpcRequestDeepCopiesParameters() {
        List<Object> values = new ArrayList<Object>(); values.add("one");
        Map<String, Object> params = new LinkedHashMap<String, Object>(); params.put("values", values);
        JsonRpcRequest request = new JsonRpcRequest("1", "tools/list", params);
        values.add("two");
        ((List<Object>) request.getParams().get("values")).add("three");
    }
}
