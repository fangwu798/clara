package com.clara.workflow.runtime;

import com.clara.dto.workflow.WorkflowActionSpec;
import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

/**
 * {@link ReviewNodeOptions} 的单元测试：REVIEW 节点属性解析、缺省值与非法输入校验。
 */
public class ReviewNodeOptionsTest {

    @Test
    public void parsesAllAttributes() {
        Map<String, Object> attributes = validAttributes();
        attributes.put("topic", "${topic}");
        attributes.put("rounds", Integer.valueOf(2));

        ReviewNodeOptions options = ReviewNodeOptions.fromSpec(spec(attributes));

        assertEquals("review_panel", options.promptId());
        assertEquals(Arrays.asList("architect", "reviewer"), options.roles());
        assertEquals("architect", options.moderator());
        assertEquals("${topic}", options.topic());
        assertEquals(2, options.rounds());
    }

    @Test
    public void defaultsTopicAndRounds() {
        ReviewNodeOptions options = ReviewNodeOptions.fromSpec(spec(validAttributes()));

        assertNull(options.topic());
        assertEquals(1, options.rounds());
    }

    @Test
    public void parsesRoundsFromText() {
        Map<String, Object> attributes = validAttributes();
        attributes.put("rounds", "3");

        assertEquals(3, ReviewNodeOptions.fromSpec(spec(attributes)).rounds());
    }

    @Test
    public void rejectsMissingRequiredAttributes() {
        Map<String, Object> withoutPrompt = validAttributes();
        withoutPrompt.remove("prompt");
        assertThrows(IllegalArgumentException.class, () -> ReviewNodeOptions.fromSpec(spec(withoutPrompt)));

        Map<String, Object> withoutModerator = validAttributes();
        withoutModerator.remove("moderator");
        assertThrows(IllegalArgumentException.class, () -> ReviewNodeOptions.fromSpec(spec(withoutModerator)));

        Map<String, Object> withoutRoles = validAttributes();
        withoutRoles.remove("roles");
        assertThrows(IllegalArgumentException.class, () -> ReviewNodeOptions.fromSpec(spec(withoutRoles)));
    }

    @Test
    public void rejectsEmptyOrWrongTypedRoles() {
        Map<String, Object> emptyRoles = validAttributes();
        emptyRoles.put("roles", Arrays.asList("  ", ""));
        assertThrows(IllegalArgumentException.class, () -> ReviewNodeOptions.fromSpec(spec(emptyRoles)));

        Map<String, Object> wrongType = validAttributes();
        wrongType.put("roles", "architect");
        assertThrows(IllegalArgumentException.class, () -> ReviewNodeOptions.fromSpec(spec(wrongType)));
    }

    @Test
    public void rejectsNonPositiveRounds() {
        Map<String, Object> zero = validAttributes();
        zero.put("rounds", Integer.valueOf(0));
        assertThrows(IllegalArgumentException.class, () -> ReviewNodeOptions.fromSpec(spec(zero)));

        Map<String, Object> notNumber = validAttributes();
        notNumber.put("rounds", "many");
        assertThrows(IllegalArgumentException.class, () -> ReviewNodeOptions.fromSpec(spec(notNumber)));
    }

    private static WorkflowActionSpec spec(Map<String, Object> attributes) {
        return new WorkflowActionSpec("panel", "REVIEW", attributes);
    }

    private static Map<String, Object> validAttributes() {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("prompt", "review_panel");
        attributes.put("roles", Arrays.asList("architect", "reviewer"));
        attributes.put("moderator", "architect");
        return attributes;
    }
}
