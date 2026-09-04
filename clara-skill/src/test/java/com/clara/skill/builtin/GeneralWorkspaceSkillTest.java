package com.clara.skill.builtin;

import com.clara.model.api.ModelCapability;
import com.clara.skill.api.SkillDefinition;
import com.clara.skill.api.SkillExecutionType;
import com.clara.skill.api.SkillLifecycle;
import org.junit.Test;
import static org.junit.Assert.*;

public class GeneralWorkspaceSkillTest {
    @Test public void exposesVersionedProviderNeutralDefinition() {
        SkillDefinition definition = GeneralWorkspaceSkill.definition();
        assertEquals("general-workspace", definition.getSkillId());
        assertEquals("1.0.0", definition.getVersion());
        assertEquals(SkillExecutionType.JAVA_HANDLER, definition.getExecutionType());
        assertEquals(SkillLifecycle.DRAFT, definition.getLifecycle());
        assertTrue(definition.getRequiredTools().contains("workspace.search"));
        assertTrue(definition.getRequiredTools().contains("workspace.apply"));
        assertTrue(definition.getRequiredTools().contains("model.chat"));
        assertTrue(definition.getRequiredTools().contains("mcp.call"));
        assertTrue(definition.getRequiredModelCapabilities().contains(ModelCapability.CHAT_TEXT));
        assertEquals(64, definition.getArtifact().getSha256().length());
        assertFalse(definition.getInputSchema().contains("com.clara.workspace"));
    }
}
