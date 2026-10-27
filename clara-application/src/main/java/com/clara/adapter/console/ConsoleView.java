package com.clara.adapter.console;

import com.clara.ApplicationBootstrap;
import com.clara.dto.model.StreamEvent;
import com.clara.enums.CapabilityKindEnum;
import com.clara.exception.ClaraError;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.core.model.IStreamObserver;
import com.clara.enums.AvailabilityLevelEnum;
import com.clara.model.configuration.ProviderYamlConfigurationLoader;
import com.clara.orchestration.api.ConversationResult;
import com.clara.tool.ToolInvocationPurpose;
import com.clara.utils.StringUtils;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 控制台视图：集中承担「显示」相关的全部职责——颜色与日志模式、框架标准输出的接管、详细日志落盘、
 * 启动横幅、模型选择交互，以及思考/正文/工具过程的流式渲染。
 *
 * <p>是什么、在哪一层：application 的边界适配层（{@code com.clara.adapter.console}），与业务流程解耦；
 * {@code ClaraApplication} 只通过本类输出，不直接操作 {@code System.out} 或颜色码。
 *
 * <p>显示与记录解耦：框架层（如 HTTP 传输）直接写 {@code System.out}，本类把 {@code System.out} 接到
 * 「控制台分支 + 文件分支」的合流上——控制台分支按模式决定「灰色显示」或「静音」，文件分支始终完整落盘。
 */
public final class ConsoleView implements Closeable {

    private static final String UTF_8 = "UTF-8";
    private static final String ERROR_PREFIX = "[error] ";

    /**
     * ANSI 控制码：淡绿色（助手回复）。
     */
    public static final String ANSI_GREEN = "\u001B[92m";

    /**
     * ANSI 控制码：灰色（过程 / 状态行）。
     */
    public static final String ANSI_GRAY = "\u001B[90m";

    /**
     * ANSI 控制码：浅蓝色（工具调用提示）。
     */
    public static final String ANSI_LIGHT_BLUE = "\u001B[94m";

    /**
     * ANSI 控制码：红色（错误）。
     */
    public static final String ANSI_RED = "\u001B[91m";

    /**
     * ANSI 控制码：黄色（跳过检查等需要留意、但未判定失败的状态）。
     */
    public static final String ANSI_YELLOW = "\u001B[93m";

    /**
     * ANSI 控制码：青色（多角色讨论的第一种角色色）。
     */
    public static final String ANSI_CYAN = "\u001B[96m";

    /**
     * ANSI 控制码：品红（多角色讨论的第二种角色色）。
     */
    public static final String ANSI_MAGENTA = "\u001B[95m";

    /**
     * ANSI 控制码：重置。
     */
    public static final String ANSI_RESET = "\u001B[0m";

    /**
     * 多角色讨论的角色配色：按首次出现顺序分配，保证同一角色每次都用同一种颜色、不同角色尽量不同色。
     */
    private static final String[] ROLE_COLORS = {
            ANSI_CYAN, ANSI_MAGENTA, ANSI_YELLOW, ANSI_GREEN, ANSI_LIGHT_BLUE, ANSI_RED
    };

    /**
     * 取可用性检查行的 ANSI 颜色码：可用为绿色、跳过为黄色、不可用为红色，普通信息不染色。
     *
     * @param level 可用性检查行的状态级别，可为 null
     * @return 颜色码；不染色时返回 null
     */
    public static String colorOf(AvailabilityLevelEnum level) {
        if (level == null) {
            return null;
        }
        switch (level) {
            case OK:
                return ANSI_GREEN;
            case WARN:
                return ANSI_YELLOW;
            case FAIL:
                return ANSI_RED;
            default:
                return null;
        }
    }

    /**
     * 「调用工具」日志中用途短句的最大字符数，超出部分以省略号截断。
     */
    private static final int TOOL_PURPOSE_MAX_CHARS = 80;

    /**
     * 提示信息中每条目的行首缩进。
     */
    private static final String HINT_INDENT = "  ";

    /**
     * 提示信息中命令列的显示宽度，与 {@code /?} 命令清单的对齐位置一致。
     */
    private static final int HINT_COMMAND_WIDTH = 14;

    /**
     * 提示信息中自然语言用法列的显示宽度，取最长用法「读取本地目录、整理技术文档」的宽度并留出间隔。
     */
    private static final int HINT_USAGE_WIDTH = 28;

    /** 命令行输出块的缩进竖线。 */
    private static final String OUTPUT_PREFIX = "  │ ";

    /** 工具子进程日志前缀，与 {@code AbstractScriptExecutor} 保持一致。 */
    private static final String TOOL_LOG_PREFIX = "[tool] ";

    /** run_command 的标准输出标签。 */
    private static final String STDOUT_TAG = "[out] ";

    /** run_command 的标准错误标签。 */
    private static final String STDERR_TAG = "[err] ";

    /** Working 行刷新间隔（毫秒）。 */
    private static final long WORKING_TICK_MILLIS = 250L;

    /**
     * 应用自身输出流（UTF-8），始终可见、不受日志模式影响。
     */
    private final PrintStream out;

    /**
     * JVM 原始标准输出，用于异常时恢复框架日志。
     */
    private final PrintStream frameworkTarget;

    /**
     * 详细日志文件输出流：始终完整落盘（含框架 HTTP / 工具过程），与显示模式无关；不可用时为 null。
     */
    private PrintStream fileOut;

    /**
     * 本轮运行的详细日志文件路径（用于展示），不可用时为 null。
     */
    private final String logFilePath;

    /** 详细日志中是否有一个尚未换行的流式段（思考 / 正文）。 */
    private boolean logStreamOpen;

    /** 当前流式段的日志前缀（如 {@code "[thinking] "}）；段被其它日志行打断后据此重新起段。 */
    private String logStreamPrefix;

    /** 本轮是否已把思考实时写入日志；已写入时 {@link #logTurn} 不再重复整段思考。 */
    private boolean logStreamedReasoning;

