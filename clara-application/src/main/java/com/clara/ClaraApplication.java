package com.clara;

import com.clara.adapter.console.CancelKeyMonitor;
import com.clara.adapter.console.ConsoleContextTrace;
import com.clara.adapter.console.ConsoleView;
import com.clara.adapter.console.LaunchOptions;
import com.clara.adapter.console.SessionCommandHandler;
import com.clara.adapter.console.UsageTrendChart;
import com.clara.codegraph.CodeGraphConsole;
import com.clara.config.CodeGraphSettings;
import com.clara.config.ConfigLocator;
import com.clara.config.ModelAvailabilityManager;
import com.clara.config.ModelFailover;
import com.clara.config.ModelRegistry;
import com.clara.context.CancellationToken;
import com.clara.enums.CapabilityKindEnum;
import com.clara.exception.ClaraError;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.kernel.message.delta.TokenUsage;
import com.clara.orchestration.api.ConversationRequest;
import com.clara.orchestration.api.ConversationResult;
import com.clara.orchestration.api.OrchestrationStatusEnum;
import com.clara.workflow.WorkflowCatalog;
import com.clara.workflow.runtime.WorkflowProgress;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

/**
 * 交互式对话入口：从 {@code config/model-providers} 选择模型（可多平台 / 多调用方式），装配各模块后逐行对话。
 *
 * <p>是什么、在哪一层：application 的最小可运行入口（{@code com.clara}）。它把 {@link ApplicationBootstrap}
 * 装配好的对话编排能力接到标准输入上，作为「本地跑通智能体」的最简宿主；真正的产品化运行入口（CLI 参数化、
 * REST / SSE）在其适配层另行提供。
 *
 * <p>职责边界：本类只负责**核心业务流程**——启动参数消费、模型选择决策、引导装配、会话主循环与收尾。
 * 启动参数识别交给 {@link LaunchOptions}，配置定位与读取交给 {@link ConfigLocator}，显示与日志交给
 * {@link ConsoleView}，会话内指令（退出 / 日志 / 颜色 / 切换模型）交给 {@link SessionCommandHandler}，
 * 模型状态交给 {@link ModelRegistry}（{@code com.clara.config}：别名解析、会话当前模型与配置生命周期）。
 *
 * <p>运行前置：
 * <ol>
 *   <li>工作目录为仓库根（{@code config/} 可被定位；非根时按「向上回溯」定位）；</li>
 *   <li>所选配置 {@code credential} 声明的环境变量在<b>启动该进程</b>的环境中可见（如
 *       {@code CLARA_DEEPSEEK_API_KEY}）。进程环境是启动时的快照，系统里设置后需重启 IDE 或显式注入。</li>
 * </ol>
 *
 * <p>使用方法：主（默认）模型由 {@code config/clara.yml} 的 {@code defaultModel} 指定；未配置或未匹配到时，
 * 若存在多个模型配置会列出编号供选择，也可用首个命令行参数直接指定配置文件。进入对话后逐行输入回车发送，
 * {@code /model} 重新选择模型，{@code /graph} 查看代码知识图谱状态、{@code /graph import} 把当前工作目录
 * 导入图谱，{@code /log} 切换日志模式，{@code /color} 开关颜色，{@code exit} / {@code quit} 结束；
 * 一轮执行过程中按 ESC 两次可取消本轮正在进行的模型调用与工具执行（首次 ESC 仅提示，避开误触）。
 *
 * <p>会话标识由工作目录推导，对话历史落盘在工作目录下的 {@code .clara/state/conversations}，因此<b>退出后
 * 再次进入同一目录会续接上次的上下文</b>；换目录即换上下文，删除该目录即清空历史。
 *
 * <p>详细日志：{@link ConsoleView} 会把<b>完整</b>交互写入仓库根 {@code logs/clara-<时间戳>.log}，与显示模式无关。
 */
public final class ClaraApplication {

    private static String conversationId;

    /**
     * 会话级用量趋势：每轮对话后记录累计 token，供 {@code /usage} 渲染趋势图（{@code /new} 时重置）。
     */
    private static final UsageTrendChart usageTrend = new UsageTrendChart();

