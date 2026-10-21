package com.clara.config;

import com.clara.utils.StringUtils;
import com.clara.enums.CapabilityKindEnum;
import com.clara.kernel.config.ModelConfig;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.model.availability.ModelCheckResult;
import com.clara.model.availability.AvailabilityLine;
import com.clara.model.availability.AvailabilityColumnWidths;
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
import java.util.function.UnaryOperator;

/**
 * 模型可用性启动管理器：扫描模型配置、检查凭据与模型响应，并把可用模型清单写回 {@code clara.yml}。
 *
 * <p>本类只负责 application 层的启动编排。实际的模型请求由 {@link IModelProbe} 完成，供应商 YAML 的解析由
 * {@link ProviderYamlConfigurationLoader} 完成。检查结果按首字符响应时间升序排列，最快的模型会成为默认模型。
 *
 * <p>输出时机：每个模型的结论在它自己的检查结束后立刻发送给输出接收器，跳过、配置不可用等无需请求的项也
 * 随即输出，**不等整批检查结束**——单条探测最长要等一次网络往返，全部攒到最后会让启动长时间没有任何反馈。
 * 为保证先输出的行与后输出的行对齐，列宽由一次本地预扫描提前确定（见 {@link AvailabilityLine#widths}）。
 */
public final class ModelAvailabilityManager {

    /**
     * 单个模型启动检查的默认超时时间，单位为毫秒。
     */
    private static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;

    /**
     * 根据环境变量名读取当前进程环境变量值的函数，生产环境使用 {@link System#getenv(String)}。
     */
    private final UnaryOperator<String> environment;

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
    ModelAvailabilityManager(UnaryOperator<String> environment, IModelProbe probe, long timeoutMillis) {
        this.environment = environment;
        this.probe = probe;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * 依次检查全部模型配置，并把检查结果持久化到 {@code clara.yml}。
     *
     * <p>配置引用的环境变量不存在或为空时直接跳过，不会发起模型请求。成功产生正文字符的模型视为可用，
     * 可用结果按首字符响应时间排序；配置的 {@code defaultModel} 仍可用时保持不变，否则才以最快模型作为 {@code defaultModel}。
     *
     * <p>输出时机：每个模型的结论在该模型检查结束后**立即**发送给输出接收器（为 null 时静默执行），
     * 不等其余模型检查完；各行的状态、别名、平台与模型、结论四列仍按预扫描确定的列宽对齐，并带上状态级别：
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
        // 先本地预扫描（只解析配置、读环境变量，不发请求）以确定列宽，再逐条检查、逐条输出：
        // 这样每条结论在它自己的检查结束时就可见，同时各列仍然对齐。
        List<PendingCheck> pending = prescan(configFiles);
        AvailabilityColumnWidths widths = AvailabilityLine.widths(aliasesOf(pending), identitiesOf(pending));
        List<ModelCheckResult> results = new ArrayList<>();
        try {
            for (PendingCheck check : pending) {
                ModelCheckResult result = check.check(probe, timeoutMillis);
                results.add(result);
                sink.accept(AvailabilityLine.line(result, widths));
            }
        } finally {
            for (PendingCheck check : pending) {
                check.close();
            }
        }

        List<ModelCheckResult> available = new ArrayList<>();
        for (ModelCheckResult result : results) {
            if (result.isAvailable()) {
                available.add(result);
            }
        }
        available.sort(Comparator.comparingLong(ModelCheckResult::getFirstCharacterMillis));
        ModelCheckResult preferred = preferred(available, readDefaultModel(claraConfig));
        try {
            persist(claraConfig, available, preferred == null ? null : preferred.getAlias());
        } catch (IOException failure) {
            throw new IllegalStateException("无法更新 " + claraConfig + ": " + failure.getMessage(), failure);
        }
        if (available.isEmpty()) {
            sink.accept(AvailabilityLine.fail("未发现可用模型。"));
        } else {
            sink.accept(AvailabilityLine.ok("默认模型：" + preferred.getAlias() + "（首字符响应 " + preferred.getFirstCharacterMillis() + "ms）"));
        }
        List<Path> paths = new ArrayList<>();
        for (ModelCheckResult result : available) {
            paths.add(result.getPath());
        }
        return paths;
    }

