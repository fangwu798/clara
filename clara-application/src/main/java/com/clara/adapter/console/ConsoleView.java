package com.clara.adapter.console;

import com.clara.ApplicationBootstrap;
import com.clara.dto.model.StreamEvent;
import com.clara.enums.CapabilityKindEnum;
import com.clara.exception.ClaraError;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.core.model.IStreamObserver;
import com.clara.model.availability.AvailabilityLine;
import com.clara.model.configuration.ProviderYamlConfigurationLoader;
import com.clara.orchestration.api.ConversationResult;
import com.clara.utils.StringUtils;

import java.io.BufferedReader;
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
import java.util.Set;

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
     * ANSI 控制码：灰色（过程 / 工具调用 / 状态行）。
     */
    public static final String ANSI_GRAY = "\u001B[90m";

    /**
     * ANSI 控制码：红色（错误）。
     */
    public static final String ANSI_RED = "\u001B[91m";

    /**
     * ANSI 控制码：黄色（跳过检查等需要留意、但未判定失败的状态）。
     */
    public static final String ANSI_YELLOW = "\u001B[93m";

    /**
     * ANSI 控制码：重置。
     */
    public static final String ANSI_RESET = "\u001B[0m";

    /**
     * 取可用性检查行的 ANSI 颜色码：可用为绿色、跳过为黄色、不可用为红色，普通信息不染色。
     *
     * @param level 可用性检查行的状态级别，可为 null
     * @return 颜色码；不染色时返回 null
     */
    public static String colorOf(AvailabilityLine.Level level) {
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

    /**
     * 丢弃输出的流：精简模式下接管框架的标准输出。
     */
    private static final OutputStream SINK = new OutputStream() {
        @Override
        public void write(int value) {
            // 精简模式下丢弃框架写入标准输出的内容。
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
            // 精简模式下丢弃框架写入标准输出的内容。
        }
    };

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

    /**
     * 是否全量日志；默认精简。
     */
    private boolean fullLogging;

    /**
     * 是否启用 ANSI 颜色。
     */
    private boolean colorsEnabled;

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
     * 输出一行到控制台（不写入详细日志文件）。
     */
    public void println(String text) {
        out.println(text);
    }

    /**
     * 输出一个空行到控制台（不写入详细日志文件）。
     */
    public void println() {
        out.println();
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
        out.println(wrapColor(colorCode, text));
        if (fileOut != null) {
            fileOut.println(text);
        }
    }

    /**
     * 把用户输入写入详细日志（控制台已回显，无需重复）。
     *
     * @param input 用户输入，非 null
     */
    public void logUserInput(String input) {
        if (fileOut != null) {
            fileOut.println();
            fileOut.println("[user] " + input);
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
        if (result.getReasoning() != null && !result.getReasoning().trim().isEmpty()) {
            fileOut.println("[thinking] " + result.getReasoning().trim());
        }
        if (result.getContent() != null) {
            fileOut.println("[assistant]");
            fileOut.println("            " + result.getContent());
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
        println(hint("/compress", "压缩当前上下文"));
        println(hint("/new", "开始新对话"));
        println(hint("/usage", "统计 token 用量"));
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
            consoleSide = SINK;
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
                    view.out.print(event.getPayload());
                    view.out.flush();
                    break;
                case TEXT_DELTA:
                    begin(MODE_ANSWER, "[assistant] ", ANSI_GREEN);
                    view.out.print(event.getPayload());
                    view.out.flush();
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
            view.out.println();
            if (view.colorsEnabled && color != null) {
                view.out.print(color);
            }
            view.out.print(prefix);
            view.out.flush();
        }

        /**
         * 打印一条灰色「调用工具」提示：工具名 +（本次调用的用途）。
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
            view.emit(toolLine(name, event.getPayload()), ANSI_GRAY);
        }

        /**
         * 组装「调用工具」日志行：工具名 +（用途短句）。
         */
        private String toolLine(String name, String argumentsJson) {
            String purpose = toolPurpose == null
                    ? "" : briefToolPurpose(toolPurpose.purpose(name, argumentsJson));
            return purpose.isEmpty()
                    ? "  [tool] 调用工具：" + name
                    : "  [tool] 调用工具：" + name + "（" + purpose + "）";
        }

        /**
         * 结束当前段：换行并复位颜色。
         */
        private void end() {
            if (mode != MODE_IDLE) {
                mode = MODE_IDLE;
                if (view.colorsEnabled) {
                    view.out.print(ANSI_RESET);
                }
                view.out.println();
                view.out.flush();
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
        public void write(byte[] buffer, int offset, int length) throws IOException {
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
        public void write(byte[] buffer, int offset, int length) throws IOException {
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
