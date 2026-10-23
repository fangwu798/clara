package com.clara.workflow;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link PendingWorkflowRegistry} 的单元测试：暂停登记、完成清除与续接提示内容。
 */
public class PendingWorkflowRegistryTest {

    private static final String WAITING = "{\"workflowId\":\"image_generation\",\"instanceId\":\"image_generation-1\","
            + "\"status\":\"WAITING_INPUT\",\"waitingInputKey\":\"promptFeedback\","
            + "\"outputs\":{\"prompt\":\"夕阳下的海边灯塔\"},\"waitingForUser\":true}";

    @Test
    public void remembersWaitingInstanceAndBuildsResumeHint() {
        PendingWorkflowRegistry registry = new PendingWorkflowRegistry();
        registry.update("conv-1", "image_generation", WAITING);

        String hint = registry.hint("conv-1");

        assertNotNull(hint);
        assertTrue(hint.contains("image_generation-1"));
        assertTrue(hint.contains("promptFeedback"));
        assertTrue(hint.contains("\"approve\""));
        assertTrue(hint.contains("clara.jar image"));
    }

    @Test
    public void clearsPendingStateWhenWorkflowCompletes() {
        PendingWorkflowRegistry registry = new PendingWorkflowRegistry();
        registry.update("conv-1", "image_generation", WAITING);

        registry.update("conv-1", "image_generation",
                "{\"workflowId\":\"image_generation\",\"instanceId\":\"image_generation-1\",\"status\":\"SUCCEEDED\"}");

        assertNull(registry.hint("conv-1"));
    }

    @Test
    public void ignoresBlankConversation() {
        PendingWorkflowRegistry registry = new PendingWorkflowRegistry();
        registry.update("  ", "image_generation", WAITING);

        assertNull(registry.hint("  "));
        assertNull(registry.hint(null));
    }

    @Test
    public void keepsPreviousStateWhenResultIsNotJson() {
        PendingWorkflowRegistry registry = new PendingWorkflowRegistry();
        registry.update("conv-1", "image_generation", WAITING);

        registry.update("conv-1", "image_generation", "not-json");

        assertNotNull(registry.hint("conv-1"));
    }

    @Test
    public void clearRemovesHint() {
        PendingWorkflowRegistry registry = new PendingWorkflowRegistry();
        registry.update("conv-1", "image_generation", WAITING);

        registry.clear("conv-1");

        assertNull(registry.hint("conv-1"));
    }
}
