package com.clara.model.provider.deepseek;

import com.clara.context.ConvContext;
import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.FinishReasonEnum;
import com.clara.kernel.api.IStreamModel;
import com.clara.kernel.config.ModelConfig;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.kernel.http.HttpRequest;
import com.clara.kernel.http.HttpResponse;
import com.clara.kernel.http.RawHttpResponse;
import com.clara.kernel.http.execute.Jdk8HttpTransport;
import com.clara.kernel.message.IContent;
import com.clara.kernel.message.delta.ImageItem;
import com.clara.kernel.message.impl.ToolContent;
import com.clara.kernel.message.impl.UserContent;
import com.clara.kernel.stream.IStreamHandle;
import com.clara.model.configuration.ProviderYamlConfigurationLoader;
import com.clara.model.provider.CompatibleStream;
import com.clara.model.stream.RecordingObserver;
import com.clara.tool.ToolCall;
import com.clara.tool.ToolCallContent;
import com.clara.tool.ToolContext;
import com.clara.tool.ToolSpecification;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.Assume;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * 加载真实平台配置、直接调用真实接口的用例（不使用任何模拟）。
 *
 * <p>覆盖场景，按平台分组：
 * <ul>
 *   <li>DeepSeek 对话：{@link #deepseekChatNonStreaming()} 非流式、{@link #deepseekChatStreaming()} 流式；</li>
 *   <li>DeepSeek 工具调用：{@link #deepseekToolCallNonStreaming()} 非流式、
 *       {@link #deepseekToolCallStreaming()} 流式（声明工具 → 收到 tool_calls → 回填结果 → 拿到最终回答）；</li>
 *   <li>火山方舟对话：{@link #volcengineChatNonStreaming()} 非流式、{@link #volcengineChatStreaming()} 流式；</li>
 *   <li>火山方舟工具调用：{@link #volcengineToolCallNonStreaming()} 非流式、
 *       {@link #volcengineToolCallStreaming()} 流式；</li>
 *   <li>火山方舟生图：{@link #volcengineImageGeneration()} 非流式文生图（并把结果真正下载下来）。</li>
 * </ul>
 *
 * <p>对话用例加载 {@code config/model-providers/deepseek.yml} 与 {@code volcengine.yml}，生图用例加载
 * {@code volcengine.yml}——DeepSeek 没有图片能力，图片只能打在火山方舟上。
 *
 * <p>调用链固定为统一入口，不含任何平台分支：
 * {@code CompatibleStream#chat} → {@code Jdk8HttpTransport} → {@code HttpRequest#toRequest()}
 * （按 {@code @ProviderAdapter} 筛字段编码）→ 真实 HTTP → 按 {@code @ResponseAdapter} 解码为
 * {@link HttpResponse}。平台与能力由 {@link ConvContext} 承载，所以每个用例都要先绑定上下文再调用；
 * 流式用例统一用 {@link RecordingObserver} 收事件，不再各自实现观察者。
 *
 * <p>运行前置条件：
 * <ol>
 *   <li>上述两个配置文件存在，且所用能力指向账号可用的模型；</li>
 *   <li>配置文件 {@code credential} 里声明的环境变量<b>在当前进程可见</b>，或已内联 {@code apiKey}；
 *       进程环境是启动时的快照，系统里设置后必须重启 IDE，或在运行配置的环境变量里注入。</li>
 * </ol>
 * 任一条件不满足时用例会被 {@link Assume} 跳过，不会让常规构建失败；条件满足时是真实网络调用，
 * 会产生真实计费。
 */
public class DeepSeekChatLiveTest {

    /**
     * DeepSeek 平台配置文件名。
     */
    private static final String DEEPSEEK_CONFIG = "deepseek.yml";
    /**
     * 火山方舟平台配置文件名。
     */
    private static final String VOLCENGINE_CONFIG = "volcengine.yml";
    /**
     * 对话提示词。
     */
    private static final String PROMPT = "用一句话介绍你自己";
    /**
     * 单次调用允许的最大输出长度；思考模式会额外消耗 token，取值要留出余量。
     */
    private static final int MAX_TOKENS = 1024;
    /**
     * 等待流式结束的上限，秒；应大于配置中的 readTimeoutMillis。
     */
    private static final long STREAM_TIMEOUT_SECONDS = 200L;
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
    private static final ToolSpecification WEATHER_TOOL =
            new ToolSpecification(WEATHER_TOOL_NAME, "查询指定城市的当前天气", weatherSchema());
    /**
     * 工具执行结果，回填给模型的第二轮回执。
     */
    private static final String TOOL_RESULT = "{\"city\":\"杭州\",\"weather\":\"晴\",\"temperature\":\"26℃\"}";
    /**
     * 生图提示词，明确要求不要出现文字，便于肉眼核对结果。
     */
    private static final String IMAGE_PROMPT = "一只戴着圆框墨镜的柴犬，坐在沙滩上，写实摄影风格，画面中不要出现任何文字";
    /**
     * 生图尺寸，显式给定以免依赖平台默认值。
     */
    private static final String IMAGE_SIZE = "2048x2048";
    /**
     * 解析工具调用参数用的映射器。
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    // ------------------------------------------------------------------ DeepSeek 对话

    /**
     * DeepSeek 非流式对话：一次拿到完整响应，检查文本、结束原因与 token 用量。
     *
     * @throws Exception 读取配置或解析响应时
     */
    @Test
    public void deepseekChatNonStreaming() throws Exception {
        try (ProviderConfiguration provider = load(DEEPSEEK_CONFIG)) {
            skipWhenCredentialMissing(provider);
            ModelConfig config = provider.toConfig(CapabilityKindEnum.CHAT);
            try (ConvContext.Scope ignored = ConvContext.open(context(provider, CapabilityKindEnum.CHAT))) {
                IStreamModel model = new CompatibleStream(new Jdk8HttpTransport());
                HttpResponse response = model.chat(
                        request(config.getModelId(), userMessages(PROMPT), null, false), HttpResponse.class);

                System.out.println("[DeepSeek 非流式] model=" + config.getModelId() + " " + describe(response));
                assertSuccess(response);
                assertFalse("响应没有正文", isBlank(response.getContent()));
                assertEquals(FinishReasonEnum.STOP, response.getFinishReason());
                assertNotNull("真实响应应带 token 用量", response.getInputTokens());
                assertTrue("输入 token 应大于 0", response.getInputTokens() > 0);
            }
        }
    }

    /**
     * DeepSeek 流式对话：按事件观察增量，交叉验证累加结果与终态响应一致。
     *
     * @throws Exception 读取配置或等待事件时
     */
    @Test
    public void deepseekChatStreaming() throws Exception {
        try (ProviderConfiguration provider = load(DEEPSEEK_CONFIG)) {
            skipWhenCredentialMissing(provider);
            ModelConfig config = provider.toConfig(CapabilityKindEnum.CHAT);
            try (ConvContext.Scope ignored = ConvContext.open(context(provider, CapabilityKindEnum.CHAT))) {
                IStreamModel model = new CompatibleStream(new Jdk8HttpTransport());
                RecordingObserver<HttpResponse> observer = observer("DeepSeek 流式");
                IStreamHandle handle = model.chat(
                        request(config.getModelId(), userMessages(PROMPT), null, true),
                        HttpResponse.class, observer);

                System.out.println("[DeepSeek 流式] model=" + config.getModelId());
                HttpResponse completed = observer.awaitResponse(STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertFinished(handle);

                System.out.println("[DeepSeek 流式] 事件数=" + observer.eventCount()
                        + " 增量文本长度=" + observer.text().length() + " " + describe(completed));
                assertFalse("响应没有正文", isBlank(completed.getContent()));
                assertFalse("没有收到任何文本增量事件", observer.text().isEmpty());
                assertEquals("增量拼接结果应与终态响应一致", completed.getContent(), observer.text());
                assertEquals(FinishReasonEnum.STOP, completed.getFinishReason());
                assertStreamingUsage(completed);
            }
        }
    }

    /**
     * DeepSeek 非流式工具调用往返：声明工具 → 解码 {@code tool_calls} → 回填结果 → 模型给出最终回答。
     *
     * <p>重点验证入参侧的 {@code tools} 编码、出参侧的 {@code tool_calls} 解码，以及
     * {@code role=tool} + {@code tool_call_id} 的回填链路。
     *
     * @throws Exception 读取配置或解析工具参数时
     */
    @Test
    public void deepseekToolCallNonStreaming() throws Exception {
        try (ProviderConfiguration provider = load(DEEPSEEK_CONFIG)) {
            skipWhenCredentialMissing(provider);
            ModelConfig config = provider.toConfig(CapabilityKindEnum.CHAT);
            try (ConvContext.Scope ignored = ConvContext.open(context(provider, CapabilityKindEnum.CHAT))) {
                IStreamModel model = new CompatibleStream(new Jdk8HttpTransport());

                HttpResponse first = model.chat(request(config.getModelId(), userMessages(TOOL_PROMPT),
                        tools(), false), HttpResponse.class);
                System.out.println("[DeepSeek 工具 非流式] 第一轮 " + describe(first));
                ToolCall call = requireToolCall(first);

                List<IContent> messages = toolResultMessages(call);
                HttpResponse second = model.chat(request(config.getModelId(), messages, tools(), false),
                        HttpResponse.class);
                System.out.println("[DeepSeek 工具 非流式] 第二轮 " + describe(second));
                assertSuccess(second);
                assertFalse("工具结果回填后模型没有给出回答", isBlank(second.getContent()));
                assertEquals(FinishReasonEnum.STOP, second.getFinishReason());
            }
        }
    }

    /**
     * DeepSeek 流式工具调用往返：工具参数以增量片段下发，由 {@link RecordingObserver} 按 index 拼装成完整
     * tool_calls，再回填结果并流式拿到最终回答。
     *
     * @throws Exception 读取配置、等待事件或解析工具参数时
     */
    @Test
    public void deepseekToolCallStreaming() throws Exception {
        try (ProviderConfiguration provider = load(DEEPSEEK_CONFIG)) {
            skipWhenCredentialMissing(provider);
            ModelConfig config = provider.toConfig(CapabilityKindEnum.CHAT);
            try (ConvContext.Scope ignored = ConvContext.open(context(provider, CapabilityKindEnum.CHAT))) {
                IStreamModel model = new CompatibleStream(new Jdk8HttpTransport());

                RecordingObserver<HttpResponse> first = observer("DeepSeek 流式工具");
                IStreamHandle firstHandle = model.chat(request(config.getModelId(), userMessages(TOOL_PROMPT),
                        tools(), true), HttpResponse.class, first);
                first.awaitResponse(STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertFinished(firstHandle);

                System.out.println("[DeepSeek 流式工具] 参数片段数=" + first.toolArgumentFragments()
                        + " 拼装结果=" + first.toolCalls());
                ToolCall call = requireToolCall(first.toolCalls());

                RecordingObserver<HttpResponse> second = observer("DeepSeek 流式工具");
                IStreamHandle secondHandle = model.chat(request(config.getModelId(),
                        toolResultMessages(call), tools(), true), HttpResponse.class, second);
                HttpResponse completed = second.awaitResponse(STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertFinished(secondHandle);

                System.out.println("[DeepSeek 流式工具] 第二轮 " + describe(completed));
                assertFalse("工具结果回填后模型没有给出回答", isBlank(completed.getContent()));
                assertEquals(FinishReasonEnum.STOP, completed.getFinishReason());
                assertStreamingUsage(completed);
            }
        }
    }

    // ------------------------------------------------------------------ 火山方舟对话

    /**
     * 火山方舟非流式对话。
     *
     * @throws Exception 读取配置或解析响应时
     */
    @Test
    public void volcengineChatNonStreaming() throws Exception {
        try (ProviderConfiguration provider = load(VOLCENGINE_CONFIG)) {
            skipWhenCredentialMissing(provider);
            ModelConfig config = provider.toConfig(CapabilityKindEnum.CHAT);
            try (ConvContext.Scope ignored = ConvContext.open(context(provider, CapabilityKindEnum.CHAT))) {
                IStreamModel model = new CompatibleStream(new Jdk8HttpTransport());
                HttpResponse response = model.chat(
                        request(config.getModelId(), userMessages(PROMPT), null, false), HttpResponse.class);

                System.out.println("[火山 非流式] model=" + config.getModelId() + " " + describe(response));
                assertSuccess(response);
                assertFalse("响应没有正文", isBlank(response.getContent()));
                assertEquals(FinishReasonEnum.STOP, response.getFinishReason());
                assertNotNull("真实响应应带 token 用量", response.getInputTokens());
            }
        }
    }

    /**
     * 火山方舟流式对话。
     *
     * @throws Exception 读取配置或等待事件时
     */
    @Test
    public void volcengineChatStreaming() throws Exception {
        try (ProviderConfiguration provider = load(VOLCENGINE_CONFIG)) {
            skipWhenCredentialMissing(provider);
            ModelConfig config = provider.toConfig(CapabilityKindEnum.CHAT);
            try (ConvContext.Scope ignored = ConvContext.open(context(provider, CapabilityKindEnum.CHAT))) {
                IStreamModel model = new CompatibleStream(new Jdk8HttpTransport());
                RecordingObserver<HttpResponse> observer = observer("火山 流式");
                IStreamHandle handle = model.chat(
                        request(config.getModelId(), userMessages(PROMPT), null, true),
                        HttpResponse.class, observer);

                System.out.println("[火山 流式] model=" + config.getModelId());
                HttpResponse completed = observer.awaitResponse(STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertFinished(handle);

                System.out.println("[火山 流式] 事件数=" + observer.eventCount()
                        + " 增量文本长度=" + observer.text().length() + " " + describe(completed));
                assertFalse("响应没有正文", isBlank(completed.getContent()));
                assertEquals("增量拼接结果应与终态响应一致", completed.getContent(), observer.text());
                assertStreamingUsage(completed);
            }
        }
    }

    /**
     * 火山方舟非流式工具调用往返。
     *
     * @throws Exception 读取配置或解析工具参数时
     */
    @Test
    public void volcengineToolCallNonStreaming() throws Exception {
        try (ProviderConfiguration provider = load(VOLCENGINE_CONFIG)) {
            skipWhenCredentialMissing(provider);
            ModelConfig config = provider.toConfig(CapabilityKindEnum.CHAT);
            try (ConvContext.Scope ignored = ConvContext.open(context(provider, CapabilityKindEnum.CHAT))) {
                IStreamModel model = new CompatibleStream(new Jdk8HttpTransport());

                HttpResponse first = model.chat(request(config.getModelId(), userMessages(TOOL_PROMPT),
                        tools(), false), HttpResponse.class);
                System.out.println("[火山 工具 非流式] 第一轮 " + describe(first));
                ToolCall call = requireToolCall(first);

                HttpResponse second = model.chat(request(config.getModelId(),
                        toolResultMessages(call), tools(), false), HttpResponse.class);
                System.out.println("[火山 工具 非流式] 第二轮 " + describe(second));
                assertSuccess(second);
                assertFalse("工具结果回填后模型没有给出回答", isBlank(second.getContent()));
                assertEquals(FinishReasonEnum.STOP, second.getFinishReason());
            }
        }
    }

    /**
     * 火山方舟流式工具调用往返。
     *
     * @throws Exception 读取配置、等待事件或解析工具参数时
     */
    @Test
    public void volcengineToolCallStreaming() throws Exception {
        try (ProviderConfiguration provider = load(VOLCENGINE_CONFIG)) {
            skipWhenCredentialMissing(provider);
            ModelConfig config = provider.toConfig(CapabilityKindEnum.CHAT);
            try (ConvContext.Scope ignored = ConvContext.open(context(provider, CapabilityKindEnum.CHAT))) {
                IStreamModel model = new CompatibleStream(new Jdk8HttpTransport());

                RecordingObserver<HttpResponse> first = observer("火山 流式工具");
                IStreamHandle firstHandle = model.chat(request(config.getModelId(), userMessages(TOOL_PROMPT),
                        tools(), true), HttpResponse.class, first);
                first.awaitResponse(STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertFinished(firstHandle);

                System.out.println("[火山 流式工具] 参数片段数=" + first.toolArgumentFragments()
                        + " 拼装结果=" + first.toolCalls());
                ToolCall call = requireToolCall(first.toolCalls());

                RecordingObserver<HttpResponse> second = observer("火山 流式工具");
                IStreamHandle secondHandle = model.chat(request(config.getModelId(),
                        toolResultMessages(call), tools(), true), HttpResponse.class, second);
                HttpResponse completed = second.awaitResponse(STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                assertFinished(secondHandle);

                System.out.println("[火山 流式工具] 第二轮 " + describe(completed));
                assertFalse("工具结果回填后模型没有给出回答", isBlank(completed.getContent()));
                assertEquals(FinishReasonEnum.STOP, completed.getFinishReason());
                assertStreamingUsage(completed);
            }
        }
    }

    // ------------------------------------------------------------------ 火山方舟生图

    /**
     * 火山方舟非流式文生图：一次拿到汇总结果，并把返回的图片真正下载下来。
     *
     * <p>验证整条生图链路：配置加载 → 请求编码（{@code model}/{@code prompt}/{@code size}/{@code watermark}/
     * {@code response_format}）→ 真实调用 → {@code data[]} 解码为 {@link ImageItem} → 链接可下载。
     *
     * @throws Exception 读取配置或下载结果时
     */
    @Test
    public void volcengineImageGeneration() throws Exception {
        try (ProviderConfiguration provider = load(VOLCENGINE_CONFIG)) {
            skipWhenCredentialMissing(provider);
            ModelConfig config = provider.toConfig(CapabilityKindEnum.IMAGE);
            try (ConvContext.Scope ignored = ConvContext.open(context(provider, CapabilityKindEnum.IMAGE))) {
                IStreamModel model = new CompatibleStream(new Jdk8HttpTransport());
                HttpRequest request = HttpRequest.builder()
                        .model(config.getModelId())
                        .prompt(IMAGE_PROMPT)
                        .size(IMAGE_SIZE)
                        .watermark(Boolean.FALSE)
                        .responseFormat("url")
                        .stream(Boolean.FALSE)
                        .build();

                System.out.println("[火山 生图] model=" + config.getModelId() + " size=" + IMAGE_SIZE);
                HttpResponse response = model.chat(request, HttpResponse.class);
                System.out.println("[火山 生图] 请求标识=" + response.getId()
                        + " 生成张数=" + response.getGeneratedImages() + " 模型=" + response.getModel());
                assertSuccess(response);

                List<ImageItem> images = response.getImages();
                assertNotNull("响应里没有 data 节点", images);
                assertFalse("没有返回任何图片", images.isEmpty());
                for (ImageItem image : images) {
                    assertTrue("图片既没有 url 也没有 b64_json",
                            !isBlank(image.getUrl()) || !isBlank(image.getB64Json()));
                }
                if (response.getGeneratedImages() != null) {
                    assertEquals("生成张数与 data 长度不一致",
                            Integer.valueOf(images.size()), response.getGeneratedImages());
                }

                ImageItem first = images.get(0);
                System.out.println("[火山 生图] 首图尺寸=" + first.getSize() + " url=" + first.getUrl());
                assertFalse("图片内容为空", download(first.getUrl()) == 0);
            }
        }
    }

    // ------------------------------------------------------------------ 通用装配

    /**
     * 加载平台配置文件。
     *
     * @param fileName {@code config/model-providers/} 下的文件名
     * @return 平台配置，非 null，由调用方负责关闭
     * @throws Exception 解析代码位置时
     */
    private static ProviderConfiguration load(String fileName) throws Exception {
        return new ProviderYamlConfigurationLoader().load(configFile(fileName));
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
        System.out.println("[凭据] 配置声明的环境变量=" + environmentVariable + "，当前进程可见=" + visible);
        if (!visible) {
            System.out.println("[凭据] 若已在系统里设置该变量，说明当前进程没继承到："
                    + "进程环境是启动时快照，需重启 IDE，或在运行配置的环境变量里显式注入。");
        }
        Assume.assumeTrue("环境变量 " + environmentVariable + " 在当前进程不可见", visible);
    }

    /**
     * 创建会话上下文：一次真实调用需要它携带平台配置与能力种类，否则编码与解码都取不到上下文。
     *
     * @param provider 平台配置
     * @param kind     本次调用的能力种类
     * @return 会话上下文
     */
    private static ConvContext context(ProviderConfiguration provider, CapabilityKindEnum kind) {
        return new ConvContext("live-user", "live-conversation", provider, kind);
    }

    /**
     * 创建带控制台日志的流事件观察者。
     *
     * @param tag 日志前缀
     * @return 观察者
     */
    private static RecordingObserver<HttpResponse> observer(String tag) {
        return new RecordingObserver<HttpResponse>(HttpResponse.class, System.out, tag);
    }

    /**
     * 构造对话请求。
     *
     * @param modelId  模型 ID，取自平台配置
     * @param messages 消息列表
     * @param tools    工具声明，可为 null
     * @param stream   是否流式
     * @return 请求对象
     */
    private static HttpRequest request(String modelId, List<IContent> messages, List<ToolContext> tools, boolean stream) {
        return HttpRequest.builder()
                .model(modelId)
                .messages(messages)
                .tools(tools)
                .maxTokens(Integer.valueOf(MAX_TOKENS))
                .stream(Boolean.valueOf(stream))
                .build();
    }

    /**
     * 构造只含一条用户消息的消息列表。
     *
     * @param text 用户文本
     * @return 消息列表
     */
    private static List<IContent> userMessages(String text) {
        return new ArrayList<IContent>(Collections.<IContent>singletonList(new UserContent(text)));
    }

    /**
     * 构造工具回填用的消息列表：用户提问 → 助手要求调用工具 → 工具结果。
     *
     * @param call 模型返回的工具调用
     * @return 消息列表
     */
    private static List<IContent> toolResultMessages(ToolCall call) {
        List<IContent> messages = new ArrayList<IContent>();
        messages.add(new UserContent(TOOL_PROMPT));
        messages.add(new ToolCallContent(Collections.singletonList(call)));
        messages.add(new ToolContent(call.getId(), TOOL_RESULT));
        return messages;
    }

    /**
     * 构造工具声明列表。
     *
     * @return 只含天气查询工具的声明列表
     */
    private static List<ToolContext> tools() {
        return Collections.singletonList(new ToolContext(WEATHER_TOOL));
    }

    /**
     * 断言平台调用成功，并取出首个工具调用校验名称与参数。
     *
     * @param response 模型响应
     * @return 首个工具调用
     * @throws Exception 参数不是合法 JSON 时
     */
    private static ToolCall requireToolCall(HttpResponse response) throws Exception {
        assertSuccess(response);
        assertEquals("模型没有按要求调用工具，实际正文=" + response.getContent(),
                FinishReasonEnum.TOOL_CALLS, response.getFinishReason());
        return requireToolCall(response.getToolCalls());
    }

    /**
     * 校验工具调用列表非空，并打印首个调用的名称与参数。
     *
     * @param calls 工具调用列表，可为 null
     * @return 首个工具调用
     * @throws Exception 参数不是合法 JSON 时
     */
    private static ToolCall requireToolCall(List<ToolCall> calls) throws Exception {
        assertNotNull("响应里没有 tool_calls 节点", calls);
        assertFalse("tool_calls 为空数组", calls.isEmpty());
        ToolCall call = calls.get(0);
        JsonNode arguments = JSON.readTree(call.getFunction().getArguments());
        System.out.println("    工具=" + call.getFunction().getName() + " id=" + call.getId()
                + " 参数=" + arguments);
        assertEquals(WEATHER_TOOL_NAME, call.getFunction().getName());
        assertTrue("工具参数缺少 city", arguments.has("city"));
        return call;
    }

    /**
     * 断言平台返回 2xx，否则把状态码与响应体一起抛出——真实接口的报错原因就在响应体里。
     *
     * @param response 已解码的响应
     */
    private static void assertSuccess(HttpResponse response) {
        RawHttpResponse raw = response.getRaw();
        assertNotNull("响应没有回填原始 HTTP 响应", raw);
        int status = raw.getStatusCode();
        if (status < 200 || status >= 300) {
            fail("平台返回非 2xx：status=" + status + " 响应体=" + body(raw));
        }
    }

    /**
     * 断言流式响应带回 token 用量。
     *
     * <p>OpenAI 兼容家族的流式响应默认不回传 usage，必须靠请求里的 {@code stream_options.include_usage}
     * 开启，所以这条断言同时也在守住「流式必须带上 stream_options」这个协议要求。
     *
     * @param response 终态累积响应
     */
    private static void assertStreamingUsage(HttpResponse response) {
        assertNotNull("流式响应没有 token 用量：请求需带 stream_options.include_usage", response.getInputTokens());
        assertTrue("流式响应的输入 token 应大于 0", response.getInputTokens() > 0);
    }

    /**
     * 汇总响应要点，便于在控制台比对。
     *
     * @param response 模型响应
     * @return 可读描述
     */
    private static String describe(HttpResponse response) {
        if (response == null) {
            return "无响应";
        }
        RawHttpResponse raw = response.getRaw();
        return "status=" + (raw == null ? "?" : raw.getStatusCode())
                + " 结束原因=" + response.getFinishReason()
                + " 正文=" + response.getContent()
                + " 工具调用数=" + (response.getToolCalls() == null ? 0 : response.getToolCalls().size())
                + " 用量=" + describeUsage(response);
    }

    /**
     * 汇总 token 用量。
     *
     * @param response 模型响应
     * @return 可读描述
     */
    private static String describeUsage(HttpResponse response) {
        return "输入=" + response.getInputTokens() + " 输出=" + response.getOutputTokens()
                + " 合计=" + response.getTotalTokens() + " 缓存=" + response.getCachedTokens();
    }

    /**
     * 读取响应体文本。
     *
     * @param response 原始响应
     * @return UTF-8 文本
     */
    private static String body(RawHttpResponse response) {
        return new String(response.getBody(), StandardCharsets.UTF_8);
    }

    /**
     * 下载图片并返回字节数，用于确认返回的链接真的可用。
     *
     * @param url 图片地址，可为 null
     * @return 字节数；地址为空时返回 0
     * @throws Exception 下载失败时
     */
    private static int download(String url) throws Exception {
        if (isBlank(url)) {
            return 0;
        }
        URLConnection connection = new URL(url).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(60000);
        InputStream input = connection.getInputStream();
        try {
            byte[] chunk = new byte[8192];
            int total = 0;
            int count;
            while ((count = input.read(chunk)) >= 0) {
                total += count;
            }
            return total;
        } finally {
            input.close();
        }
    }

    /**
     * 等待句柄进入终态。
     *
     * @param handle 流式句柄
     * @throws InterruptedException 等待被中断时
     */
    private static void assertFinished(IStreamHandle handle) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000L;
        while (!handle.isDone() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue("流式任务未结束", handle.isDone());
    }

    /**
     * 判断文本是否为空。
     *
     * @param value 待判断文本
     * @return 为 null 或全空白时返回 true
     */
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
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
}
