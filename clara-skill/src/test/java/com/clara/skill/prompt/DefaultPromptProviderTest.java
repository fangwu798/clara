package com.clara.skill.prompt;

import com.clara.skill.registry.SkillRegistry;
import org.junit.Test;

import java.io.File;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link DefaultPromptProvider} 的单元测试：内置总结提示词回退、从内置资源加载与按标识取回。
 */
public class DefaultPromptProviderTest {

    @Test
    public void summarizePromptFallsBackToBuiltInDefault() {
        DefaultPromptProvider provider = new DefaultPromptProvider(null);
        String prompt = provider.summarizePrompt();
        assertNotNull(prompt);
        assertFalse(prompt.trim().isEmpty());
    }

    @Test
    public void summarizePromptLoadedFromBuiltinResource() {
        File root = SkillRegistry.resolveBuiltinRoot();
        assertNotNull(root);
        SkillRegistry registry = new SkillRegistry(root, null, null, null, null);
        registry.reload();
        DefaultPromptProvider provider = new DefaultPromptProvider(registry);
        String prompt = provider.summarizePrompt();
        assertNotNull(prompt);
        assertTrue(prompt.contains("结构化"));
    }

    @Test
    public void promptByIdReturnsText() {
        File root = SkillRegistry.resolveBuiltinRoot();
        assertNotNull(root);
        SkillRegistry registry = new SkillRegistry(root, null, null, null, null);
        registry.reload();
        DefaultPromptProvider provider = new DefaultPromptProvider(registry);
        String prompt = provider.prompt("summarize");
        assertNotNull(prompt);
        assertFalse(prompt.trim().isEmpty());
    }
}