    /**
     * 本地预扫描：解析每份配置、判定能否发起请求，全程不发网络请求。
     *
     * <p>为什么要先扫一遍：输出要「检查完一个就出一个」，但列宽必须先定下来，而列宽依赖别名与
     * 「平台与模型」文本——后者只能由本地解析取得。预扫描只读 YAML 与查环境变量，耗时可忽略，
     * 换来逐条输出时各列对齐。
     *
     * @param configFiles 待检查的供应商配置文件列表
     * @return 与输入顺序一致的待检查项，非 null
     */
    private List<PendingCheck> prescan(List<Path> configFiles) {
        List<PendingCheck> pending = new ArrayList<>();
        ProviderYamlConfigurationLoader loader = new ProviderYamlConfigurationLoader();
        for (Path path : configFiles) {
            String alias = ConfigLocator.aliasOf(path);
            ProviderConfiguration configuration = null;
            try {
                configuration = loader.load(path);
                if (!configuration.getCapabilities().containsKey(CapabilityKindEnum.CHAT)) {
                    pending.add(PendingCheck.decided(ModelCheckResult.skipped(path, alias, "未配置 CHAT 能力")));
                } else {
                    String variable = configuration.getCredential().getEnvironmentVariable();
                    if (variable != null && StringUtils.isBlank(environment.apply(variable))) {
                        pending.add(PendingCheck.decided(ModelCheckResult.missingCredential(path, alias, variable)));
                    } else {
                        ModelConfig chat = configuration.toConfig(CapabilityKindEnum.CHAT);
                        pending.add(PendingCheck.ready(path, alias, configuration, chat));
                        // 平台配置的所有权移交给待检查项，由它在检查结束后关闭；置空以免这里重复关闭。
                        configuration = null;
                    }
                }
            } catch (RuntimeException failure) {
                pending.add(PendingCheck.decided(ModelCheckResult.unavailable(path, alias, null, null, concise(failure))));
            } finally {
                if (configuration != null) {
                    configuration.close();
                }
            }
        }
        return pending;
    }

    /**
     * 取全部待检查项的别名，供预计算列宽使用。
     *
     * @param pending 待检查项，非 null
     * @return 与输入顺序一致的别名列表，非 null
     */
    private static List<String> aliasesOf(List<PendingCheck> pending) {
        List<String> aliases = new ArrayList<>();
        for (PendingCheck check : pending) {
            aliases.add(check.alias());
        }
        return aliases;
    }

    /**
     * 取全部待检查项在「平台与模型」列的文本，供预计算列宽使用。
     *
     * <p>预扫描已判定为跳过 / 不可用的项不展示该列（与实际渲染口径一致），只有即将发起请求的项才有内容。
     *
     * @param pending 待检查项，非 null
     * @return 与输入顺序一致的列文本列表，非 null
     */
    private static List<String> identitiesOf(List<PendingCheck> pending) {
        List<String> identities = new ArrayList<>();
        for (PendingCheck check : pending) {
            identities.add(check.identity());
        }
        return identities;
    }

