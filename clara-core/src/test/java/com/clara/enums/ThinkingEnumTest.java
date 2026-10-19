package com.clara.enums;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ThinkingEnumTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    public void serializesWireValue() throws Exception {
        assertEquals("\"enabled\"", mapper.writeValueAsString(ThinkingEnum.ENABLED));
        assertEquals("\"disabled\"", mapper.writeValueAsString(ThinkingEnum.DISABLED));
        assertEquals("\"auto\"", mapper.writeValueAsString(ThinkingEnum.AUTO));
    }

    @Test
    public void deserializesWireValue() throws Exception {
        assertEquals(ThinkingEnum.ENABLED, mapper.readValue("\"enabled\"", ThinkingEnum.class));
        assertEquals(ThinkingEnum.DISABLED, mapper.readValue("\"disabled\"", ThinkingEnum.class));
        assertEquals(ThinkingEnum.AUTO, mapper.readValue("\"auto\"", ThinkingEnum.class));
    }
}
