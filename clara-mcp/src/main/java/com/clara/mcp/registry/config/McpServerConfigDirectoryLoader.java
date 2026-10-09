package com.clara.mcp.registry.config;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 从本地目录（可选叠加 classpath 内置目录）批量识别并加载 MCP 服务端配置。
 *
 * <p>是什么、在哪一层：{@code clara-mcp} 注册层的目录识别器，封装 {@link McpServerConfigLoader}：
 * 扫描「运行目录」（默认 {@code config/mcp}，相对运行目录）下递归匹配的所有 {@code *.json} 文件，逐个加载并合并；
 * 可选叠加 classpath 上的内置 {@code config/mcp}。
 *
 * <p>覆盖规则（同名 {@code serverKey}）：<b>运行目录覆盖内置（classpath）；同一来源内按路径排序后「后加载覆盖先加载」</b>。
 * 合并结果中每个 {@code serverKey} 只保留胜出者，可直接交给 {@link McpServerRegistrar} 注册。
 *
 * <p>目录不存在或没有任何 {@code *.json} 时返回空列表，不报错；非 {@code *.json} 文件被忽略。
 */
public final class McpServerConfigDirectoryLoader {
    /** classpath 内置配置目录名。 */
    public static final String CLASSPATH_DIRECTORY = "config/mcp";

    private final McpServerConfigLoader configLoader;
    private final Path runtimeDirectory;
    private final boolean includeClasspath;

    /**
     * 创建仅扫描运行目录的目录识别器。
     *
     * @param configLoader     底层单文件加载器，不可为 null
     * @param runtimeDirectory 运行目录，不可为 null
     */
    public McpServerConfigDirectoryLoader(McpServerConfigLoader configLoader, Path runtimeDirectory) {
        this(configLoader, runtimeDirectory, false);
    }

    /**
     * 创建目录识别器。
     *
     * @param configLoader     底层单文件加载器，不可为 null
     * @param runtimeDirectory 运行目录，不可为 null
     * @param includeClasspath 是否叠加 classpath 内置 {@code config/mcp}
     */
    public McpServerConfigDirectoryLoader(McpServerConfigLoader configLoader, Path runtimeDirectory,
            boolean includeClasspath) {
        if (configLoader == null) { throw new IllegalArgumentException("configLoader must not be null"); }
        if (runtimeDirectory == null) { throw new IllegalArgumentException("runtimeDirectory must not be null"); }
        this.configLoader = configLoader;
        this.runtimeDirectory = runtimeDirectory;
        this.includeClasspath = includeClasspath;
    }

    /**
     * 生产用默认配置：运行目录 {@code config/mcp} + classpath 内置 {@code config/mcp}。
     *
     * @return 默认目录识别器，非 null
     */
    public static McpServerConfigDirectoryLoader defaults() {
        return new McpServerConfigDirectoryLoader(new McpServerConfigLoader(),
                Paths.get("config", "mcp"), true);
    }

    /**
     * 扫描并合并配置。
     *
     * @return 已按 {@code serverKey} 去重的配置列表（保持首次出现的顺序），非 null
     */
    public List<McpServerConfig> load() {
        Map<String, McpServerConfig> merged = new LinkedHashMap<String, McpServerConfig>();
        if (includeClasspath) {
            mergeInto(merged, classpathDirectories());
        }
        mergeInto(merged, Collections.singletonList(runtimeDirectory));
        return Collections.unmodifiableList(new ArrayList<McpServerConfig>(merged.values()));
    }

    public Path getRuntimeDirectory() { return runtimeDirectory; }
    public boolean isIncludeClasspath() { return includeClasspath; }

    private void mergeInto(Map<String, McpServerConfig> merged, List<Path> directories) {
        for (Path directory : directories) {
            for (Path file : jsonFiles(directory)) {
                for (McpServerConfig config : configLoader.loadFile(file)) {
                    merged.put(config.getServerKey(), config);
                }
            }
        }
    }

    private static List<Path> classpathDirectories() {
        List<Path> result = new ArrayList<Path>();
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        if (loader == null) { loader = McpServerConfigDirectoryLoader.class.getClassLoader(); }
        if (loader == null) { return result; }
        try {
            Enumeration<URL> resources = loader.getResources(CLASSPATH_DIRECTORY);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                if (!"file".equals(url.getProtocol())) { continue; }
                try {
                    URI uri = url.toURI();
                    result.add(Paths.get(uri));
                } catch (URISyntaxException ignored) {
                    // skip malformed classpath entries
                }
            }
        } catch (IOException ignored) {
            // no classpath configuration available
        }
        return result;
    }

    private static List<Path> jsonFiles(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) { return Collections.emptyList(); }
        final List<Path> files = new ArrayList<Path>();
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    if (attributes.isRegularFile() && isJson(file)) { files.add(file); }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            return Collections.emptyList();
        }
        Collections.sort(files);
        return files;
    }

    private static boolean isJson(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json");
    }
}
