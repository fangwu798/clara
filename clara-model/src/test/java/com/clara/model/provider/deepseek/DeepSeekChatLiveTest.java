package com.clara.model.provider.deepseek;

import com.clara.kernel.CancellationToken;
import com.clara.kernel.ClaraError;
import com.clara.kernel.InvocationContext;
import com.clara.media.MediaType;
import com.clara.media.MediaUpload;
import com.clara.model.api.ChatMessage;
import com.clara.model.api.ChatModel;
import com.clara.model.api.ChatRequest;
import com.clara.model.api.ChatResponse;
import com.clara.model.api.Content;
import com.clara.model.api.FinishReason;
import com.clara.model.api.ModelCapability;
import com.clara.model.api.ModelParameters;
import com.clara.model.api.Role;
import com.clara.model.api.StreamEvent;
import com.clara.model.api.StreamHandle;
import com.clara.model.api.StreamObserver;
import com.clara.model.api.TextContent;
import com.clara.model.api.ToolCallContent;
import com.clara.model.api.media.ImageGenerationEvent;
import com.clara.model.api.media.ImageGenerationObserver;
import com.clara.model.api.media.ImageGenerationRequest;
import com.clara.model.api.media.ImageGenerationResult;
import com.clara.model.configuration.ProviderConfiguration;
import com.clara.model.configuration.ProviderYamlConfigurationLoader;
import com.clara.model.provider.compatible.OpenAiCompatibleChatModel;
import com.clara.model.provider.compatible.OpenAiCompatibleStreamingChatModel;
import com.clara.model.provider.compatible.config.OpenAiCompatibleChatConfig;
import com.clara.model.provider.compatible.image.OpenAiCompatibleImageConfig;
import com.clara.model.provider.compatible.image.OpenAiCompatibleImageModel;
import com.clara.model.provider.compatible.image.OpenAiCompatibleStreamingImageModel;
import com.clara.model.provider.http.impl.Jdk8ProviderHttpTransport;
import com.clara.model.provider.media.ProviderMediaAssetBridge;
import com.clara.model.runtime.ProviderModelDescriptor;
import com.clara.tool.ToolSpecification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * 加载真实平台配置、直接调用真实接口的用例（不使用任何模拟）。
 *
 * <p>覆盖场景：
 * <ul>
 *   <li>{@link #nonStreamingChat()}：非流式对话；</li>
 *   <li>{@link #nonStreamingToolCallRoundTrip()}：非流式工具调用往返（声明工具 → 返回 tool_calls → 回填结果）；</li>
 *   <li>{@link #streamingChat()}：流式对话；</li>
 *   <li>{@link #streamingToolCallRoundTrip()}：流式工具调用往返；</li>
 *   <li>{@link #nonStreamingImageGeneration()}：非流式文生图；</li>
 *   <li>{@link #streamingImageGeneration()}：流式文生图（逐张产出事件）。</li>
 * </ul>
 *
 * <p>对话用例加载 {@code config/model-providers/deepseek.yml}，生图用例加载
 * {@code config/model-providers/volcengine.yml}——对话平台没有图片能力，两者各取所长。
 *
 * <p>运行前置条件：
 * <ol>
 *   <li>上述两个配置文件存在，且所用能力指向账号可用的模型；</li>
 *   <li>配置文件 {@code credential} 里声明的环境变量<b>在当前进程可见</b>，或已内联 {@code apiKey}；
 *       环境变量在当前进程不可见时用例会被 {@link Assume} 跳过：进程环境是启动时的快照，系统里设置后必须
 *       重启 IDE，或在运行配置的环境变量里注入。</li>
 * </ol>
 * 任一条件不满足时用例会被 {@link Assume} 跳过，不会让常规构建失败；条件满足时是真实网络调用，
 * 会产生真实计费。
 *
 * <p>调试建议断点：{@code ProviderYamlConfigurationLoader#load} →
 * {@code OpenAiCompatibleChatExchange#encode/#send} → {@code OpenAiCompatibleChatCodec#decode}
 * （非流式对话）或 {@code OpenAiCompatibleStreamAccumulator#accept/#complete}（流式对话）→
 * {@code OpenAiCompatibleStreamingChatModel#emitChunk}；
 * 生图链路为 {@code OpenAiCompatibleImageCodec#encodeJson/#decode}（非流式）或
 * {@code OpenAiCompatibleImageStreamAccumulator#accept} →
 * {@code OpenAiCompatibleStreamingImageModel#execute}（流式）。
 */
public class DeepSeekChatLiveTest {
    /**
     * 平台配置文件名，与 {@code config/model-providers/} 下的约定一致。
     */
    private static final String CONFIG_FILE = "deepseek.yml";
    /**
     * 生图用例的平台配置文件名：对话平台的配置里没有图片能力。
     */
    private static final String IMAGE_CONFIG_FILE = "volcengine.yml";
    /**
     * 非流式生图使用的能力名称：Seedream 5.0 pro 与 5.0 lite（快速版）。
     */
    private static final List<String> IMAGE_CAPABILITIES = Collections.unmodifiableList(
            Arrays.asList("image", "image-fast"));
    /**
     * 流式生图使用的能力名称：在当前通道只有 5.0 lite 支持 {@code stream=true}。
     *
     * <p>实测 Seedream 5.0 pro 对 {@code stream=true} 返回 400，5.0 lite 正常返回
     * {@code image_generation.*} 事件流，因此流式用例只跑 lite；pro 的流式一旦开放，把它加回本列表即可。
     */
    private static final List<String> STREAMING_IMAGE_CAPABILITIES = Collections.unmodifiableList(
            Arrays.asList("image-fast"));
    /**
     * 对话能力名称，对应 YAML 中 {@code capabilities} 的键。
     */
    private static final String CAPABILITY = "chat";
    /**
     * 提示词，两条链路共用。
     */
    private static final String PROMPT = "用一句话介绍你自己";
    /**
     * 单次调用允许的最大输出长度，避免调试时产生过多用量。
     */
    private static final int MAX_TOKENS = 256;
    /**
     * 等待流式结束的上限，秒；应大于配置中的 readTimeoutMillis。
     */
    private static final long STREAM_TIMEOUT_SECONDS = 200L;
    /**
     * 生图用例的提示词，明确要求单张、无文字，便于肉眼核对结果。
     */
    private static final String IMAGE_PROMPT = "一只戴着圆框墨镜的柴犬，坐在沙滩上，写实摄影风格，画面中不要出现任何文字";
    /**
     * 工具调用场景的提示词：明确要求先调用工具，避免模型直接作答。
     */
    private static final String TOOL_PROMPT = "杭州现在的天气怎么样？必须调用 get_weather 工具查询后再回答。";
    /**
     * 工具名。
     */
    private static final String WEATHER_TOOL_NAME = "get_weather";
    /**
     * 工具声明：入参是一个必填的 city。
     */
    private static final ToolSpecification WEATHER_TOOL = new ToolSpecification(WEATHER_TOOL_NAME, "查询指定城市的当前天气", weatherSchema());
    /**
     * 解析工具调用参数用的映射器。
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 非流式调用：一次拿到完整响应，便于逐字段检查解码结果。
     *
     * @throws Exception 读取配置或解析响应时
     */
    @Test
    public void nonStreamingChat() throws Exception {
        Path file = configFile();
        try (ProviderConfiguration provider = new ProviderYamlConfigurationLoader().load(file)) {
            skipWhenCredentialMissing(provider);
            OpenAiCompatibleChatConfig config = provider.toOpenAiCompatibleChatConfig(CAPABILITY);
            ChatModel model = new OpenAiCompatibleChatModel(config, new Jdk8ProviderHttpTransport(provider.getTransport().toHttpTransportConfig()), provider.credentialResolver());

            System.out.println("[非流式] endpoint=" + config.getEndpoint() + " model=" + config.getModelId());
            ChatResponse response = model.chat(request());

            System.out.println("[非流式] 文本=" + textOf(response));
            System.out.println("[非流式] 结束原因=" + response.getFinishReason() + " 元数据=" + response.getProviderMetadata());
            System.out.println("[非流式] 用量=" + describeUsage(response));

            assertFalse("响应没有文本内容", textOf(response).isEmpty());
            assertEquals(provider.getProvider().toString(), response.getProviderMetadata().get("provider").toString());
            assertNotNull("真实响应应带 usage", response.getTokenUsage());
            assertTrue(response.getTokenUsage().getInputTokens() > 0);
        }
    }

    /**
     * 非流式工具调用往返：声明工具 → 模型返回结构化 {@code tool_calls} → 回填工具结果 → 模型给出最终回答。
     *
     * <p>重点验证三件事：工具声明与调用结果的编码、{@code finish_reason=tool_calls} 与
     * {@code ToolCallContent} 的解码、以及 {@code tool} 角色消息把工具结果按 {@code tool_call_id} 回填。
     *
     * @throws Exception 读取配置、解析参数时
     */
    @Test
    public void nonStreamingToolCallRoundTrip() throws Exception {
        Path file = configFile();
        try (ProviderConfiguration provider = new ProviderYamlConfigurationLoader().load(file)) {
            skipWhenCredentialMissing(provider);
            OpenAiCompatibleChatConfig config = provider.toOpenAiCompatibleChatConfig(CAPABILITY);
            ChatModel model = new OpenAiCompatibleChatModel(config, new Jdk8ProviderHttpTransport(provider.getTransport().toHttpTransportConfig()), provider.credentialResolver());
            System.out.println("[工具调用] endpoint=" + config.getEndpoint() + " model=" + config.getModelId() + " 工具=" + WEATHER_TOOL_NAME);

            // 第一次调用：只给工具声明，要求模型先调用工具
            ChatResponse first = model.chat(new ChatRequest(
                    Collections.singletonList(text(Role.USER, TOOL_PROMPT)),
                    parameters(),
                    Collections.singletonList(WEATHER_TOOL),
                    null,
                    context())
            );
            System.out.println("[工具调用] 第一次结束原因=" + first.getFinishReason() + " 内容=" + first.getMessage().getContents() + " 用量=" + describeUsage(first));

            assertEquals("模型没有按要求调用工具，实际文本=" + textOf(first), FinishReason.TOOL_CALLS, first.getFinishReason());
            ToolCallContent call = onlyToolCall(first);
            JsonNode arguments = JSON.readTree(call.getArguments());
            System.out.println("[工具调用] 工具=" + call.getName() + " id=" + call.getId() + " 参数=" + arguments);
            assertEquals(WEATHER_TOOL_NAME, call.getName());
            assertTrue("工具参数缺少 city", arguments.has("city"));

            // 第二次调用：把工具执行结果按 tool 角色回填，tool_call_id 通过 ChatMessage.name 承载
            List<ChatMessage> messages = Arrays.asList(text(Role.USER, TOOL_PROMPT),
                    first.getMessage(),
                    new ChatMessage(Role.TOOL, Collections.<Content>singletonList(new TextContent(
                            "{\"city\":\"杭州\",\"weather\":\"晴\",\"temperature\":\"26℃\"}")),
                            call.getId(), null));
            ChatResponse second = model.chat(new ChatRequest(messages, parameters(),
                    Collections.<ToolSpecification>singletonList(WEATHER_TOOL), null, context()));
            System.out.println("[工具调用] 第二次结束原因=" + second.getFinishReason()
                    + " 最终回答=" + textOf(second) + " 用量=" + describeUsage(second));

            assertFalse("工具结果回填后模型没有给出回答", textOf(second).isEmpty());
            assertEquals(FinishReason.STOP, second.getFinishReason());
        }
    }

    /**
     * 流式调用：按事件逐个观察增量，并检查终态响应与统计。
     *
     * @throws Exception 读取配置、等待事件或解析响应时
     */
    @Test
    public void streamingChat() throws Exception {
        Path file = configFile();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ProviderConfiguration provider = new ProviderYamlConfigurationLoader().load(file)) {
            skipWhenCredentialMissing(provider);
            OpenAiCompatibleChatConfig config = provider.toOpenAiCompatibleChatConfig(CAPABILITY);
            OpenAiCompatibleStreamingChatModel model = streamingModel(provider, config, executor);

            System.out.println("[流式] endpoint=" + config.getEndpoint()
                    + " model=" + config.getModelId());
            DebugObserver observer = new DebugObserver("流式");
            StreamHandle handle = model.chat(request(), observer);

            assertTrue("等待流式结束超时", observer.terminal.await(STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertFinished(handle);
            assertNull("流以错误收尾: " + (observer.failure == null ? null : observer.failure.getError()), observer.failure);
            assertNotNull(observer.completed);
            assertFalse("响应没有文本内容", observer.text.toString().isEmpty());
            assertEquals(provider.getProvider().toString(), observer.completed.getProviderMetadata().get("provider").toString());
            assertTrue(observer.completed.getTokenUsage().getInputTokens() > 0);
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 流式工具调用往返：工具参数以增量事件下发 → 拼装出完整 {@code tool_calls} → 回填工具结果
     * → 再流式调用一次拿到最终回答。
     *
     * <p>重点验证：{@code TOOL_ARGUMENT_DELTA} 片段顺序拼装的结果与终态响应里的参数完全一致、
     * {@code TOOL_CALL_COMPLETE} 在收尾时补齐、以及工具结果按 {@code tool_call_id} 回填后模型能继续作答。
     *
     * @throws Exception 读取配置、等待事件或解析参数时
     */
    @Test
    public void streamingToolCallRoundTrip() throws Exception {
        Path file = configFile();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ProviderConfiguration provider = new ProviderYamlConfigurationLoader().load(file)) {
            skipWhenCredentialMissing(provider);
            OpenAiCompatibleChatConfig config = provider.toOpenAiCompatibleChatConfig(CAPABILITY);
            OpenAiCompatibleStreamingChatModel model = streamingModel(provider, config, executor);
            System.out.println("[流式工具] endpoint=" + config.getEndpoint() + " model=" + config.getModelId() + " 工具=" + WEATHER_TOOL_NAME);

            // 第一次：流式拿工具调用，参数以增量片段拼起来
            DebugObserver firstObserver = new DebugObserver("流式工具");
            StreamHandle firstHandle = model.chat(new ChatRequest(
                    Collections.singletonList(text(Role.USER, TOOL_PROMPT)), parameters(),
                    Collections.singletonList(WEATHER_TOOL), null, context()),
                    firstObserver);
            assertTrue("等待流式结束超时", firstObserver.terminal.await(STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertFinished(firstHandle);
            assertNull("流以错误收尾: " + (firstObserver.failure == null ? null : firstObserver.failure.getError()), firstObserver.failure);

            ChatResponse first = firstObserver.completed;
            assertNotNull(first);
            assertEquals("模型没有按要求调用工具，实际文本=" + firstObserver.text, FinishReason.TOOL_CALLS, first.getFinishReason());
            ToolCallContent call = onlyToolCall(first);
            JsonNode arguments = JSON.readTree(call.getArguments());
            System.out.println("[流式工具] 终态拼装结果 工具=" + call.getName() + " id=" + call.getId() + " 参数=" + arguments);
            assertEquals(WEATHER_TOOL_NAME, call.getName());
            assertTrue("工具参数缺少 city", arguments.has("city"));
            assertEquals("增量片段拼装结果应与终态参数一致", call.getArguments(), firstObserver.toolArguments.toString());

            // 第二次：把工具结果按 tool 角色回填后再流式调用
            List<ChatMessage> messages = Arrays.asList(text(Role.USER, TOOL_PROMPT),
                    first.getMessage(),
                    new ChatMessage(Role.TOOL, Collections.<Content>singletonList(new TextContent(
                            "{\"city\":\"杭州\",\"weather\":\"晴\",\"temperature\":\"26℃\"}")),
                            call.getId(), null));
            DebugObserver secondObserver = new DebugObserver("流式工具");
            StreamHandle secondHandle = model.chat(new ChatRequest(messages, parameters(),
                    Collections.<ToolSpecification>singletonList(WEATHER_TOOL), null, context()),
                    secondObserver);
            assertTrue("等待流式结束超时", secondObserver.terminal.await(STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            assertFinished(secondHandle);
            assertNull("流以错误收尾: " + (secondObserver.failure == null ? null : secondObserver.failure.getError()), secondObserver.failure);

            assertEquals(FinishReason.STOP, secondObserver.completed.getFinishReason());
            assertFalse("工具结果回填后模型没有给出回答", secondObserver.text.toString().isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 非流式文生图：对每个图片能力各调用一次，拿到汇总结果并把图片真正下载落盘。
     *
     * <p>验证的是整条非流式链路：配置加载 → 请求编码（{@code model}/{@code prompt}/{@code n}/{@code size}
     * 加平台字段）→ 真实调用 → {@code data[].url} 解析 → 资源层下载落盘。
     *
     * @throws Exception 读取配置或下载结果时
     */
    @Test
    public void nonStreamingImageGeneration() throws Exception {
        Path file = configFile(IMAGE_CONFIG_FILE);
        try (ProviderConfiguration provider = new ProviderYamlConfigurationLoader().load(file)) {
            skipWhenCredentialMissing(provider);
            for (String capability : IMAGE_CAPABILITIES) {
                OpenAiCompatibleImageConfig config = provider.toVolcengineImageConfig(capability);
                DownloadingAssets assets = new DownloadingAssets();
                OpenAiCompatibleImageModel model = new OpenAiCompatibleImageModel(config,
                        provider.toVolcengineImageOptions(capability), imageDescriptor(config), assets,
                        new Jdk8ProviderHttpTransport(provider.getTransport().toHttpTransportConfig()),
                        provider.credentialResolver());

                System.out.println("[非流式生图] 能力=" + capability + " endpoint=" + config.getEndpoint() + " model=" + config.getModelId());
                ImageGenerationResult result = model.generate(imageRequest());

                System.out.println("[非流式生图] 请求标识=" + result.getRequestId() + " 资产=" + result.getAssetIds() + " 元数据=" + result.getProviderMetadata());
                System.out.println("[非流式生图] " + assets.describe());

                assertFalse("没有返回任何图片", result.getAssetIds().isEmpty());
                assertEquals(config.getModelId(), result.getProviderMetadata().get("model"));
                assets.assertDownloaded();
            }
        }
    }

    /**
     * 流式文生图：对每个图片能力各调用一次，逐张观察事件并交叉验证终态汇总。
     *
     * <p>验证的是流式链路：请求置 {@code stream=true} → 平台按事件推送 → 每张图立即落盘并回调
     * {@code IMAGE} → 终态 {@code RESPONSE_COMPLETE} 的资产数量与逐张回调的数量一致。
     *
     * <p>所用能力见 {@link #STREAMING_IMAGE_CAPABILITIES}——并非所有图片模型都支持流式。
     *
     * @throws Exception 读取配置、等待事件或下载结果时
     */
    @Test
    public void streamingImageGeneration() throws Exception {
        Path file = configFile(IMAGE_CONFIG_FILE);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ProviderConfiguration provider = new ProviderYamlConfigurationLoader().load(file)) {
            skipWhenCredentialMissing(provider);
            for (String capability : STREAMING_IMAGE_CAPABILITIES) {
                OpenAiCompatibleImageConfig config = provider.toVolcengineImageConfig(capability);
                DownloadingAssets assets = new DownloadingAssets();
                OpenAiCompatibleStreamingImageModel model = new OpenAiCompatibleStreamingImageModel(config,
                        provider.toVolcengineImageOptions(capability), imageDescriptor(config), assets,
                        new Jdk8ProviderHttpTransport(provider.getTransport().toHttpTransportConfig(),
                                8192, 65536, 100000),
                        provider.credentialResolver(), executor);

                System.out.println("[流式生图] 能力=" + capability + " endpoint=" + config.getEndpoint()
                        + " model=" + config.getModelId());
                ImageObserver observer = new ImageObserver("流式生图-" + capability);
                StreamHandle handle = model.generate(imageRequest(), observer);

                assertTrue("等待流式结束超时", observer.terminal.await(STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                assertFinished(handle);
                assertNull("流以错误收尾: " + (observer.failure == null ? null : describeError(observer.failure.getError())),
                        observer.failure);
                assertNotNull(observer.completed);
                assertFalse("没有返回任何图片", observer.completed.getAssetIds().isEmpty());
                assertEquals("逐张回调的图片数与终态汇总不一致",
                        observer.imageCount, observer.completed.getAssetIds().size());

                System.out.println("[流式生图] 请求标识=" + observer.completed.getRequestId()
                        + " 资产=" + observer.completed.getAssetIds()
                        + " 元数据=" + observer.completed.getProviderMetadata());
                System.out.println("[流式生图] " + assets.describe());
                assets.assertDownloaded();
            }
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * 构造流式对话模型：SSE 传输 + 调用方提供的执行器。
     *
     * @param provider 已加载的平台配置
     * @param config   对话配置
     * @param executor 承载流读取的执行器
     * @return 流式对话模型
     */
    private static OpenAiCompatibleStreamingChatModel streamingModel(ProviderConfiguration provider,
            OpenAiCompatibleChatConfig config, ExecutorService executor) {
        return new OpenAiCompatibleStreamingChatModel(config,
                new Jdk8ProviderHttpTransport(provider.getTransport().toHttpTransportConfig(),
                        8192, 65536, 100000),
                provider.credentialResolver(), executor, 8, 1000000);
    }

    /**
     * 定位对话用例的平台配置文件。
     *
     * @return 配置文件路径
     * @throws Exception 解析代码位置时
     */
    private static Path configFile() throws Exception {
        return configFile(CONFIG_FILE);
    }

    /**
     * 定位平台配置文件：按已编译测试类的位置回推仓库根目录，因此不受运行目录影响。
     *
     * @param fileName {@code config/model-providers/} 下的文件名
     * @return 配置文件路径
     * @throws Exception 解析代码位置时
     */
    private static Path configFile(String fileName) throws Exception {
        Path classes = Paths.get(DeepSeekChatLiveTest.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        Path file = classes.getParent().getParent().getParent()
                .resolve("config").resolve("model-providers").resolve(fileName);
        Assume.assumeTrue("未找到平台配置文件: " + file.toAbsolutePath(), Files.isRegularFile(file));
        return file;
    }

    /**
     * 凭据来自环境变量且当前进程不可见时跳过用例，并打印排查提示。
     *
     * <p>进程环境是启动时的快照：设置完用户级/系统级环境变量后，已运行的 IDE 及其拉起的测试 JVM
     * 仍然读不到，必须重启 IDE，或在运行配置里显式注入变量名与值。
     *
     * @param provider 已加载的平台配置
     */
    private static void skipWhenCredentialMissing(ProviderConfiguration provider) {
        String environmentVariable = provider.getCredential().getEnvironmentVariable();
        if (environmentVariable == null) {
            System.out.println("[凭据] 使用配置文件内联 apiKey");
            return;
        }
        String value = System.getenv(environmentVariable);
        boolean visible = value != null && !value.trim().isEmpty();
        System.out.println("[凭据] 配置声明的环境变量=" + environmentVariable
                + "，当前进程可见=" + visible);
        if (!visible) {
            System.out.println("[凭据] 若已在系统里设置该变量，说明当前进程没继承到："
                    + "进程环境是启动时快照，需重启 IDE，或在运行配置的环境变量里显式注入；"
                    + "也可改用配置文件内联 apiKey（见 config/model-providers/local/README.md）。");
        }
        Assume.assumeTrue("环境变量 " + environmentVariable
                + " 在当前进程不可见（设置后需重启 IDE/终端，或在运行配置中注入）", visible);
    }

    private static ChatRequest request() {
        return new ChatRequest(Collections.singletonList(text(Role.USER, PROMPT)), parameters(), null, null, context());
    }

    private static ChatMessage text(Role role, String value) {
        return new ChatMessage(role, Collections.<Content>singletonList(new TextContent(value)),
                null, null);
    }

    private static ModelParameters parameters() {
        return new ModelParameters(null, null, null, Integer.valueOf(MAX_TOKENS), null, false);
    }

    /**
     * 按图片配置派生模型登记项：真实平台由注册表提供描述符，用例里只要能力集合与配置一致即可。
     *
     * @param config 图片配置
     * @return 只声明图片生成能力的描述符
     */
    private static ProviderModelDescriptor imageDescriptor(OpenAiCompatibleImageConfig config) {
        return new ProviderModelDescriptor(config.getProvider(), config.getModelId(), config.getModelId(),
                null, null, null, EnumSet.of(ModelCapability.IMAGE_GENERATION));
    }

    /**
     * 构造文生图请求：单张、不带源图，尺寸交给平台默认值。
     *
     * @return 图片请求
     */
    private static ImageGenerationRequest imageRequest() {
        return new ImageGenerationRequest(IMAGE_PROMPT, Collections.<String>emptyList(), null, 1,
                null, null, null, null, null, null, null, null, context());
    }

    private static InvocationContext context() {
        return new InvocationContext("local-debug", null, null, "trace-live",
                System.currentTimeMillis() + 300000L, new CancellationToken());
    }

    /**
     * 取出响应中唯一的工具调用，便于断言与回填结果。
     *
     * @param response 模型响应
     * @return 工具调用内容
     */
    private static ToolCallContent onlyToolCall(ChatResponse response) {
        for (Content content : response.getMessage().getContents()) {
            if (content instanceof ToolCallContent) {
                return (ToolCallContent) content;
            }
        }
        throw new AssertionError("响应中没有工具调用");
    }

    /**
     * 构造 {@code get_weather} 的入参 schema。
     *
     * @return JSON Schema 结构
     */
    private static Map<String, Object> weatherSchema() {
        Map<String, Object> city = new LinkedHashMap<String, Object>();
        city.put("type", "string");
        city.put("description", "城市名称，例如：杭州");
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put("city", city);
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("city"));
        return schema;
    }

    private static String textOf(ChatResponse response) {
        StringBuilder text = new StringBuilder();
        for (Content content : response.getMessage().getContents()) {
            if (content instanceof TextContent) {
                text.append(((TextContent) content).getText());
            }
        }
        return text.toString();
    }

    private static String describeUsage(ChatResponse response) {
        if (response.getTokenUsage() == null) {
            return "无";
        }
        return "输入=" + response.getTokenUsage().getInputTokens()
                + " 输出=" + response.getTokenUsage().getOutputTokens()
                + " 合计=" + response.getTokenUsage().getTotalTokens()
                + " 缓存=" + response.getTokenUsage().getCacheTokens();
    }

    /**
     * 展开领域错误：错误对象默认的 {@code toString} 看不出任何信息，控制台里需要它的码与归因字段。
     *
     * @param error 错误信息，可为 null
     * @return 可读描述
     */
    private static String describeError(ClaraError error) {
        if (error == null) {
            return "无";
        }
        return "码=" + error.getCode() + " 类别=" + error.getCategory()
                + " 平台=" + error.getProvider() + " 平台码=" + error.getProviderCode()
                + " 可重试=" + error.isRetryable();
    }

    private static void assertFinished(StreamHandle handle) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000L;
        while (!handle.isDone() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue("流式任务未结束", handle.isDone());
    }

    /**
     * 调试用观察者：打印每个流事件，并聚合文本与工具参数片段，记录终态事件与错误。
     *
     * <p>工具参数片段按到达顺序拼接，因此 {@link #toolArguments} 只在「本次流只有一个工具调用」时
     * 与终态响应里的参数一致；用例正是用它交叉验证增量拼装是否与终态结果相同。
     */
    private static final class DebugObserver implements StreamObserver {
        /**
         * 日志前缀，用于区分同一用例里的多次流式调用。
         */
        private final String tag;
        /**
         * 已累积的文本增量。
         */
        private final StringBuilder text = new StringBuilder();
        /**
         * 已累积的工具参数片段。
         */
        private final StringBuilder toolArguments = new StringBuilder();
        /**
         * 终态事件（完成或错误）到达时放行。
         */
        private final CountDownLatch terminal = new CountDownLatch(1);
        /**
         * 终态响应，仅在 {@code RESPONSE_COMPLETE} 时写入。
         */
        private ChatResponse completed;
        /**
         * 错误事件，仅在 {@code ERROR} 时写入。
         */
        private StreamEvent failure;

        private DebugObserver(String tag) {
            this.tag = tag;
        }

        public void onEvent(StreamEvent event) {
            switch (event.getType()) {
                case TEXT_DELTA:
                    text.append(event.getPayload());
                    System.out.println("[" + tag + "] TEXT_DELTA " + event.getPayload());
                    break;
                case REASONING_DELTA:
                    System.out.println("[" + tag + "] REASONING_DELTA " + event.getPayload());
                    break;
                case TOOL_ARGUMENT_DELTA:
                    toolArguments.append(event.getPayload());
                    System.out.println("[" + tag + "] TOOL_ARGUMENT_DELTA id=" + event.getToolCallId()
                            + " name=" + event.getToolName() + " 片段=" + event.getPayload());
                    break;
                case TOOL_CALL_COMPLETE:
                    System.out.println("[" + tag + "] TOOL_CALL_COMPLETE id=" + event.getToolCallId()
                            + " name=" + event.getToolName() + " 参数=" + event.getPayload());
                    break;
                case RESPONSE_COMPLETE:
                    completed = event.getResponse();
                    System.out.println("[" + tag + "] RESPONSE_COMPLETE 文本=" + text
                            + " 结束原因=" + event.getResponse().getFinishReason()
                            + " 用量=" + describeUsage(event.getResponse()));
                    terminal.countDown();
                    break;
                default:
                    failure = event;
                    System.out.println("[" + tag + "] ERROR " + event.getPayload()
                            + " 详情=" + event.getError());
                    terminal.countDown();
                    break;
            }
        }
    }

    /**
     * 调试用图片流观察者：打印每个事件，记录逐张落盘的图片数与终态结果。
     *
     * <p>{@link #imageCount} 用来交叉验证「逐张回调」与「终态汇总」是否一致——这正是流式链路最容易出错的
     * 地方（漏发、重发或收尾时少算）。
     */
    private static final class ImageObserver implements ImageGenerationObserver {
        /**
         * 日志前缀，用于区分同一用例里的多次调用。
         */
        private final String tag;
        /**
         * 终态事件（完成或错误）到达时放行。
         */
        private final CountDownLatch terminal = new CountDownLatch(1);
        /**
         * 逐张回调的图片数量。
         */
        private int imageCount;
        /**
         * 终态结果，仅在 {@code RESPONSE_COMPLETE} 时写入。
         */
        private ImageGenerationResult completed;
        /**
         * 错误事件，仅在 {@code ERROR} 时写入。
         */
        private ImageGenerationEvent failure;

        private ImageObserver(String tag) {
            this.tag = tag;
        }

        public void onEvent(ImageGenerationEvent event) {
            switch (event.getType()) {
                case IMAGE:
                    imageCount++;
                    System.out.println("[" + tag + "] IMAGE 序号=" + event.getImageIndex()
                            + " 资产=" + event.getAssetId());
                    break;
                case IMAGE_FAILED:
                    System.out.println("[" + tag + "] IMAGE_FAILED 序号=" + event.getImageIndex()
                            + " 错误=" + describeError(event.getError()));
                    break;
                case RESPONSE_COMPLETE:
                    completed = event.getResult();
                    System.out.println("[" + tag + "] RESPONSE_COMPLETE 图片数="
                            + event.getResult().getAssetIds().size());
                    terminal.countDown();
                    break;
                default:
                    failure = event;
                    System.out.println("[" + tag + "] ERROR " + describeError(event.getError()));
                    terminal.countDown();
                    break;
            }
        }
    }

    /**
     * 最小可用的资源桥：把平台返回的图片真正下载下来写到临时文件，用文件路径当资产 ID。
     *
     * <p>生产环境的资源桥在 application 层（含对象存储与签名 URL），这里只需要「能看到字节」，
     * 因此刻意只实现图片落盘需要的那三个方法，其余方法一律拒绝调用。
     */
    private static final class DownloadingAssets implements ProviderMediaAssetBridge {
        /**
         * 已落盘的文件，顺序与下载顺序一致。
         */
        private final List<Path> files = new ArrayList<Path>();
        /**
         * 与 {@link #files} 一一对应的字节数，用于断言内容非空。
         */
        private final List<Long> sizes = new ArrayList<Long>();

        public String inputReference(String assetId, MediaType expectedType, InvocationContext context) {
            throw new UnsupportedOperationException("文生图用例不涉及源图");
        }

        public byte[] inputBytes(String assetId, MediaType expectedType, int maxBytes, InvocationContext context) {
            throw new UnsupportedOperationException("文生图用例不涉及源图");
        }

        public String storeRemote(String sourceUrl, MediaType type, String mimeType, InvocationContext context) {
            try {
                URLConnection connection = new URL(sourceUrl).openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(60000);
                try (InputStream input = connection.getInputStream()) {
                    // 平台给的是签名 URL，后缀不一定可靠，优先按响应声明的类型决定落盘扩展名
                    return persist(readAll(input), extension(connection.getContentType(), sourceUrl));
                }
            } catch (IOException exception) {
                throw new IllegalStateException("下载平台返回的图片失败: " + sourceUrl, exception);
            }
        }

        public MediaUpload remoteUpload(String sourceUrl, MediaType type, String mimeType,
                InvocationContext context) {
            throw new UnsupportedOperationException("生图用例不使用上传通道");
        }

        public String storeBytes(byte[] content, MediaType type, String mimeType, InvocationContext context) {
            return persist(content, extension(mimeType, null));
        }

        /**
         * 把字节写到临时文件并记录，返回文件绝对路径。
         *
         * @param content 图片字节
         * @param suffix  文件扩展名，以点开头
         * @return 文件绝对路径
         */
        private String persist(byte[] content, String suffix) {
            try {
                Path file = Files.createTempFile("clara-live-image-", suffix);
                Files.write(file, content);
                files.add(file);
                sizes.add(Long.valueOf(content.length));
                return file.toAbsolutePath().toString();
            } catch (IOException exception) {
                throw new IllegalStateException("写入生成结果失败", exception);
            }
        }

        /**
         * 推断落盘扩展名，让临时文件可以直接用看图工具打开。
         *
         * <p>顺序：先看声明的 MIME 类型，其次看 URL 路径的后缀，都判断不出来才退回 {@code .bin}。
         *
         * @param contentType 响应头或调用方给出的 MIME 类型，可为 null
         * @param sourceUrl   结果地址，可为 null
         * @return 以点开头的扩展名
         */
        private static String extension(String contentType, String sourceUrl) {
            String mime = contentType == null ? "" : contentType.toLowerCase();
            if (mime.contains("jpeg") || mime.contains("jpg")) {
                return ".jpg";
            }
            if (mime.contains("png")) {
                return ".png";
            }
            if (mime.contains("webp")) {
                return ".webp";
            }
            if (mime.contains("gif")) {
                return ".gif";
            }
            if (sourceUrl != null) {
                String path = sourceUrl.split("\\?")[0].toLowerCase();
                for (String candidate : Arrays.asList(".jpg", ".jpeg", ".png", ".webp", ".gif")) {
                    if (path.endsWith(candidate)) {
                        return candidate;
                    }
                }
            }
            return ".bin";
        }

        /**
         * 逐块读完全部字节（Java 8 没有 {@code InputStream#readAllBytes}）。
         *
         * @param input 输入流
         * @return 全部字节
         * @throws IOException 读取失败时
         */
        private static byte[] readAll(InputStream input) throws IOException {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int count;
            while ((count = input.read(chunk)) >= 0) {
                buffer.write(chunk, 0, count);
            }
            return buffer.toByteArray();
        }

        /**
         * 描述落盘结果，便于在控制台比对大小。
         *
         * @return 可读描述
         */
        private String describe() {
            StringBuilder text = new StringBuilder("落盘文件=");
            for (int index = 0; index < files.size(); index++) {
                text.append(files.get(index)).append('(').append(sizes.get(index)).append(" 字节) ");
            }
            return text.toString();
        }

        /**
         * 断言至少落盘一张图片且内容非空。
         */
        private void assertDownloaded() {
            assertFalse("没有任何图片落盘", files.isEmpty());
            for (int index = 0; index < files.size(); index++) {
                assertTrue("落盘的图片内容为空: " + files.get(index), sizes.get(index).longValue() > 0L);
            }
        }
    }
}
