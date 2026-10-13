package com.clara.adapter.cli;

import com.clara.ApplicationBootstrap;
import com.clara.config.ConfigLocator;
import com.clara.enums.CapabilityKindEnum;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.kernel.http.execute.Jdk8HttpTransport;
import com.clara.media.video.VideoGenerationClient;
import com.clara.orchestration.api.ConversationRequest;
import com.clara.orchestration.api.ConversationResult;
import com.clara.orchestration.api.OrchestrationStatusEnum;
import com.clara.session.ModelBinding;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 非交互媒体生成入口：让主模型通过 {@code run_command} 以子命令方式调用 clara.jar 内的生图 / 生视频能力。
 *
 * <p>是什么、在哪一层：application 适配层（{@code com.clara.adapter.cli}）的命令行入口之一，与交互式
 * {@link com.clara.ClaraApplication} 共用同一份 {@link ApplicationBootstrap} 装配。宿主在识别到首参为
 * {@code image} / {@code video} 时转交本类，执行完即退出，不进入对话循环。
 *
 * <p>为什么需要：主模型（如 DeepSeek）只负责意图解析，需要出图 / 出视频时以「执行命令」的方式驱动本入口，
 * 复用 clara.jar 内已配置的能力（默认 {@code volcengine-response} 的字节 Seedream / Seedance），不新增工具
 * 或改变既有工具链路。为避免与交互式会话争用媒体库目录锁，且避免传输层调试输出污染结果，本入口直接按能力
 * 调用模型并自行落盘：生成期间把标准输出重定向到丢弃流，结束后只打印一行结果 JSON。
 *
 * <p>执行流程：解析命令行 → 装配 {@link ApplicationBootstrap} → 按能力生成（图片走 IMAGE 能力一次往返；
 * 视频走 {@link VideoGenerationClient} 的提交 + 轮询）→ 取回媒体引用（URL / data URI / base64）→ 落成文件
 * → 以单行 JSON 打印结果（含文件绝对路径）。
 *
 * <p>入参：{@code image --prompt "画面描述" [--out 输出文件] [--model 模型别名]}；
 * {@code video --prompt "画面描述" [--out 输出文件] [--model 模型别名]}；{@code --subject} 等价于
 * {@code --prompt}。缺省输出到 {@code <工作目录>/clara-output/}，缺省模型别名 {@code volcengine-response}。
 * 失败语义：参数非法返回 {@link CliExitCodes#INVALID}；生成或落盘失败返回 {@link CliExitCodes#FAILED}，
 * 并在标准输出给出错误 JSON。
 */
public final class MediaGenerationCli {

    /**
     * JSON 映射器，用于打印结果对象。
     */
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 生图子命令名。
     */
    private static final String IMAGE_COMMAND = "image";

    /**
     * 生视频子命令名。
     */
    private static final String VIDEO_COMMAND = "video";

    /**
     * 缺省模型别名（config/model-providers 下去掉扩展名的名字）。
     */
    private static final String DEFAULT_ALIAS = "volcengine-response";

    /**
     * 缺省输出目录名（相对工作目录）。
     */
    private static final String DEFAULT_OUTPUT_DIR = "clara-output";

    /**
     * 输出文件名中的时间戳格式。
     */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /**
     * 单个媒体文件读取上限（字节），超出即拒绝。
     */
    private static final int MAX_BYTES = 128 * 1024 * 1024;

    /**
     * 下载连接超时（毫秒）。
     */
    private static final int CONNECT_TIMEOUT_MS = 10_000;

    /**
     * 下载读取超时（毫秒）。
     */
    private static final int READ_TIMEOUT_MS = 180_000;

    /**
     * 丢弃流：生成期间吞掉传输层打印到标准输出的原始报文，保证模型只看到最终结果 JSON。
     */
    private static final OutputStream DISCARD = new OutputStream() {
        @Override
        public void write(int value) {
            // 丢弃
        }

        @Override
        public void write(byte[] buffer, int offset, int length) {
            // 丢弃
        }
    };

    private MediaGenerationCli() {
    }

    /**
     * 判断首个命令行参数是否为媒体生成子命令。
     *
     * @param firstArg 首个参数，可为 null
     * @return 是 {@code image} / {@code video} 时返回 true
     */
    public static boolean supports(String firstArg) {
        if (firstArg == null) {
            return false;
        }
        String value = firstArg.trim().toLowerCase(Locale.ROOT);
        return IMAGE_COMMAND.equals(value) || VIDEO_COMMAND.equals(value);
    }

    /**
     * 执行媒体生成子命令，返回退出码。
     *
     * @param args 完整命令行参数，数组首项为子命令名
     * @return 退出码，见 {@link CliExitCodes}
     */
    public static int run(String[] args) {
        String command = args[0].trim().toLowerCase(Locale.ROOT);
        boolean video = VIDEO_COMMAND.equals(command);
        Map<String, String> options;
        try {
            options = parseOptions(args);
        } catch (IllegalArgumentException invalid) {
            System.out.println(errorJson(invalid.getMessage()));
            return CliExitCodes.INVALID;
        }
        String prompt = firstNonBlank(options.get("--prompt"), options.get("--subject"));
        if (prompt == null) {
            System.out.println(errorJson("缺少必填参数 --prompt：请给出要生成的画面描述。"));
            return CliExitCodes.INVALID;
        }
        String alias = firstNonBlank(options.get("--model"), DEFAULT_ALIAS);
        String out = options.get("--out");

        PrintStream realOut = System.out;
        String resultJson;
        int exitCode;
        System.setOut(new PrintStream(DISCARD, true));
        try (ApplicationBootstrap bootstrap = new ApplicationBootstrap()) {
            Generated generated = video
                    ? generateVideo(bootstrap, alias, prompt, out, realOut)
                    : generateImage(bootstrap, alias, prompt, out, realOut);
            resultJson = generated.json;
            exitCode = generated.ok ? CliExitCodes.SUCCESS : CliExitCodes.FAILED;
        } catch (Exception failure) {
            resultJson = errorJson(rootMessage(failure));
            exitCode = CliExitCodes.FAILED;
        } finally {
            System.setOut(realOut);
        }
        realOut.println(resultJson);
        return exitCode;
    }

    /**
     * 按 IMAGE 能力生成图片并落成文件。
     */
    private static Generated generateImage(ApplicationBootstrap bootstrap, String alias, String prompt,
            String out, PrintStream progress) throws IOException {
        ProviderConfiguration configuration = resolve(bootstrap, alias, CapabilityKindEnum.IMAGE);
        String model = modelOf(configuration, CapabilityKindEnum.IMAGE);
        progress.println("[info] 调用图片生成能力：alias=" + alias + "，model=" + model);
        ConversationResult result = bootstrap.orchestrator().converse(ConversationRequest.builder()
                .conversationId("media-cli-image-" + LocalDateTime.now().format(STAMP))
                .provider(configuration.getProvider())
                .model(model)
                .capabilityKind(CapabilityKindEnum.IMAGE)
                .userInput(prompt)
                .providerConfiguration(configuration)
                .stream(false)
                .build());
        if (result.getStatus() != OrchestrationStatusEnum.COMPLETED) {
            return Generated.failed("图片生成失败（" + alias + "）：" + statusDetail(result));
        }
        String reference = result.getContent();
        if (reference == null || reference.trim().isEmpty()) {
            return Generated.failed("图片生成失败：模型未返回图片内容。");
        }
        progress.println("[info] 已收到生成结果，正在下载并保存图片…");
        ImagePayload payload = readImage(reference.trim());
        Path target = outputFile(out, IMAGE_COMMAND, extensionFor(payload.mimeType));
        Files.createDirectories(target.getParent());
        Files.write(target, payload.bytes);
        progress.println("[info] 图片已保存：" + target.toAbsolutePath());
        return Generated.ok(target, IMAGE_COMMAND, payload.mimeType);
    }

    /**
     * 按 VIDEO 能力提交文生视频任务并轮询，落成文件。
     */
    private static Generated generateVideo(ApplicationBootstrap bootstrap, String alias, String prompt,
            String out, PrintStream progress) throws IOException {
        ProviderConfiguration configuration = resolve(bootstrap, alias, CapabilityKindEnum.VIDEO);
        progress.println("[info] 调用视频生成能力：alias=" + alias);
        String url = new VideoGenerationClient(new Jdk8HttpTransport()).generate(configuration, prompt, progress::println);
        progress.println("[info] 视频生成完成，正在下载并保存…");
        byte[] bytes = download(url);
        Path target = outputFile(out, VIDEO_COMMAND, ".mp4");
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        progress.println("[info] 视频已保存：" + target.toAbsolutePath());
        return Generated.ok(target, VIDEO_COMMAND, "video/mp4");
    }

    /**
     * 解析别名到平台配置，并校验该能力已配置。
     */
    private static ProviderConfiguration resolve(ApplicationBootstrap bootstrap, String alias,
            CapabilityKindEnum capability) {
        ModelBinding binding = bootstrap.modelRegistry().resolve(null, alias);
        ProviderConfiguration configuration = binding.configuration();
        // 该平台未配置目标能力时立即失败（配置问题，不静默降级）
        configuration.getCapability(capability);
        return configuration;
    }

    private static String modelOf(ProviderConfiguration configuration, CapabilityKindEnum capability) {
        return configuration.toConfig(capability).getModelId();
    }

    /**
     * 解析 {@code --prompt} / {@code --subject} / {@code --out} / {@code --model} 选项。
     */
    private static Map<String, String> parseOptions(String[] args) {
        Map<String, String> options = new LinkedHashMap<String, String>();
        for (int index = 1; index < args.length; index++) {
            String key = args[index];
            if (!"--prompt".equals(key) && !"--subject".equals(key) && !"--out".equals(key) && !"--model".equals(key)) {
                throw new IllegalArgumentException("未知参数：" + key
                        + "（可用：--prompt / --subject / --out / --model）");
            }
            if (index + 1 >= args.length) {
                throw new IllegalArgumentException("参数 " + key + " 缺少取值。");
            }
            options.put(key, args[++index]);
        }
        return options;
    }

    /**
     * 读取图片引用为字节与 MIME：支持公网 URL、{@code data:} URI 与原始 base64。
     */
    private static ImagePayload readImage(String reference) throws IOException {
        String lower = reference.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            byte[] bytes = download(reference);
            return new ImagePayload(bytes, detectMime(bytes));
        }
        String base64 = reference;
        String declared = null;
        if (reference.startsWith("data:")) {
            int comma = reference.indexOf(',');
            if (comma < 0) {
                throw new IllegalArgumentException("data URI 缺少逗号分隔的载荷");
            }
            String header = reference.substring("data:".length(), comma);
            declared = header.endsWith(";base64")
                    ? header.substring(0, header.length() - ";base64".length()) : header;
            base64 = reference.substring(comma + 1);
        }
        byte[] bytes = Base64.getMimeDecoder().decode(base64);
        String mime = declared != null && !declared.trim().isEmpty() ? normalizeMime(declared) : detectMime(bytes);
        return new ImagePayload(bytes, mime);
    }

    private static byte[] download(String url) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("媒体下载失败：HTTP " + status);
            }
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int total = 0;
                int read;
                while ((read = input.read(chunk)) >= 0) {
                    total += read;
                    if (total > MAX_BYTES) {
                        throw new IOException("媒体超出大小上限：" + MAX_BYTES + " 字节");
                    }
                    buffer.write(chunk, 0, read);
                }
                return buffer.toByteArray();
            }
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String detectMime(byte[] bytes) {
        if (startsWith(bytes, new int[] {0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})) {
            return "image/png";
        }
        if (startsWith(bytes, new int[] {0xFF, 0xD8, 0xFF})) {
            return "image/jpeg";
        }
        if (startsWith(bytes, new int[] {'G', 'I', 'F', '8'})) {
            return "image/gif";
        }
        return "image/png";
    }

    private static boolean startsWith(byte[] bytes, int[] prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if ((bytes[index] & 0xFF) != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static String normalizeMime(String value) {
        int semicolon = value.indexOf(';');
        String mime = (semicolon < 0 ? value : value.substring(0, semicolon)).trim().toLowerCase(Locale.ROOT);
        return mime.isEmpty() ? null : mime;
    }

    /**
     * 计算输出文件：显式 {@code --out} 优先，否则落在 {@code <工作目录>/clara-output/} 下。
     */
    private static Path outputFile(String out, String prefix, String extension) {
        if (out != null && !out.trim().isEmpty()) {
            return Paths.get(out.trim()).toAbsolutePath().normalize();
        }
        String name = prefix + "-" + LocalDateTime.now().format(STAMP) + extension;
        return ConfigLocator.workspaceRoot().resolve(DEFAULT_OUTPUT_DIR).resolve(name);
    }

    private static String extensionFor(String mimeType) {
        if (mimeType == null) {
            return ".png";
        }
        String value = mimeType.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("image/jpeg") || value.startsWith("image/jpg")) {
            return ".jpg";
        }
        if (value.startsWith("image/gif")) {
            return ".gif";
        }
        return ".png";
    }

    private static String statusDetail(ConversationResult result) {
        return result.getError() == null ? result.getStatus().name() : result.getError().getSafeMessage();
    }

    private static String successJson(Path target, String type, String mimeType) {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("status", "ok");
        value.put("type", type);
        value.put("file", target.toAbsolutePath().toString());
        value.put("mimeType", mimeType);
        return json(value);
    }

    private static String errorJson(String message) {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("status", "failed");
        value.put("error", message);
        return json(value);
    }

    private static String json(Map<String, Object> value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception failure) {
            return "{\"status\":\"failed\",\"error\":\"结果序列化失败\"}";
        }
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.trim().isEmpty()) {
            return first.trim();
        }
        if (second != null && !second.trim().isEmpty()) {
            return second.trim();
        }
        return null;
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.trim().isEmpty() ? current.getClass().getSimpleName() : message;
    }

    /**
     * 生成结果：是否成功，以及要打印给调用方的一行 JSON。
     */
    private static final class Generated {

        private final boolean ok;
        private final String json;

        private Generated(boolean ok, String json) {
            this.ok = ok;
            this.json = json;
        }

        static Generated ok(Path target, String type, String mimeType) {
            return new Generated(true, successJson(target, type, mimeType));
        }

        static Generated failed(String message) {
            return new Generated(false, errorJson(message));
        }
    }

    /**
     * 图片载荷：解码后的字节与识别出的 MIME 类型。
     */
    private static final class ImagePayload {

        private final byte[] bytes;
        private final String mimeType;

        ImagePayload(byte[] bytes, String mimeType) {
            this.bytes = bytes;
            this.mimeType = mimeType == null ? "image/png" : mimeType;
        }
    }
}
