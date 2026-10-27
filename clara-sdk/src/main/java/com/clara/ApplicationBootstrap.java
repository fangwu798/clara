package com.clara;

import com.clara.codegraph.CodeGraphSupplement;
import com.clara.config.AlwaysPromptSettings;
import com.clara.config.CodeGraphSettings;
import com.clara.config.ConfigLocator;
import com.clara.config.IntentPlanSettings;
import com.clara.config.ModelRegistry;
import com.clara.config.TopicIsolationSettings;
import com.clara.context.ConvContext;
import com.clara.core.annotation.PublicApi;
import com.clara.core.diagnostics.Diagnostics;
import com.clara.core.memory.IContextSupplementProvider;
import com.clara.core.memory.IContextTraceSink;
import com.clara.core.telemetry.Telemetry;
import com.clara.core.telemetry.TelemetrySpan;
import com.clara.core.telemetry.TelemetrySpanKindEnum;
import com.clara.core.memory.IEmbeddingClient;
import com.clara.core.memory.ISemanticMemoryStore;
import com.clara.core.memory.ITokenCountEstimator;
import com.clara.core.memory.IWindowMemory;
import com.clara.core.message.IContent;
import com.clara.core.model.IStreamModel;
import com.clara.core.skill.IPromptProvider;
import com.clara.core.skill.IToolInvoker;
import com.clara.core.workflow.IWorkflowActionFactory;
import com.clara.core.workflow.IWorkflowEngine;
import com.clara.dto.memory.TopicIsolationConfig;
import com.clara.dto.skill.ToolMethod;
import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.ModelProviderEnum;
import com.clara.enums.interaction.HistoryClearScopeEnum;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.kernel.http.execute.Jdk8HttpTransport;
import com.clara.mcp.api.IMcpCapabilityCatalog;
import com.clara.mcp.api.IMcpClientFactory;
import com.clara.mcp.api.IMcpDiscoveryService;
import com.clara.mcp.api.IMcpGateway;
import com.clara.mcp.api.IMcpServerRegistry;
import com.clara.mcp.client.DefaultMcpClientFactory;
import com.clara.mcp.client.gateway.DefaultMcpGateway;
import com.clara.mcp.registry.DefaultMcpDiscoveryService;
import com.clara.mcp.registry.InMemoryMcpCapabilityCatalog;
import com.clara.mcp.registry.InMemoryMcpServerRegistry;
import com.clara.mcp.registry.McpServerRegistration;
import com.clara.mcp.registry.McpServerSnapshot;
import com.clara.mcp.registry.McpTransportProfile;
import com.clara.mcp.registry.config.McpServerConfig;
import com.clara.mcp.registry.config.McpServerConfigDirectoryLoader;
import com.clara.mcp.registry.config.McpServerConfigLoader;
import com.clara.mcp.registry.config.McpServerRegistrar;
import com.clara.mcp.tool.ExternalToolCatalog;
import com.clara.mcp.transport.JacksonMcpJsonCodec;
import com.clara.memory.persistence.ConversationStore;
import com.clara.memory.persistence.ConversationSnapshot;
import com.clara.memory.persistence.InMemoryConversationStore;
import com.clara.memory.persistence.local.LocalConversationStore;
import com.clara.memory.semantic.local.LocalSemanticMemoryStore;
import com.clara.memory.semantic.spi.ISemanticMemoryBackendProvider;
import com.clara.memory.semantic.spi.SemanticMemoryBackend;
import com.clara.memory.summarize.DefaultSummarizeCoordinator;
import com.clara.memory.window.ConversationIndexingWindowMemory;
import com.clara.memory.window.DefaultWindowMemory;
import com.clara.model.embedding.HashingEmbeddingClient;
import com.clara.model.provider.CompatibleStream;
import com.clara.model.token.TiktokenTokenCountEstimator;
import com.clara.orchestration.DefaultConversationOrchestrator;
import com.clara.orchestration.capability.CapabilityDispatcher;
import com.clara.orchestration.context.ContextAssembler;
import com.clara.orchestration.context.HistorySanitizer;
import com.clara.orchestration.context.SemanticRecallCoordinator;
import com.clara.orchestration.context.WindowCompressionCoordinator;
import com.clara.orchestration.intent.ExecutionPlanValidator;
import com.clara.orchestration.intent.IntentPlanCoordinator;
import com.clara.orchestration.record.CallRecordPublisher;
import com.clara.orchestration.skill.SkillMatchCoordinator;
import com.clara.orchestration.tool.ToolRoundTripCoordinator;
import com.clara.planner.ValidateExecutionPlanTool;
import com.clara.record.InMemoryCallRecordSink;
import com.clara.skill.executor.SkillPermissionAuthorizer;
import com.clara.skill.executor.SkillSchemaValidator;
import com.clara.skill.prompt.DefaultPromptProvider;
import com.clara.skill.registry.RegistryToolCatalog;
import com.clara.skill.registry.SkillLoader;
import com.clara.skill.registry.SkillRegistry;
import com.clara.skill.tool.DefaultToolExecutor;
import com.clara.skill.tool.DefaultToolInvoker;
import com.clara.skill.tool.ScriptExecutorRegistry;
import com.clara.skill.tool.builtin.BuiltinToolExecutor;
import com.clara.tool.CompositeToolCatalog;
import com.clara.tool.CompositeToolInvoker;
import com.clara.media.VideoDownloader;
import com.clara.usage.CapabilityUsageLedger;
import com.clara.usage.HistoryUsageSummary;
import com.clara.usage.IUsageLineColorizer;
import com.clara.usage.ProviderTokenUsage;
import com.clara.usage.TokenCountFormatter;
import com.clara.utils.StringUtils;
import com.clara.tool.McpToolCatalog;
import com.clara.tool.ToolInvocationPurpose;
import com.clara.workflow.AgentActionFactory;
import com.clara.workflow.PendingWorkflowRegistry;
import com.clara.workflow.PublishWorkflowTool;
import com.clara.workflow.ReviewActionFactory;
import com.clara.workflow.WorkflowCatalog;
import com.clara.workflow.WorkflowRunTool;
import com.clara.workflow.checkpoint.InMemoryCheckpointStore;
import com.clara.workflow.definition.WorkflowDefinitionLoader;
import com.clara.workflow.definition.WorkflowDefinitionRegistry;
import com.clara.workflow.engine.DefaultWorkflowEngine;
import com.clara.workflow.runtime.ToolActionFactory;
import com.clara.workflow.runtime.WorkflowRunner;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.function.Function;