    /**
     * 会话内代码知识图谱入口：{@code /graph} 查状态、{@code /graph import} 把工作目录导入图谱。
     */
    /** 图谱初始化日志的标签列：与启动横幅其它行同宽。 */
    private static final String CODEGRAPH_PREFIX = "  codegraph      : ";

    /** 图谱初始化日志的续行缩进：宽度等于 CODEGRAPH_PREFIX，续行文字与首行文字对齐。 */
    private static final String CODEGRAPH_DETAIL = "                   ";

    /** 图谱技能脚本结果的首行前缀：步骤名已经说明是「结果」，打印时去掉这个重复前缀。 */
    private static final String RESULT_PREFIX = "结果：";

    private static final CodeGraphConsole codeGraphConsole = new CodeGraphConsole();

    /**
     * 进程入口：选择模型、装配各模块并进入交互式对话循环。
     *
     * @param args 可选；{@code -v}/{@code --full} 全量日志、{@code --no-color} 关闭颜色，
     *             其余首个参数视为模型配置文件路径（缺省则扫描目录并交互选择）
     * @throws Exception 输出流初始化失败时
     */
    public static void main(String[] args) throws Exception {
        // 会话标识由工作目录推导：同一目录再次进入即续接上次的对话历史（历史落盘位置见启动横幅）。
        conversationId = ConfigLocator.cliSessionId();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        LaunchOptions options = LaunchOptions.parse(args);

        try (ConsoleView view = new ConsoleView(ConfigLocator.logsDirectory(), options.fullLogging(), options.forcedColor()); CancelKeyMonitor monitor = CancelKeyMonitor.create(view)) {
            List<Path> candidates = new ModelAvailabilityManager().checkAndPersist(ConfigLocator.allModelConfigs(), ConfigLocator.claraConfigFile(), line -> view.emit(line.getText(), ConsoleView.colorOf(line.getLevel())));
            ModelChoice choice = selectModelConfig(view, reader, options, candidates);
            runSession(view, reader, candidates, choice, monitor);
        }
    }

    /**
     * 装配模型与各模块，进入会话循环，并做启动异常兜底。
     *
     * @param view       控制台视图，非 null
     * @param reader     标准输入读取器，非 null
     * @param candidates 候选模型配置，非 null
     * @param choice     选中的模型配置与来源，非 null
     * @param monitor    ESC 取消监听器，非 null
     * @throws IOException 读取标准输入失败时
     */
    private static void runSession(ConsoleView view, BufferedReader reader, List<Path> candidates, ModelChoice choice, CancelKeyMonitor monitor) throws IOException {
        // 控制台观测：每轮把图谱补充与向量召回的命中情况打在控制台（同时落详细日志）。
        try (ApplicationBootstrap bootstrap = new ApplicationBootstrap(new ConsoleContextTrace(view))) {
            ModelRegistry registry = bootstrap.modelRegistry();
            registry.select(choice.configPath());
            SessionCommandHandler commands = new SessionCommandHandler(view, reader, registry, candidates, contextControls(view, bootstrap, registry));

            view.printBanner(bootstrap, registry.currentConfiguration(), choice.configPath(), choice.source());
            printMcpStatus(view, bootstrap);
            prepareCodeGraph(view, bootstrap);
            view.emit("  sessionId      : " + conversationId + "（由工作目录生成，同一目录再次进入续接同一份历史）");
            view.printHints(WorkflowCatalog.customSummaries());
            if (monitor.isAvailable()) {
                view.emit("执行中可按 ESC 两次请求取消本轮模型调用与工具执行。", ConsoleView.ANSI_GRAY);
            }
            loop(view, reader, bootstrap, registry, candidates, commands, monitor);
        } catch (RuntimeException startupFailure) {
            view.println("启动失败：" + startupFailure.getClass().getSimpleName() + ": " + startupFailure.getMessage());
            view.println("请确认：1) 所选模型配置存在且合法（当前 " + choice.configPath().toAbsolutePath() + "）；" + "2) 其 credential 声明的环境变量已设置。");
            System.exit(1);
        }
    }