    /** 本轮是否已把正文实时写入日志；已写入时 {@link #logTurn} 不再重复整段正文。 */
    private boolean logStreamedContent;

    /**
     * 是否全量日志；默认精简。
     */
    private boolean fullLogging;

    /**
     * 是否启用 ANSI 颜色。
     */
    private boolean colorsEnabled;

    /** 控制台输出锁：Working 行、流式正文与工具输出可能来自不同线程。 */
    private final Object outputLock = new Object();

    /** 是否支持原地刷新 Working 行；测试与非交互环境关闭。 */
    private final boolean workingEnabled = System.console() != null;

    /** Working 行是否处于活动状态。 */
    private volatile boolean workingActive;

    /** Working 行代次：停止后旧线程即使醒来也不会继续绘制。 */
    private volatile long workingGeneration;

    /** Working 行开始时间。 */
    private volatile long workingStartedAt;

    /** 固定状态栏上一次展示的 Working 秒数：只在秒数变化时重绘，避免高频刷新干扰流式正文。 */
    private long workingLastStatusSecond;

    /** Working 行标题。 */
    private volatile String workingLabel = "Working";
    /** 是否暂停 Working 行刷新（执行期补充输入时避免覆盖输入行）；受 {@code outputLock} 保护。 */
    private boolean workingPaused;
    /** 执行期补充输入是否进行中：为 true 时所有控制台输出先暂存，避免覆盖输入行。 */
    private boolean interactiveInputActive;
    /** 输入期间暂存的控制台输出（保留 ANSI 片段），输入结束后一次性回放。 */
    private final StringBuilder deferredConsole = new StringBuilder();

    /** 是否显示 ESC 取消提示。 */
    private volatile boolean interruptHintAvailable = true;

    /** 底部状态行里的「当前发言人 · 模型」；null 表示当前不在多角色讨论中。 */
    private volatile String speakerStatus;

    /** 底部状态行刷新回调：状态内容变化时立即重绘；未接行编辑器时为 null。 */
    private volatile Runnable statusRefresh;

    /** 角色 → 颜色：首次出现时按 {@link #ROLE_COLORS} 顺序分配，保证稳定且不串色。 */
    private final Map<String, String> roleColors = new ConcurrentHashMap<>();

    /** 角色配色游标。 */
    private final AtomicInteger roleColorCursor = new AtomicInteger();

    /** Working 行后台线程。 */
    private Thread workingThread;

    /** Working 行当前已绘制的字符宽度，用于原地清除。 */
    private int workingRenderedWidth;

    /** 当前是否有一个流式段正在输出；为 true 时不能再原地刷新 Working 行，否则会覆盖思考正文。 */
    private boolean streamSegmentOpen;