/**
 * 程序装配入口：按默认配置构造各模块实现，并组合成可运行的对话编排能力。
 *
 * <p>是什么、在哪一层：application 的唯一装配点（{@code com.clara}）。进程启动时调用一次，把
 * core 定义的接口契约与 memory / skill / model / workflow / orchestration 各模块的具体实现在启动期绑定，
 * 避免运行时散落 {@code new}。
 *
 * <p>装配内容：model 提供 {@link IStreamModel} 与 {@link ITokenCountEstimator}；memory 提供窗口记忆工厂、
 * 会话存储、语义记忆存储与压缩编排；skill 提供提示词、工具调用与技能注册表；mcp 提供统一调用门面与外部
 * 工具路由目录（工具往返经 {@link CompositeToolInvoker} 在本地技能与外部工具间分派）；orchestration 侧由
 * {@code ContextAssembler} / {@code CapabilityDispatcher} / {@code ToolRoundTripCoordinator} /
 * {@code CallRecordPublisher} 组装 {@link DefaultConversationOrchestrator}。
 *
 * <p>工具声明：由 {@link RegistryToolCatalog} 从技能注册表取「已注册的全部工具」下发，模型自主选择调用；
 * 语义匹配只用于提示词注入与知识召回，不决定工具是否可见。
 *
 * <p>工作流配置化：工作流定义从 {@code config/workflow} 下的 YAML 加载（见 {@link WorkflowDefinitionRegistry}），
 * AGENT / TOOL 节点动作由 {@code AgentActionFactory} / {@code ToolActionFactory} 生成；内置工具
 * {@code run_workflow} 经 {@link WorkflowRunTool} 暴露给模型，据此以自然语言触发工作流（如读取本地目录代码
 * 并整理技术文档）。AGENT 节点所需的平台配置为会话级，由宿主在确定所选模型后经 {@link #modelRegistry()}
 * 选择当前模型（节点可再按 {@code role} / {@code model} 指定各自的模型）。
 *
 * <p>自定义工作流：模型生成的流程经内置工具 {@code publish_workflow}（{@link PublishWorkflowTool}）用同一套
 * 加载器校验后写入 {@code config/workflow/custom}；运行器在每次调用前按文件签名刷新定义注册表，因此
 * 「会话中生成、立即可用，且无需重启」。可用清单另由 {@link WorkflowCatalog} 拼进 {@code run_workflow} 的
 * 工具描述下发，模型据此在用户提到某个流程时按名调用。
 *
 * <p>媒体能力通过配置化工作流提供：AGENT 节点可声明 {@code IMAGE} / {@code VIDEO} 能力，生成结果以
 * 供应商返回的引用写入工作流作用域，不在 application 层维护独立媒体存储。
 *
 * <p>对话历史：窗口记忆由工作目录下的本地文件存储承载（{@code <工作目录>/.clara/state/conversations}，见
 * {@link LocalConversationStore}），因此「同一目录再次进入」能续接上次的上下文；存储同样惰性创建，且目录被
 * 占用或不可写时降级为进程内存储（对话照常，只是重启后不保留）。是否落盘可用
 * {@link #conversationHistoryDescription()} 查看。
 *
 * <p>调用记录：{@code ICallRecordSink} 由进程内实现 {@link InMemoryCallRecordSink} 提供，保留最近若干条
 * 记录供上层读取，同时按平台累计 token 用量（{@code /usage} 的平台明细即来自这里）；
 * {@code IWorkflowDefinitionResolver} 本阶段未接入，以 null 注入，编排层已做降级（不影响调用）。MCP 调用门面与外部工具路由目录已装配，宿主可通过 {@link #mcpGateway()} /
 * {@link #externalToolCatalog()} 登记外部工具；也可由 loadMcpServers(String, Path) 完成
 * 「配置加载 → 注册服务端 → 发现能力 → 登记外部工具」的一站式接入。
 *
 * <p>生命周期：构造本身不访问网络（仅装配各组件，MCP 服务端连接推迟到显式调用 {@link #loadMcpServers}），
 * 也不创建对话历史目录 / 不取历史文件锁（首次真正读写历史时才创建）；{@link #close()} 释放会话存储、
 * 语义记忆存储与工作流引擎，无状态组件（模型、估算器）自然随之释放。
 */
@PublicApi("SDK 装配入口")
public final class ApplicationBootstrap implements Closeable {

    /**
     * 语义记忆本地存储的向量维度，须与所用 {@link IEmbeddingClient} 的输出维度一致。
     */
    private static final int EMBEDDING_DIMENSION = 1024;

    /**
     * 供应商缺省标识：窗口记忆要求供应商非 null，未指定时以此占位。
     */
    private static final String DEFAULT_PROVIDER = "default";

    /**
     * {@code /usage} 模型列宽上限：超长模型标识按此宽度截断，避免单行过宽。
     */
    private static final int MODEL_COLUMN_MAX_WIDTH = 48;

    /**
     * MCP 客户端单次请求超时（毫秒）：装配期给出的默认值，随宿主登记服务端后生效。
     */
    private static final long MCP_REQUEST_TIMEOUT_MILLIS = 30_000L;

    /**
     * MCP 发现支持的协议版本列表：当前仅支持 {@code 2024-11-05}，服务端协商出其它版本将标记为不兼容。
     */
    private static final List<String> MCP_SUPPORTED_PROTOCOL_VERSIONS =
            Collections.singletonList("2024-11-05");

    /**
     * MCP 发现单类能力的最大分页页数，防止服务端返回无限分页。
     */
    private static final int MCP_DISCOVERY_MAX_PAGES = 16;

    /**
     * MCP 服务端配置的默认加载目录（相对运行目录）。
     */
    private static final Path DEFAULT_MCP_CONFIG_DIRECTORY = Paths.get("config", "mcp");

    /**
     * 工作流定义（YAML）的默认加载目录（相对运行目录）。
     */
    private static final Path DEFAULT_WORKFLOW_DIRECTORY = Paths.get("config", "workflow");

    private final IEmbeddingClient embeddingClient;
    private final ISemanticMemoryStore semanticMemoryStore;
    /**
     * 是否启用「对话记录写入语义记忆」：仅当 clara.yml 配置并成功连上 Milvus 时为 true；否则行为与原来一致。
     */
    private final boolean conversationIndexing;
    /**
     * 语义记忆的存放说明（local / milvus 及地址），用于启动横幅确认配置是否生效。
     */
    private final String semanticMemoryDescription;
    /**
     * 对话历史存储；首次真正需要读写历史时才创建，见 {@link #conversationStore()}。
     */
    private ConversationStore conversationStore;
    private final ITokenCountEstimator tokenCountEstimator;
    private final SkillRegistry skillRegistry;
    private final IMcpServerRegistry mcpServerRegistry;
    private final IMcpCapabilityCatalog mcpCapabilityCatalog;
    private final IMcpDiscoveryService mcpDiscoveryService;
    private final IMcpGateway mcpGateway;
    private final ExternalToolCatalog externalToolCatalog;
    private final IWorkflowEngine workflowEngine;
    private final ModelRegistry modelRegistry;
    private final WorkflowDefinitionRegistry workflowDefinitionRegistry;
    private final WorkflowRunner workflowRunner;
    /** 会话级待确认工作流登记表：工作流暂停后由宿主注入续接提示，不依赖模型记住上一条工具结果。 */
    private final PendingWorkflowRegistry pendingWorkflows = new PendingWorkflowRegistry();
    private final DefaultConversationOrchestrator orchestrator;
    private final DefaultConversationOrchestrator.WindowMemoryFactory windowMemoryFactory;
    private final WindowCompressionCoordinator windowCompression;
    private final CodeGraphSettings codeGraphSettings;
    private final InMemoryCallRecordSink callRecordSink;
    /**
     * 图片 / 视频 / 图谱的独立用量台账，由工作流动作写入、{@code /usage} 读取。
     */
    private final CapabilityUsageLedger capabilityUsage = new CapabilityUsageLedger();
    /** 链路追踪入口：由宿主注入，默认空实现。 */
    private final Telemetry telemetry;

    /**
     * 使用默认配置装配：离线哈希嵌入（{@link HashingEmbeddingClient}）、工作目录下的本地对话历史存储、
     * 进程内语义存储、tiktoken 估算器、内存检查点工作流引擎。
     */
    public ApplicationBootstrap() {
        this(new HashingEmbeddingClient(EMBEDDING_DIMENSION), EMBEDDING_DIMENSION);
    }

    /**
     * 使用默认装配，并接入上下文来源观测口（供控制台观察图谱 / 召回的执行情况）。
     *
     * @param contextTrace 上下文来源观测口；可为 null（不上报）
     */
    public ApplicationBootstrap(IContextTraceSink contextTrace) {
        this(new HashingEmbeddingClient(EMBEDDING_DIMENSION), EMBEDDING_DIMENSION, defaultMcpClientFactory(),
                contextTrace, Telemetry.noop());
    }

