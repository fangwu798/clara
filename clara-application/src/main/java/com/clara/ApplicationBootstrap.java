package com.clara;

import com.clara.config.ConfigLocator;
import com.clara.config.ModelRegistry;
import com.clara.api.IBuiltinTool;
import com.clara.memory.semantic.milvus.MilvusSettings;
import com.clara.context.ConvContext;
import com.clara.core.memory.IEmbeddingClient;
import com.clara.core.memory.ISemanticMemoryStore;
import com.clara.core.memory.ITokenCountEstimator;
import com.clara.core.memory.IWindowMemory;
import com.clara.core.message.IContent;
import com.clara.core.model.IResponse;
import com.clara.core.skill.IPromptProvider;
import com.clara.core.skill.IToolInvoker;
import com.clara.core.workflow.IWorkflowActionFactory;
import com.clara.core.workflow.IWorkflowEngine;
import com.clara.dto.record.CallRecord;
import com.clara.dto.skill.ToolMethod;
import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.ModelProviderEnum;
import com.clara.core.model.IStreamModel;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.kernel.http.HttpResponse;
import com.clara.kernel.http.execute.Jdk8HttpTransport;
import com.clara.mcp.client.gateway.DefaultMcpGateway;
import com.clara.mcp.client.gateway.McpGateway;
import com.clara.mcp.client.DefaultMcpClientFactory;
import com.clara.mcp.registry.DefaultMcpDiscoveryService;
import com.clara.mcp.registry.InMemoryMcpCapabilityCatalog;
import com.clara.mcp.registry.InMemoryMcpServerRegistry;
import com.clara.mcp.registry.McpCapabilityCatalog;
import com.clara.mcp.registry.McpDiscoveryService;
import com.clara.mcp.registry.McpServerRegistration;
import com.clara.mcp.registry.McpServerRegistry;
import com.clara.mcp.registry.McpServerSnapshot;
import com.clara.mcp.registry.config.McpServerConfig;
import com.clara.mcp.registry.config.McpServerConfigDirectoryLoader;
import com.clara.mcp.registry.config.McpServerConfigLoader;
import com.clara.mcp.registry.config.McpServerRegistrar;
import com.clara.mcp.client.McpClientFactory;
import com.clara.mcp.transport.JacksonMcpJsonCodec;
import com.clara.memory.persistence.ConversationStore;
import com.clara.memory.persistence.InMemoryConversationStore;
import com.clara.memory.persistence.local.LocalConversationStore;
import com.clara.memory.semantic.local.LocalSemanticMemoryStore;
import com.clara.memory.semantic.milvus.MilvusBackend;
import com.clara.memory.semantic.milvus.MilvusBackendConfig;
import com.clara.memory.semantic.milvus.MilvusBackendFactory;
import com.clara.memory.semantic.milvus.MilvusCollectionSpec;
import com.clara.memory.semantic.milvus.MilvusSemanticMemoryStore;
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
import com.clara.orchestration.record.CallRecordPublisher;
import com.clara.orchestration.skill.SkillMatchCoordinator;
import com.clara.orchestration.tool.ToolRoundTripCoordinator;
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
import com.clara.tool.CompositeToolInvoker;
import com.clara.mcp.tool.ExternalToolCatalog;
import com.clara.mcp.tool.McpExternalToolRegistrar;
import com.clara.tool.ToolInvocationPurpose;
import com.clara.workflow.AgentActionFactory;
import com.clara.workflow.PublishWorkflowTool;
import com.clara.workflow.ReviewActionFactory;
import com.clara.workflow.WorkflowCatalog;
import com.clara.workflow.runtime.ToolActionFactory;
import com.clara.workflow.WorkflowRunTool;
import com.clara.workflow.runtime.WorkflowRunner;
import com.clara.workflow.checkpoint.InMemoryCheckpointStore;
import com.clara.workflow.definition.WorkflowDefinitionLoader;
import com.clara.workflow.definition.WorkflowDefinitionRegistry;
import com.clara.workflow.engine.DefaultWorkflowEngine;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

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
 * 记录供上层读取；{@code IWorkflowDefinitionResolver} 本阶段未接入，以 null 注入，编排层已做降级（不影响
 * 调用）。MCP 调用门面与外部工具路由目录已装配，宿主可通过 {@link #mcpGateway()} /
 * {@link #externalToolCatalog()} 登记外部工具；也可由 {@link #loadMcpServers(String, Path)} 完成
 * 「配置加载 → 注册服务端 → 发现能力 → 登记外部工具」的一站式接入。
 *
 * <p>生命周期：构造本身不访问网络（仅装配各组件，MCP 服务端连接推迟到显式调用 {@link #loadMcpServers}），
 * 也不创建对话历史目录 / 不取历史文件锁（首次真正读写历史时才创建）；{@link #close()} 释放会话存储、
 * 语义记忆存储与工作流引擎，无状态组件（模型、估算器）自然随之释放。
 */
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
     * 本地工作区标识：媒体资产与任务存储在本地单机部署下的工作区维度标识。
     */
    private static final String WORKSPACE_ID = "local";

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
    private final McpServerRegistry mcpServerRegistry;
    private final McpCapabilityCatalog mcpCapabilityCatalog;
    private final McpClientFactory mcpClientFactory;
    private final McpDiscoveryService mcpDiscoveryService;
    private final McpGateway mcpGateway;
    private final McpExternalToolRegistrar mcpExternalToolRegistrar;
    private final ExternalToolCatalog externalToolCatalog;
    private final IWorkflowEngine workflowEngine;
    private final ModelRegistry modelRegistry;
    private final WorkflowDefinitionRegistry workflowDefinitionRegistry;
    private final WorkflowRunner workflowRunner;
    private final DefaultConversationOrchestrator orchestrator;
    private final DefaultConversationOrchestrator.WindowMemoryFactory windowMemoryFactory;
    private final WindowCompressionCoordinator windowCompression;
    private final InMemoryCallRecordSink callRecordSink;

    /**
     * 使用默认配置装配：离线哈希嵌入（{@link HashingEmbeddingClient}）、工作目录下的本地对话历史存储、
     * 进程内语义存储、tiktoken 估算器、内存检查点工作流引擎。
     */
    public ApplicationBootstrap() {
        this(new HashingEmbeddingClient(EMBEDDING_DIMENSION), EMBEDDING_DIMENSION);
    }

    /**
     * 指定嵌入客户端与向量维度装配，便于接入真实凭据（如 {@code HttpEmbeddingClient}）。
     *
     * @param embeddingClient  嵌入客户端，不可为 null
     * @param embeddingDimension 向量维度，须与嵌入客户端输出一致，必须为正
     * @throws IllegalArgumentException 参数非法时
     */
    public ApplicationBootstrap(IEmbeddingClient embeddingClient, int embeddingDimension) {
        this(embeddingClient, embeddingDimension, defaultMcpClientFactory());
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
            McpClientFactory mcpClientFactory) {
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
        this.mcpClientFactory = mcpClientFactory;

        // 语义记忆：clara.yml 配了 milvus 就用 Milvus（对话记录写入并召回），否则维持进程内实现、行为不变。
        MilvusSettings milvus = MilvusSettings.load(ConfigLocator.claraConfigFile(), embeddingDimension,
                ConfigLocator.projectName());
        MilvusSemanticMemoryStore milvusStore = milvus == null ? null : connectMilvus(milvus);
        this.semanticMemoryStore = milvusStore == null ? new LocalSemanticMemoryStore(embeddingDimension) : milvusStore;
        this.conversationIndexing = milvusStore != null;
        this.semanticMemoryDescription = milvusStore == null ? "进程内 local（不写对话记录）" : "milvus " + milvus.getHost() + ":" + milvus.getPort() + "/" + milvus.getCollection() + "（对话记录写入并召回）";

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
        this.mcpExternalToolRegistrar = new McpExternalToolRegistrar();
        this.externalToolCatalog = new ExternalToolCatalog();

        // 工具调用：本地 skill 优先、外部 mcp 兜底，交给编排层的工具往返协调器
        // 脚本执行器注册表默认含 Python / JavaScript / Shell，稍后追加内置执行器（工具名 run_workflow）
        ScriptExecutorRegistry scriptExecutors = new ScriptExecutorRegistry();
        IToolInvoker localToolInvoker = new DefaultToolInvoker(new DefaultToolExecutor(skillRegistry, scriptExecutors, new SkillSchemaValidator(), new SkillPermissionAuthorizer(), 30_000L));
        IToolInvoker toolInvoker = new CompositeToolInvoker(skillRegistry, localToolInvoker, mcpGateway, externalToolCatalog);

        // clara-memory：窗口压缩编排（把被淘汰历史交给模型总结为摘要）
        this.windowCompression = new WindowCompressionCoordinator(new DefaultSummarizeCoordinator(streamModel, promptProvider));

        // orchestration 协作者
        SemanticRecallCoordinator semanticRecall = new SemanticRecallCoordinator(embeddingClient, semanticMemoryStore);
        // 工具描述按请求组装时现扫一遍工作流清单，使运行期新生成的自定义工作流无需重启即可被模型看到
        SkillMatchCoordinator skillMatch = new SkillMatchCoordinator(embeddingClient, semanticMemoryStore, promptProvider,
                new RegistryToolCatalog(skillRegistry, WorkflowCatalog::decorateToolDescription));
        ContextAssembler contextAssembler = new ContextAssembler(windowCompression, semanticRecall, skillMatch);
        CapabilityDispatcher capabilityDispatcher = new CapabilityDispatcher(streamModel);
        ToolRoundTripCoordinator toolRoundTrip = new ToolRoundTripCoordinator(toolInvoker);
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

        this.orchestrator = new DefaultConversationOrchestrator(windowMemoryFactory, contextAssembler, capabilityDispatcher, toolRoundTrip, callRecordPublisher, tokenCountEstimator);

        // clara-workflow：默认内存检查点工作流引擎；IWorkflowDefinitionResolver 本阶段未接入
        this.workflowEngine = new DefaultWorkflowEngine(new InMemoryCheckpointStore(), 4);

        // 工作流配置化装配：模型绑定注册表 + 动作工厂 → 定义目录加载 → 定义注册表 → 运行器 → 内置工具
        this.modelRegistry = new ModelRegistry(ConfigLocator.modelConfigs(), ConfigLocator.roleBindings());
        List<IWorkflowActionFactory> actionFactories = Arrays.asList(
                new AgentActionFactory(orchestrator, promptProvider, modelRegistry),
                new ReviewActionFactory(orchestrator, promptProvider, modelRegistry),
                new ToolActionFactory(toolInvoker));
        this.workflowDefinitionRegistry = new WorkflowDefinitionRegistry(DEFAULT_WORKFLOW_DIRECTORY, new WorkflowDefinitionLoader(actionFactories));
        // 目录不存在时不加载、不报错
        this.workflowDefinitionRegistry.reload();
        this.workflowRunner = new WorkflowRunner(workflowEngine, workflowDefinitionRegistry);
        // 内置工具与本地工具调用共享同一脚本执行器注册表，故可在运行器就绪后再注册内置执行器：
        // run_workflow 负责按标识运行；publish_workflow 负责校验并发布模型生成的自定义工作流
        scriptExecutors.register(new BuiltinToolExecutor(Arrays.<IBuiltinTool>asList(
                new WorkflowRunTool(workflowRunner), new PublishWorkflowTool(workflowDefinitionRegistry))));
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
        try (ConvContext.Scope ignored = ConvContext.open(context)) {
            summary = windowCompression.summarize(history);
        }
        if (summary == null) {
            return 0;
        }
        memory.clear();
        memory.append(Collections.singletonList(summary));
        return history.size();
    }

    /**
     * 统计 token 用量说明（供交互端 {@code /usage} 展示）。
     *
     * <p>统计两类口径：
     * <ol>
     *   <li>当前会话全部历史记录占用的 token 估算（基于持久化历史 + token 估算器，跨重启有效）；</li>
     *   <li>本次进程内已发生的模型调用累计用量（从调用记录汇总，进程内保留最近若干条）。</li>
     * </ol>
     *
     * @param conversationId        会话标识，不可为空白
     * @param providerConfiguration 当前平台配置，可为 null（未绑定时只统计进程内调用）
     * @param model                 当前模型标识，可为 null
     * @return 多行用量说明，非 null
     */
    public String conversationUsageReport(String conversationId, ProviderConfiguration providerConfiguration,
            String model) {
        StringBuilder report = new StringBuilder();
        report.append("会话：").append(conversationId);
        if (providerConfiguration != null && model != null && !model.trim().isEmpty()) {
            ModelProviderEnum provider = providerConfiguration.getProvider();
            IWindowMemory memory = windowMemoryFactory.create(conversationId, provider.name(), model);
            List<IContent> history = HistorySanitizer.dropInvalidAssistantMessages(memory.messages());
            long tokens = tokenCountEstimator == null ? 0L : tokenCountEstimator.estimate(history);
            report.append('\n').append("历史记录：").append(history.size()).append(" 条")
                    .append("，估算 token：").append(tokens);
        } else {
            report.append('\n').append("历史记录：未知（未绑定模型配置）");
        }

        long input = 0L;
        long output = 0L;
        long total = 0L;
        int calls = 0;
        for (CallRecord record : callRecordSink.snapshot()) {
            IResponse response = record.response();
            if (!(response instanceof HttpResponse)) {
                continue;
            }
            HttpResponse http = (HttpResponse) response;
            long in = http.getInputTokens() == null ? 0L : http.getInputTokens().longValue();
            long out = http.getOutputTokens() == null ? 0L : http.getOutputTokens().longValue();
            long sum = http.getTotalTokens() == null
                    ? in + out : http.getTotalTokens().longValue();
            calls++;
            input += in;
            output += out;
            total += sum == 0L ? in + out : sum;
        }
        report.append('\n').append("本次进程累计调用：").append(calls).append(" 次")
                .append("（输入 ").append(input).append(" / 输出 ").append(output)
                .append(" / 合计 ").append(total).append(" tokens）");
        return report.toString();
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
    public McpGateway mcpGateway() {
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
    public McpServerRegistry mcpServerRegistry() {
        return mcpServerRegistry;
    }

    /**
     * 返回 MCP 能力目录，保存各服务端发现出的能力快照。
     *
     * @return MCP 能力目录，非 null
     */
    public McpCapabilityCatalog mcpCapabilityCatalog() {
        return mcpCapabilityCatalog;
    }

    /**
     * 返回 MCP 发现服务，可对已登记服务端单独触发刷新发现。
     *
     * @return MCP 发现服务，非 null
     */
    public McpDiscoveryService mcpDiscoveryService() {
        return mcpDiscoveryService;
    }

    /**
     * 从默认目录 {@code config/mcp}（相对运行目录）加载并接入 MCP 服务端。
     *
     * @param tenantId 租户标识，不可为空白
     * @return 成功发现的服务端数，非负
     * @throws IllegalArgumentException 租户标识非法或配置加载失败时
     */
    public int loadMcpServers(String tenantId) {
        return loadMcpServers(tenantId, DEFAULT_MCP_CONFIG_DIRECTORY);
    }

    /**
     * 完成「配置加载 → 注册服务端 → 发现能力 → 登记外部工具」的一站式接入链路。
     *
     * <p>流程：用 {@link McpServerConfigDirectoryLoader} 递归加载 {@code configDirectory} 下的 {@code *.json}
     * 配置；用 {@link McpServerRegistrar#registerAll(String, List)} 把配置注册进 {@link McpServerRegistry}；
     * 对每个已注册服务端调用 {@link McpDiscoveryService#refresh(String, String)} 完成发现（发布快照并更新状态）；
     * 再从 {@link McpCapabilityCatalog#latest(String, String)} 取回快照，用 {@link McpExternalToolRegistrar}
     * 把其工具登记进 {@link ExternalToolCatalog}。
     *
     * <p>失败隔离：单个服务端的发现失败（协议不兼容、身份变化、连接异常等）会被捕获并跳过，不影响其余服务端，
     * 也不阻断方法返回；返回值只统计发现成功的服务端数。发现失败时注册状态已由发现服务按需更新为
     * {@code INCOMPATIBLE} / {@code REVIEW_REQUIRED}。配置加载失败属装配期错误，会直接抛出。
     *
     * <p>本方法会按需连接配置中的服务端，故不在构造阶段调用。
     *
     * @param tenantId        租户标识，不可为空白
     * @param configDirectory 配置目录，不可为 null；不存在或无 {@code *.json} 时相当于不接入任何服务端
     * @return 成功发现的服务端数，非负
     * @throws IllegalArgumentException 租户标识非法、配置目录为 null 或配置加载失败时
     */
    public int loadMcpServers(String tenantId, Path configDirectory) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (configDirectory == null) {
            throw new IllegalArgumentException("configDirectory must not be null");
        }
        List<McpServerConfig> configs = new McpServerConfigDirectoryLoader(
                new McpServerConfigLoader(), configDirectory).load();
        List<McpServerRegistration> registrations =
                new McpServerRegistrar(mcpServerRegistry).registerAll(tenantId, configs);
        int discovered = 0;
        for (McpServerRegistration registration : registrations) {
            String serverKey = registration.getServerKey();
            try {
                mcpDiscoveryService.refresh(tenantId, serverKey);
                McpServerSnapshot snapshot = mcpCapabilityCatalog.latest(tenantId, serverKey);
                if (snapshot != null) {
                    mcpExternalToolRegistrar.register(tenantId, snapshot, externalToolCatalog);
                }
                discovered++;
            } catch (RuntimeException ignored) {
                // 单个服务端发现失败不中断其余：状态已由发现服务按需更新，此处跳过并继续
            }
        }
        return discovered;
    }

    /**
     * 创建 MCP 客户端工厂：按服务端注册项的传输方式创建客户端。
     *
     * <p>装配期尚无服务端登记，允许执行的命令与工作目录在此给出默认占位值；宿主登记 stdio 服务端时应按
     * 实际可执行文件绝对路径调整。
     *
     * @return MCP 客户端工厂，非 null
     */
    private static McpClientFactory defaultMcpClientFactory() {
        return new DefaultMcpClientFactory(new JacksonMcpJsonCodec(), null,
                Collections.singletonList(Paths.get("node").toAbsolutePath()),
                Paths.get("").toAbsolutePath(), null, MCP_REQUEST_TIMEOUT_MILLIS);
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
            System.err.println("[memory] 本地对话历史不可用，本次运行的历史不会保留："
                    + unavailable.getMessage());
            return new InMemoryConversationStore();
        }
    }

    /**
     * 连接 Milvus 语义记忆存储：构造期即校验集合存在且维度一致。
     *
     * <p>连不上时给出原因并返回 null，由调用方回退为进程内实现——配了向量库但没起服务不该让整个应用起不来，
     * 横幅上的 {@code semantic} 一行会写明最终用的是哪一个。
     *
     * @param settings Milvus 配置，非 null
     * @return Milvus 语义记忆存储；不可用时 null
     */
    private static MilvusSemanticMemoryStore connectMilvus(MilvusSettings settings) {
        quietMilvusLogging();
        try {
            MilvusBackend backend = MilvusBackendFactory.create(new MilvusBackendConfig(
                    settings.getHost(), settings.getPort(), settings.getUsername(), settings.getPassword(),
                    MilvusBackendConfig.DEFAULT_DATABASE,
                    MilvusBackendConfig.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                    MilvusBackendConfig.DEFAULT_IDLE_TIMEOUT_MILLIS));
            return new MilvusSemanticMemoryStore(new MilvusCollectionSpec(settings.getCollection(), settings.getDimension()), backend);
        } catch (RuntimeException unavailable) {
            System.err.println("[memory] Milvus 不可用，语义记忆回退为进程内实现（本次不写对话记录）：" + unavailable.getMessage());
            return null;
        }
    }

    /**
     * 抑制 Milvus SDK 自带 gRPC 的告警噪音。
     *
     * <p>milvus-sdk-java 内部打包了 shaded gRPC，并以 {@code java.util.logging} 打日志；空闲连接保活 ping
     * 超时或关连接时会打印告警堆栈（含其 shaded 内部类的 {@code NoClassDefFoundError}），属 SDK 内部行为、
     * 不影响功能。这里把 {@code io.milvus} 日志层级抬到 {@code SEVERE}，只保留严重错误，避免控制台刷屏。
     */
    private static void quietMilvusLogging() {
        Logger.getLogger("io.milvus").setLevel(Level.SEVERE);
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
        } catch (IOException ignored) {
            // 释放失败不阻断关闭
        }
    }
}
