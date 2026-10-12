package com.clara.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

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

    /**
     * 模型配置目录（相对运行目录）。
     */
    private static final Path MODEL_DIRECTORY = Paths.get("config", "model-providers");

    /**
     * 本地运行配置文件（相对运行目录）：其 {@code defaultModel} 指定主（默认）模型。
     */
    private static final Path CLARA_CONFIG = Paths.get("config", "clara.yml");

    /**
     * 配置根目录（相对运行目录），用于推导仓库根与日志目录。
     */
    private static final Path CONFIG_DIRECTORY = Paths.get("config");

    /**
     * 主模型配置键名。
     */
    private static final String DEFAULT_MODEL_KEY = "defaultModel";

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
     * 扫描模型配置：递归收集 {@code *.yml} / {@code *.yaml}，忽略 {@code examples} 目录与 {@code .example.yml}
     * 样例文件。
     *
     * @return 排序后的配置路径列表，非 null
     */
    public static List<Path> modelConfigs() {
        Path directory = modelDirectory();
        if (directory == null || !Files.isDirectory(directory)) {
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
     * 读取 {@code config/clara.yml} 的 {@code defaultModel}（主模型）。
     *
     * <p>该文件只有一个顶层标量键，故按行简单解析（不做通用 YAML 解析）：取首个非注释的
     * {@code defaultModel:} 行，去掉两端引号后返回。文件缺失、键缺失或读取失败时返回 null。
     *
     * @return 主模型标识，未配置时 null
     */
    public static String defaultModel() {
        Path configFile = locateFile(CLARA_CONFIG);
        if (!Files.isRegularFile(configFile)) {
            return null;
        }
        try {
            for (String raw : Files.readAllLines(configFile, StandardCharsets.UTF_8)) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int colon = line.indexOf(':');
                if (colon <= 0 || !DEFAULT_MODEL_KEY.equals(line.substring(0, colon).trim())) {
                    continue;
                }
                String value = line.substring(colon + 1).trim();
                if (value.length() >= 2
                        && ((value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"')
                        || (value.charAt(0) == '\'' && value.charAt(value.length() - 1) == '\''))) {
                    value = value.substring(1, value.length() - 1).trim();
                }
                return value.isEmpty() ? null : value;
            }
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
            String fileName = candidate.getFileName().toString();
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
     * 判断是否为可用的模型配置：扩展名为 yml/yaml，且不在 examples 目录、文件名不含 {@code .example.}。
     */
    private static boolean isModelConfig(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
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
