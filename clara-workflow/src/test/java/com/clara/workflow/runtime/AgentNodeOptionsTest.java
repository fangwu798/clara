package com.clara.workflow.runtime;

import com.clara.dto.workflow.WorkflowActionSpec;
import com.clara.enums.CapabilityKindEnum;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link AgentNodeOptions} 的单元测试：节点级 role / model / capability 属性的解析与校验。
 */
public class AgentNodeOptionsTest {

    @Test
    public void defaultsToChatWhenAttributesAbsent() {
        AgentNodeOptions options = AgentNodeOptions.fromSpec(spec(attributes()));

        assertNull(options.role());
        assertNull(options.modelAlias());
        assertEquals(CapabilityKindEnum.CHAT, options.capability());
    }

    @Test
    public void parsesRoleModelAndCapability() {
        Map<String, Object> attributes = attributes();
        attributes.put("role", "architect");
        attributes.put("model", "deepseek-response");
        attributes.put("capability", "image");

        AgentNodeOptions options = AgentNodeOptions.fromSpec(spec(attributes));

        assertEquals("architect", options.role());
        assertEquals("deepseek-response", options.modelAlias());
        assertEquals(CapabilityKindEnum.IMAGE, options.capability());
    }

    @Test
    public void treatsBlankAttributesAsAbsent() {
        Map<String, Object> attributes = attributes();
        attributes.put("role", "   ");
        attributes.put("model", "");

        AgentNodeOptions options = AgentNodeOptions.fromSpec(spec(attributes));

        assertNull(options.role());
        assertNull(options.modelAlias());
    }

    @Test
    public void rejectsUnknownCapability() {
        Map<String, Object> attributes = attributes();
        attributes.put("capability", "vision");

        WorkflowActionSpec nodeSpec = spec(attributes);
        assertThrows(IllegalArgumentException.class, () -> AgentNodeOptions.fromSpec(nodeSpec));
    }

    @Test
    public void rejectsNonEnumCapability() {
        Map<String, Object> attributes = attributes();
        attributes.put("capability", 3);

        WorkflowActionSpec nodeSpec = spec(attributes);
        assertThrows(IllegalArgumentException.class, () -> AgentNodeOptions.fromSpec(nodeSpec));
    }

    @Test
    public void parsesAttachments() {
        Map<String, Object> attributes = attributes();
        attributes.put("attachments", Arrays.asList("${image}", "https://example.com/a.png"));

        AgentNodeOptions options = AgentNodeOptions.fromSpec(spec(attributes));

        assertEquals(Arrays.asList("${image}", "https://example.com/a.png"), options.attachments());
    }

    @Test
    public void defaultsAttachmentsToEmpty() {
        assertTrue(AgentNodeOptions.fromSpec(spec(attributes())).attachments().isEmpty());
    }

    @Test
    public void rejectsWrongTypedAttachments() {
        Map<String, Object> attributes = attributes();
        attributes.put("attachments", "https://example.com/a.png");

        WorkflowActionSpec nodeSpec = spec(attributes);
        assertThrows(IllegalArgumentException.class, () -> AgentNodeOptions.fromSpec(nodeSpec));
    }

    private static WorkflowActionSpec spec(Map<String, Object> attributes) {
        return new WorkflowActionSpec("node-a", "AGENT", attributes);
    }

    private static Map<String, Object> attributes() {
        return new LinkedHashMap<>();
    }
}