    /**
     * 使用默认装配，并接入上下文来源观测口与链路追踪。
     *
     * @param contextTrace 上下文来源观测口；可为 null（不上报）
     * @param telemetry    链路追踪入口；可为 null（不记录）
     */
    public ApplicationBootstrap(IContextTraceSink contextTrace, Telemetry telemetry) {
        this(new HashingEmbeddingClient(EMBEDDING_DIMENSION), EMBEDDING_DIMENSION, defaultMcpClientFactory(),
                contextTrace, telemetry);
    }

    /**
     * 指定嵌入客户端与向量维度装配，便于接入真实凭据（如 {@code HttpEmbeddingClient}）。
     *
     * @param embeddingClient    嵌入客户端，不可为 null
     * @param embeddingDimension 向量维度，须与嵌入客户端输出一致，必须为正
     * @throws IllegalArgumentException 参数非法时
     */
    public ApplicationBootstrap(IEmbeddingClient embeddingClient, int embeddingDimension) {
        this(embeddingClient, embeddingDimension, defaultMcpClientFactory(), null);
    }

    /**
     * 指定嵌入客户端、向量维度与 MCP 客户端工厂装配。
     *
     * <p>包内可见：生产走 {@link #ApplicationBootstrap(IEmbeddingClient, int)}（用默认工厂），
     * 测试可注入 fake 工厂以便在不连网络的前提下验证 MCP 接入链路。
     *
     * @param embeddingClient    嵌入客户端，不可为 null
     * @param embeddingDimension 向量维度，须与嵌入客户端输出一致，必须为正
     * @param mcpClientFactory   MCP 客户端工厂，不可为 null
     * @throws IllegalArgumentException 参数非法时
     */
    ApplicationBootstrap(IEmbeddingClient embeddingClient, int embeddingDimension,
                         IMcpClientFactory mcpClientFactory) {
        this(embeddingClient, embeddingDimension, mcpClientFactory, null);
    }

    /**
     * 指定嵌入客户端、向量维度、MCP 客户端工厂与上下文来源观测口装配。
     *
     * <p>包内可见：测试可注入 fake 工厂与自定义观测口，在不连网络的前提下验证 MCP 接入与上报链路。
     *
     * @param embeddingClient    嵌入客户端，不可为 null
     * @param embeddingDimension 向量维度，须与嵌入客户端输出一致，必须为正
     * @param mcpClientFactory   MCP 客户端工厂，不可为 null
     * @param contextTrace       上下文来源观测口；可为 null（不上报）
     * @throws IllegalArgumentException 参数非法时
     */
    ApplicationBootstrap(IEmbeddingClient embeddingClient, int embeddingDimension,
                         IMcpClientFactory mcpClientFactory, IContextTraceSink contextTrace) {
        this(embeddingClient, embeddingDimension, mcpClientFactory, contextTrace, Telemetry.noop());
    }

