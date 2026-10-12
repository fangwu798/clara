package com.clara;

import com.clara.core.memory.IEmbeddingClient;
import com.clara.core.memory.ISemanticMemoryStore;
import com.clara.core.memory.ITokenCountEstimator;
import com.clara.core.memory.IWindowMemory;
import com.clara.core.skill.IPromptProvider;
import com.clara.core.skill.IToolInvoker;
import com.clara.core.workflow.IWorkflowActionFactory;
import com.clara.core.workflow.IWorkflowEngine;
import com.clara.kernel.api.IStreamModel;
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
import com.clara.memory.semantic.local.LocalSemanticMemoryStore;
import com.clara.memory.summarize.DefaultSummarizeCoordinator;
import com.clara.memory.window.DefaultWindowMemory;
import com.clara.model.embedding.HashingEmbeddingClient;
import com.clara.model.provider.CompatibleStream;
import com.clara.model.token.TiktokenTokenCountEstimator;
import com.clara.orchestration.DefaultConversationOrchestrator;
import com.clara.orchestration.capability.CapabilityDispatcher;
import com.clara.orchestration.context.ContextAssembler;
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
import com.clara.tool.ExternalToolCatalog;
import com.clara.tool.McpExternalToolRegistrar;
import com.clara.workflow.application.AgentActionFactory;
import com.clara.workflow.application.ToolActionFactory;
import com.clara.workflow.application.WorkflowModelContext;
import com.clara.workflow.application.WorkflowRunTool;
import com.clara.workflow.application.WorkflowRunner;
import com.clara.workflow.checkpoint.InMemoryCheckpointStore;
import com.clara.workflow.definition.WorkflowDefinitionLoader;
import com.clara.workflow.definition.WorkflowDefinitionRegistry;
import com.clara.workflow.engine.DefaultWorkflowEngine;
import com.clara.workspace.application.DefaultWorkspaceBusinessRunner;
import com.clara.workspace.application.WorkspaceBusinessRunner;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

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
 * 并整理技术文档）。AGENT 节点所需的平台配置为会话级，由宿主在加载模型配置后经 {@link #workflowModelContext()}
 * 注入。
 *
 * <p>调用记录：{@code ICallRecordSink} 由进程内实现 {@link InMemoryCallRecordSink} 提供，保留最近若干条
 * 记录供上层读取；{@code IWorkflowDefinitionResolver} 本阶段未接入，以 null 注入，编排层已做降级（不影响
 * 调用）。MCP 调用门面与外部工具路由目录已装配，宿主可通过 {@link #mcpGateway()} /
 * {@link #externalToolCatalog()} 登记外部工具；也可由 {@link #loadMcpServers(String, Path)} 完成
 * 「配置加载 → 注册服务端 → 发现能力 → 登记外部工具」的一站式接入。
 *
 * <p>生命周期：构造本身不访问网络（仅装配各组件，MCP 服务端连接推迟到显式调用 {@link #loadMcpServers}）；
 * {@link #close()} 释放会话存储、语义记忆存储与工作流引擎，无状态组件（模型、估算器）自然随之释放。
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
     * MCP 客户端单次请求超时（毫秒）：装配期给出的默认值，随宿主登记服务端后生效。
     */
    private static final long MCP_REQUEST_TIMEOUT_MILLIS = 30_000L;

    /**
     * MCP 发现支持的协议版本列表：当前仅支持 {@code 2024-11-05}，服务端协商出其它版本将标记为不兼容。
     */
    private static final List<String> MCP_SUPPORTED_PROTOCOL_VERSIONS =
            Collections.unmodifiableList(Collections.singletonList("2024-11-05"));

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
    private final ConversationStore conversationStore;
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
    private final WorkflowModelContext workflowModelContext;
    private final WorkflowDefinitionRegistry workflowDefinitionRegistry;
    private final WorkflowRunner workflowRunner;
    private final DefaultConversationOrchestrator orchestrator;
    private final WorkspaceBusinessRunner workspaceRunner;
    private final InMemoryCallRecordSink callRecordSink;

    /**
     * 使用默认配置装配：离线哈希嵌入（{@link HashingEmbeddingClient}）、进程内会话与语义存储、
     * tiktoken 估算器、内存检查点工作流引擎。
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
        this.semanticMemoryStore = new LocalSemanticMemoryStore(embeddingDimension);
        this.conversationStore = new InMemoryConversationStore();
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
        WindowCompressionCoordinator windowCompression = new WindowCompressionCoordinator(new DefaultSummarizeCoordinator(streamModel, promptProvider));

        // orchestration 协作者
        SemanticRecallCoordinator semanticRecall = new SemanticRecallCoordinator(embeddingClient, semanticMemoryStore);
        SkillMatchCoordinator skillMatch = new SkillMatchCoordinator(embeddingClient, semanticMemoryStore, promptProvider, new RegistryToolCatalog(skillRegistry));
        ContextAssembler contextAssembler = new ContextAssembler(windowCompression, semanticRecall, skillMatch);
        CapabilityDispatcher capabilityDispatcher = new CapabilityDispatcher(streamModel);
        ToolRoundTripCoordinator toolRoundTrip = new ToolRoundTripCoordinator(toolInvoker);
        // 调用记录接收方：进程内保留最近若干条记录，供上层读取（不打印、不落盘）
        this.callRecordSink = new InMemoryCallRecordSink();
        CallRecordPublisher callRecordPublisher = new CallRecordPublisher(callRecordSink);

        // 窗口记忆工厂：按「会话 + 供应商 + 模型」提供按会话隔离的窗口记忆实例
        DefaultConversationOrchestrator.WindowMemoryFactory windowMemoryFactory =
                (conversationId, provider, model) -> {
                    String effectiveProvider = provider == null ? DEFAULT_PROVIDER : provider;
                    return new DefaultWindowMemory(conversationId, effectiveProvider, model, conversationStore);
                };

        this.orchestrator = new DefaultConversationOrchestrator(windowMemoryFactory, contextAssembler, capabilityDispatcher, toolRoundTrip, callRecordPublisher, tokenCountEstimator);

        // 核心业务入口：把工作区 run / resume / cancel 落到上面的对话编排能力，供 CLI 使用
        this.workspaceRunner = new DefaultWorkspaceBusinessRunner(orchestrator);

        // clara-workflow：默认内存检查点工作流引擎；IWorkflowDefinitionResolver 本阶段未接入
        this.workflowEngine = new DefaultWorkflowEngine(new InMemoryCheckpointStore(), 4);

        // 工作流配置化装配：模型上下文 + 动作工厂 → 定义目录加载 → 定义注册表 → 运行器 → 内置工具
        this.workflowModelContext = new WorkflowModelContext();
        List<IWorkflowActionFactory> actionFactories = Arrays.asList(
                new AgentActionFactory(orchestrator, promptProvider, workflowModelContext),
                new ToolActionFactory(toolInvoker));
        this.workflowDefinitionRegistry = new WorkflowDefinitionRegistry(DEFAULT_WORKFLOW_DIRECTORY, new WorkflowDefinitionLoader(actionFactories));
        // 目录不存在时不加载、不报错
        this.workflowDefinitionRegistry.reload();
        this.workflowRunner = new WorkflowRunner(workflowEngine, workflowDefinitionRegistry);
        // 内置工具与本地工具调用共享同一脚本执行器注册表，故可在运行器就绪后再注册内置执行器
        scriptExecutors.register(new BuiltinToolExecutor(Collections.singletonList(new WorkflowRunTool(workflowRunner))));
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
     * 返回装配好的工作区核心业务入口。
     *
     * @return 工作区业务运行器，非 null
     */
    public WorkspaceBusinessRunner workspaceRunner() {
        return workspaceRunner;
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
     * 返回工作流模型上下文，宿主在加载 / 切换模型配置后据此为 AGENT 节点绑定平台配置。
     *
     * @return 工作流模型上下文，非 null
     */
    public WorkflowModelContext workflowModelContext() {
        return workflowModelContext;
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

    @Override
    public void close() {
        closeQuietly(workflowEngine);
        closeQuietly(semanticMemoryStore);
        conversationStore.close();
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
