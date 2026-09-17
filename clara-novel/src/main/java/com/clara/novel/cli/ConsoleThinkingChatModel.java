package com.clara.novel.cli;

import com.clara.model.api.ChatModel;
import com.clara.model.api.ChatRequest;
import com.clara.model.api.ChatResponse;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicBoolean;

/** Adds a compact terminal spinner around synchronous chat-model calls. */
public final class ConsoleThinkingChatModel implements ChatModel {
    private static final char[] FRAMES = new char[] { '|', '/', '-', '\\' };
    private final ChatModel delegate;
    private final String modelName;
    private final PrintStream console;
    private final boolean preserveInputLine;

    public ConsoleThinkingChatModel(ChatModel delegate, String modelName, PrintStream console) {
        this(delegate, modelName, console, false);
    }

    /**
     * In interactive mode the terminal input line must not be overwritten by a carriage-return
     * spinner. A stable prompt is printed instead while the background reader accepts supplements.
     */
    public ConsoleThinkingChatModel(ChatModel delegate, String modelName, PrintStream console,
            boolean preserveInputLine) {
        if (delegate == null || blank(modelName) || console == null) {
            throw new IllegalArgumentException("thinking model dependencies are invalid");
        }
        this.delegate = delegate;
        this.modelName = modelName;
        this.console = console;
        this.preserveInputLine = preserveInputLine;
    }

    public ChatResponse chat(ChatRequest request) {
        if (preserveInputLine) return chatWithStableInput(request);
        final AtomicBoolean running = new AtomicBoolean(true);
        final long started = System.currentTimeMillis();
        Thread animation = new Thread(new Runnable() {
            public void run() {
                int frame = 0;
                while (running.get()) {
                    synchronized (console) {
                        console.print("\r" + InteractiveNovelPlanner.ConsoleColors.YELLOW
                                + FRAMES[frame++ % FRAMES.length] + " " + modelName
                                + " 模型思考中..." + InteractiveNovelPlanner.ConsoleColors.RESET);
                        console.flush();
                    }
                    try {
                        Thread.sleep(120L);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }, "clara-model-thinking");
        animation.setDaemon(true);
        animation.start();
        boolean succeeded = false;
        try {
            ChatResponse response = delegate.chat(request);
            succeeded = true;
            return response;
        } finally {
            running.set(false);
            animation.interrupt();
            try { animation.join(500L); }
            catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
            long elapsed = Math.max(0L, System.currentTimeMillis() - started);
            synchronized (console) {
                console.print("\r" + repeat(' ', modelName.length() + 24) + "\r");
                console.println(modelName + (succeeded ? " 思考完成" : " 调用失败")
                        + "，耗时 " + formatElapsed(elapsed));
                console.flush();
            }
        }
    }

    private ChatResponse chatWithStableInput(ChatRequest request) {
        final long started = System.currentTimeMillis();
        synchronized (console) {
            console.println(InteractiveNovelPlanner.ConsoleColors.YELLOW + modelName
                    + " 模型思考中...（可直接输入补充，回车提交）"
                    + InteractiveNovelPlanner.ConsoleColors.RESET);
            console.print(InteractiveNovelPlanner.ConsoleColors.CYAN + "作者补充 > "
                    + InteractiveNovelPlanner.ConsoleColors.RESET);
            console.flush();
        }
        boolean succeeded = false;
        try {
            ChatResponse response = delegate.chat(request);
            succeeded = true;
            return response;
        } finally {
            long elapsed = Math.max(0L, System.currentTimeMillis() - started);
            synchronized (console) {
                console.println();
                console.println(modelName + (succeeded ? " 思考完成" : " 调用失败")
                        + "，耗时 " + formatElapsed(elapsed));
                console.flush();
            }
        }
    }

    private static String formatElapsed(long millis) {
        return String.format(java.util.Locale.ROOT, "%.1f 秒", Double.valueOf(millis / 1000.0d));
    }
    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }
}
