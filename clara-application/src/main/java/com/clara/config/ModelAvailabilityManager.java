package com.clara.config;

import com.clara.enums.CapabilityKindEnum;
import com.clara.kernel.config.ModelConfig;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.model.availability.ModelCheckResult;
import com.clara.model.availability.AvailabilityLine;
import com.clara.model.availability.StreamingModelProbe;
import com.clara.model.availability.api.IModelProbe;
import com.clara.model.configuration.ProviderYamlConfigurationLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 模型可用性启动管理器：扫描模型配置、检查凭据与模型响应，并把可用模型清单写回 {@code clara.yml}。
 *
 * <p>本类只负责 application 层的启动编排。实际的模型请求由 {@link IModelProbe} 完成，供应商 YAML 的解析由
 * {@link ProviderYamlConfigurationLoader} 完成。检查结果按首字符响应时间升序排列，最快的模型会成为默认模型。
 */
public final class ModelAvailabilityManager {

    /**
     * 单个模型启动检查的默认超时时间，单位为毫秒。
     */
    private static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;

    /**
     * 根据环境变量名读取当前进程环境变量值的函数，生产环境使用 {@link System#getenv(String)}。
     */
    private final Function<String, String> environment;

    /**
     * 执行真实模型流式请求并测量首字符响应时间的探测器。
     */
    private final IModelProbe probe;

    /**
     * 单个模型探测允许等待的最长时间，单位为毫秒。
     */
    private final long timeoutMillis;

    /**
     * 创建生产环境使用的管理器，使用系统环境变量、默认流式探测器和 30 秒探测超时。
     */
    public ModelAvailabilityManager() {
        this(System::getenv, new StreamingModelProbe(), DEFAULT_TIMEOUT_MILLIS);
    }

