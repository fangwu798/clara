package com.clara.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

/**
 * 配置定位与读取：集中承担「模型配置目录 / 文件的定位、模型清单扫描、主模型解析、日志目录推导」。
 *
 * <p>是什么、在哪一层：application 的配置访问组件（{@code com.clara.config}），把文件系统定位与读取细节从
 * 入口主流程中剥离；调用方只消费其返回结果，不再内联路径回溯与文本解析。
 *
 * <p>定位策略：先在「相对当前工作目录」查找，找不到则逐级向上回溯（兼容从模块子目录启动）；模型清单扫描
 * <b>忽略 {@code examples/} 目录与 {@code *.example.yml} 样例文件</b>（样例不是真实配置）。
 */
public final class ConfigLocator {

    private static final String CONFIG_DIR = "config";

    /**
     * 模型配置目录（相对运行目录）。
     */
    private static final Path MODEL_DIRECTORY = Paths.get(CONFIG_DIR, "model-providers");

    /**
     * 本地运行配置文件（相对运行目录）：其 {@code defaultModel} 指定主（默认）模型。
     */
    private static final Path CLARA_CONFIG = Paths.get(CONFIG_DIR, "clara.yml");

    /**
     * 配置根目录（相对运行目录），用于推导仓库根与日志目录。
     */
    private static final Path CONFIG_DIRECTORY = Paths.get(CONFIG_DIR);

    /**
     * 角色 → 模型别名映射文件（相对运行目录），可选。
     */
    private static final Path MODEL_ROLES = Paths.get(CONFIG_DIR, "model-roles.yml");

    /**
     * 拼音输出格式：无声调、小写、{@code ü} 写作 {@code v}，保证转写结果仅含 ASCII 字母 / 数字。
     */
    private static final HanyuPinyinOutputFormat PINYIN_FORMAT = pinyinFormat();

    /**
     * 主模型配置键名。
     */
    private static final String DEFAULT_MODEL_KEY = "defaultModel";

    /**
     * 启用供应商列表键名（{@code config/clara.yml}）。
     */
    private static final String ENABLED_PROVIDERS_KEY = "enabledProviders";

    /**
     * YAML 映射器，用于读取 {@code enabledProviders} 列表（支持流式与块式序列）。
     */
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /**
     * 定位目录时的向上回溯最大层数。
     */
    private static final int SEARCH_DEPTH = 6;

    /**
     * 扫描模型配置的最大目录深度。
     */
    private static final int MODEL_SCAN_DEPTH = 3;

    private ConfigLocator() {
    }

    /**
     * 定位模型配置目录。
     *
     * @return 定位到的目录（找不到时返回相对路径本身），非 null
     */
    public static Path modelDirectory() {
        return locate(MODEL_DIRECTORY);
    }

    /**
     * 扫描模型配置：递归收集 {@code *.yml} / {@code *.yaml}，忽略 {@code examples} 目录与 {@code *.example.yml}
     * 样例文件。
     *
     * <p>启用过滤：{@code config/clara.yml} 配置了 {@code enabledProviders} 时，只返回别名在启用列表内的
     * 配置（未启用的一律不加载、{@code /model} 也不列出）；未配置该列表时返回全部。
     *
     * @return 排序后的配置路径列表，非 null
     */
    public static List<Path> modelConfigs() {
        return filterModelConfigs(allModelConfigs(), claraConfigFile());
    }

    static List<Path> filterModelConfigs(List<Path> all, Path configFile) {
        List<String> enabled = enabledProviders(configFile);
        if (!hasEnabledProvidersSetting(configFile)) {
            return all;
        }
        List<Path> filtered = new ArrayList<>();
        for (Path path : all) {
            String alias = aliasOf(path);
            if (alias != null && containsIgnoreCase(enabled, alias)) {
                filtered.add(path);
            }
        }
        return filtered;
    }

    /**
     * 扫描全部模型配置，不应用 {@code enabledProviders} 过滤。
     *
     * @return 排序后的配置文件列表
     */
    public static List<Path> allModelConfigs() {
        return scanModelConfigs();
    }

    private static boolean hasEnabledProvidersSetting(Path configFile) {
        if (!Files.isRegularFile(configFile)) {
            return false;
        }
        try {
            JsonNode root = YAML.readTree(Files.readAllBytes(configFile));
            return root != null && root.has(ENABLED_PROVIDERS_KEY);
        } catch (IOException ignored) {
            return false;
        }
    }

