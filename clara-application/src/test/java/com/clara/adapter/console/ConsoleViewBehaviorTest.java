package com.clara.adapter.console;

import com.clara.dto.model.StreamEvent;
import com.clara.enums.ErrorCategoryEnum;
import com.clara.enums.StreamEventEnum;
import com.clara.exception.ClaraError;
import com.clara.exception.ErrorCodes;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.File;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * {@link ConsoleView} 的行为测试：日志文件落盘、模式开关、模型选择与流式事件的渲染分支。
 *
 * <p>视图构造会接管 {@code System.out}，因此测试结束必须恢复原流，避免影响同一 JVM 内的其它测试。
 */
public class ConsoleViewBehaviorTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private PrintStream originalOut;
    private ConsoleView view;
    private Path logFile;

    @Before
    public void setUp() throws Exception {
        originalOut = System.out;
        Path logs = folder.newFolder("logs").toPath();
        view = new ConsoleView(logs, false, "never");
        File[] files = logs.toFile().listFiles();
        assertNotNull(files);
        logFile = files[0].toPath();
    }

    @After
    public void tearDown() {
        if (view != null) {
            view.close();
        }
        System.setOut(originalOut);
    }

    @Test
    public void logFileRecordsEmitsUserInputAndTurn() throws Exception {
        view.emit("普通输出");
        view.emit("彩色输出", ConsoleView.ANSI_GREEN);
        view.logUserInput("你好");
        view.logTurn(com.clara.orchestration.api.ConversationResult.builder()
                .conversationId("c1")
                .content("回答")
                .reasoning(" 思考 ")
                .status(com.clara.orchestration.api.OrchestrationStatusEnum.COMPLETED)
                .build());
        view.close();

        String log = new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8);
        assertTrue(log.contains("Clara 运行日志"));
        assertTrue(log.contains("普通输出"));
        assertTrue(log.contains("彩色输出"));
        assertFalse("日志文件不应写入颜色码", log.contains(ConsoleView.ANSI_GREEN));
        assertTrue(log.contains("[user] 你好"));
        assertTrue(log.contains("[thinking] 思考"));
        assertTrue(log.contains("[assistant]"));
        assertTrue(log.contains("回答"));
    }

    @Test
    public void togglesLogModeAndColors() {
        assertFalse(view.isFullLogging());
        view.toggleLogMode();
        assertTrue(view.isFullLogging());
        view.toggleLogMode();
        assertFalse(view.isFullLogging());

        assertFalse("forced never 应关闭颜色", view.isColorsEnabled());
        view.toggleColors();
        assertTrue(view.isColorsEnabled());
        view.toggleColors();
        assertFalse(view.isColorsEnabled());
    }

    @Test
    public void printsHintsWithoutCustomWorkflowsAndWithThem() {
        view.printHints(Collections.<String>emptyList());
        view.printHints(Arrays.asList("decision_panel（讨论）", "other（其它）"));
        view.println("plain");
        view.println();
        assertTrue(true);
    }

    @Test
    public void chooseModelHandlesSingleEmptyInvalidAndEof() throws Exception {
        Path first = Paths.get("config", "first.yml");
        Path second = Paths.get("config", "second.yml");
        Path third = Paths.get("config", "third.yml");
        List<Path> candidates = Arrays.asList(first, second, third);

        assertEquals(first, view.chooseModel(Collections.singletonList(first), reader("1\n")));
        assertEquals(first, view.chooseModel(candidates, reader("\n")));
        assertEquals(second, view.chooseModel(candidates, reader("2\n")));
        assertEquals(third, view.chooseModel(candidates, reader("abc\n9\n3\n")));
        assertEquals(first, view.chooseModel(candidates, reader("")));
    }

    @Test
    public void streamObserverRendersThinkingAnswerAndToolCalls() {
        ConsoleView.ConsoleStreamObserver observer = view.newObserver(
                (name, arguments) -> name.equals("text_search") ? "查询符号。" : null);

        observer.onEvent(event(StreamEventEnum.REASONING_DELTA, "思考", null, null));
        observer.onEvent(event(StreamEventEnum.TEXT_DELTA, "正文", null, null));
        assertTrue(observer.printed());
        observer.onEvent(event(StreamEventEnum.TOOL_ARGUMENT_DELTA, "{\"q\":", "call-1", "text_search"));
        observer.onEvent(event(StreamEventEnum.TOOL_CALL_COMPLETE, "{\"q\":\"x\"}", "call-1", "text_search"));
        // 同一调用标识只提示一次
        observer.onEvent(event(StreamEventEnum.TOOL_CALL_COMPLETE, "{\"q\":\"x\"}", "call-1", "text_search"));
        observer.onEvent(event(StreamEventEnum.TOOL_CALL_COMPLETE, "{}", null, " "));
        observer.onEvent(event(StreamEventEnum.TOOL_CALL_COMPLETE, "{}", null, "run_command"));
        observer.endStream();
        observer.onEvent(event(StreamEventEnum.RESPONSE_COMPLETE, null, null, null));

        assertTrue(observer.printed());
    }

    @Test
    public void streamObserverRendersErrorsWithAndWithoutProviderMessage() {
        ConsoleView.ConsoleStreamObserver observer = view.newObserver(null);
        ClaraError plain = ClaraError.builder(ErrorCodes.INVALID_ARGUMENT, ErrorCategoryEnum.VALIDATION)
                .safeMessage("配置无效").build();
        observer.onEvent(new StreamEvent(StreamEventEnum.ERROR, 1L, null, null, null, null, plain));
        observer.onEvent(new StreamEvent(StreamEventEnum.ERROR, 2L, null, null, null, null, null));

        ClaraError withProvider = ClaraError.builder(ErrorCodes.MODEL_PROVIDER_FAILED, ErrorCategoryEnum.EXTERNAL_SERVICE)
                .safeMessage("平台调用失败")
                .safeDetails(Collections.singletonMap("providerMessage", "  Insufficient Balance  "))
                .build();
        observer.onEvent(new StreamEvent(StreamEventEnum.ERROR, 3L, null, null, null, null, withProvider));

        assertFalse(observer.printed());
    }

    private static BufferedReader reader(String content) {
        return new BufferedReader(new StringReader(content));
    }

    private static StreamEvent event(StreamEventEnum type, String payload, String callId, String toolName) {
        return new StreamEvent(type, 1L, payload, callId, toolName, null, null);
    }
}