    /**
     * 指定嵌入客户端、向量维度、MCP 客户端工厂、上下文来源观测口与链路追踪装配。
     *
     * @param embeddingClient    嵌入客户端，不可为 null
     * @param embeddingDimension 向量维度，须与嵌入客户端输出一致，必须为正
     * @param mcpClientFactory   MCP 客户端工厂，不可为 null
     * @param contextTrace       上下文来源观测口；可为 null（不上报）
     * @param telemetry          链路追踪入口；可为 null（不记录）
     * @throws IllegalArgumentException 参数非法时
     */
    ApplicationBootstrap(IEmbeddingClient embeddingClient, int embeddingDimension,
                         IMcpClientFactory mcpClientFactory, IContextTraceSink contextTrace,
                         Telemetry telemetry) {
        if (embeddingClient == null) {
            throw new IllegalArgumentException("embeddingClient must not be null");
        }
        if (embeddingDimension <= 0) {
            throw new IllegalArgumentException("embeddingDimension must be positive");
        }
        if (mcpClientFactory == null) {
            throw new IllegalArgumentException("mcpClientFactory must not be null");
        }
        this.embeddingClient = embeddingClient;
        this.telemetry = telemetry == null ? Telemetry.noop() : telemetry;

        // 语义记忆：可选后端（如 Milvus）通过 ServiceLoader 发现；未引入或不可用时回退进程内实现。
        SemanticMemoryBackend optionalBackend = openSemanticBackend(embeddingDimension);
        this.semanticMemoryStore = optionalBackend == null
                ? new LocalSemanticMemoryStore(embeddingDimension) : optionalBackend.getStore();
        this.conversationIndexing = optionalBackend != null;
        this.semanticMemoryDescription = optionalBackend == null
                ? "进程内 local（不写对话记录）" : optionalBackend.getDescription();

        this.tokenCountEstimator = new TiktokenTokenCountEstimator(TiktokenTokenCountEstimator.DEFAULT_ENCODING);

        // clara-model：统一的模型调用入口（传输实现无状态、可全局共享）
        IStreamModel streamModel = new CompatibleStream(new Jdk8HttpTransport());

        // clara-skill：技能注册表（内置 + 运行期目录，描述向量化写入语义记忆）与提示词、工具调用
        this.skillRegistry = new SkillRegistry(SkillRegistry.resolveBuiltinRoot(),
                SkillRegistry.resolveRuntimeRoot(), new SkillLoader(), embeddingClient, semanticMemoryStore);
        this.skillRegistry.reload();
        IPromptProvider promptProvider = new DefaultPromptProvider(skillRegistry);

        // clara-mcp：注册表 + 能力目录 + 客户端工厂 → 统一调用门面 + 发现服务；外部工具路由目录供宿主 / 发现流程登记
        this.mcpServerRegistry = new InMemoryMcpServerRegistry();
        this.mcpCapabilityCatalog = new InMemoryMcpCapabilityCatalog();
        this.mcpGateway = new DefaultMcpGateway(mcpServerRegistry, mcpCapabilityCatalog, mcpClientFactory);
        this.mcpDiscoveryService = new DefaultMcpDiscoveryService(mcpServerRegistry, mcpCapabilityCatalog, mcpClientFactory, MCP_SUPPORTED_PROTOCOL_VERSIONS, MCP_DISCOVERY_MAX_PAGES);
        this.externalToolCatalog = new ExternalToolCatalog();

        // 工具调用：本地 skill 优先、外部 mcp 兜底，交给编排层的工具往返协调器
        // 脚本执行器注册表默认含 Python / JavaScript / Shell，稍后追加内置执行器（工具名 run_workflow）
        ScriptExecutorRegistry scriptExecutors = new ScriptExecutorRegistry();
        IToolInvoker localToolInvoker = new DefaultToolInvoker(new DefaultToolExecutor(skillRegistry, scriptExecutors, new SkillSchemaValidator(), new SkillPermissionAuthorizer(), 30_000L));
        IToolInvoker toolInvoker = new CompositeToolInvoker(skillRegistry, localToolInvoker, mcpGateway,
                externalToolCatalog, capabilityUsage, telemetry);
        // 意图识别与动态执行计划：复杂任务先生成计划，并交给内置工具 validate_execution_plan 校验；
        // 简单任务不生成计划，仍由主链路直接回答。开关见 config/clara.yml 的 intentPlanning 节点。
        IntentPlanSettings intentPlanSettings = IntentPlanSettings.load(ConfigLocator.claraConfigFile());
        ExecutionPlanValidator executionPlanValidator = new ExecutionPlanValidator(intentPlanSettings.getMaxSteps());
        IntentPlanCoordinator intentPlanCoordinator = intentPlanSettings.isEnabled()
                ? new IntentPlanCoordinator(streamModel, toolInvoker, executionPlanValidator,
                intentPlanSettings.getMaxRepairAttempts(), contextTrace)
                : null;

        // clara-memory：窗口压缩编排（把被淘汰历史交给模型总结为摘要）
        this.windowCompression = new WindowCompressionCoordinator(
                new DefaultSummarizeCoordinator(streamModel, promptProvider, telemetry));

        // orchestration 协作者
        // 语义记忆召回只在启用外部向量库（Milvus）时接线：关闭时对话记录根本不进语义库
        // （见下方 windowMemoryFactory 的 conversationIndexing），召回必然是空的——再跑一次空检索
        // 只会多打一行 [向量] 观测行，把「关闭的功能」留在日志里。
        SemanticRecallCoordinator semanticRecall = conversationIndexing
                ? new SemanticRecallCoordinator(embeddingClient, semanticMemoryStore, contextTrace, telemetry)
                : new SemanticRecallCoordinator(null, null, null, telemetry);
        // 代码知识图谱：装配提示词前先查一次图谱，命中的知识作为 system 补充信息注入；图谱里没有的，
        // 模型按 code_graph_guide 的约定改用工具查本地文件。开关在 config/clara.yml 的 codeGraph 节点。
        this.codeGraphSettings = CodeGraphSettings.load(ConfigLocator.claraConfigFile());
        IContextSupplementProvider codeGraphSupplement = codeGraphSettings.isSupplementEnabled()
                ? new CodeGraphSupplement(toolInvoker, codeGraphSettings, contextTrace) : null;
        // 工具描述按请求组装时现扫一遍工作流清单，使运行期新生成的自定义工作流无需重启即可被模型看到
        SkillMatchCoordinator skillMatch = new SkillMatchCoordinator(embeddingClient, semanticMemoryStore, promptProvider,
                new CompositeToolCatalog(
                        new RegistryToolCatalog(skillRegistry, WorkflowCatalog::decorateToolDescription),
                        new McpToolCatalog(mcpServerRegistry, mcpCapabilityCatalog)),
                contextTrace);
        // 常驻提示词：不走向量检索、每轮原样进 system（如「读代码先查知识图谱」这类固定约定）。
        // 为什么要单独一条通道见 {@link AlwaysPromptSettings} 的类注释。
        List<String> basePrompts = alwaysPrompts(AlwaysPromptSettings.load(ConfigLocator.claraConfigFile()),
                promptProvider, codeGraphSettings.isEnabled());
        // 话题隔离：默认启用。用户从 A 话题切到 B 话题时，旧话题轮次不进入本轮请求；
        // 旧话题仍可通过语义召回或用户显式引用带回。配置见 config/clara.yml 的 context.topicIsolation。
        TopicIsolationConfig topicIsolation = TopicIsolationSettings
                .load(ConfigLocator.claraConfigFile()).toConfig();
        ContextAssembler contextAssembler = new ContextAssembler(windowCompression, semanticRecall,
                codeGraphSupplement, skillMatch, basePrompts, topicIsolation, telemetry);
        CapabilityDispatcher capabilityDispatcher = new CapabilityDispatcher(streamModel);
        ToolRoundTripCoordinator toolRoundTrip = new ToolRoundTripCoordinator(toolInvoker, telemetry);
        // 调用记录接收方：进程内保留最近若干条记录，供上层读取（不打印、不落盘）
        this.callRecordSink = new InMemoryCallRecordSink();
        CallRecordPublisher callRecordPublisher = new CallRecordPublisher(callRecordSink);

        // 窗口记忆工厂：按「会话 + 供应商 + 模型」提供按会话隔离的窗口记忆实例
        this.windowMemoryFactory =
                (conversationId, provider, model) -> {
                    String effectiveProvider = provider == null ? DEFAULT_PROVIDER : provider;
                    IWindowMemory memory = new DefaultWindowMemory(conversationId, effectiveProvider, model,
                            conversationStore());
                    // 启用外部向量库时，顺手把每条对话记录写入语义记忆，供后续按会话召回。
                    return conversationIndexing
                            ? new ConversationIndexingWindowMemory(memory, embeddingClient, semanticMemoryStore)
                            : memory;
                };

        this.orchestrator = new DefaultConversationOrchestrator(windowMemoryFactory, contextAssembler,
                capabilityDispatcher, toolRoundTrip, callRecordPublisher, tokenCountEstimator,
                intentPlanCoordinator).telemetry(telemetry);

        // clara-workflow：默认内存检查点工作流引擎；IWorkflowDefinitionResolver 本阶段未接入
        this.workflowEngine = new DefaultWorkflowEngine(new InMemoryCheckpointStore(), 4, telemetry);

        // 工作流配置化装配：模型绑定注册表 + 动作工厂 → 定义目录加载 → 定义注册表 → 运行器 → 内置工具
        this.modelRegistry = new ModelRegistry(ConfigLocator.modelConfigs(), ConfigLocator.roleBindings());
        List<IWorkflowActionFactory> actionFactories = Arrays.asList(
                new AgentActionFactory(orchestrator, promptProvider, modelRegistry, new VideoDownloader(),
                        capabilityUsage),
                new ReviewActionFactory(orchestrator, promptProvider, modelRegistry),
                new ToolActionFactory(toolInvoker));
        this.workflowDefinitionRegistry = new WorkflowDefinitionRegistry(DEFAULT_WORKFLOW_DIRECTORY, new WorkflowDefinitionLoader(actionFactories));
        // 目录不存在时不加载、不报错
        this.workflowDefinitionRegistry.reload();
        this.workflowRunner = new WorkflowRunner(workflowEngine, workflowDefinitionRegistry);
        // 内置工具与本地工具调用共享同一脚本执行器注册表，故可在运行器就绪后再注册内置执行器：
        // run_workflow 负责按标识运行；publish_workflow 负责校验并发布模型生成的自定义工作流
        scriptExecutors.register(new BuiltinToolExecutor(Arrays.asList(
                new WorkflowRunTool(workflowRunner, pendingWorkflows), new PublishWorkflowTool(workflowDefinitionRegistry),
                new ValidateExecutionPlanTool(executionPlanValidator))));
    }

    /**
     * 返回装配好的对话编排入口。
     *
     * @return 对话编排器，非 null
     */
    public DefaultConversationOrchestrator orchestrator() {
        return orchestrator;
    }

    /**
     * 返回宿主注入的链路追踪入口，供 CLI 的命令层读取当前 Span。
     *
     * @return 追踪入口，非 null
     */
    public Telemetry telemetry() {
        return telemetry;
    }

    /**
     * 手动压缩指定会话的上下文：把当前全部历史交给模型归纳为一条摘要，并用该摘要替换历史。
     *
     * <p>用途：长对话历史（尤其工具结果）会随每轮请求重发、持续膨胀，占用大量上下文与费用。本方法提供
     * 「立即压缩」能力，供交互端 {@code /compress} 指令调用；压缩后后续轮次只携带摘要。
     *
     * <p>压缩本身是一次独立的同步模型调用（不进入历史、不产生调用记录，与超限自动压缩一致）；非法助手消息
     * 会先被净化，避免把脏历史发给模型。历史为空或压缩实现不可用时不做任何改动。
     *
     * @param conversationId        会话标识，不可为空白
     * @param providerConfiguration 当前平台配置（提供端点与凭据），不可为 null
     * @param model                 当前模型标识，不可为空白
     * @return 被归纳的历史消息条数；无可压缩内容时返回 0
     */
    public int compressConversation(String conversationId, ProviderConfiguration providerConfiguration, String model) {
        if (conversationId == null || conversationId.trim().isEmpty() || providerConfiguration == null || model == null || model.trim().isEmpty()) {
            return 0;
        }
        ModelProviderEnum provider = providerConfiguration.getProvider();
        IWindowMemory memory = windowMemoryFactory.create(conversationId, provider.name(), model);
        List<IContent> history = HistorySanitizer.dropInvalidAssistantMessages(memory.messages());
        if (history.isEmpty()) {
            return 0;
        }
        // 归纳走模型调用，需要绑定会话上下文（平台配置 + 能力种类），与编排层自动压缩时的绑定一致。
        IContent summary;
        ConvContext context = new ConvContext(null, conversationId, providerConfiguration, CapabilityKindEnum.CHAT);
        try (TelemetrySpan span = telemetry.startSpan("context.compress", TelemetrySpanKindEnum.CONTEXT)) {
            span.attribute("reason", "manual").attribute("messages", history.size());
            try (ConvContext.Scope ignored = ConvContext.open(context)) {
                summary = windowCompression.summarize(history);
            } catch (RuntimeException failure) {
                span.error(failure);
                throw failure;
            }
            span.attribute("summary", summary != null);
        }
        if (summary == null) {
            return 0;
        }
        memory.clear();
        memory.append(Collections.singletonList(summary));
        return history.size();
    }