    /**
     * 创建可注入依赖的管理器，供测试或其他受控启动场景使用。
     *
     * @param environment   环境变量读取函数
     * @param probe         模型可用性探测器
     * @param timeoutMillis 单个模型的探测超时时间，单位为毫秒
     */
    ModelAvailabilityManager(Function<String, String> environment, IModelProbe probe, long timeoutMillis) {
        this.environment = environment;
        this.probe = probe;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * 依次检查全部模型配置，并把检查结果持久化到 {@code clara.yml}。
     *
     * <p>配置引用的环境变量不存在或为空时直接跳过，不会发起模型请求。成功产生正文字符的模型视为可用，
     * 可用结果按首字符响应时间排序，并以最快模型作为 {@code defaultModel}。全部模型检查完后，各行的状态、
     * 别名、平台与模型、结论四列按本批次最大显示宽度对齐后发送给输出接收器，并带上状态级别：
     * 可用为 {@code OK}、跳过为 {@code WARN}、不可用为 {@code FAIL}，供调用方着色。
     *
     * @param configFiles 待检查的供应商配置文件列表
     * @param claraConfig 要更新的 {@code clara.yml} 文件
     * @param output      状态行接收器；为 null 时静默执行
     * @return 按首字符响应时间升序排列的可用模型配置路径
     * @throws IllegalStateException 无法更新 {@code clara.yml} 时抛出
     */
    public List<Path> checkAndPersist(List<Path> configFiles, Path claraConfig, Consumer<AvailabilityLine> output) {
        Consumer<AvailabilityLine> sink = output == null ? value -> {} : output;
        sink.accept(AvailabilityLine.info("正在检查模型可用性..."));
        List<ModelCheckResult> results = new ArrayList<>();
        ProviderYamlConfigurationLoader loader = new ProviderYamlConfigurationLoader();
        for (Path path : configFiles) {
            String alias = ConfigLocator.aliasOf(path);
            ProviderConfiguration configuration = null;
            try {
                configuration = loader.load(path);
                if (!configuration.getCapabilities().containsKey(CapabilityKindEnum.CHAT)) {
                    results.add(ModelCheckResult.skipped(path, alias, "未配置 CHAT 能力"));
                    continue;
                }
                String variable = configuration.getCredential().getEnvironmentVariable();
                if (variable != null && isBlank(environment.apply(variable))) {
                    results.add(ModelCheckResult.missingCredential(path, alias, variable));
                    continue;
                }
                ModelConfig chat = configuration.toConfig(CapabilityKindEnum.CHAT);
                results.add(probe.probe(path, alias, configuration, chat, timeoutMillis));
            } catch (RuntimeException failure) {
                results.add(ModelCheckResult.unavailable(path, alias, null, null, concise(failure)));
            } finally {
                if (configuration != null) {
                    configuration.close();
                }
            }
        }

        List<ModelCheckResult> available = new ArrayList<>();
        for (AvailabilityLine line : AvailabilityLine.render(results)) {
            sink.accept(line);
        }
        for (ModelCheckResult result : results) {
            if (result.isAvailable()) {
                available.add(result);
            }
        }
        Collections.sort(available, Comparator.comparingLong(ModelCheckResult::getFirstCharacterMillis));
        try {
            persist(claraConfig, available);
        } catch (IOException failure) {
            throw new IllegalStateException("无法更新 " + claraConfig + ": " + failure.getMessage(), failure);
        }
        if (available.isEmpty()) {
            sink.accept(AvailabilityLine.fail("未发现可用模型。"));
        } else {
            ModelCheckResult fastest = available.get(0);
            sink.accept(AvailabilityLine.ok("默认模型：" + fastest.getAlias() + "（首字符响应 " + fastest.getFirstCharacterMillis() + "ms）"));
        }
        List<Path> paths = new ArrayList<>();
        for (ModelCheckResult result : available) {
            paths.add(result.getPath());
        }
        return paths;
    }

    /**
     * 把可用模型清单和最快模型写入 {@code clara.yml}，保留文件中的其他顶层配置。
     *
     * <p>先写入同目录临时文件，再优先使用原子移动替换目标文件；文件系统不支持原子移动时退化为普通替换。
     * 当没有可用模型时写入空的 {@code enabledProviders}，并把 {@code defaultModel} 写为空值。
     *
     * @param file      {@code clara.yml} 文件路径
     * @param available 已按响应时间排序的可用模型结果
     * @throws IOException 读取、创建目录、写入或移动文件失败时抛出
     */
    private static void persist(Path file, List<ModelCheckResult> available) throws IOException {
        List<String> original = Files.isRegularFile(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : Collections.emptyList();
        String defaultLine = "defaultModel:" + (available.isEmpty() ? "" : " " + available.get(0).getAlias());
        StringBuilder enabledLine = new StringBuilder("enabledProviders: [");
        for (int index = 0; index < available.size(); index++) {
            if (index > 0) {
                enabledLine.append(", ");
            }
            enabledLine.append(available.get(index).getAlias());
        }
        enabledLine.append(']');

        List<String> updated = replaceTopLevel(original, "defaultModel", defaultLine);
        updated = replaceTopLevel(updated, "enabledProviders", enabledLine.toString());
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = Files.createTempFile(parent, "clara-", ".yml.tmp");
        Files.write(temporary, updated, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * 替换 YAML 中指定的顶层键，并移除该键原有的缩进子项。
     *
     * <p>该方法用于兼容流式列表与块式列表。目标键不存在时，新配置会插入到文件首行。
     *
     * @param lines       原始文件行
     * @param key         待替换的顶层键名
     * @param replacement 完整替换行
     * @return 替换后的新行列表
     */
    private static List<String> replaceTopLevel(List<String> lines, String key, String replacement) {
        List<String> result = new ArrayList<>();
        boolean replaced = false;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!isTopLevelKey(line, key)) {
                result.add(line);
                continue;
            }
            if (!replaced) {
                result.add(replacement);
                replaced = true;
            }
            while (index + 1 < lines.size() && isIndented(lines.get(index + 1))) {
                index++;
            }
        }
        if (!replaced) {
            result.add(0, replacement);
        }
        return result;
    }

    /**
     * 判断一行是否声明了指定的 YAML 顶层键。
     *
     * @param line 待检查文本行
     * @param key  目标键名
     * @return 行无缩进且冒号前的键名匹配时返回 true
     */
    private static boolean isTopLevelKey(String line, String key) {
        if (line == null || line.isEmpty() || Character.isWhitespace(line.charAt(0))) {
            return false;
        }
        int colon = line.indexOf(':');
        return colon > 0 && key.equals(line.substring(0, colon).trim());
    }

    /**
     * 判断文本行是否以空白字符开头。
     *
     * @param line 待检查文本行
     * @return 非空行存在缩进时返回 true
     */
    private static boolean isIndented(String line) {
        return line != null && !line.isEmpty() && Character.isWhitespace(line.charAt(0));
    }

    /**
     * 判断字符串是否为 null、空串或只包含空白字符。
     *
     * @param value 待检查字符串
     * @return 字符串无有效内容时返回 true
     */
    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /**
     * 把异常压缩为适合控制台展示的单行说明，不包含堆栈信息。
     *
     * @param failure 检查模型配置或能力时捕获的异常
     * @return 由异常类型和非空异常消息组成的简短文本
     */
    private static String concise(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (isBlank(message) ? "" : ": " + message);
    }

}
