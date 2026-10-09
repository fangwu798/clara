package com.clara.model.provider.compatible;

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
import com.clara.core.message.IContent;
import com.clara.kernel.message.delta.ImageItem;
import com.clara.kernel.message.delta.ToolContent;
import com.clara.kernel.message.delta.UserContent;
import com.clara.kernel.stream.IStreamHandle;
import com.clara.model.configuration.ProviderYamlConfigurationLoader;
import com.clara.model.provider.CompatibleStream;
import com.clara.model.stream.RecordingObserver;
import com.clara.tool.ResponsesToolCall;
import com.clara.tool.ToolCall;
import com.clara.tool.ToolCallContent;
import com.clara.tool.ToolCallFunction;
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
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

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
 * <p>用例按「平台 × 协议 × 是否调用工具 × 流式/非流式」四个维度展开，函数命名顺序即
 * {@code [平台][协议][是否工具][流式]}：
 * <ul>
 *   <li>平台：deepseek（DeepSeek）/ volcengine（火山方舟）/ kimi（月之暗面）；</li>
 *   <li>协议：Completion（Chat Completions）/ Response（Responses）；</li>
 *   <li>是否工具：Chat（纯对话）/ Tool（工具调用往返）；</li>
 *   <li>流式：NonStreaming（非流式）/ Streaming（流式）。</li>
 * </ul>
 *
 * <p>对话用例加载 {@code config/model-providers/<平台>-<协议>.yml}，生图用例加载火山方舟的
 * {@code volcengine-response.yml}（含 IMAGE 能力）。每个平台各有 completion / response 两份配置，
 * 端点与 {@code apiProfile} 不同，其余一致。
 *
 * <p>调用链固定为统一入口，不含任何平台分支：
 * {@code CompatibleStream#chat} → {@code Jdk8HttpTransport} → {@code HttpRequest#toRequest()}
 * （按 {@code @ProviderAdapter} / {@code @ProtocolField} 筛字段编码）→ 真实 HTTP →
 * 按 {@code @ResponseAdapter} / {@code @ProtocolResponse} 解码为 {@link HttpResponse}。平台、能力与协议
 * 由 {@link ConvContext} 承载，所以每个用例都要先绑定上下文再调用；流式用例统一用 {@link RecordingObserver}
 * 收事件，不再各自实现观察者。
 *
 * <p>运行前置条件：
 * <ol>
 *   <li>对应配置文件存在，且所用能力指向账号可用的模型；</li>
 *   <li>配置文件 {@code credential} 里声明的环境变量<b>在当前进程可见</b>，或已内联 {@code apiKey}；
 *       进程环境是启动时的快照，系统里设置后必须重启 IDE，或在运行配置的环境变量里注入。</li>
 * </ol>
 * 任一条件不满足时用例会被 {@link Assume} 跳过，不会让常规构建失败；条件满足时是真实网络调用，
 * 会产生真实计费。
 */
public class CompatibleTest {

    /**
     * DeepSeek Chat Completions 配置文件名。
     */
    private static final String DEEPSEEK_COMPLETION = "deepseek-completion.yml";
    /**
     * DeepSeek Responses 配置文件名。
     */
    private static final String DEEPSEEK_RESPONSE = "deepseek-response.yml";
    /**
     * 火山方舟 Chat Completions 配置文件名。
     */
    private static final String VOLCENGINE_COMPLETION = "volcengine-completion.yml";
    /**
     * 火山方舟 Responses 配置文件名。
     */
    private static final String VOLCENGINE_RESPONSE = "volcengine-response.yml";
    /**
     * Kimi Chat Completions 配置文件名。
     */
    private static final String KIMI_COMPLETION = "kimi-completion.yml";
    /**
     * Kimi Responses 配置文件名。
     */
    private static final String KIMI_RESPONSE = "kimi-response.yml";
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

    // ---------------------------------------------------------------- DeepSeek Completion

    @Test
    public void _01_deepseekCompletionChatNonStreaming() throws Exception {
        chat(DEEPSEEK_COMPLETION, "DeepSeek Completion 非流式对话", false);
    }

    @Test
    public void _02_deepseekCompletionChatStreaming() throws Exception {
        chat(DEEPSEEK_COMPLETION, "DeepSeek Completion 流式对话", true);
    }

    @Test
    public void _03_deepseekCompletionToolNonStreaming() throws Exception {
        tool(DEEPSEEK_COMPLETION, "DeepSeek Completion 非流式工具", false);
    }

    @Test
    public void _04_deepseekCompletionToolStreaming() throws Exception {
        tool(DEEPSEEK_COMPLETION, "DeepSeek Completion 流式工具", true);
    }

    // ---------------------------------------------------------------- DeepSeek Response

    @Test
    public void deepseekResponseChatNonStreaming() throws Exception {
        chat(DEEPSEEK_RESPONSE, "DeepSeek Response 非流式对话", false);
    }

    @Test
    public void _06_deepseekResponseChatStreaming() throws Exception {
        chat(DEEPSEEK_RESPONSE, "DeepSeek Response 流式对话", true);
    }

    @Test
    public void deepseekResponseToolNonStreaming() throws Exception {
        tool(DEEPSEEK_RESPONSE, "DeepSeek Response 非流式工具", false);
    }

    @Test
    public void _08_deepseekResponseToolStreaming() throws Exception {
        tool(DEEPSEEK_RESPONSE, "DeepSeek Response 流式工具", true);
    }

    // ---------------------------------------------------------------- 火山方舟 Completion

    @Test
    public void _09_volcengineCompletionChatNonStreaming() throws Exception {
        chat(VOLCENGINE_COMPLETION, "火山 Completion 非流式对话", false);
    }

    @Test
    public void volcengineCompletionChatStreaming() throws Exception {
        chat(VOLCENGINE_COMPLETION, "火山 Completion 流式对话", true);
    }

    @Test
    public void _11_volcengineCompletionToolNonStreaming() throws Exception {
        tool(VOLCENGINE_COMPLETION, "火山 Completion 非流式工具", false);
    }

    @Test
    public void volcengineCompletionToolStreaming() throws Exception {
        tool(VOLCENGINE_COMPLETION, "火山 Completion 流式工具", true);
    }

    // ---------------------------------------------------------------- 火山方舟 Response

    @Test
    public void _13_volcengineResponseChatNonStreaming() throws Exception {
        chat(VOLCENGINE_RESPONSE, "火山 Response 非流式对话", false);
    }

    @Test
    public void _14_volcengineResponseChatStreaming() throws Exception {
        chat(VOLCENGINE_RESPONSE, "火山 Response 流式对话", true);
    }

    @Test
    public void _15_volcengineResponseToolNonStreaming() throws Exception {
        tool(VOLCENGINE_RESPONSE, "火山 Response 非流式工具", false);
    }

    @Test
    public void _16_volcengineResponseToolStreaming() throws Exception {
        tool(VOLCENGINE_RESPONSE, "火山 Response 流式工具", true);
    }

    // ---------------------------------------------------------------- Kimi Completion

    @Test
    public void _18_kimiCompletionChatNonStreaming() throws Exception {
        chat(KIMI_COMPLETION, "Kimi Completion 非流式对话", false);
    }

    @Test
    public void _19_kimiCompletionChatStreaming() throws Exception {
        chat(KIMI_COMPLETION, "Kimi Completion 流式对话", true);
    }

    @Test
    public void _20_kimiCompletionToolNonStreaming() throws Exception {
        tool(KIMI_COMPLETION, "Kimi Completion 非流式工具", false);
    }

    @Test
    public void _21_kimiCompletionToolStreaming() throws Exception {
        tool(KIMI_COMPLETION, "Kimi Completion 流式工具", true);
    }

    // ---------------------------------------------------------------- Kimi Response

    @Test
    public void _22_kimiResponseChatNonStreaming() throws Exception {
        chat(KIMI_RESPONSE, "Kimi Response 非流式对话", false);
    }

    @Test
    public void _23_kimiResponseChatStreaming() throws Exception {
        chat(KIMI_RESPONSE, "Kimi Response 流式对话", true);
    }

    @Test
    public void kimiResponseToolNonStreaming() throws Exception {
        tool(KIMI_RESPONSE, "Kimi Response 非流式工具", false);
    }

    @Test
    public void _25_kimiResponseToolStreaming() throws Exception {
        tool(KIMI_RESPONSE, "Kimi Response 流式工具", true);
    }

    // ---------------------------------------------------------------- 火山方舟生图

    /**
     * 火山方舟非流式文生图：一次拿到汇总结果，并把返回的图片真正下载下来。
     *
     * @throws Exception 读取配置或下载结果时
     */
    @Test
    public void _17_volcengineImageGeneration() throws Exception {
        try (ProviderConfiguration provider = load(VOLCENGINE_RESPONSE)) {
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

    // ------------------------------------------------------------------ 通用测试执行

    /**
     * 纯对话用例：非流式一次取回完整响应，流式按事件观察增量并交叉验证累加结果。
     *
     * @param configFile 平台配置文件
     * @param tag        日志前缀
     * @param stream     是否流式
     * @throws Exception 读取配置或等待事件时
     */
    private static void chat(String configFile, String tag, boolean stream) throws Exception {
        try (ProviderConfiguration provider = load(configFile)) {
            skipWhenCredentialMissing(provider);
            ModelConfig config = provider.toConfig(CapabilityKindEnum.CHAT);
            try (ConvContext.Scope ignored = ConvContext.open(context(provider, CapabilityKindEnum.CHAT))) {
                IStreamModel model = new CompatibleStream(new Jdk8HttpTransport());
                if (stream) {
                    RecordingObserver<HttpResponse> observer = observer(tag);
                    IStreamHandle handle = model.chat(request(config.getModelId(), userMessages(PROMPT), null, true), HttpResponse.class, observer);
                    HttpResponse completed = observer.awaitResponse(STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    assertFinished(handle);
                    System.out.println("[" + tag + "] model=" + config.getModelId() + " 事件数=" + observer.eventCount() + " 增量文本长度=" + observer.text().length() + " " + describe(completed));
                    assertFalse("响应没有正文", isBlank(completed.getContent()));
                    assertFalse("没有收到任何文本增量事件", observer.text().isEmpty());
                    assertEquals("增量拼接结果应与终态响应一致", completed.getContent(), observer.text());
                    assertEquals(FinishReasonEnum.STOP, completed.getFinishReason());
                    assertStreamingUsage(completed);
                } else {
                    HttpResponse response = model.chat(request(config.getModelId(), userMessages(PROMPT), null, false), HttpResponse.class);
                    System.out.println("[" + tag + "] model=" + config.getModelId() + " " + describe(response));
                    assertSuccess(response);
                    assertFalse("响应没有正文", isBlank(response.getContent()));
                    assertEquals(FinishReasonEnum.STOP, response.getFinishReason());
                    assertNotNull("真实响应应带 token 用量", response.getInputTokens());
                    assertTrue("输入 token 应大于 0", response.getInputTokens() > 0);
                }
            }
        }
    }

    /**
     * 工具调用往返用例：声明工具 → 解码 tool_calls → 回填结果 → 模型给出最终回答。
     *
     * @param configFile 平台配置文件
     * @param tag        日志前缀
     * @param stream     是否流式
     * @throws Exception 读取配置、等待事件或解析工具参数时
     */
    private static void tool(String configFile, String tag, boolean stream) throws Exception {
        try (ProviderConfiguration provider = load(configFile)) {
            skipWhenCredentialMissing(provider);
            ModelConfig config = provider.toConfig(CapabilityKindEnum.CHAT);
            try (ConvContext.Scope ignored = ConvContext.open(context(provider, CapabilityKindEnum.CHAT))) {
                IStreamModel model = new CompatibleStream(new Jdk8HttpTransport());
                if (stream) {
                    RecordingObserver<HttpResponse> first = observer(tag);
                    IStreamHandle firstHandle = model.chat(request(config.getModelId(), userMessages(TOOL_PROMPT), tools(), true), HttpResponse.class, first);
                    first.awaitResponse(STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    assertFinished(firstHandle);
                    System.out.println("[" + tag + "] 参数片段数=" + first.toolArgumentFragments() + " 拼装结果=" + first.toolCalls());
                    ToolCall call = requireToolCall(first.toolCalls());

                    RecordingObserver<HttpResponse> second = observer(tag);
                    IStreamHandle secondHandle = model.chat(request(config.getModelId(), toolResultMessages(call), tools(), true), HttpResponse.class, second);
                    HttpResponse completed = second.awaitResponse(STREAM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                    assertFinished(secondHandle);
                    System.out.println("[" + tag + "] 第二轮 " + describe(completed));
                    assertFalse("工具结果回填后模型没有给出回答", isBlank(completed.getContent()));
                    assertEquals(FinishReasonEnum.STOP, completed.getFinishReason());
                    assertStreamingUsage(completed);
                } else {
                    HttpResponse first = model.chat(request(config.getModelId(), userMessages(TOOL_PROMPT), tools(), false), HttpResponse.class);
                    System.out.println("[" + tag + "] 第一轮 " + describe(first));
                    ToolCall call = requireToolCall(first);

                    HttpResponse second = model.chat(request(config.getModelId(), toolResultMessages(call), tools(), false), HttpResponse.class);
                    System.out.println("[" + tag + "] 第二轮 " + describe(second));
                    assertSuccess(second);
                    assertFalse("工具结果回填后模型没有给出回答", isBlank(second.getContent()));
                    assertEquals(FinishReasonEnum.STOP, second.getFinishReason());
                }
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
        Path classes = Paths.get(CompatibleTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path file = classes.getParent().getParent().getParent().resolve("config").resolve("model-providers").resolve(fileName);
        Assume.assumeTrue("未找到平台配置文件: " + file.toAbsolutePath(), Files.isRegularFile(file));
        return file;
    }

    /**
     * 凭据来自环境变量且当前进程不可见时跳过用例，并打印排查提示。
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
            System.out.println("[凭据] 若已在系统里设置该变量，说明当前进程没继承到：" + "进程环境是启动时快照，需重启 IDE，或在运行配置的环境变量里显式注入。");
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
        return new RecordingObserver<>(HttpResponse.class, System.out, tag);
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
        return new ArrayList<>(Collections.<IContent>singletonList(new UserContent(text)));
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
        if (response.getResponsesToolCalls() != null && !response.getResponsesToolCalls().isEmpty()) {
            List<ToolCall> calls = new ArrayList<ToolCall>();
            for (ResponsesToolCall call : response.getResponsesToolCalls()) {
                calls.add(toToolCall(call));
            }
            return requireToolCall(calls);
        }
        return requireToolCall(response.getToolCalls());
    }

    /**
     * 把 Responses 协议的独立工具调用类型转换为 Chat Completions 的 {@link ToolCall}，供回填消息复用。
     *
     * @param call Responses 工具调用，不可为 null
     * @return 统一契约类型的工具调用
     */
    private static ToolCall toToolCall(ResponsesToolCall call) {
        ToolCall toolCall = new ToolCall();
        toolCall.setId(call.getCallId());
        toolCall.setType("function");
        ToolCallFunction function = new ToolCallFunction();
        function.setName(call.getName());
        function.setArguments(call.getArguments());
        toolCall.setFunction(function);
        return toolCall;
    }

    /**
     * 校验工具调用列表非空，并打印首个调用的名称与参数。
     *
     * @param calls 工具调用列表，可为 null
     * @return 首个工具调用
     * @throws Exception 参数不是合法 JSON 时
     */
    private static ToolCall requireToolCall(List<ToolCall> calls) throws Exception {
        calls = calls.stream().filter(p -> !Objects.equals(p.getType(), "reasoning")).collect(Collectors.toList());
        assertNotNull("响应里没有 tool_calls 节点", calls);
        assertFalse("tool_calls 为空数组", calls.isEmpty());
        ToolCall call = calls.get(0);
        JsonNode arguments = JSON.readTree(call.getFunction().getArguments());
        System.out.println("    工具=" + call.getFunction().getName() + " id=" + call.getId() + " 参数=" + arguments);
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
        int toolCalls = (response.getToolCalls() == null ? 0 : response.getToolCalls().size()) + (response.getResponsesToolCalls() == null ? 0 : response.getResponsesToolCalls().size());
        return "status=" + (raw == null ? "?" : raw.getStatusCode()) + " 结束原因=" + response.getFinishReason() + " 正文=" + response.getContent() + " 工具调用数=" + toolCalls + " 用量=" + describeUsage(response);
    }

    /**
     * 汇总 token 用量。
     *
     * @param response 模型响应
     * @return 可读描述
     */
    private static String describeUsage(HttpResponse response) {
        return "输入=" + response.getInputTokens() + " 输出=" + response.getOutputTokens() + " 合计=" + response.getTotalTokens() + " 缓存=" + response.getCachedTokens();
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