    /**
     * 读取指定会话的历史消息（供启动回顾、{@code /history} 与方向键历史输入使用）。
     *
     * <p>为什么只读不写：展示与行编辑属于观测能力，不能改变上下文；真正参与请求的历史仍由编排层按窗口策略筛选。
     * 返回前会净化非法助手消息，避免半截工具调用之类的脏数据出现在展示里。
     *
     * @param conversationId        会话标识，不可为空白
     * @param providerConfiguration 当前平台配置，可为 null（返回空列表）
     * @param model                 当前模型标识，可为 null（返回空列表）
     * @return 历史消息，非 null；无历史或参数非法时为空列表
     */
    public List<IContent> conversationMessages(String conversationId, ProviderConfiguration providerConfiguration,
                                               String model) {
        if (conversationId == null || conversationId.trim().isEmpty()
                || providerConfiguration == null || model == null || model.trim().isEmpty()) {
            return Collections.emptyList();
        }
        IWindowMemory memory = windowMemoryFactory.create(conversationId,
                providerConfiguration.getProvider().name(), model);
        List<IContent> current = HistorySanitizer.dropInvalidAssistantMessages(memory.messages());
        List<ConversationSnapshot> snapshots = conversationStore().loadAllSnapshots();
        if (snapshots.isEmpty()) {
            return current;
        }
        // .clara 下的历史按「会话 + 供应商 + 模型」分文件：切换模型或 /new 之前的记录不会出现在当前键里，
        // 历史查看需要把它们一并带上，按文件最后写入时间拼接。
        List<IContent> merged = new ArrayList<>();
        for (ConversationSnapshot snapshot : snapshots) {
            merged.addAll(snapshot.messages());
        }
        return HistorySanitizer.dropInvalidAssistantMessages(merged);
    }

    /**
     * 清理历史聊天记录，并同步当前会话的语义记忆。
     *
     * <p>清理口径与 {@code /history} 一致（仅用户与助手消息）；持久化历史被重写或删除后，当前会话在语义库中的
     * 对话条目也必须删除，否则后续召回仍会带回已被用户清理的内容。语义记忆删除失败不影响历史清理结果，
     * 只输出告警——历史已经清掉，不应因为附加能力失败让用户误以为操作没生效。
     *
     * @param scope          清理范围，不可为 null
     * @param count          清理条数；{@link HistoryClearScopeEnum#ALL} 时忽略
     * @param conversationId 当前会话标识，可为 null（此时跳过语义记忆清理）
     * @return 实际清理的聊天消息条数
     */
    public int clearHistory(HistoryClearScopeEnum scope, int count, String conversationId) {
        int removed = conversationStore().deleteChatMessages(scope, count);
        if (conversationId == null || conversationId.trim().isEmpty()) {
            return removed;
        }
        try {
            semanticMemoryStore.deleteByConversation(conversationId);
        } catch (RuntimeException failure) {
            Diagnostics.warn("[memory] 清理会话语义记忆失败（历史已清理）：" + failure.getMessage());
        }
        return removed;
    }

    /**
     * 统计 token 用量说明（供交互端 {@code /usage} 展示）。
     *
     * <p>统计两类口径：
     * <ol>
     *   <li>当前会话全部历史记录占用的真实 token（模型响应返回后随消息落盘，跨重启有效）；</li>
     *   <li>本次会话内已发生的模型调用累计用量，并按「平台/模型」拆分（累计值不受调用记录容量淘汰影响）。</li>
     * </ol>
     *
     * @param conversationId        会话标识，不可为空白
     * @param providerConfiguration 当前平台配置，可为 null（未绑定时只统计进程内调用）
     * @param model                 当前模型标识，可为 null
     * @return 多行用量说明，非 null
     */
    public String conversationUsageReport(String conversationId, ProviderConfiguration providerConfiguration,
                                          String model) {
        return conversationUsageReport(conversationId, providerConfiguration, model, null);
    }

    /**
     * 生成会话用量报告，并允许宿主编排每一行的颜色。
     *
     * @param conversationId        会话标识，不可为空白
     * @param providerConfiguration 当前平台配置，可为 null（未绑定时只统计进程内调用）
     * @param model                 当前模型标识，可为 null
     * @param colorizer             行着色器；null 表示返回纯文本
     * @return 多行用量说明，非 null
     */
    public String conversationUsageReport(String conversationId, ProviderConfiguration providerConfiguration,
                                          String model, IUsageLineColorizer colorizer) {
        StringBuilder report = new StringBuilder();
        report.append("会话：").append(conversationId);
        appendHistorySummary(report, conversationId, providerConfiguration, model, colorizer);

        ProviderTokenUsage total = callRecordSink.totalUsage();
        String summary = "本次会话累计调用：" + total.getCalls() + " 次"
                + "（输入 " + TokenCountFormatter.format(total.getInputTokens())
                + " / 输出 " + TokenCountFormatter.format(total.getOutputTokens())
                + " / 合计 " + TokenCountFormatter.format(total.getTotalTokens()) + " tokens）";
        report.append('\n').append(colorize(colorizer, IUsageLineColorizer.TOTAL_KEY, summary));
        report.append('\n').append("按模型统计：");
        Map<String, ProviderTokenUsage> byModel = callRecordSink.usageByModel();
        if (byModel.isEmpty()) {
            report.append('\n').append("  （暂无模型调用）");
            return report.toString();
        }
        appendModelUsage(report, byModel, total, colorizer);
        return report.toString();
    }

    /**
     * 追加按模型统计的明细与总计行。
     *
     * @param report    报告缓冲，非 null
     * @param byModel   模型到用量的映射，非空
     * @param total     全部模型合计，非 null
     * @param colorizer 行着色器，可为 null
     */
    private static void appendModelUsage(StringBuilder report, Map<String, ProviderTokenUsage> byModel,
                                         ProviderTokenUsage total, IUsageLineColorizer colorizer) {
        int columnWidth = modelColumnWidth(byModel);
        appendModelRows(report, byModel, columnWidth, colorizer, ApplicationBootstrap::usageText);
        String totalLine = "  " + StringUtils.padRight("总计", columnWidth) + usageText(total);
        report.append('\n').append(colorize(colorizer, IUsageLineColorizer.TOTAL_KEY, totalLine));
    }

    /**
     * 逐行追加「模型 + 用量」明细，不输出总计行。
     *
     * @param report      报告缓冲，非 null
     * @param byModel     模型到用量的映射，非空
     * @param columnWidth 模型列宽
     * @param colorizer   行着色器，可为 null
     * @param formatter   单条用量的渲染函数，非 null
     */
    private static void appendModelRows(StringBuilder report, Map<String, ProviderTokenUsage> byModel,
                                        int columnWidth, IUsageLineColorizer colorizer,
                                        Function<ProviderTokenUsage, String> formatter) {
        for (Map.Entry<String, ProviderTokenUsage> entry : byModel.entrySet()) {
            String line = "  " + StringUtils.padRight(fitModelLabel(entry.getKey(), columnWidth), columnWidth)
                    + formatter.apply(entry.getValue());
            report.append('\n').append(colorize(colorizer, entry.getKey(), line));
        }
    }