    /**
     * 读取 {@code config/clara.yml} 的启用供应商列表 {@code enabledProviders}。
     *
     * <p>格式：YAML 序列（{@code [a, b]} 单行流式或 {@code - a} 块式均可）或逗号分隔字符串；元素为模型别名
     * （{@code config/model-providers} 下文件名去掉扩展名）。文件缺失、未配置该键、显式配置空列表或读取失败时
     * 均返回空列表；应用筛选时会额外区分“未配置”与“显式空列表”。
     *
     * @return 启用别名列表，非 null（可为空）
     */
    public static List<String> enabledProviders() {
        return enabledProviders(claraConfigFile());
    }

    private static List<String> enabledProviders(Path configFile) {
        if (!Files.isRegularFile(configFile)) {
            return Collections.emptyList();
        }
        JsonNode node;
        try {
            JsonNode root = YAML.readTree(Files.readAllBytes(configFile));
            node = root == null ? null : root.get(ENABLED_PROVIDERS_KEY);
        } catch (IOException ignored) {
            // 读取失败视为未配置
            return Collections.emptyList();
        }
        if (node == null || node.isNull()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        if (node.isArray()) {
            for (JsonNode item : node) {
                addAlias(result, item == null ? null : item.asText());
            }
        } else {
            for (String part : node.asText().split(",")) {
                addAlias(result, part);
            }
        }
        return result;
    }

    private static void addAlias(List<String> target, String value) {
        if (value != null && !value.trim().isEmpty()) {
            target.add(value.trim());
        }
    }

    private static boolean containsIgnoreCase(List<String> values, String value) {
        for (String item : values) {
            if (item.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 扫描 {@code config/model-providers} 下的全部模型配置（不做启用过滤）。
     *
     * @return 排序后的配置路径列表，非 null
     */
    private static List<Path> scanModelConfigs() {
        Path directory = modelDirectory();
        if (!Files.isDirectory(directory)) {
            return Collections.emptyList();
        }
        List<Path> result = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(directory, MODEL_SCAN_DEPTH)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                if (isModelConfig(path)) {
                    result.add(path);
                }
            });
        } catch (IOException ignored) {
            return Collections.emptyList();
        }
        Collections.sort(result);
        return result;
    }

    /**
     * 定位本地运行配置文件 {@code config/clara.yml}（主模型、语义记忆等本地开关都写在这里）。
     *
     * @return 配置文件路径（找不到时返回相对路径本身），非 null
     */
    public static Path claraConfigFile() {
        return locateFile(CLARA_CONFIG);
    }

    /**
     * 读取 {@code config/clara.yml} 的 {@code defaultModel}（主模型）。
     *
     * <p>该文件只有一个顶层标量键，故按行简单解析（不做通用 YAML 解析）：取首个非注释的
     * {@code defaultModel:} 行，去掉两端引号后返回。文件缺失、键缺失或读取失败时返回 null。
     *
     * @return 主模型标识，未配置时 null
     */
    public static String defaultModel() {
        Path configFile = claraConfigFile();
        if (!Files.isRegularFile(configFile)) {
            return null;
        }
        try {
            Iterator<String> lines = Files.readAllLines(configFile, StandardCharsets.UTF_8).iterator();
            String model = null;
            while (lines.hasNext() && model == null) {
                String line = lines.next().trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon > 0 && DEFAULT_MODEL_KEY.equals(line.substring(0, colon).trim())) {
                    model = unquote(line.substring(colon + 1).trim());
                }
            }
            return model == null || model.isEmpty() ? null : model;
        } catch (IOException ignored) {
            // 读取失败视为未配置
        }
        return null;
    }

