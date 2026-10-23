package com.clara.adapter.console;

import com.clara.ApplicationBootstrap;
import com.clara.dto.model.StreamEvent;
import com.clara.enums.ModelProviderEnum;
import com.clara.enums.StreamEventEnum;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.model.configuration.ProviderYamlConfigurationLoader;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.mcp.api.IMcpClient;
import com.clara.mcp.api.IMcpClientFactory;
import com.clara.mcp.registry.McpServerRegistration;
import com.clara.model.configuration.ProviderYamlConfigurationLoader;
import com.clara.model.embedding.HashingEmbeddingClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.PrintStream;
import java.io.StringReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Rendering edge cases of ConsoleView that the behaviour tests do not exercise:
 * Working line, tool process lines, stream segments, model selection and the byte streams.
 */
public class ConsoleViewRenderingEdgeTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private PrintStream originalOut;
    private ConsoleView view;

    @Before
    public void setUp() throws Exception {
        originalOut = System.out;
        view = new ConsoleView(folder.newFolder("logs-edge").toPath(), true, "always");
    }

    @After
    public void tearDown() {
        if (view != null) {
            view.close();
        }
        System.setOut(originalOut);
    }

    @Test
    public void rendersToolProcessLinesWithTagsAndInterventionBuffering() throws Exception {
        view.setInterruptHintAvailable(false);
        view.printToolProcessLine(null);
        view.printToolProcessLine("plain");
        view.printToolProcessLine("[out] stdout line");
        view.printToolProcessLine("[err] stderr line");

        view.beginInterventionInput("intervene");
        assertTrue(view.isInterventionInputActive());
        view.println("deferred text");
        view.println();
        view.emit("deferred emit", ConsoleView.ANSI_RED);
        view.printStream("deferred stream");
        view.beginStreamSegment("[thinking] ", ConsoleView.ANSI_GRAY);
        view.endStreamSegment();
        view.printToolProcessLine("[tool] deferred tool");
        view.endInterventionInput();
        assertFalse(view.isInterventionInputActive());
        view.endInterventionInput();
    }

    @Test
    public void drivesWorkingLineLifecycle() throws Exception {
        setField("workingRenderedWidth", 12);
        setField("workingStartedAt", System.currentTimeMillis() - 3000L);
        setField("workingLabel", "Thinking");
        setField("workingPaused", false);
        call("renderWorkingLineLocked", new Class<?>[0]);
        setField("workingRenderedWidth", 100);
        call("renderWorkingLineLocked", new Class<?>[0]);
        setField("workingPaused", true);
        call("renderWorkingLineLocked", new Class<?>[0]);
        setField("workingPaused", false);
        call("clearWorkingLineLocked", new Class<?>[0]);
        call("clearWorkingLineLocked", new Class<?>[0]);

        setField("workingActive", true);
        setField("workingGeneration", 7L);
        Thread loop = new Thread(() -> {
            try {
                call("runWorkingLoop", new Class<?>[]{long.class}, 7L);
            } catch (Exception ignored) {
                // reflected invocation wrapper
            }
        });
        loop.start();
        Thread.sleep(80L);
        setField("workingActive", false);
        loop.interrupt();
        loop.join(2000L);

        setField("workingEnabled", true);
        view.startWorking("first");
        view.startWorking("second");
        view.startWorking(null);
        view.stopWorking();
        view.stopWorking();
        setField("workingEnabled", false);
    }

    @Test
    public void rendersStreamSegmentsOutsideIntervention() throws Exception {
        view.beginStreamSegment("[assistant] ", ConsoleView.ANSI_GREEN);
        view.printStream("hello");
        view.printStream(null);
        view.emit("interrupt log stream", ConsoleView.ANSI_GRAY);
        view.printStream("resumed");
        view.endStreamSegment();
        view.endStreamSegment();
        view.emit("colored", ConsoleView.ANSI_GREEN);
        view.println();
        view.logUserInput("input");
        assertEquals("x", call("wrapColor", new Class<?>[]{String.class, String.class}, null, "x"));
        assertNull(call("wrapColor", new Class<?>[]{String.class, String.class}, "c", null));
    }

    @Test
    public void choosesModelWithRetriesAndFailures() throws Exception {
        Path good = writeConfig("good.yml");
        assertEquals(good, view.chooseModel(Collections.singletonList(good),
                new BufferedReader(new StringReader(""))));

        Path missing = folder.getRoot().toPath().resolve("missing.yml");
        BufferedReader reader = new BufferedReader(new StringReader("abc\n0\n2\n"));
        Path selected = view.chooseModel(Arrays.asList(good, missing), reader);
        assertEquals(missing, selected);

        BufferedReader emptyLine = new BufferedReader(new StringReader("\n"));
        assertEquals(good, view.chooseModel(Arrays.asList(good, missing), emptyLine));

        BufferedReader eof = new BufferedReader(new StringReader(""));
        assertEquals(good, view.chooseModel(Arrays.asList(good, missing), eof));

        BufferedReader badNumber = new BufferedReader(new StringReader("not-a-number\n"));
        assertEquals(good, view.chooseModel(Arrays.asList(good, missing), badNumber));

        BufferedReader failing = new BufferedReader(new StringReader("")) {
            @Override
            public String readLine() throws java.io.IOException {
                throw new java.io.IOException("boom");
            }
        };
        assertEquals(good, view.chooseModel(Arrays.asList(good, missing), failing));

        assertTrue(((String) call("describe", new Class<?>[]{Path.class}, missing)).startsWith("("));
    }

    @Test
    public void printsHintsAndBanner() throws Exception {
        view.printHints(Collections.<String>emptyList());
        view.printHints(Arrays.asList("custom-one (demo)", "custom-two (demo)"));
        view.printHints(null);

        Path imageOnly = writeImageOnlyConfig("image-only.yml");
        try (ProviderConfiguration imageConfig = new ProviderYamlConfigurationLoader().load(imageOnly)) {
            assertTrue(view.describe(imageConfig).contains("CHAT"));
        }


    }

    @Test
    public void createsViewWithoutLogFileAndDegradesWhenDirectoryIsAFile() throws Exception {
        ConsoleView noLog = new ConsoleView(null, false, "never");
        noLog.emit("no file");
        noLog.beginStreamSegment("[thinking] ", null);
        noLog.endStreamSegment();
        noLog.logTurn(com.clara.orchestration.api.ConversationResult.builder()
                .content("x").reasoning("y").build());
        noLog.close();

        File asFile = folder.newFile("not-a-directory");
        ConsoleView degraded = new ConsoleView(asFile.toPath(), false, "never");
        degraded.emit("degraded");
        degraded.close();
    }

    @Test
    public void detectsColorsFromExplicitValues() throws Exception {
        Class<?>[] types = new Class<?>[]{String.class};
        assertEquals(Boolean.FALSE, callStatic("detectColors", types, (Object) "never"));
        assertEquals(Boolean.FALSE, callStatic("detectColors", types, (Object) "off"));
        assertEquals(Boolean.FALSE, callStatic("detectColors", types, (Object) "0"));
        assertEquals(Boolean.FALSE, callStatic("detectColors", types, (Object) "false"));
        assertEquals(Boolean.TRUE, callStatic("detectColors", types, (Object) "always"));
        assertEquals(Boolean.TRUE, callStatic("detectColors", types, (Object) "on"));
        assertEquals(Boolean.TRUE, callStatic("detectColors", types, (Object) "1"));
        assertEquals(Boolean.TRUE, callStatic("detectColors", types, (Object) "true"));
        assertEquals(System.console() != null, callStatic("detectColors", types, (Object) "unknown"));
        assertNotNull(callStatic("workDirectory", new Class<?>[0]));
    }

    @Test
    public void observerSkipsNamelessToolCallsAndResetsStreams() throws Exception {
        ConsoleView.ConsoleStreamObserver observer = view.newObserver(new ConsoleView.ToolPurposeResolver() {
            @Override
            public String purpose(String toolName, String argumentsJson) {
                return null;
            }
        });
        observer.onEvent(event(StreamEventEnum.REASONING_DELTA, "  ", null, null));
        observer.onEvent(event(StreamEventEnum.TEXT_DELTA, "answer", null, null));
        observer.onEvent(event(StreamEventEnum.TOOL_ARGUMENT_DELTA, "partial", null, null));
        observer.onEvent(event(StreamEventEnum.TOOL_CALL_COMPLETE, "{}", null, null));
        observer.onEvent(event(StreamEventEnum.TOOL_CALL_COMPLETE, "{}", "   ", null));
        observer.onEvent(event(StreamEventEnum.TOOL_CALL_COMPLETE, "{\"command\":\"mvn test\"}", "run_command", "call-1"));
        observer.onEvent(event(StreamEventEnum.TOOL_CALL_COMPLETE, "{\"command\":\"mvn test\"}", "run_command", "call-1"));
        observer.onEvent(event(StreamEventEnum.TOOL_CALL_COMPLETE, "{}", "file_ops", "call-2"));
        observer.onEvent(event(StreamEventEnum.RESPONSE_COMPLETE, null, null, null));
        assertTrue(observer.printed());
        observer.endStream();

        ConsoleView.ConsoleStreamObserver other = view.newObserver(null);
        other.onEvent(event(StreamEventEnum.TOOL_CALL_COMPLETE, "{}", "file_ops", "call-3"));
        assertFalse(other.printed());
    }

    @Test
    public void byteStreamsForwardBytesAndLines() throws Exception {
        ByteArrayOutputStream console = new ByteArrayOutputStream();

        Class<?> compactClass = Class.forName("com.clara.adapter.console.ConsoleView$CompactProcessOutputStream");
        Constructor<?> compactCtor = compactClass.getDeclaredConstructor(ConsoleView.class);
        compactCtor.setAccessible(true);
        java.io.OutputStream compact = (java.io.OutputStream) compactCtor.newInstance(view);
        compact.write('\n');
        compact.write('a');
        compact.write('\r');
        compact.write('\n');
        compact.write("[tool] hello\n".getBytes(StandardCharsets.UTF_8));
        compact.write("[tool] bytes\n".getBytes(StandardCharsets.UTF_8), 0, 13);
        compact.flush();

        Class<?> grayClass = Class.forName("com.clara.adapter.console.ConsoleView$GrayLineOutputStream");
        Constructor<?> grayCtor = grayClass.getDeclaredConstructor(java.io.OutputStream.class, String.class);
        grayCtor.setAccessible(true);
        java.io.OutputStream plainGray = (java.io.OutputStream) grayCtor.newInstance(console, "");
        plainGray.write('x');
        plainGray.write(new byte[]{'y', '\n'}, 0, 2);
        plainGray.flush();

        java.io.OutputStream gray = (java.io.OutputStream) grayCtor.newInstance(console, "C");
        gray.write('a');
        gray.write('\n');
        gray.write("bc\n".getBytes(StandardCharsets.UTF_8), 0, 3);
        gray.flush();
        assertTrue(new String(console.toByteArray(), StandardCharsets.UTF_8).contains("bc"));

        Class<?> teeClass = Class.forName("com.clara.adapter.console.ConsoleView$TeeOutputStream");
        Constructor<?> teeCtor = teeClass.getDeclaredConstructor(java.io.OutputStream.class, java.io.OutputStream.class);
        teeCtor.setAccessible(true);
        ByteArrayOutputStream primary = new ByteArrayOutputStream();
        ByteArrayOutputStream secondary = new ByteArrayOutputStream();
        java.io.OutputStream tee = (java.io.OutputStream) teeCtor.newInstance(primary, secondary);
        tee.write('p');
        tee.write("q\n".getBytes(StandardCharsets.UTF_8), 0, 2);
        tee.flush();
        assertEquals("pq\n", new String(primary.toByteArray(), StandardCharsets.UTF_8));
        assertEquals("pq\n", new String(secondary.toByteArray(), StandardCharsets.UTF_8));
    }

    private static StreamEvent event(StreamEventEnum type, String payload, String toolName, String callId) {
        return new StreamEvent(type, 0L, payload, callId, toolName, null, null);
    }

    private void setField(String name, Object value) throws Exception {
        Field field = ConsoleView.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(view, value);
    }

    private Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = ConsoleView.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(view, args);
    }

    private static Object callStatic(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = ConsoleView.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(null, args);
    }

    private Path writeConfig(String name) throws Exception {
        Path file = folder.newFile(name).toPath();
        String yaml = "provider: " + ModelProviderEnum.DEEPSEEK.name() + "\n"
                + "credential:\n"
                + "  secretRef: deepseek\n"
                + "  apiKey: test-key\n"
                + "transport:\n"
                + "  allowedOrigins:\n"
                + "    - https://api.deepseek.com\n"
                + "  connectTimeoutMillis: 10000\n"
                + "  readTimeoutMillis: 180000\n"
                + "  maxRequestBytes: 8388608\n"
                + "  maxResponseBytes: 33554432\n"
                + "  allowHttp: false\n"
                + "capabilities:\n"
                + "  CHAT:\n"
                + "    endpoint: https://api.deepseek.com/responses\n"
                + "    modelId: demo-model\n"
                + "    apiProfile: RESPONSE\n";
        Files.write(file, yaml.getBytes(StandardCharsets.UTF_8));
        return file;
    }


    private Path writeImageOnlyConfig(String name) throws Exception {
        Path file = folder.newFile(name).toPath();
        String yaml = "provider: " + ModelProviderEnum.DEEPSEEK.name() + "\n"
                + "credential:\n"
                + "  secretRef: deepseek\n"
                + "  apiKey: test-key\n"
                + "transport:\n"
                + "  allowedOrigins:\n"
                + "    - https://api.deepseek.com\n"
                + "  connectTimeoutMillis: 10000\n"
                + "  readTimeoutMillis: 180000\n"
                + "  maxRequestBytes: 8388608\n"
                + "  maxResponseBytes: 33554432\n"
                + "  allowHttp: false\n"
                + "capabilities:\n"
                + "  IMAGE:\n"
                + "    endpoint: https://api.deepseek.com/images\n"
                + "    modelId: demo-image\n"
                + "    apiProfile: images-v1\n";
        Files.write(file, yaml.getBytes(StandardCharsets.UTF_8));
        return file;
    }
}
