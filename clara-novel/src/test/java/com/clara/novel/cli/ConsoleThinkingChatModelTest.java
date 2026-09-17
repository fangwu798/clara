package com.clara.novel.cli;

import com.clara.kernel.CancellationToken;
import com.clara.kernel.InvocationContext;
import com.clara.model.api.ChatMessage;
import com.clara.model.api.ChatModel;
import com.clara.model.api.ChatRequest;
import com.clara.model.api.ChatResponse;
import com.clara.model.api.Content;
import com.clara.model.api.FinishReason;
import com.clara.model.api.Role;
import com.clara.model.api.TextContent;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class ConsoleThinkingChatModelTest {
    @Test public void showsModelNameAndCompletionStatus() throws Exception {
        ChatModel delegate = new ChatModel() {
            public ChatResponse chat(ChatRequest request) {
                try { Thread.sleep(150L); }
                catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                return response("完成");
            }
        };
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        ConsoleThinkingChatModel model = new ConsoleThinkingChatModel(delegate,
                "deepseek-chat", new PrintStream(captured, true, "UTF-8"));

        assertEquals("完成", ((TextContent) model.chat(request()).getMessage()
                .getContents().get(0)).getText());

        String output = captured.toString("UTF-8");
        assertTrue(output.contains("deepseek-chat 模型思考中"));
        assertTrue(output.contains("deepseek-chat 思考完成"));
        assertTrue(output.contains("耗时"));
    }

    private static ChatRequest request() {
        InvocationContext context = new InvocationContext("local", "test", "run", "trace",
                System.currentTimeMillis() + 10000L, new CancellationToken());
        return new ChatRequest(Collections.singletonList(new ChatMessage(Role.USER,
                Collections.<Content>singletonList(new TextContent("测试")), null, null)),
                null, Collections.emptyList(), null, context);
    }
    private static ChatResponse response(String text) {
        return new ChatResponse(new ChatMessage(Role.ASSISTANT,
                Collections.<Content>singletonList(new TextContent(text)), null, null),
                null, FinishReason.STOP, null);
    }
}