    /**
     * 把主模型标识解析为候选配置：按「文件名」「去掉扩展名的名字」或「相对路径」匹配，忽略大小写。
     *
     * @param candidates 候选配置，非 null
     * @param name       主模型标识，非空白
     * @return 匹配到的配置；未匹配时 null
     */
    public static Path resolveModel(List<Path> candidates, String name) {
        for (Path candidate : candidates) {
            Path fileNamePath = candidate.getFileName();
            if (fileNamePath == null) {
                continue;
            }
            String fileName = fileNamePath.toString();
            if (fileName.equalsIgnoreCase(name) || stem(fileName).equalsIgnoreCase(name)) {
                return candidate;
            }
        }
        String normalized = name.replace('\\', '/');
        for (Path candidate : candidates) {
            if (candidate.toString().replace('\\', '/').equalsIgnoreCase(normalized)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * 定位目录：先在相对当前工作目录下查找，找不到则逐级向上回溯。
     *
     * @param relative 相对目录，非 null
     * @return 定位到的目录（找不到时返回相对路径本身），非 null
     */
    public static Path locate(Path relative) {
        if (Files.isDirectory(relative)) {
            return relative;
        }
        Path directory = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < SEARCH_DEPTH && directory != null; depth++) {
            Path candidate = directory.resolve(relative);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        return relative;
    }

    /**
     * 定位文件：先用给定路径；不存在时向上回溯查找同名相对路径。
     *
     * @param configured 配置路径，非 null
     * @return 定位到的路径（找不到时返回原路径），非 null
     */
    public static Path locateFile(Path configured) {
        if (configured.isAbsolute() || Files.isRegularFile(configured)) {
            return configured;
        }
        Path directory = Paths.get("").toAbsolutePath();
        for (int depth = 0; depth < SEARCH_DEPTH && directory != null; depth++) {
            Path candidate = directory.resolve(configured);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            directory = directory.getParent();
        }
        return configured;
    }

    /**
     * 计算详细日志目录（仓库根下 {@code logs}）：由定位到的 {@code config} 目录上溯一级得到仓库根。
     *
     * @return 日志目录；无法定位仓库根时 null（视图将降级为不落盘）
     */
    public static Path logsDirectory() {
        try {
            Path configDirectory = locate(CONFIG_DIRECTORY);
            Path root = configDirectory.toAbsolutePath().getParent();
            if (root == null) {
                root = Paths.get("").toAbsolutePath();
            }
            return root.resolve("logs");
        } catch (RuntimeException failure) {
            return null;
        }
    }

    /**
     * 工作区根目录：优先取启动脚本注入的 {@code CLARA_WORK_DIR}，未注入时退回进程当前工作目录。
     *
     * @return 已规范化的绝对路径，非 null
     */
    public static Path workspaceRoot() {
        String configured = System.getenv("CLARA_WORK_DIR");
        if (configured != null && !configured.trim().isEmpty()) {
            return Paths.get(configured.trim()).toAbsolutePath().normalize();
        }
        return Paths.get("").toAbsolutePath().normalize();
    }

    /**
     * 项目名：供外部向量库（Milvus）按项目隔离存储——不同项目各自使用独立的集合名，避免互相串数据。
     *
     * <p>取「工作目录的文件名」：优先启动脚本注入的 {@code CLARA_WORK_DIR}，未注入时用进程当前工作目录，
     * 即「你从哪个目录启动，就以该目录名作为项目名」。
     *
     * <p>Milvus 集合名只允许 ASCII（字母 / 数字 / 下划线，且以字母或下划线开头），因此：目录名本身合法时
     * 原样返回（如 {@code clara}）；含中文等非 ASCII 字符时用拼音转写（如 {@code 我的项目 → wodexiangmu}）；
     * 其余非法字符折叠为下划线。转写后仍为空（如目录名全为符号）时，退回 {@code p + 目录绝对路径 SHA-256 前 12 位}，
     * 保证合法、稳定且不同目录不冲突。
     *
     * @return ASCII 安全的项目名，非 null
     */
    public static String projectName() {
        Path directory = workspaceRoot();
        Path name = directory.getFileName();
        String raw = name == null ? "" : name.toString().trim();
        String identifier = toAsciiIdentifier(raw);
        if (identifier.isEmpty()) {
            return "p" + sha256(directory.toString()).substring(0, 12);
        }
        return identifier;
    }

    /**
     * 把任意目录名规范化为合法的 Milvus 标识：汉字转拼音（无声调），仅保留 ASCII 字母 / 数字，其余字符折叠为
     * 下划线并去除首尾多余下划线；以数字开头时补 {@code p} 前缀。
     *
     * @return 规范化结果，可能为空（输入无任何可映射字符时）
     */
    private static String toAsciiIdentifier(String raw) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < raw.length(); index++) {
            char c = raw.charAt(index);
            if (isAsciiLetter(c) || c >= '0' && c <= '9') {
                builder.append(Character.toLowerCase(c));
            } else if (c == '_') {
                builder.append('_');
            } else {
                builder.append(pinyinOf(c));
            }
        }
        String normalized = collapseAndTrim(builder.toString());
        if (!normalized.isEmpty() && normalized.charAt(0) >= '0' && normalized.charAt(0) <= '9') {
            normalized = "p" + normalized;
        }
        return normalized;
    }

    /**
     * 折叠连续下划线并去掉首尾下划线，避免正则回溯。
     */
    private static String collapseAndTrim(String text) {
        StringBuilder builder = new StringBuilder(text.length());
        boolean lastUnderscore = false;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (current == '_') {
                if (!lastUnderscore) {
                    builder.append(current);
                }
                lastUnderscore = true;
            } else {
                builder.append(current);
                lastUnderscore = false;
            }
        }
        int start = 0;
        int end = builder.length();
        while (start < end && builder.charAt(start) == '_') {
            start++;
        }
        while (end > start && builder.charAt(end - 1) == '_') {
            end--;
        }
        return builder.substring(start, end);
    }