    /**
     * 接入外部 MCP 服务端，并把接入结果写进启动横幅。
     *
     * <p>为什么在启动阶段做：发现（握手 + 列工具）要起子进程 / 连网络，结果应提前告诉用户；但失败不许阻断
     * 对话，故整体兜底为一行提示。接入成功后模型能在工具清单里看到这些外部工具（本地技能与外部工具由
     * {@code CompositeToolCatalog} 合并下发），调用则经 {@code CompositeToolInvoker} 路由到 MCP 网关。
     *
     * @param view      控制台视图，不可为 null
     * @param bootstrap 已装配的引导器，不可为 null
     */
    private static void printMcpStatus(ConsoleView view, ApplicationBootstrap bootstrap) {
        try {
            // 发现要起子进程并做一次 MCP 握手，stdio 服务端可能要好几秒；先打一行提示，避免横幅之后长时间静置。
            view.emit("  mcp            : 正在接入外部 MCP 服务端（config/mcp）...", ConsoleView.ANSI_GRAY);
            int servers = bootstrap.loadMcpServers(ApplicationBootstrap.DEFAULT_MCP_TENANT);
            if (servers <= 0) {
                view.emit("  mcp            : 未接入外部 MCP 服务端（config/mcp 无配置或发现失败）", ConsoleView.ANSI_GRAY);
                return;
            }
            int tools = bootstrap.externalToolCatalog().snapshot().size();
            view.emit("  mcp            : " + servers + " 个服务端 / " + tools + " 个外部工具（config/mcp）", ConsoleView.ANSI_GRAY);
        } catch (RuntimeException failure) {
            view.emit("  mcp            : 接入失败：" + failure.getMessage() + "（不影响对话）", ConsoleView.ANSI_GRAY);
        }
    }

    /**
     * 按 {@code config/clara.yml} 的 {@code codeGraph} 开关在启动时处理图谱：{@code autoMount} 先确保图谱
     * 容器能看到当前工作目录（容器看不到就补一条只读挂载），{@code autoImport} 再幂等地处理导入
     * （已导入只提示一句，没导入则后台发起，不阻塞启动）。
     *
     * <p>为什么挂载排在导入前面：容器是按「一条目录一条挂载」带上工作目录的，缺挂载时图谱对该目录一律
     * 回「容器里看不到它」，导入会直接失败。
     *
     * <p>为什么每一步都要打日志：这两步各自要起一次子进程（挂载检查查 docker、导入检查查图谱项目，
     * 合起来可能好几秒），静默期只会让人以为程序卡住；而且「图谱怎么没命中」的原因基本都写在这里
     * （目录没挂上 / 没导入），不打印就只能靠猜。
     *
     * <p>总开关关掉时整块不打：功能既然关了，日志里就不该再出现它（要确认开关看 config/clara.yml）。
     *
     * @param view      控制台视图，不可为 null
     * @param bootstrap 已装配的引导器，不可为 null
     */
    private static void prepareCodeGraph(ConsoleView view, ApplicationBootstrap bootstrap) {
        CodeGraphSettings settings = bootstrap.codeGraphSettings();
        if (!settings.isEnabled()) {
            return;
        }
        emitCodeGraph(view, "初始化：工作目录 " + ConfigLocator.workspaceRoot() + "，supplement="
                + settings.isSupplementEnabled() + " autoMount=" + settings.isAutoMountEnabled()
                + " autoImport=" + settings.isAutoImportEnabled());
        if (settings.isAutoMountEnabled()) {
            emitCodeGraphStep(view, "挂载检查", new CodeGraphStep() {
                @Override
                public String run() {
                    return codeGraphConsole.ensureMount();
                }
            });
        } else {
            emitCodeGraph(view, "挂载检查：跳过（autoMount=false）——容器看不到本目录时读代码会退回本地文件；"
                    + "需要补挂见 deploy\\codebase-memory-mcp\\install-docker.ps1");
        }
        if (settings.isAutoImportEnabled()) {
            emitCodeGraphStep(view, "导入检查", new CodeGraphStep() {
                @Override
                public String run() {
                    return codeGraphConsole.prepare(true);
                }
            });
        } else {
            emitCodeGraph(view, "导入检查：跳过（autoImport=false）——图谱里没有本项目时不会被自动导入"
                    + "（用 /graph 查看、/graph import 导入）");
        }
    }

