package com.clara.memory.persistence;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link ConversationKey} 的值语义：会话 + 供应商 + 模型三段共同定位一份历史。
 *
 * <p>历史按模型隔离，键一旦比较错误会让不同模型读到同一份历史，因此这里固定拼接格式与相等规则。
 */
public class ConversationKeyTest {

    @Test
    public void exposesComponentsAndNormalizesNulls() {
        ConversationKey key = new ConversationKey("conv-1", null, null);

        assertEquals("conv-1", key.conversationId());
        assertEquals("", key.provider());
        assertEquals("", key.model());
        assertFalse(key.value().isEmpty());
    }

    @Test
    public void valueJoinsComponentsWithNulSeparator() {
        ConversationKey key = new ConversationKey("conv-1", "DEEPSEEK", "deepseek-v4-pro");

        assertEquals("conv-1\u0000DEEPSEEK\u0000deepseek-v4-pro", key.value());
        assertEquals(key.value(), key.toString());
    }

    @Test
    public void requiresNonBlankConversationId() {
        assertThrows(IllegalArgumentException.class, () -> new ConversationKey(null, "p", "m"));
        assertThrows(IllegalArgumentException.class, () -> new ConversationKey("  ", "p", "m"));
    }

    @Test
    public void comparesAllComponents() {
        ConversationKey key = new ConversationKey("conv-1", "DEEPSEEK", "m1");
        ConversationKey same = new ConversationKey("conv-1", "DEEPSEEK", "m1");
        ConversationKey otherProvider = new ConversationKey("conv-1", "OPENAI", "m1");
        ConversationKey otherModel = new ConversationKey("conv-1", "DEEPSEEK", "m2");
        ConversationKey otherConversation = new ConversationKey("conv-2", "DEEPSEEK", "m1");

        assertEquals(key, key);
        assertEquals(key, same);
        assertEquals(key.hashCode(), same.hashCode());
        assertFalse(key.equals("other"));
        assertNotEquals(key, otherProvider);
        assertNotEquals(key, otherModel);
        assertNotEquals(key, otherConversation);
        assertTrue(new ConversationKey("conv-1", null, null)
                .equals(new ConversationKey("conv-1", "", "")));
    }
}