    /**
     * 渲染一份用量的调用次数与 token 明细。
     *
     * @param usage 用量快照，非 null
     * @return 形如 {@code  调用 1 次，输入 ...，合计 ... tokens} 的文本，非 null
     */
    private static String usageText(ProviderTokenUsage usage) {
        return " 调用 " + usage.getCalls() + " 次"
                + "，输入 " + TokenCountFormatter.format(usage.getInputTokens())
                + "，输出 " + TokenCountFormatter.format(usage.getOutputTokens())
                + "，合计 " + TokenCountFormatter.format(usage.getTotalTokens()) + " tokens";
    }

    /**
     * 渲染一条历史记录的用量明细。
     *
     * <p>历史以「助手回答」为单位落盘，一轮内的多次模型调用已经合并，因此这里按回答条数展示。
     *
     * @param usage 用量快照，非 null
     * @return 形如 {@code N 条回答，输入 ...，合计 ... tokens} 的文本，非 null
     */
    private static String historyUsageText(ProviderTokenUsage usage) {
        return " " + usage.getCalls() + " 条回答"
                + "，输入 " + TokenCountFormatter.format(usage.getInputTokens())
                + "，输出 " + TokenCountFormatter.format(usage.getOutputTokens())
                + "，合计 " + TokenCountFormatter.format(usage.getTotalTokens()) + " tokens";
    }

    /**
     * 计算模型列宽：取最长模型标识的显示宽度，并留出总计行所需宽度。
     *
     * @param byModel 模型到用量的映射，非空
     * @return 列宽，非负
     */
    private static int modelColumnWidth(Map<String, ProviderTokenUsage> byModel) {
        int width = StringUtils.displayWidth("总计");
        for (String key : byModel.keySet()) {
            width = Math.max(width, StringUtils.displayWidth(key));
        }
        return Math.min(width, MODEL_COLUMN_MAX_WIDTH);
    }

    /**
     * 按显示宽度截断超长模型标识，避免单行过宽。
     *
     * @param label 模型标识，非 null
     * @param width 最大显示宽度
     * @return 不超过指定宽度的标签，非 null
     */
    private static String fitModelLabel(String label, int width) {
        if (StringUtils.displayWidth(label) <= width) {
            return label;
        }
        StringBuilder fitted = new StringBuilder();
        int used = 0;
        for (int index = 0; index < label.length(); index++) {
            char value = label.charAt(index);
            int charWidth = value > 0xFF ? 2 : 1;
            if (used + charWidth > width - 1) {
                break;
            }
            fitted.append(value);
            used += charWidth;
        }
        return fitted.append('…').toString();
    }

    /**
     * 调用宿主的行着色器；未提供或返回 null 时保留原始行。
     *
     * @param colorizer 行着色器，可为 null
     * @param key       系列标识，非 null
     * @param line      原始行，非 null
     * @return 着色后的行，非 null
     */
    private static String colorize(IUsageLineColorizer colorizer, String key, String line) {
        if (colorizer == null) {
            return line;
        }
        String colored = colorizer.colorize(key, line);
        return colored == null ? line : colored;
    }

    /**
     * 追加历史记录的 token 摘要与按模型明细。
     *
     * <p>只累加消息落盘时记录的真实用量；升级前没有用量的旧消息只计数提示，不做估算。
     *
     * @param report                报告缓冲，非 null
     * @param conversationId        会话标识，不可为空白
     * @param providerConfiguration 当前平台配置，可为 null
     * @param model                 当前模型标识，可为 null
     * @param colorizer             行着色器，可为 null
     */
    private void appendHistorySummary(StringBuilder report, String conversationId,
                                      ProviderConfiguration providerConfiguration, String model,
                                      IUsageLineColorizer colorizer) {
        if (providerConfiguration == null || model == null || model.trim().isEmpty()) {
            report.append('\n').append("历史记录：未知（未绑定模型配置）");
            return;
        }
        ModelProviderEnum provider = providerConfiguration.getProvider();
        IWindowMemory memory = windowMemoryFactory.create(conversationId, provider.name(), model);
        List<IContent> history = HistorySanitizer.dropInvalidAssistantMessages(memory.messages());
        HistoryUsageSummary summary = HistoryUsageSummary.of(history);
        report.append('\n').append(summary.summaryLine());
        Map<String, ProviderTokenUsage> byModel = summary.getByModel();
        if (!byModel.isEmpty()) {
            report.append('\n').append("历史按模型统计：");
            appendModelRows(report, byModel, modelColumnWidth(byModel), colorizer,
                    ApplicationBootstrap::historyUsageText);
        }
    }

    /**
     * 返回装配好的调用记录接收方。
     *
     * @return 进程内调用记录接收方，非 null
     */
    public InMemoryCallRecordSink callRecordSink() {
        return callRecordSink;
    }

    /**
     * 图片 / 视频 / 图谱的独立用量台账（{@code /usage} 与工作流节点共用同一实例）。
     *
     * @return 非 null
     */
    public CapabilityUsageLedger capabilityUsage() {
        return capabilityUsage;
    }

    /**
     * 对话历史的存放说明，用于启动横幅：确认历史是否落盘、落在哪里。
     *
     * <p>调用它即触发历史存储的创建（见 {@link #conversationStore()}），因此启动横幅上就能看到「历史能否
     * 持久化」，而不是等第一次对话失败才发现目录被占用。
     *
     * @return 一行说明，非 null
     */
    public String conversationHistoryDescription() {
        if (conversationStore() instanceof LocalConversationStore) {
            return "本地持久化 " + ConfigLocator.workspaceRoot()
                    .resolve(".clara").resolve("state").resolve("conversations")
                    + "（同一目录再次进入可续接，删除该目录即清空）";
        }
        return "仅进程内（退出即丢失）";
    }

    /**
     * 语义记忆的存放说明，用于启动横幅：确认 clara.yml 里的 milvus 配置是否生效。
     *
     * @return 一行说明，非 null
     */
    public String semanticMemoryDescription() {
        return semanticMemoryDescription;
    }

    /**
     * 推导一次工具调用的用途说明，供控制台在「调用工具」日志里简要说明本次调用目的。
     *
     * <p>工具声明里的描述只能说明「这个工具是干什么的」，同一声明覆盖多种用法（如 {@code run_command} 既可编译
     * 也可查看 Git 状态），故这里结合本次入参推导「这次调用是为了什么」，规则见
     * {@link ToolInvocationPurpose}；描述随 {@link SkillRegistry} 的热加载而刷新。
     *
     * @param toolName      工具名，可为 null
     * @param argumentsJson 本次调用的入参 JSON，可为 null
     * @return 一句用途说明；无可展示内容时返回 null
     */
    public String describeToolInvocation(String toolName, String argumentsJson) {
        ToolMethod method = toolName == null || toolName.trim().isEmpty()
                ? null : skillRegistry.findToolByName(toolName.trim());
        String declared = method == null ? null : method.getDescription();
        return ToolInvocationPurpose.describe(toolName, argumentsJson, declared);
    }

    /**
     * 返回装配好的工作流引擎。
     *
     * @return 工作流引擎，非 null
     */
    public IWorkflowEngine workflowEngine() {
        return workflowEngine;
    }

    /**
     * 返回装配好的工作流定义注册表，宿主可据此查询已加载的工作流标识。
     *
     * @return 工作流定义注册表，非 null
     */
    public WorkflowDefinitionRegistry workflowDefinitionRegistry() {
        return workflowDefinitionRegistry;
    }

    /**
     * 返回装配好的工作流运行器。
     *
     * @return 工作流运行器，非 null
     */
    public WorkflowRunner workflowRunner() {
        return workflowRunner;
    }

