package com.clara;

import com.clara.adapter.console.ConsoleView;
import com.clara.adapter.console.LaunchOptions;
import com.clara.adapter.console.SessionCommandHandler;
import com.clara.config.ConfigLocator;
import com.clara.enums.CapabilityKindEnum;
import com.clara.exception.ClaraError;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.model.configuration.ProviderYamlConfigurationLoader;
import com.clara.orchestration.api.ConversationRequest;
import com.clara.orchestration.api.ConversationResult;
import com.clara.session.ModelSession;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

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
 * 模型状态交给 {@link ModelSession}。
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
 * {@code /model} 重新选择模型，{@code /log} 切换日志模式，{@code /color} 开关颜色，{@code exit} / {@code quit} 结束。
 *
 * <p>详细日志：{@link ConsoleView} 会把<b>完整</b>交互写入仓库根 {@code logs/clara-<时间戳>.log}，与显示模式无关。
 */
public final class ClaraApplication {

    private static String conversationId;

    /**
     * 进程入口：选择模型、装配各模块并进入交互式对话循环。
     *
     * @param args 可选；{@code -v}/{@code --full} 全量日志、{@code --no-color} 关闭颜色，
     *             其余首个参数视为模型配置文件路径（缺省则扫描目录并交互选择）
     * @throws Exception 输出流初始化失败时
     */
    public static void main(String[] args) throws Exception {
        conversationId = UUID.randomUUID().toString();
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        LaunchOptions options = LaunchOptions.parse(args);

        try (ConsoleView view = new ConsoleView(ConfigLocator.logsDirectory(), options.fullLogging(), options.forcedColor())) {
            List<Path> candidates = ConfigLocator.modelConfigs();
            ModelChoice choice = selectModelConfig(view, reader, options, candidates);
            runSession(view, reader, candidates, choice);
        }
    }

    /**
     * 装配模型与各模块，进入会话循环，并做启动异常兜底。
     *
     * @param view       控制台视图，非 null
     * @param reader     标准输入读取器，非 null
     * @param candidates 候选模型配置，非 null
     * @param choice     选中的模型配置与来源，非 null
     * @throws IOException 读取标准输入失败时
     */
    private static void runSession(ConsoleView view, BufferedReader reader, List<Path> candidates, ModelChoice choice) throws IOException {
        ProviderConfiguration initial = new ProviderYamlConfigurationLoader().load(choice.configPath());
        try (ApplicationBootstrap bootstrap = new ApplicationBootstrap(); ModelSession session = new ModelSession(bootstrap, candidates, initial, choice.configPath())) {
            bootstrap.workflowModelContext().use(initial, initial.toConfig(CapabilityKindEnum.CHAT).getModelId());
            SessionCommandHandler commands = new SessionCommandHandler(view, reader, session);

            view.printBanner(bootstrap, session.providerConfig(), choice.configPath(), choice.source());
            view.printHints();
            loop(view, reader, bootstrap, session, commands);
        } catch (RuntimeException startupFailure) {
            view.println("启动失败：" + startupFailure.getClass().getSimpleName() + ": " + startupFailure.getMessage());
            view.println("请确认：1) 所选模型配置存在且合法（当前 " + choice.configPath().toAbsolutePath() + "）；"
                    + "2) 其 credential 声明的环境变量已设置。");
            System.exit(1);
        }
    }

    /**
     * 逐行读取输入：指令交给处理器决定「结束 / 跳过 / 作为对话输入」，其余走一轮对话。
     *
     * @param view      控制台视图，非 null
     * @param reader    标准输入读取器，非 null
     * @param bootstrap 已装配的引导器，非 null
     * @param session   会话级模型状态，非 null
     * @param commands  会话内指令处理器，非 null
     * @throws IOException 读取标准输入失败时
     */
    private static void loop(ConsoleView view, BufferedReader reader, ApplicationBootstrap bootstrap, ModelSession session, SessionCommandHandler commands) throws IOException {
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
                converse(view, bootstrap, session.providerConfig(), input);
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
    private static ModelChoice selectModelConfig(ConsoleView view, BufferedReader reader,
                                                 LaunchOptions options, List<Path> candidates) {
        if (options.configFile() != null) {
            return new ModelChoice(ConfigLocator.locateFile(Paths.get(options.configFile())), "命令行参数");
        }
        String defaultModel = ConfigLocator.defaultModel();
        Path preferred = defaultModel == null ? null : ConfigLocator.resolveModel(candidates, defaultModel);
        if (preferred != null) {
            return new ModelChoice(preferred, "config/clara.yml 指定的主模型（" + defaultModel + "）");
        }
        if (defaultModel != null) {
            view.println("config/clara.yml 的 defaultModel=" + defaultModel + " 未匹配到任何模型配置，转为手动选择。");
        }
        if (candidates.isEmpty()) {
            view.println("未找到模型配置：请确认工作目录为仓库根，且 "
                    + ConfigLocator.modelDirectory().toAbsolutePath()
                    + " 下存在 *.yml / *.yaml（examples 样例会被忽略）。");
            System.exit(1);
            return new ModelChoice(Paths.get(""), "无可用模型配置");
        }
        return new ModelChoice(view.chooseModel(candidates, reader), "交互选择");
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
     * 执行一轮对话并打印回复与用量。
     *
     * @param view           控制台视图，非 null
     * @param bootstrap      已装配的引导器，非 null
     * @param providerConfig 当前模型配置，非 null
     * @param input          用户输入，非空白
     */
    private static void converse(ConsoleView view, ApplicationBootstrap bootstrap, ProviderConfiguration providerConfig, String input) {
        long started = System.currentTimeMillis();
        try {
            ConsoleView.ConsoleStreamObserver observer = view.newObserver();
            ConversationResult result = bootstrap.orchestrator().converse(ConversationRequest.builder()
                    .conversationId(conversationId)
                    .provider(providerConfig.getProvider())
                    .model(providerConfig.toConfig(CapabilityKindEnum.CHAT).getModelId())
                    .capabilityKind(CapabilityKindEnum.CHAT)
                    .userInput(input)
                    .providerConfiguration(providerConfig)
                    .stream(true)
                    .observer(observer)
                    .build());
            // 思考与正文已实时流式展示，这里只把完整记录补写进详细日志文件（控制台不重复打印）。
            view.logTurn(result);
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
        } catch (RuntimeException turnFailure) {
            view.emit("[error] " + turnFailure.getClass().getSimpleName() + ": " + turnFailure.getMessage(), ConsoleView.ANSI_RED);
        }
        view.println();
    }
}