    /**
     * 跑一步图谱初始化，把结果与耗时打成「  codegraph      : 步骤 123ms：首行」。
     *
     * <p>其余结果行按续行缩进对齐：脚本回的结果里常有项目 / 目录 / 说明好几种，压成一行看不清。
     *
     * @param view  控制台视图，不可为 null
     * @param label 步骤名（挂载检查 / 导入检查），非 null
     * @param step  要执行的一步，非 null
     */
    private static void emitCodeGraphStep(ConsoleView view, String label, CodeGraphStep step) {
        long started = System.nanoTime();
        String result;
        try {
            result = step.run();
        } catch (RuntimeException failure) {
            result = "失败：" + failure.getMessage() + "（不影响对话）";
        }
        long elapsedMillis = (System.nanoTime() - started) / 1000000L;
        String[] lines = (result == null ? "" : result.trim()).split("\r?\n");
        String headline = lines[0].trim();
        if (headline.startsWith(RESULT_PREFIX)) {
            headline = headline.substring(RESULT_PREFIX.length()).trim();
        }
        emitCodeGraph(view, label + " " + elapsedMillis + "ms："
                + (headline.isEmpty() ? "（脚本没有输出，可能没装 Python）" : headline));
        for (int index = 1; index < lines.length; index++) {
            String detail = lines[index].trim();
            if (!detail.isEmpty()) {
                view.emit(CODEGRAPH_DETAIL + detail, ConsoleView.ANSI_GRAY);
            }
        }
    }

    /**
     * 打一行图谱初始化日志（标签列与横幅其它行对齐）。
     *
     * @param view    控制台视图，不可为 null
     * @param message 内容，非 null
     */
    private static void emitCodeGraph(ConsoleView view, String message) {
        view.emit(CODEGRAPH_PREFIX + message, ConsoleView.ANSI_GRAY);
    }

    /**
     * 图谱初始化的一步：跑一次脚本调用并回可打印的中文结果。
     */
    private interface CodeGraphStep {

        /**
         * @return 可打印的中文结果，非 null
         */
        String run();
    }

    /**
     * 构造会话级上下文控制：{@code /compress} 压缩当前上下文、{@code /new} 开始新对话。
     *
     * @param view      控制台视图，非 null
     * @param bootstrap 已装配的引导器，非 null
     * @param registry  模型注册表（取当前平台与模型），非 null
     * @return 上下文控制实现，非 null
     */
    private static SessionCommandHandler.ConversationControls contextControls(ConsoleView view, ApplicationBootstrap bootstrap, ModelRegistry registry) {
        return new SessionCommandHandler.ConversationControls() {
            @Override
            public void compressContext() {
                try {
                    int count = bootstrap.compressConversation(conversationId, registry.currentConfiguration(), registry.currentConfiguration().toConfig(CapabilityKindEnum.CHAT).getModelId());
                    if (count <= 0) {
                        view.emit("当前上下文为空，无需压缩。", ConsoleView.ANSI_GRAY);
                    } else {
                        view.emit("已压缩当前上下文：把 " + count + " 条历史归纳为摘要，后续轮次只携带摘要。", ConsoleView.ANSI_GRAY);
                    }
                } catch (RuntimeException failure) {
                    view.emit("[error] 压缩失败：" + failure.getMessage(), ConsoleView.ANSI_RED);
                }
            }

            @Override
            public void newConversation() {
                conversationId = newConversationId();
                usageTrend.reset();
                view.emit("已开始新对话，会话标识：" + conversationId, ConsoleView.ANSI_GRAY);
            }

            @Override
            public void showCodeGraph() {
                view.emit("正在查询代码知识图谱（工作目录：" + ConfigLocator.workspaceRoot() + "）...", ConsoleView.ANSI_GRAY);
                view.println(codeGraphConsole.status());
                view.println("");
            }

            @Override
            public void importCodeGraph() {
                view.emit("正在把当前工作目录导入代码知识图谱（大仓库要几分钟，请等待）...", ConsoleView.ANSI_GRAY);
                view.println(codeGraphConsole.importWorkdir(false));
                view.println("");
            }

            @Override
            public void showUsage() {
                try {
                    String report = bootstrap.conversationUsageReport(conversationId, registry.currentConfiguration(), registry.currentConfiguration().toConfig(CapabilityKindEnum.CHAT).getModelId());
                    view.println(report);
                    view.println("");
                    view.println(usageTrend.render());
                } catch (RuntimeException failure) {
                    view.emit("[error] 统计用量失败：" + failure.getMessage(), ConsoleView.ANSI_RED);
                }
            }
        };
    }