    /**
     * 创建控制台视图：接管应用输出、打开详细日志、按参数初始化颜色与日志模式。
     *
     * @param logsDirectory 详细日志目录，可为 null（不可用时降级为不落盘）
     * @param fullLogging   是否以全量日志模式启动
     * @param forcedColor   命令行强制的颜色取值（如 {@code never}），可为 null
     * @throws UnsupportedEncodingException 应用输出流初始化失败时
     */
    public ConsoleView(Path logsDirectory, boolean fullLogging, String forcedColor) throws UnsupportedEncodingException {
        this.frameworkTarget = System.out; // NOSONAR CLI output by design
        this.out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, UTF_8);
        this.fullLogging = fullLogging;
        this.logFilePath = openLogFile(logsDirectory);
        writeLogHeader();
        this.colorsEnabled = detectColors(forcedColor);
        applyLogMode();
    }

    /**
     * 当前是否全量日志模式。
     */
    boolean isFullLogging() {
        return fullLogging;
    }

    /**
     * 当前是否启用颜色。
     */
    boolean isColorsEnabled() {
        return colorsEnabled;
    }

    /**
     * 开关颜色。
     */
    void toggleColors() {
        colorsEnabled = !colorsEnabled;
        applyLogMode();
    }

    /**
     * 开关日志模式（精简 / 全量）。
     */
    void toggleLogMode() {
        fullLogging = !fullLogging;
        applyLogMode();
    }

    /**
     * 设置 ESC 取消提示是否可见。
     *
     * @param available 当前终端是否支持 ESC 取消
     */
    public void setInterruptHintAvailable(boolean available) {
        this.interruptHintAvailable = available;
    }

    /**
     * 启动 Working 进度行（默认标题 {@code Working}）。
     */
    public void startWorking() {
        startWorking("Working");
    }

    /**
     * 启动 Working 进度行：在终端底部原地刷新已用时间。
     *
     * <p>非交互环境（测试、管道输出）下不启动，避免污染日志；已在运行时只更新标题，不重置计时。
     *
     * @param label 进度标题，可为 null（回退为 Working）
     */
    public void startWorking(String label) {
        // 固定底部状态栏可用时，即使 System.console() 为空也要启动计时线程（由状态栏负责展示）。
        if (!workingEnabled && statusRefresh == null) {
            return;
        }
        synchronized (outputLock) {
            workingLabel = label == null || label.trim().isEmpty() ? "Working" : label.trim();
            workingPaused = interactiveInputActive;
            if (workingActive) {
                return;
            }
            workingGeneration++;
            long generation = workingGeneration;
            workingStartedAt = System.currentTimeMillis();
            workingLastStatusSecond = 0L;
            workingActive = true;
            workingThread = new Thread(() -> runWorkingLoop(generation), "clara-working");
            workingThread.setDaemon(true);
            workingThread.start();
        }
        notifyStatusRefresh();
    }

    /**
     * 进入执行期补充输入：暂停 Working 行并暂存后续输出，保证输入行不被流式内容覆盖。
     *
     * @param prompt 提示文案，非 null
     */
    public void beginInterventionInput(String prompt) {
        synchronized (outputLock) {
            clearWorkingLineLocked();
            workingPaused = true;
            interactiveInputActive = true;
            out.println();
            out.println(wrapColor(ANSI_YELLOW, prompt));
            out.flush();
        }
    }

    /**
     * 结束执行期补充输入：一次性回放暂存输出，并恢复 Working 行刷新。
     */
    public void endInterventionInput() {
        synchronized (outputLock) {
            interactiveInputActive = false;
            if (deferredConsole.length() > 0) {
                out.print(deferredConsole);
                deferredConsole.setLength(0);
                out.flush();
            }
            workingPaused = false;
        }
    }

    /**
     * 当前是否处于执行期补充输入。
     *
     * @return 输入中返回 true
     */
    public boolean isInterventionInputActive() {
        synchronized (outputLock) {
            return interactiveInputActive;
        }
    }

    /**
     * 停止 Working 进度行并清除当前行。
     */
    public void stopWorking() {
        Thread thread;
        synchronized (outputLock) {
            workingGeneration++;
            workingActive = false;
            workingPaused = interactiveInputActive;
            if (!interactiveInputActive) {
                clearWorkingLineLocked();
            }
            thread = workingThread;
            workingThread = null;
        }
        if (thread != null && thread != Thread.currentThread()) {
            thread.interrupt();
        }
        notifyStatusRefresh();
    }

    /**
     * Working 行后台刷新循环：每 {@value #WORKING_TICK_MILLIS} 毫秒重绘一次已用时间。
     *
     * @param generation 启动时的代次，用于避免停止后旧线程继续绘制
     */
    private void runWorkingLoop(long generation) {
        while (workingActive && generation == workingGeneration) {
            boolean statusTick = false;
            synchronized (outputLock) {
                if (!workingActive || generation != workingGeneration) {
                    return;
                }
                if (statusRefresh != null) {
                    long elapsedSeconds = elapsedWorkingSeconds();
                    if (elapsedSeconds != workingLastStatusSecond) {
                        workingLastStatusSecond = elapsedSeconds;
                        statusTick = true;
                    }
                } else {
                    renderWorkingLineLocked();
                }
            }
            if (statusTick) {
                notifyStatusRefresh();
            }
            try {
                Thread.sleep(WORKING_TICK_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 绘制 Working 行：覆盖上一次内容，避免残影。
     */
    private void renderWorkingLineLocked() {
        // 固定状态栏可用时由状态栏展示 Working；流式段输出期间原地刷新会覆盖正文，必须让位。
        if (workingPaused || statusRefresh != null || streamSegmentOpen) {
            return;
        }
        long elapsedSeconds = elapsedWorkingSeconds();
        String hint = interruptHintAvailable ? " • esc cancel • Tab steer" : "";
        String line = "• " + workingLabel + " (" + elapsedSeconds + "s" + hint + ")";
        StringBuilder rendered = new StringBuilder();
        rendered.append('\r');
        if (colorsEnabled) {
            rendered.append(ANSI_LIGHT_BLUE);
        }
        rendered.append(line);
        for (int index = line.length(); index < workingRenderedWidth; index++) {
            rendered.append(' ');
        }
        if (colorsEnabled) {
            rendered.append(ANSI_RESET);
        }
        out.print(rendered);
        out.flush();
        workingRenderedWidth = line.length();
    }

    /**
     * 清除当前 Working 行（若有），让后续输出从行首开始。
     */
    private void clearWorkingLineLocked() {
        if (workingRenderedWidth <= 0) {
            return;
        }
        out.print('\r');
        for (int index = 0; index < workingRenderedWidth; index++) {
            out.print(' ');
        }
        out.print('\r');
        out.flush();
        workingRenderedWidth = 0;
    }

    /**
     * 输出一行工具子进程内容：默认标准输出，标准错误用红色。
     *
     * @param line 已去掉 {@code [tool] } 前缀的单行内容，非 null
     */
    void printToolProcessLine(String line) {
        String text = line == null ? "" : line;
        String color = null;
        if (text.startsWith(STDERR_TAG)) {
            color = ANSI_RED;
            text = text.substring(STDERR_TAG.length());
        } else if (text.startsWith(STDOUT_TAG)) {
            text = text.substring(STDOUT_TAG.length());
        }
        synchronized (outputLock) {
            if (interactiveInputActive) {
                deferredConsole.append(OUTPUT_PREFIX).append(text).append('\n');
                return;
            }
            clearWorkingLineLocked();
            if (colorsEnabled) {
                out.print(ANSI_GRAY);
            }
            out.print(OUTPUT_PREFIX);
            if (colorsEnabled) {
                out.print(ANSI_RESET);
                if (color != null) {
                    out.print(color);
                }
            }
            out.println(text);
            if (colorsEnabled && color != null) {
                out.print(ANSI_RESET);
            }
        }
    }

    /**
     * 输出一行到控制台（不写入详细日志文件）。
     */
    public void println(String text) {
        synchronized (outputLock) {
            if (interactiveInputActive) {
                deferredConsole.append(text).append('\n');
                return;
            }
            clearWorkingLineLocked();
            out.println(text);
        }
    }

    /**
     * 输出一个空行到控制台（不写入详细日志文件）。
     */
    public void println() {
        synchronized (outputLock) {
            if (interactiveInputActive) {
                deferredConsole.append('\n');
                return;
            }
            clearWorkingLineLocked();
            out.println();
        }
    }

    /**
     * 输出一行：控制台按颜色显示，同时以纯文本写入详细日志（保证日志不含颜色码）。
     *
     * @param text 文本，非 null
     */
    public void emit(String text) {
        emit(text, null);
    }

    /**
     * 输出一行：控制台按颜色显示，同时以纯文本写入详细日志。
     *
     * @param text      文本，非 null
     * @param colorCode ANSI 颜色码；null 表示不染色
     */
    public void emit(String text, String colorCode) {
        synchronized (outputLock) {
            String rendered = wrapColor(colorCode, text);
            // 流式段尚未换行时先收尾，避免工具 / 进度日志粘在思考正文后面。
            closeLogStreamLineLocked();
            if (fileOut != null) {
                fileOut.println(text);
                fileOut.flush();
            }
            if (interactiveInputActive) {
                deferredConsole.append(rendered).append('\n');
                return;
            }
            clearWorkingLineLocked();
            out.println(rendered);
        }
    }

    /**
     * 输出某个角色的一整段发言，按角色着色。
     *
     * <p>同一角色（按角色名）始终使用同一种颜色，不同角色按首次出现顺序取不同颜色；
     * 这样多轮讨论里「谁说的」一眼可辨，也便于在日志里对照。
     *
     * @param speaker 发言人（角色名），可为 null（使用默认颜色）
     * @param text    发言正文（可含换行，通常首行是角色与轮次标题），非 null
     */
    public void emitSpeaker(String speaker, String text) {
        emit(text, roleColor(speaker));
    }

    /**
     * 取角色对应的颜色码：首次出现时按调色板顺序分配。
     *
     * @param speaker 角色名，可为 null
     * @return ANSI 颜色码，非 null
     */
    String roleColor(String speaker) {
        String key = speaker == null || speaker.trim().isEmpty() ? "?" : speaker.trim();
        return roleColors.computeIfAbsent(key,
                ignored -> ROLE_COLORS[Math.floorMod(roleColorCursor.getAndIncrement(), ROLE_COLORS.length)]);
    }

    /**
     * 设置底部状态行的「当前发言人 · 模型」，并立即刷新状态行。
     *
     * @param speaker 当前角色名；null 或空串表示讨论结束、恢复默认模型信息
     * @param model   该角色使用的模型标识，可为 null
     */
    public void setSpeakerStatus(String speaker, String model) {
        String next = null;
        if (speaker != null && !speaker.trim().isEmpty()) {
            next = model == null || model.trim().isEmpty()
                    ? speaker.trim() : speaker.trim() + " · " + model.trim();
        }
        speakerStatus = next;
        // Working 行同样位于终端底部：行编辑器不支持固定状态栏时，靠它实时展示「角色 · 模型」。
        synchronized (outputLock) {
            workingLabel = next == null ? "Working" : "多角色 · " + next;
            if (workingActive && !workingPaused) {
                renderWorkingLineLocked();
            }
        }
        notifyStatusRefresh();
    }

    /**
     * 当前发言人状态文本（「角色 · 模型」）；讨论结束后为 null。
     *
     * @return 状态文本，可为 null
     */
    public String speakerStatus() {
        return speakerStatus;
    }

    /**
     * 注册底部状态行刷新回调：行编辑器可用时传入 {@code ConsoleInput::refreshStatus}。
     *
     * <p>回调统一包在控制台输出锁里：状态行由 JLine 终端写入，思考 / 正文由本类直接写标准输出，
     * 两者并发写终端时会在最后一行交错，把状态行开头字符覆盖掉；而 JLine 只做差异重绘，不会补回
     * 未变化的开头。串行化后每次刷新都能画出一整行。
     *
     * @param statusRefresh 刷新动作，可为 null（不刷新）
     */
    public void setStatusRefresh(Runnable statusRefresh) {
        this.statusRefresh = statusRefresh == null ? null : () -> {
            synchronized (outputLock) {
                statusRefresh.run();
            }
        };
        notifyStatusRefresh();
    }

    /**
     * 当前 Working 计时文本（如 {@code 12s}）；未在执行为 null。
     *
     * @return 计时文本，可为 null
     */
    public String workingElapsedLabel() {
        if (!workingActive) {
            return null;
        }
        return elapsedWorkingSeconds() + "s";
    }

    /**
     * 本轮执行已用秒数：Working 行、底部状态栏共用同一口径。
     *
     * @return 已用秒数，非负
     */
    private long elapsedWorkingSeconds() {
        return Math.max(0L, (System.currentTimeMillis() - workingStartedAt) / 1000L);
    }

    /**
     * 通知宿主刷新固定状态栏；未接状态栏或刷新失败时静默，不能影响本轮执行。
     */
    private void notifyStatusRefresh() {
        Runnable refresh = statusRefresh;
        if (refresh == null) {
            return;
        }
        try {
            refresh.run();
        } catch (RuntimeException ignored) {
            // 状态栏只是展示：刷新失败不能影响本轮执行
        }
    }

    /**
     * 把用户输入写入详细日志（控制台已回显，无需重复）。
     *
     * @param input 用户输入，非 null
     */
    public void logUserInput(String input) {
        synchronized (outputLock) {
            closeLogStreamLineLocked();
            logStreamPrefix = null;
            logStreamedReasoning = false;
            logStreamedContent = false;
            if (fileOut != null) {
                fileOut.println();
                fileOut.println("[user] " + input);
                fileOut.flush();
            }
        }
    }

    /**
     * 把本轮的思考与回复完整写入详细日志文件（控制台已实时流式展示，避免重复）。
     *
     * @param result 本轮对话结果，非 null
     */
    public void logTurn(ConversationResult result) {
        if (fileOut == null) {
            return;
        }
        synchronized (outputLock) {
            closeLogStreamLineLocked();
            logStreamPrefix = null;
            // 流式阶段已实时落盘的内容不再整段重复；仅无流式输出（同步调用）时补写。
            if (!logStreamedReasoning
                    && result.getReasoning() != null && !result.getReasoning().trim().isEmpty()) {
                fileOut.println("[thinking] " + result.getReasoning().trim());
            }
            if (!logStreamedContent && result.getContent() != null) {
                fileOut.println("[assistant]");
                fileOut.println("            " + result.getContent());
            }
            fileOut.flush();
        }
    }

    /**
     * 创建一个新流式观察者，用于实时渲染模型思考、正文与工具调用过程。
     *
     * @param toolPurpose 工具用途解析器：按「工具名 + 本次入参」返回一句用途说明（为 null 时不展示）；
     *                    用于在「调用工具」日志中简要说明调用目的
     * @return 流式观察者，非 null
     */
    public ConsoleStreamObserver newObserver(ToolPurposeResolver toolPurpose) {
        return new ConsoleStreamObserver(this, toolPurpose);
    }

    /**
     * 工具用途解析器：把一次工具调用（工具名 + 入参 JSON）翻译成一句人可读的用途说明。
     *
     * <p>为什么需要入参：同一个工具可以做很多不同的事（{@code run_command} 既可编译也可看 Git 状态），
     * 只按工具名只能回答「这个工具是干什么的」，带上入参才能回答「这次调用是为了什么」。
     */
    @FunctionalInterface
    public interface ToolPurposeResolver {

        /**
         * 解析一次工具调用的用途。
         *
         * @param toolName      工具名，非 null
         * @param argumentsJson 本次调用的入参 JSON，可为 null
         * @return 一句用途说明；无可展示内容时返回 null
         */
        String purpose(String toolName, String argumentsJson);
    }

    /**
     * 把工具声明里的用途描述压成一行短句，供控制台「调用工具」日志展示。
     *
     * <p>工具描述本是为模型编写的长段落（含入参说明、调用时机等），整段打印会淹没控制台，故只取首句
     * （以「。」为界）并限制最大长度。
     *
     * @param description 工具用途描述，可为 null
     * @return 一行短句；无可用描述时为空串
     */
    static String briefToolPurpose(String description) {
        if (description == null) {
            return "";
        }
        String text = description.trim().replaceAll("\\s+", " ");
        if (text.isEmpty()) {
            return "";
        }
        int end = text.length();
        int stop = text.indexOf('。');
        if (stop >= 0) {
            end = stop + 1;
        }
        String sentence = text.substring(0, end);
        return sentence.length() <= TOOL_PURPOSE_MAX_CHARS
                ? sentence
                : sentence.substring(0, TOOL_PURPOSE_MAX_CHARS) + "…";
    }

    /**
     * 打印装配结果、当前模型信息与对话历史存放位置。
     */
    public void printBanner(ApplicationBootstrap bootstrap, ProviderConfiguration providerConfig,
            Path configPath, String source) {
        emit("Clara application assembled successfully.");
        emit("  orchestrator   : " + bootstrap.orchestrator().getClass().getName());
        emit("  workflowEngine : " + bootstrap.workflowEngine().getClass().getName());
        emit("  embeddingClient: " + bootstrap.embeddingClient().getClass().getName());
        emit("  memory         : " + bootstrap.conversationHistoryDescription());
        emit("  semantic       : " + bootstrap.semanticMemoryDescription());
        emit("  model          : " + describe(providerConfig) + "   [" + configPath + "]");
        emit("  modelSource    : " + source);
        emit("  workDir        : " + workDirectory());
        if (logFilePath != null) {
            emit("  logFile        : " + logFilePath);
        }
        emit("");
    }

    /**
     * 打印交互提示：常用命令与自然语言可触发的工作流，末尾补一个空行与后续对话分隔。
     *
     * <p>显示约定：命令列与用法列按显示宽度右侧补充空格，纵向成列便于扫读；完整命令清单由 {@code /?} 给出，
     * 此处只列最常用的几条，避免启动时刷屏；ESC 取消能力由调用方按终端是否支持单独提示，这里不重复。
     *
     * @param customWorkflows 自定义工作流的「标识（描述）」文本行（{@code config/workflow/custom} 下），
     *                        非 null，可为空（为空时不打印该段）
     */
    public void printHints(List<String> customWorkflows) {
        println("常用命令（/? 查看全部）：");
        println(hint("/model", "切换模型"));
        println(hint("/init", "重新初始化（重新检测模型与配置）"));
        println(hint("/compress", "压缩当前上下文"));
        println(hint("/new", "开始新对话"));
        println(hint("/usage", "统计 token 用量"));
        println(hint("/history", "查看 / 清理历史聊天记录（/? 查看用法）"));
        println(hint("/log", "切换精简 / 全量日志"));
        println(hint("/color", "开关彩色输出"));
        println(hint("exit | quit", "结束会话"));
        println();
        println("自然语言即可触发工作流（模型会自动调用 run_workflow）：");
        println(workflowHint("读取本地目录、整理技术文档", "analyze_repo_docs"));
        println(workflowHint("生成需求与技术方案", "design_business_solution"));
        println(workflowHint("生成开发计划", "plan_development"));
        println(workflowHint("生成图片", "image_generation"));
        println(workflowHint("生成视频", "video_generation"));
        println(workflowHint("编辑图片", "image_edit"));
        println(workflowHint("识别图片", "image_understanding"));
        if (customWorkflows != null && !customWorkflows.isEmpty()) {
            println("自定义工作流（config/workflow/custom，生成后即可按名触发）：");
            for (String line : customWorkflows) {
                println(HINT_INDENT + line);
            }
        }
        println();
        println("执行中按 Tab 输入补充说明，Enter 提交，空行取消；补充会在下一次模型请求前生效。");
        println("直接输入内容回车即发送。");
        println();
    }

    /**
     * 拼一行「命令 + 作用」，命令列按显示宽度对齐。
     *
     * @param command     命令，非 null
     * @param description 作用说明，非 null
     * @return 已对齐的单行文本
     */
    private static String hint(String command, String description) {
        return HINT_INDENT + StringUtils.padRight(command, HINT_COMMAND_WIDTH) + description;
    }

    /**
     * 拼一行「自然语言用法 + 工作流名」，用法列按显示宽度对齐。
     *
     * @param usage    自然语言用法，非 null
     * @param workflow 工作流名，非 null
     * @return 已对齐的单行文本
     */
    private static String workflowHint(String usage, String workflow) {
        return HINT_INDENT + StringUtils.padRight(usage, HINT_USAGE_WIDTH) + workflow;
    }

    /**
     * 让用户从候选配置中选择一个。
     *
     * @param candidates 候选配置，非空
     * @param reader     标准输入读取器，非 null
     * @return 选中的配置路径，非 null
     */
    public Path chooseModel(List<Path> candidates, BufferedReader reader) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        println("检测到多个模型配置（已忽略 examples 样例）：");
        for (int index = 0; index < candidates.size(); index++) {
            Path candidate = candidates.get(index);
            println("  " + (index + 1) + ") " + describe(candidate) + "   [" + candidate + "]");
        }
        while (true) {
            out.print("请选择模型 [1-" + candidates.size() + "]（直接回车默认 1）：");
            out.flush();
            try {
                String line = reader.readLine();
                if (line == null) {
                    return candidates.get(0);
                }
                String value = line.trim();
                if (value.isEmpty()) {
                    return candidates.get(0);
                }
                int index = Integer.parseInt(value);
                if (index >= 1 && index <= candidates.size()) {
                    return candidates.get(index - 1);
                }
            } catch (NumberFormatException ignored) {
                // 非数字输入落回重试
            } catch (IOException failure) {
                return candidates.get(0);
            }
            println("输入无效，请重试。");
        }
    }

    /**
     * 读取已加载配置的「平台 / 模型」描述。
     */
    String describe(ProviderConfiguration providerConfig) {
        try {
            return providerConfig.getProvider() + " / " + providerConfig.toConfig(CapabilityKindEnum.CHAT).getModelId();
        } catch (RuntimeException failure) {
            return providerConfig.getProvider() + " / (无 CHAT 能力: " + failure.getMessage() + ")";
        }
    }

    /**
     * 读取一个配置文件的「平台 / 模型」描述，用于选择列表展示；加载失败时返回失败原因。
     */
    private String describe(Path configPath) {
        try (ProviderConfiguration config = new ProviderYamlConfigurationLoader().load(configPath)) {
            return describe(config);
        } catch (RuntimeException failure) {
            return "(加载失败: " + failure.getMessage() + ")";
        }
    }

    /**
     * 关闭视图：刷盘详细日志。
     */
    @Override
    public void close() {
        if (fileOut != null) {
            fileOut.flush();
        }
    }

    /**
     * 应用当前日志模式：把 {@code System.out} 接到「控制台分支 + 文件分支」的合流上。
     */
    private void applyLogMode() {
        OutputStream consoleSide;
        if (fullLogging) {
            consoleSide = new GrayLineOutputStream(out, colorsEnabled ? ANSI_GRAY : "");
        } else {
            consoleSide = new CompactProcessOutputStream(this);
        }
        OutputStream target = fileOut == null ? consoleSide : new TeeOutputStream(consoleSide, fileOut);
        try {
            System.setOut(new PrintStream(target, true, UTF_8));
        } catch (UnsupportedEncodingException failure) {
            System.setOut(frameworkTarget);
        }
    }

    /**
     * 打开本轮运行的详细日志文件（{@code <仓库根>/logs/clara-<时间戳>.log}）。
     *
     * <p>日志始终完整记录（框架 HTTP 请求 / 响应、工具调用过程、对话往返），与「精简 / 全量」显示模式无关；
     * 目录创建或文件打开失败时降级为不落盘，不影响正常对话。
     *
     * @param logsDirectory 日志目录，可为 null
     * @return 日志文件路径；不可用时 null
     */
    private String openLogFile(Path logsDirectory) {
        if (logsDirectory == null) {
            return null;
        }
        try {
            Files.createDirectories(logsDirectory);
            Path logFile = logsDirectory.resolve("clara-" + timestamp() + ".log");
            fileOut = new PrintStream(new FileOutputStream(logFile.toFile(), true), true, UTF_8);
            LogRetention.prune(logsDirectory, LogRetention.keepCount());
            return logFile.toString();
        } catch (IOException | RuntimeException failure) {
            fileOut = null;
            return null;
        }
    }

    /**
     * 写入日志文件头（启动时间、工作目录）。
     */
    private void writeLogHeader() {
        if (fileOut == null) {
            return;
        }
        fileOut.println("============================================================");
        fileOut.println("Clara 运行日志  启动时间=" + timestamp());
        fileOut.println("工作目录=" + Paths.get("").toAbsolutePath());
        fileOut.println("============================================================");
    }

    /**
     * 当前时间戳
     */
    private static String timestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }

    /**
     * 取本轮要分析的项目目录：优先取启动脚本注入的 {@code CLARA_WORK_DIR}，未注入时退回进程当前工作目录。
     *
     * @return 分析目录，非 null
     */
    private static String workDirectory() {
        String configured = System.getenv("CLARA_WORK_DIR");
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        return Paths.get("").toAbsolutePath().toString();
    }

    /**
     * 判定是否启用 ANSI 颜色：显式覆盖（命令行或 {@code CLARA_COLOR}）优先，其次 {@code NO_COLOR}，
     * 默认仅在交互式终端开启。
     *
     * @param forced 命令行强制值（如 {@code never}），可为 null
     * @return 是否启用颜色
     */
    private static boolean detectColors(String forced) {
        String explicit = forced != null ? forced : System.getenv("CLARA_COLOR");
        if (explicit != null) {
            String value = explicit.trim().toLowerCase(Locale.ROOT);
            if ("never".equals(value) || "off".equals(value) || "0".equals(value) || "false".equals(value)) {
                return false;
            }
            if ("always".equals(value) || "on".equals(value) || "1".equals(value) || "true".equals(value)) {
                return true;
            }
        }
        if (System.getenv("NO_COLOR") != null) {
            return false;
        }
        return System.console() != null;
    }

    /**
     * 输出流式文本：执行期补充输入期间先暂存，避免覆盖用户正在输入的内容。
     *
     * @param text 文本片段，非 null
     */
    void printStream(String text) {
        synchronized (outputLock) {
            if (fileOut != null) {
                // 若上一行日志把流式段打断了，按原前缀重新起段，保证思考仍可读。
                if (!logStreamOpen && logStreamPrefix != null) {
                    beginLogStreamLineLocked(logStreamPrefix);
                }
                fileOut.print(text);
                fileOut.flush();
            }
            if (interactiveInputActive) {
                deferredConsole.append(text);
                return;
            }
            out.print(text);
            out.flush();
        }
    }

    /**
     * 开始一个流式展示段：换行并输出前缀；输入期间改为暂存。
     *
     * @param prefix 段前缀，非 null
     * @param color  段颜色码，可为 null
     */
    void beginStreamSegment(String prefix, String color) {
        synchronized (outputLock) {
            // 流式正文开始后不再原地刷新 Working，但固定状态栏仍持续显示进度。
            streamSegmentOpen = true;
            beginLogStreamLineLocked(prefix);
            if (interactiveInputActive) {
                deferredConsole.append('\n');
                if (colorsEnabled && color != null) {
                    deferredConsole.append(color);
                }
                deferredConsole.append(prefix);
                return;
            }
            clearWorkingLineLocked();
            out.println();
            if (colorsEnabled && color != null) {
                out.print(color);
            }
            out.print(prefix);
            out.flush();
        }
    }

    /**
     * 结束当前流式展示段：复位颜色并换行；输入期间改为暂存。
     */
    void endStreamSegment() {
        synchronized (outputLock) {
            streamSegmentOpen = false;
            closeLogStreamLineLocked();
            logStreamPrefix = null;
            if (interactiveInputActive) {
                if (colorsEnabled) {
                    deferredConsole.append(ANSI_RESET);
                }
                deferredConsole.append('\n');
                return;
            }
            if (colorsEnabled) {
                out.print(ANSI_RESET);
            }
            out.println();
            out.flush();
        }
    }

    /**
     * 在详细日志里开始一个流式段：先收尾上一段，再写入前缀；段内容由 {@link #printStream} 原样追加。
     *
     * <p>思考（{@code [thinking] }）与正文（{@code [assistant] }）都走这里，因此工作流节点的思考也会
     * 与主对话一样实时落盘。
     *
     * @param prefix 段前缀，非 null
     */
    private void beginLogStreamLineLocked(String prefix) {
        if (fileOut == null) {
            return;
        }
        closeLogStreamLineLocked();
        fileOut.print(prefix);
        fileOut.flush();
        logStreamOpen = true;
        logStreamPrefix = prefix;
        if (prefix.contains("thinking")) {
            logStreamedReasoning = true;
        }
        if (prefix.contains("assistant")) {
            logStreamedContent = true;
        }
    }

    /**
     * 收尾当前流式段：只补一个换行并保留前缀，便于被其它日志行打断后继续同一段。
     */
    private void closeLogStreamLineLocked() {
        if (fileOut != null && logStreamOpen) {
            fileOut.println();
            fileOut.flush();
        }
        logStreamOpen = false;
    }

    /**
     * 按当前颜色开关给文本包色。
     */
    private String wrapColor(String color, String text) {
        if (!colorsEnabled || color == null || text == null) {
            return text;
        }
        return color + text + ANSI_RESET;
    }

    /**
     * 控制台流式观察者：把模型思考、正文与工具调用过程实时打印到控制台。
     *
     * <p>思考以灰色、正文以淡绿色呈现，思考与正文各自成段（切换时换行并打前缀）；工具调用在参数拼装完整时
     * （{@code TOOL_CALL_COMPLETE}）按调用标识去重，每次调用打印一条灰色提示（含工具名与本次调用用途短句）。
     */
    public static final class ConsoleStreamObserver implements IStreamObserver {

        private static final int MODE_IDLE = 0;
        private static final int MODE_THINKING = 1;
        private static final int MODE_ANSWER = 2;

        private final ConsoleView view;
        private final ToolPurposeResolver toolPurpose;
        private int mode = MODE_IDLE;
        private final Set<String> notedCalls = new HashSet<>();
        private boolean printed;

        ConsoleStreamObserver(ConsoleView view, ToolPurposeResolver toolPurpose) {
            this.view = view;
            this.toolPurpose = toolPurpose;
        }

        /**
         * 本轮是否输出过思考或正文段落；调用方据此决定是否补一个空行。
         *
         * @return 有过输出时 true
         */
        public boolean printed() {
            return printed;
        }

        /**
         * 结束当前流式段（若有）：插入外部内容（如工作流节点进度提示）前调用，避免与流式文本粘连。
         */
        public void endStream() {
            end();
        }

        @Override
        public void onEvent(StreamEvent event) {
            switch (event.getType()) {
                case REASONING_DELTA:
                    begin(MODE_THINKING, "[thinking] ", ANSI_GRAY);
                    view.printStream(event.getPayload());
                    break;
                case TEXT_DELTA:
                    begin(MODE_ANSWER, "[assistant] ", ANSI_GREEN);
                    view.printStream(event.getPayload());
                    break;
                case TOOL_ARGUMENT_DELTA:
                    // 参数分片期间只做展示准备，不打印：等参数拼装完整（TOOL_CALL_COMPLETE）才发出提示，
                    // 这样提示里能带上本次调用的入参（如 run_command 实际执行的命令）。
                    break;
                case TOOL_CALL_COMPLETE:
                    noteToolCall(event);
                    break;
                case RESPONSE_COMPLETE:
                    end();
                    break;
                case ERROR:
                    view.stopWorking();
                    end();
                    view.emit(errorLine(event.getError()), ANSI_RED);
                    break;
                default:
                    break;
            }
        }

        /**
         * 组装错误提示行：基础文案 +（平台返回的错误提示，若有）。
         *
         * <p>非 2xx 时错误细节里的 {@code providerMessage} 承载平台原文（如「Insufficient Balance」），
         * 展示出来便于直接判断失败原因；无该细节时退化为仅展示基础文案。
         */
        private static String errorLine(ClaraError error) {
            if (error == null) {
                return ERROR_PREFIX;
            }
            String message = error.getSafeDetails().get("providerMessage");
            return message == null || message.trim().isEmpty()
                    ? ERROR_PREFIX + error.getSafeMessage()
                    : ERROR_PREFIX + error.getSafeMessage() + "：" + message.trim();
        }

        /**
         * 切换到指定输出段：段变化时先结束上一段，再换行并打印带颜色的前缀。
         */
        private void begin(int nextMode, String prefix, String color) {
            if (mode == nextMode) {
                return;
            }
            end();
            mode = nextMode;
            printed = true;
            view.beginStreamSegment(prefix, color);
        }

        /**
         * 打印一条浅蓝色「调用工具」提示：工具名 +（本次调用的用途）。
         *
         * <p>由 {@code TOOL_CALL_COMPLETE} 触发（参数已拼装完整，能拿到完整入参），按调用标识去重，
         * 因此同一轮里多次调用同一工具也会各打印一条。
         */
        private void noteToolCall(StreamEvent event) {
            String name = event.getToolName() == null ? null : event.getToolName().trim();
            if (name == null || name.isEmpty()) {
                return;
            }
            String key = event.getToolCallId() != null
                    ? event.getToolCallId() : name + "\u0000" + event.getPayload();
            if (!notedCalls.add(key)) {
                return;
            }
            end();
            view.emit(toolLine(name, event.getPayload()), ANSI_LIGHT_BLUE);
        }

        /**
         * 组装工具调用标题：命令类工具显示完整命令行，其余工具显示名称 +（用途短句）。
         */
        private String toolLine(String name, String argumentsJson) {
            String command = ToolInvocationPurpose.displayCommand(name, argumentsJson);
            if (command != null && !command.isEmpty()) {
                return "● 执行 " + command;
            }
            String purpose = toolPurpose == null
                    ? "" : briefToolPurpose(toolPurpose.purpose(name, argumentsJson));
            return purpose.isEmpty()
                    ? "● 调用 " + name
                    : "● 调用 " + name + "（" + purpose + "）";
        }

        /**
         * 结束当前段：换行并复位颜色。
         */
        private void end() {
            if (mode != MODE_IDLE) {
                mode = MODE_IDLE;
                view.endStreamSegment();
            }
        }
    }

    /**
     * 精简模式下的框架输出过滤器：只保留工具子进程日志，按「竖线缩进输出块」渲染，其余框架日志丢弃。
     *
     * <p>工具子进程日志由 {@code AbstractScriptExecutor} 以 {@code [tool] } 开头写入标准输出；
     * run_command 的 stdout / stderr 再分别带 {@code [out] } / {@code [err] } 标签。
     */
    private static final class CompactProcessOutputStream extends OutputStream {

        private final ConsoleView view;
        private final ByteArrayOutputStream pending = new ByteArrayOutputStream();

        CompactProcessOutputStream(ConsoleView view) {
            this.view = view;
        }

        @Override
        public synchronized void write(int value) {
            if (value == '\n') {
                flushLine();
            } else if (value != '\r') {
                pending.write(value);
            }
        }

        @Override
        public synchronized void write(byte @NotNull [] buffer, int offset, int length) {
            for (int index = offset; index < offset + length; index++) {
                write(buffer[index]);
            }
        }

        @Override
        public synchronized void flush() {
            // 只按整行渲染，半行内容等待换行后再显示。
        }

        /**
         * 把已完成的一行交给视图渲染；非工具日志直接丢弃。
         */
        private void flushLine() {
            if (pending.size() == 0) {
                return;
            }
            String line = new String(pending.toByteArray(), StandardCharsets.UTF_8);
            pending.reset();
            if (line.startsWith(TOOL_LOG_PREFIX)) {
                view.printToolProcessLine(line.substring(TOOL_LOG_PREFIX.length()));
            }
        }
    }

    /**
     * 在每一行行首插入颜色、行尾插入重置的 {@link OutputStream}，用于把框架过程日志整体染成灰色。
     *
     * <p>以「按字节」方式处理，故对框架一次 {@code println} 输出的多行文本也逐行生效；文件分支不经过本流，
     * 因此日志文件不会被写入颜色码。
     */
    private static final class GrayLineOutputStream extends OutputStream {

        private final OutputStream delegate;
        private final byte[] color;
        private final byte[] reset;
        private boolean atLineStart = true;

        GrayLineOutputStream(OutputStream delegate, String colorCode) {
            this.delegate = delegate;
            this.color = colorCode.isEmpty() ? new byte[0] : colorCode.getBytes(StandardCharsets.UTF_8);
            this.reset = colorCode.isEmpty() ? new byte[0] : ANSI_RESET.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void write(int value) throws IOException {
            if (color.length > 0 && atLineStart) {
                delegate.write(color);
            }
            delegate.write(value);
            if (color.length > 0) {
                if (value == '\n') {
                    delegate.write(reset);
                    atLineStart = true;
                } else {
                    atLineStart = false;
                }
            }
        }

        @Override
        public void write(byte @NotNull [] buffer, int offset, int length) throws IOException {
            for (int index = offset; index < offset + length; index++) {
                write(buffer[index]);
            }
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }
    }

    /**
     * 把同一份输出同时写入两个目标（控制台分支 + 日志文件分支）。
     */
    private static final class TeeOutputStream extends OutputStream {

        private final OutputStream primary;
        private final OutputStream secondary;

        TeeOutputStream(OutputStream primary, OutputStream secondary) {
            this.primary = primary;
            this.secondary = secondary;
        }

        @Override
        public void write(int value) throws IOException {
            primary.write(value);
            secondary.write(value);
        }

        @Override
        public void write(byte @NotNull [] buffer, int offset, int length) throws IOException {
            primary.write(buffer, offset, length);
            secondary.write(buffer, offset, length);
        }

        @Override
        public void flush() throws IOException {
            primary.flush();
            secondary.flush();
        }
    }
}