    /**
     * 是否为 ASCII 字母（大小写）。
     */
    private static boolean isAsciiLetter(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    /**
     * 构造不会抛异常的拼音输出格式（固定配置，独立成方法便于静态初始化）。
     */
    private static HanyuPinyinOutputFormat pinyinFormat() {
        HanyuPinyinOutputFormat format = new HanyuPinyinOutputFormat();
        format.setCaseType(HanyuPinyinCaseType.LOWERCASE);
        format.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
        format.setVCharType(HanyuPinyinVCharType.WITH_V);
        return format;
    }

    /**
     * 单字符转拼音：汉字返回无声调拼音（如 {@code 中 → zhong}），非汉字或转换失败返回下划线占位。
     */
    private static String pinyinOf(char c) {
        try {
            String[] pinyin = PinyinHelper.toHanyuPinyinStringArray(c, PINYIN_FORMAT);
            if (pinyin != null && pinyin.length > 0 && !pinyin[0].isEmpty()) {
                return pinyin[0];
            }
        } catch (BadHanyuPinyinOutputFormatCombination ignored) {
            // 输出格式固定且合法，正常不会抛出；兜底按分隔符处理。
        }
        return "_";
    }

    /**
     * 由工作目录推导的稳定会话标识，使「同一目录再次进入」能续接同一份对话历史。
     *
     * <p>形如 {@code cli-<工作目录绝对路径的 sha256 前 16 位>}：同一目录恒定、不同目录互不干扰，且不含路径
     * 明文（不会出现在日志与历史文件名里）；换目录即换上下文，删掉工作目录下的 {@code .clara/state/conversations}
     * 即清空历史。
     *
     * @return 会话标识，非 null
     */
    public static String cliSessionId() {
        return "cli-" + sha256(workspaceRoot().toString()).substring(0, 16);
    }

    /**
     * 读取角色 → 模型别名映射（{@code config/model-roles.yml}）。
     *
     * <p>格式：每行 {@code role: modelAlias}，忽略空行与以 {@code #} 开头的注释行；值两端引号会被去掉。
     * 文件缺失、读取失败或行内缺少冒号时忽略该项；不配置时返回空映射，此时角色名直接作为模型别名使用。
     *
     * @return 角色到别名的映射，非 null（可为空）
     */
    public static Map<String, String> roleBindings() {
        Path file = locateFile(MODEL_ROLES);
        Map<String, String> bindings = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) {
            return bindings;
        }
        try {
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        String role = line.substring(0, colon).trim();
                        String alias = unquote(line.substring(colon + 1).trim());
                        if (!role.isEmpty() && !alias.isEmpty()) {
                            bindings.put(role, alias);
                        }
                    }
                }
            }
        } catch (IOException ignored) {
            // 读取失败视为未配置
        }
        return bindings;
    }

    /**
     * 由模型配置路径推导模型别名（文件名去掉 {@code yml} / {@code yaml} 扩展名）。
     *
     * @param configPath 模型配置文件路径，可为 null
     * @return 模型别名；入参为 null 时返回 null
     */
    public static String aliasOf(Path configPath) {
        if (configPath == null) {
            return null;
        }
        Path fileName = configPath.getFileName();
        return fileName == null ? null : stem(fileName.toString());
    }

    /**
     * 去掉值两端的成对单 / 双引号。
     */
    private static String unquote(String value) {
        if (value.length() >= 2
                && (value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"'
                || value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\'')) {
            return value.substring(1, value.length() - 1).trim();
        }
        return value;
    }

    /**
     * 判断是否为可用的模型配置：扩展名为 yml/yaml，且不在 examples 目录、文件名不含 {@code .example.}。
     */
    private static boolean isModelConfig(Path path) {
        Path fileName = path.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString().toLowerCase(Locale.ROOT);
        if (!name.endsWith(".yml") && !name.endsWith(".yaml")) {
            return false;
        }
        if (name.endsWith(".example.yml") || name.endsWith(".example.yaml")) {
            return false;
        }
        String normalized = path.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
        return !normalized.contains("/examples/");
    }

    /**
     * 计算字符串的 SHA-256 十六进制摘要。
     */
    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                builder.append(String.format("%02x", item & 0xff));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    /**
     * 去掉 yml / yaml 扩展名。
     */
    private static String stem(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".yaml")) {
            return fileName.substring(0, fileName.length() - 5);
        }
        if (lower.endsWith(".yml")) {
            return fileName.substring(0, fileName.length() - 4);
        }
        return fileName;
    }
}