    /**
     * Reads the configured default model alias from clara.yml.
     */
    private static String readDefaultModel(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return null;
        }
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon <= 0 || !"defaultModel".equals(line.substring(0, colon).trim())) {
                    continue;
                }
                String value = line.substring(colon + 1).trim();
                if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                        || value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                return value.isEmpty() ? null : value;
            }
        } catch (IOException ignored) {
            // An unreadable file is treated as having no configured default.
        }
        return null;
    }

    /**
     * Chooses the configured default when it is still available, otherwise the fastest model.
     */
    private static ModelCheckResult preferred(List<ModelCheckResult> available, String configuredDefault) {
        if (available.isEmpty()) {
            return null;
        }
        if (StringUtils.isNotBlank(configuredDefault)) {
            for (ModelCheckResult result : available) {
                Path path = result.getPath();
                String fileName = path == null || path.getFileName() == null
                        ? "" : path.getFileName().toString();
                if (result.getAlias().equalsIgnoreCase(configuredDefault)
                        || fileName.equalsIgnoreCase(configuredDefault)) {
                    return result;
                }
            }
        }
        return available.get(0);
    }

    /**
     * 把可用模型清单和选定的默认模型写入 {@code clara.yml}，保留文件中的其他顶层配置。
     *
     * <p>先写入同目录临时文件，再优先使用原子移动替换目标文件；文件系统不支持原子移动时退化为普通替换。
     * 当没有可用模型时写入空的 {@code enabledProviders}，并把 {@code defaultModel} 写为空值。
     *
     * @param file      {@code clara.yml} 文件路径
     * @param available    已按响应时间排序的可用模型结果
     * @param defaultAlias 选定的默认模型别名；无可用模型时为 null
     * @throws IOException 读取、创建目录、写入或移动文件失败时抛出
     */
    private static void persist(Path file, List<ModelCheckResult> available, String defaultAlias) throws IOException {
        List<String> original = Files.isRegularFile(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : Collections.emptyList();
        String defaultLine = "defaultModel:" + (defaultAlias == null ? "" : " " + defaultAlias);
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
        Path temporary;
        if (parent == null) {
            temporary = Files.createTempFile("clara-", ".yml.tmp");
        } else {
            Files.createDirectories(parent);
            temporary = Files.createTempFile(parent, "clara-", ".yml.tmp");
        }
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
        int index = 0;
        while (index < lines.size()) {
            String line = lines.get(index);
            if (!isTopLevelKey(line, key)) {
                result.add(line);
                index++;
                continue;
            }
            if (!replaced) {
                result.add(replacement);
                replaced = true;
            }
            index++;
            while (index < lines.size() && isIndented(lines.get(index))) {
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

    /**
     * 把异常压缩为适合控制台展示的单行说明，不包含堆栈信息。
     *
     * @param failure 检查模型配置或能力时捕获的异常
     * @return 由异常类型和非空异常消息组成的简短文本
     */
    private static String concise(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName() + (StringUtils.isBlank(message) ? "" : ": " + message);
    }

    /**
     * 单个模型的待检查项：预扫描已完成「不需要网络即可判定」的部分（配置解析、CHAT 能力、凭据），
     * 需要真实请求的部分留到 {@link #check} 执行。
     *
     * <p>为什么拆成两项而不是一个结果对象：预扫描阶段必须知道「这一行会不会有平台与模型可展示」，
     * 才能把列宽算准；而结果要等请求结束才产生。
     */
    private static final class PendingCheck {

        /**
         * 预扫描即可判定的终态结果（跳过 / 配置不可用）；需要发起请求时为 null。
         */
        private final ModelCheckResult terminal;

        /**
         * 供应商配置文件路径；已判定项为 null。
         */
        private final Path path;

        /**
         * 模型别名，始终非 null，用于输出与列宽计算。
         */
        private final String alias;

        /**
         * 待探测的平台配置；已判定项为 null，且其生命周期归本对象。
         */
        private final ProviderConfiguration configuration;

        /**
         * 待探测的对话能力配置；已判定项为 null。
         */
        private final ModelConfig chat;

        /**
         * 是否已释放平台配置，避免重复关闭。
         */
        private boolean closed;

        private PendingCheck(ModelCheckResult terminal, Path path, String alias,
                ProviderConfiguration configuration, ModelConfig chat) {
            this.terminal = terminal;
            this.path = path;
            this.alias = alias;
            this.configuration = configuration;
            this.chat = chat;
        }

        /**
         * 创建无需网络请求即可判定的待检查项。
         *
         * @param result 预扫描得到的结果，非 null
         * @return 待检查项，非 null
         */
        private static PendingCheck decided(ModelCheckResult result) {
            return new PendingCheck(result, null, result.getAlias(), null, null);
        }

        /**
         * 创建需要真实请求的待检查项，平台配置所有权随之移交。
         *
         * @param path          配置文件路径
         * @param alias         模型别名
         * @param configuration 平台配置，不可为 null
         * @param chat          对话能力配置，不可为 null
         * @return 待检查项，非 null
         */
        private static PendingCheck ready(Path path, String alias, ProviderConfiguration configuration,
                ModelConfig chat) {
            return new PendingCheck(null, path, alias, configuration, chat);
        }

        /**
         * 模型别名。
         *
         * @return 别名，非 null
         */
        private String alias() {
            return alias;
        }

        /**
         * 「平台与模型」列的文本：需要请求时按配置预拼，与探测结果渲染出的文本一致；已判定项为空串。
         *
         * @return 列文本，非 null，可为空串
         */
        private String identity() {
            return terminal != null
                    ? ""
                    : AvailabilityLine.identity(configuration.getProvider().name(), chat.getModelId());
        }

        /**
         * 执行本项检查：已判定项直接返回既有结果，否则发起一次真实探测。
         *
         * <p>探测抛错按「不可用」处理并保留原因，不中断其余模型的检查。
         *
         * @param probe         模型探测器，非 null
         * @param timeoutMillis 探测超时，单位为毫秒
         * @return 检查结果，非 null
         */
        private ModelCheckResult check(IModelProbe probe, long timeoutMillis) {
            if (terminal != null) {
                return terminal;
            }
            try {
                return probe.probe(path, alias, configuration, chat, timeoutMillis);
            } catch (RuntimeException failure) {
                return ModelCheckResult.unavailable(path, alias, null, null, concise(failure));
            }
        }

        /**
         * 关闭本项持有的平台配置；已判定项无配置可关，重复调用无副作用。
         */
        private void close() {
            if (configuration != null && !closed) {
                closed = true;
                configuration.close();
            }
        }
    }

}