    /**
     * 返回会话级待确认工作流登记表。
     *
     * <p>交互端在每轮请求前用 {@link PendingWorkflowRegistry#hint(String)} 取提示，注入为临时系统提示，
     * 使工作流暂停后即使发生模型故障转移，也能继续用同一实例标识续接。
     *
     * @return 登记表，非 null
     */
    public PendingWorkflowRegistry pendingWorkflows() {
        return pendingWorkflows;
    }

    /**
     * 返回代码知识图谱开关（{@code config/clara.yml} 的 {@code codeGraph} 节点）。
     *
     * @return 开关配置，非 null
     */
    public CodeGraphSettings codeGraphSettings() {
        return codeGraphSettings;
    }

    /**
     * 解析常驻提示词：把配置里的标识取成提示词文本，取不到的跳过——配错了不该挡住对话。
     *
     * <p>图谱关闭时跳过图谱使用约定：那条约定通篇在讲怎么用图谱，图谱不可用时留着它只会把模型
     * 引向一个会报错的工具。
     *
     * @param settings         常驻提示词开关，不可为 null
     * @param promptProvider   提示词提供器，不可为 null
     * @param codeGraphEnabled 代码知识图谱总开关是否打开
     * @return 提示词文本列表，可能为空，非 null
     */
    private static List<String> alwaysPrompts(AlwaysPromptSettings settings, IPromptProvider promptProvider,
                                              boolean codeGraphEnabled) {
        List<String> prompts = new ArrayList<>();
        for (String id : settings.getIds()) {
            if (!AlwaysPromptSettings.CODE_GRAPH_GUIDE.equals(id) || codeGraphEnabled) {
                addPrompt(prompts, promptProvider, id);
            }
        }
        return prompts;
    }

    /**
     * 读取一条常驻提示词并追加到结果；单条不可用时跳过，不影响其余提示词。
     */
    private static void addPrompt(List<String> prompts, IPromptProvider promptProvider, String id) {
        try {
            String prompt = promptProvider.prompt(id);
            if (prompt != null && !prompt.trim().isEmpty()) {
                prompts.add(prompt);
            }
        } catch (RuntimeException unavailable) {
            // 标识写错或提示词文件缺失：跳过这一条，其余常驻提示词照常生效。
        }
    }

    /**
     * 返回模型绑定注册表，宿主据此把默认模型（及可选角色）绑定给工作流 AGENT 节点。
     *
     * @return 模型绑定注册表，非 null
     */
    public ModelRegistry modelRegistry() {
        return modelRegistry;
    }

    /**
     * 返回装配好的 embedding 客户端。
     *
     * @return embedding 客户端，非 null
     */
    public IEmbeddingClient embeddingClient() {
        return embeddingClient;
    }

    /**
     * 返回装配好的 MCP 调用门面，便于宿主登记外部工具后由工具往返调用。
     *
     * @return MCP 调用门面，非 null
     */
    public IMcpGateway mcpGateway() {
        return mcpGateway;
    }

    /**
     * 返回外部工具路由目录，宿主 / 发现流程在发现外部工具后向其登记绑定。
     *
     * @return 外部工具路由目录，非 null
     */
    public ExternalToolCatalog externalToolCatalog() {
        return externalToolCatalog;
    }

    /**
     * 返回 MCP 服务端注册表，宿主可据此检查某租户下已登记的服务端。
     *
     * @return MCP 服务端注册表，非 null
     */
    public IMcpServerRegistry mcpServerRegistry() {
        return mcpServerRegistry;
    }

    /**
     * 返回 MCP 能力目录，保存各服务端发现出的能力快照。
     *
     * @return MCP 能力目录，非 null
     */
    public IMcpCapabilityCatalog mcpCapabilityCatalog() {
        return mcpCapabilityCatalog;
    }

    /**
     * 返回 MCP 发现服务，可对已登记服务端单独触发刷新发现。
     *
     * @return MCP 发现服务，非 null
     */
    public IMcpDiscoveryService mcpDiscoveryService() {
        return mcpDiscoveryService;
    }

    /**
     * 从默认目录 {@code config/mcp}（相对运行目录）加载并接入 MCP 服务端。
     *
     * @return 成功发现的服务端数，非负
     * @throws IllegalArgumentException 配置目录非法或配置加载失败时
     */
    public int loadMcpServers() {
        return loadMcpServers(ConfigLocator.locate(DEFAULT_MCP_CONFIG_DIRECTORY));
    }

    /**
     * 完成「配置加载 → 注册服务端 → 发现能力 → 登记外部工具」的一站式接入链路。
     *
     * <p>流程：用 {@link McpServerConfigDirectoryLoader} 递归加载 {@code configDirectory} 下的 {@code *.json}
     * 配置；用 {@link McpServerRegistrar#registerAll(List)} 把配置注册进 {@link IMcpServerRegistry}；
     * 对每个已注册服务端调用 {@link IMcpDiscoveryService#refresh(String)} 完成发现（发布快照并更新状态）；
     * 再从 {@link IMcpCapabilityCatalog#latest(String)} 取回快照，用
     * {@link ExternalToolCatalog#registerSnapshot(McpServerSnapshot)} 把其工具登记进路由目录。
     *
     * <p>失败隔离：单个服务端的发现失败（协议不兼容、身份变化、连接异常等）会被捕获并跳过，不影响其余服务端，
     * 也不阻断方法返回；返回值只统计发现成功的服务端数。发现失败时注册状态已由发现服务按需更新为
     * {@code INCOMPATIBLE} / {@code REVIEW_REQUIRED}。配置加载失败属装配期错误，会直接抛出。
     *
     * <p>本方法会按需连接配置中的服务端，故不在构造阶段调用。
     *
     * @param configDirectory 配置目录，不可为 null；不存在或无 {@code *.json} 时相当于不接入任何服务端
     * @return 成功发现的服务端数，非负
     * @throws IllegalArgumentException 配置目录为 null 或配置加载失败时
     */
    public int loadMcpServers(Path configDirectory) {
        if (configDirectory == null) {
            throw new IllegalArgumentException("configDirectory must not be null");
        }
        List<McpServerConfig> configs = new McpServerConfigDirectoryLoader(
                new McpServerConfigLoader(), configDirectory).load();
        List<McpServerRegistration> registrations =
                new McpServerRegistrar(mcpServerRegistry).registerAll(configs);
        try (TelemetrySpan load = telemetry.startSpan("mcp.load", TelemetrySpanKindEnum.OTHER)) {
            load.attribute("configs", configs.size()).attribute("servers", registrations.size());
            int discovered = 0;
            int failed = 0;
            for (McpServerRegistration registration : registrations) {
                String serverKey = registration.getServerKey();
                try (TelemetrySpan span = telemetry.startSpan("mcp.discovery", TelemetrySpanKindEnum.OTHER)) {
                    span.attribute("server", serverKey);
                    try {
                        mcpDiscoveryService.refresh(serverKey);
                        McpServerSnapshot snapshot = mcpCapabilityCatalog.latest(serverKey);
                        if (snapshot != null) {
                            externalToolCatalog.registerSnapshot(snapshot);
                            span.attribute("tools", snapshot.getTools().size())
                                    .attribute("resources", snapshot.getResources().size())
                                    .attribute("prompts", snapshot.getPrompts().size());
                        }
                        discovered++;
                    } catch (RuntimeException failure) {
                        // 单个服务端发现失败不中断其余：链路记录失败原因后继续。
                        failed++;
                        span.error(failure);
                    }
                }
            }
            load.attribute("discovered", discovered).attribute("failed", failed);
            return discovered;
        }
    }