    /**
     * 记录本轮用量到趋势：优先取平台返回的总量，缺失时按输入加输出推导；无用量时跳过。
     *
     * @param usage 本轮 token 用量，可为 null
     */
    private static void recordUsageTrend(TokenUsage usage) {
        if (usage == null) {
            return;
        }
        long total = usage.getTotalTokens() != null ? usage.getTotalTokens().longValue() : (long) usage.getInputTokens() + usage.getOutputTokens();
        usageTrend.record(total);
    }

    /**
     * 生成新会话标识：在「工作目录派生标识」后追加时间戳，保证新对话与既有历史相互隔离。
     *
     * @return 新会话标识，非 null
     */
    private static String newConversationId() {
        return ConfigLocator.cliSessionId() + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
    }

    /**
     * 逐行读取输入：指令交给处理器决定「结束 / 跳过 / 作为对话输入」，其余走一轮对话。
     *
     * @param view      控制台视图，非 null
     * @param reader    标准输入读取器，非 null
     * @param bootstrap 已装配的引导器，非 null
     * @param registry  模型注册表（提供当前生效的平台配置），非 null
     * @param commands  会话内指令处理器，非 null
     * @param monitor   ESC 取消监听器，非 null
     * @throws IOException 读取标准输入失败时
     */
    private static void loop(ConsoleView view, BufferedReader reader, ApplicationBootstrap bootstrap, ModelRegistry registry, List<Path> candidates, SessionCommandHandler commands, CancelKeyMonitor monitor) throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            String input = line.trim();
            if (input.isEmpty()) {
                continue;
            }
            SessionCommandHandler.Outcome outcome = commands.handle(input);
            if (outcome == SessionCommandHandler.Outcome.EXIT) {
                return;
            }
            if (outcome == SessionCommandHandler.Outcome.INPUT) {
                view.logUserInput(input);
                converse(view, bootstrap, registry, candidates, input, monitor);
            }
        }
    }

    /**
     * 选择本轮使用的模型配置：命令行指定优先，其次 {@code config/clara.yml} 的主模型，最后交互选择。
     *
     * @param view       控制台视图，非 null
     * @param reader     标准输入读取器，非 null
     * @param options    启动参数，非 null
     * @param candidates 候选模型配置，非 null
     * @return 选中的配置路径与来源描述，非 null
     */
    private static ModelChoice selectModelConfig(ConsoleView view, BufferedReader reader, LaunchOptions options, List<Path> candidates) {
        if (options.configFile() != null) {
            return new ModelChoice(ConfigLocator.locateFile(Paths.get(options.configFile())), "命令行参数");
        }
        String defaultModel = ConfigLocator.defaultModel();
        List<String> enabled = ConfigLocator.enabledProviders();
        Path configPath = Paths.get("");
        if (defaultModel != null && !enabled.isEmpty() && !containsIgnoreCase(enabled, defaultModel)) {
            view.println("启动失败：config/clara.yml 的 defaultModel=" + defaultModel + " 不在 enabledProviders 启用列表内 " + enabled + "。");
            view.println("请把主模型加入 enabledProviders，或把 defaultModel 改为已启用的模型。");
            System.exit(1);
            return new ModelChoice(configPath, "配置错误");
        }
        Path preferred = defaultModel == null ? null : ConfigLocator.resolveModel(candidates, defaultModel);
        if (preferred != null) {
            return new ModelChoice(preferred, "config/clara.yml 指定的主模型（" + defaultModel + "）");
        }
        if (defaultModel != null) {
            view.println("config/clara.yml 的 defaultModel=" + defaultModel + " 未匹配到任何模型配置，转为手动选择。");
        }
        if (candidates.isEmpty()) {
            view.println("未找到模型配置：请确认工作目录为仓库根，且 " + ConfigLocator.modelDirectory().toAbsolutePath() + " 下存在 *.yml / *.yaml（examples 样例会被忽略）。");
            System.exit(1);
            return new ModelChoice(configPath, "无可用模型配置");
        }
        return new ModelChoice(view.chooseModel(candidates, reader), "交互选择");
    }

    /**
     * 判断值是否在列表中（忽略大小写）。
     *
     * @param values 值列表，非 null
     * @param value  待判断值，非 null
     * @return 命中时返回 true
     */
    private static boolean containsIgnoreCase(List<String> values, String value) {
        for (String item : values) {
            if (item.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 模型选择结果：选中的配置文件路径与来源描述（用于启动横幅展示）。
     */
    private static final class ModelChoice {

        private final Path configPath;
        private final String source;

        ModelChoice(Path configPath, String source) {
            this.configPath = configPath;
            this.source = source;
        }

        Path configPath() {
            return configPath;
        }

        String source() {
            return source;
        }
    }

    /**
     * 执行一轮对话：当前模型不可用时，按启动期响应时间顺序自动切到下一个可用模型重试。
     *
     * <p>为什么在这层做故障转移：换模型只影响「这一轮对话用哪个平台」，属于宿主（会话）的决策，
     * 与编排层无关——工作流 AGENT 节点有各自的 role / model 绑定，不该被这里的切换影响。
     *
     * <p>尝试顺序取自 {@link ModelFailover#attemptOrder}（当前模型 + 启动期按响应时间升序的其余可用模型）。
     * 只有在「失败属于模型不可用」且「本轮还没执行过工具调用」时才继续往下换：工具可能有副作用
     * （写文件、发请求），换个模型把同一轮重放一遍等于把这些副作用重做一次。
     *
     * <p>切过去的模型会写入 {@link ModelRegistry}（即会话当前模型），后续轮次默认用它；同时会打印一行
     * {@code [failover]} 说明，不做静默切换。注意会话历史按「会话 + 平台 + 模型」隔离，
     * 所以新模型看到的是它自己那份历史（切回原模型即恢复原上下文）。
     *
     * @param view       控制台视图，非 null
     * @param bootstrap  已装配的引导器，非 null
     * @param registry   模型注册表（读当前模型、写切换结果），非 null
     * @param candidates 启动期按首字符响应时间升序的可用模型配置，非 null
     * @param input      用户输入，非空白
     * @param monitor    ESC 取消监听器，非 null
     */
    private static void converse(ConsoleView view, ApplicationBootstrap bootstrap, ModelRegistry registry, List<Path> candidates, String input, CancelKeyMonitor monitor) {
        // 本轮取消令牌：交给监听器置位，随请求下发给编排层，进而让模型调用与脚本工具在检查点终止。
        // 一次对话可能换模型重试，令牌跨尝试共用——用户按 ESC 取消的是整轮，不是某一次尝试。
        CancellationToken cancellationToken = new CancellationToken();
        List<Path> plan = ModelFailover.attemptOrder(candidates, registry.currentConfigPath());
        if (plan.isEmpty()) {
            // 极端情况：候选清单为空且当前模型没解析出别名——仍然要跑一轮，别把用户输入吞掉（此时不换模型）。
            plan = Collections.singletonList(registry.currentConfigPath());
        }
        try (CancelKeyMonitor.Session ignored = monitor.watch(cancellationToken)) {
            for (int index = 0; index < plan.size(); index++) {
                Path configPath = plan.get(index);
                if (index > 0) {
                    registry.select(configPath);
                    view.emit("  [failover] 上一个模型不可用，已切到 " + ConfigLocator.aliasOf(configPath) + " 重试（会话历史按模型隔离，切回原模型即恢复原上下文）", ConsoleView.ANSI_YELLOW);
                }
                ConversationResult result = converseOnce(view, bootstrap, registry.currentConfiguration(), input,
                        cancellationToken);
                if (result == null
                        || result.getStatus() != OrchestrationStatusEnum.FAILED
                        || !ModelFailover.isUnavailable(result.getError())) {
                    // 成功、异常已就地报告，或失败原因与「模型不可用」无关（入参、内容安全等）：如实报告，不换模型。
                    return;
                }
                if (result.getToolRoundTrips() > 0) {
                    view.emit("  [failover] 本轮已经执行过工具调用（可能有副作用），不再换模型重试", ConsoleView.ANSI_YELLOW);
                    return;
                }
                if (index == plan.size() - 1) {
                    // 只有一个候补都没有时别报「试完 N 个」，那种说法会让用户以为换过了别的模型。
                    view.emit(plan.size() > 1 ? "  [failover] 已按响应时间顺序试完 " + plan.size() + " 个可用模型，均不可用；可用 /model 手动指定，或检查网络与凭据" : "  [failover] 没有其它可用模型可切换；可用 /model 手动指定，或检查网络与凭据", ConsoleView.ANSI_RED);
                }
            }
        }
    }

    /**
     * 用指定模型跑一轮对话，并把回复、用量与错误打印到控制台。
     *
     * @param view              控制台视图，非 null
     * @param bootstrap         已装配的引导器，非 null
     * @param providerConfig    本次尝试使用的模型配置，非 null
     * @param input             用户输入，非空白
     * @param cancellationToken 本轮取消令牌，非 null
     * @return 编排结果；非预期异常已就地报告时返回 null（调用方据此停止本轮，不再换模型）
     */
    private static ConversationResult converseOnce(ConsoleView view, ApplicationBootstrap bootstrap,
            ProviderConfiguration providerConfig, String input, CancellationToken cancellationToken) {
        long started = System.currentTimeMillis();
        // 传入工具用途解析器：调用工具时在日志里附一句「这次调用是为了什么」（结合本次入参推导）。
        ConsoleView.ConsoleStreamObserver observer = view.newObserver(bootstrap::describeToolInvocation);
        // 轮流式观察者与提示通道注册到线程：工作流节点执行时可复用（文本节点实时显示思考、各节点输出进度）。
        try (WorkflowProgress.Scope ignored1 = WorkflowProgress.open(observer, text -> {
                    observer.endStream();
                    view.emit(text, ConsoleView.ANSI_GRAY);
                })) {
            ConversationResult result = bootstrap.orchestrator().converse(ConversationRequest.builder()
                    .conversationId(conversationId)
                    .provider(providerConfig.getProvider())
                    .model(providerConfig.toConfig(CapabilityKindEnum.CHAT).getModelId())
                    .capabilityKind(CapabilityKindEnum.CHAT)
                    .userInput(input)
                    .providerConfiguration(providerConfig)
                    .stream(true)
                    .observer(observer)
                    .cancellationToken(cancellationToken)
                    .build());
            // 思考与正文已实时流式展示，这里只把完整记录写进详细日志文件（控制台不重复打印）。
            view.logTurn(result);
            // 记录本轮用量，形成 /usage 的累计趋势。
            recordUsageTrend(result.getUsage());
            // 回复段结束后空一行，避免状态行紧贴助手回复。
            if (observer.printed()) {
                view.println();
            }
            if (result.getToolRoundTrips() > 0) {
                view.emit("  [tool] 工具调用往返 " + result.getToolRoundTrips() + " 次", ConsoleView.ANSI_GRAY);
            }
            view.emit("  (status=" + result.getStatus() + (result.getUsage() == null ? "" : ", tokens=" + result.getUsage()) + ", 用时=" + (System.currentTimeMillis() - started) + "ms)", ConsoleView.ANSI_GRAY);
            if (result.getError() != null) {
                ClaraError failure = result.getError();
                String details = failure.getSafeDetails().isEmpty() ? "" : " details=" + failure.getSafeDetails();
                view.emit("  error: [" + failure.getCode() + "/" + failure.getCategory() + "] " + failure.getSafeMessage() + details, ConsoleView.ANSI_RED);
            }
            return result;
        } catch (RuntimeException turnFailure) {
            view.emit("[error] " + turnFailure.getClass().getSimpleName() + ": " + turnFailure.getMessage(), ConsoleView.ANSI_RED);
            return null;
        } finally {
            view.println();
        }
    }
}