    /**
     * 创建 MCP 客户端工厂：按服务端注册项的传输方式创建客户端。
     *
     * <p>允许执行的命令由 {@link #allowedStdioExecutables()} 现扫配置得出，不再写死单个命令：stdio 传输会校验
     * 「首个命令是否为允许清单中真实存在的绝对路径」，写死一个命令意味着每接入一个 stdio 服务端都要改代码。
     *
     * <p>工作目录取进程当前目录：MCP 服务端多为「按相对路径读取配置 / 缓存」的实现，与操作台同根最直观。
     *
     * @return MCP 客户端工厂，非 null
     */
    private static IMcpClientFactory defaultMcpClientFactory() {
        return new DefaultMcpClientFactory(new JacksonMcpJsonCodec(), null,
                allowedStdioExecutables(), Paths.get("").toAbsolutePath(), null,
                MCP_REQUEST_TIMEOUT_MILLIS);
    }

    /**
     * 解析允许以 stdio 方式启动的可执行文件清单。
     *
     * <p>来源三处：{@code config/mcp} 下各 stdio 服务端配置声明的首个命令、环境变量
     * {@code CLARA_MCP_ALLOWED_EXECUTABLES}（分号或逗号分隔）、历史默认值 {@code node}。候选一律解析为
     * 真实存在的绝对路径后才纳入清单——{@code StdioMcpTransport} 会对清单逐项取真实路径，清单里混入不存在的
     * 项会让所有 stdio 服务端一起失败。
     *
     * <p>为什么保留 {@code node} 兜底：清单为空时工厂构造即抛错。解析不到任何可执行文件时退回「当前目录下的
     * node」占位，使装配得以完成、只在真正调用 stdio 服务端时按「不在允许清单」报错。
     *
     * @return 允许清单，非 null、非空
     */
    private static List<Path> allowedStdioExecutables() {
        Set<Path> allowed = new LinkedHashSet<>();
        Set<String> seen = new LinkedHashSet<>();
        try {
            List<McpServerConfig> configs = new McpServerConfigDirectoryLoader(
                    new McpServerConfigLoader(), ConfigLocator.locate(DEFAULT_MCP_CONFIG_DIRECTORY)).load();
            for (McpServerConfig config : configs) {
                if (config.getTransportProfile() != McpTransportProfile.STDIO || config.getCommand().isEmpty()) {
                    continue;
                }
                addExecutable(allowed, seen, config.getCommand().get(0));
            }
        } catch (RuntimeException ignored) {
            // 配置目录不存在或配置非法不阻断装配：MCP 服务端本就允许「一个都不接」
        }
        String extra = System.getenv("CLARA_MCP_ALLOWED_EXECUTABLES");
        if (extra != null) {
            for (String part : extra.split("[;,]")) {
                addExecutable(allowed, seen, part);
            }
        }
        addExecutable(allowed, seen, "node");
        if (allowed.isEmpty()) {
            allowed.add(Paths.get("node").toAbsolutePath());
        }
        return Collections.unmodifiableList(new ArrayList<>(allowed));
    }

    /**
     * 解析单个候选命令并纳入允许清单（同一命令只解析一次）。
     *
     * @param allowed 允许清单累积集合，不可为 null
     * @param seen    已解析过的原始命令名，不可为 null
     * @param token   候选命令名或路径，可为 null / 空白（此时忽略）
     */
    private static void addExecutable(Set<Path> allowed, Set<String> seen, String token) {
        if (token == null || token.trim().isEmpty() || !seen.add(token.trim())) {
            return;
        }
        Path resolved = resolveExecutable(token.trim());
        if (resolved != null) {
            allowed.add(resolved);
        }
    }

    /**
     * 把命令名解析为绝对真实路径：绝对路径直接校验；否则按 PATH 逐目录查找，命令名不含扩展名时按 PATHEXT 补全
     * （Windows 下 {@code node} 实际是 {@code node.exe}）。
     *
     * @param token 命令名或路径，不可为空白
     * @return 绝对真实路径；未找到或不是普通文件时返回 null
     */
    private static Path resolveExecutable(String token) {
        Path direct = Paths.get(token);
        if (direct.isAbsolute()) {
            return realFile(direct);
        }
        String pathValue = System.getenv("PATH");
        if (pathValue == null) {
            return null;
        }
        return searchPath(pathValue, executableCandidates(token));
    }

    private static List<String> executableCandidates(String token) {
        List<String> names = new ArrayList<>();
        // Windows 上先按 PATHEXT 补全、裸名字放最后：nodejs 目录里同时存在无扩展名的 npx（Unix shell 脚本）
        // 与可执行的 npx.cmd，先试裸名字会选中那个脚本，最终以「不是有效的 Win32 应用程序」启动失败。
        if (token.indexOf('.') < 0) {
            String pathExt = System.getenv("PATHEXT");
            if (pathExt != null) {
                for (String ext : pathExt.split(";")) {
                    String trimmed = ext.trim();
                    if (!trimmed.isEmpty()) {
                        names.add(token + trimmed);
                    }
                }
            }
        }
        names.add(token);
        return names;
    }

    private static Path searchPath(String pathValue, List<String> names) {
        for (String directory : pathValue.split(File.pathSeparator)) {
            String trimmed = directory.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            for (String name : names) {
                Path found = realFile(Paths.get(trimmed, name));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /**
     * 校验候选是否为普通文件，是则返回其真实路径。
     *
     * @param candidate 候选路径，不可为 null
     * @return 真实路径；不存在或不可解析时返回 null
     */
    private static Path realFile(Path candidate) {
        try {
            return Files.isRegularFile(candidate) ? candidate.toRealPath() : null;
        } catch (IOException notFound) {
            return null;
        }
    }

    /**
     * 取对话历史存储，首次调用时才创建。
     *
     * <p>为什么惰性：「只装配、不对话」的场景（单元测试、仅做配置检查）不该在工作目录下生成 {@code .clara}
     * 目录、更不该抢占历史文件锁，否则同机运行第二个实例会互相干扰。
     *
     * @return 对话历史存储，非 null
     */
    private synchronized ConversationStore conversationStore() {
        if (conversationStore == null) {
            conversationStore = createConversationStore();
        }
        return conversationStore;
    }

    /**
     * 创建对话历史存储：优先用工作目录下的本地文件存储；目录被其他实例占用或不可写时降级为进程内存储。
     *
     * <p>降级只影响「重启后能否续接历史」，不阻断对话，故提示一次后继续。
     */
    private static ConversationStore createConversationStore() {
        try {
            return new LocalConversationStore(ConfigLocator.workspaceRoot());
        } catch (RuntimeException unavailable) {
            Diagnostics.warn("[memory] 本地对话历史不可用，本次运行的历史不会保留："
                    + unavailable.getMessage());
            return new InMemoryConversationStore();
        }
    }

    /**
     * 发现并打开可选的语义记忆后端。
     *
     * <p>后端模块通过 {@link ServiceLoader} 注册；某个提供者返回 null（未配置 / 不可用）时继续尝试
     * 下一个，全部失败则返回 null，由调用方回退到进程内实现。未引入可选模块时不会报错。
     *
     * @param dimension 默认向量维度
     * @return 已打开的后端；没有可用后端时 null
     */
    private static SemanticMemoryBackend openSemanticBackend(int dimension) {
        Path claraConfig = ConfigLocator.claraConfigFile();
        String projectName = ConfigLocator.projectName();
        for (ISemanticMemoryBackendProvider provider : ServiceLoader.load(ISemanticMemoryBackendProvider.class)) {
            SemanticMemoryBackend backend = provider.open(claraConfig, dimension, projectName);
            if (backend != null) {
                return backend;
            }
        }
        return null;
    }

    @Override
    public void close() {
        closeQuietly(modelRegistry);
        closeQuietly(workflowEngine);
        closeQuietly(semanticMemoryStore);
        if (conversationStore != null) {
            conversationStore.close();
        }
    }

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException | RuntimeException | LinkageError ignored) {
            // 释放失败不阻断关闭：可选的图谱 / 向量库后端可能因为依赖类缺失抛 LinkageError，
            // 关闭阶段再抛出去只会掩盖真正的退出原因。
        }
    }
}
