package com.clara;

import com.clara.enums.CapabilityKindEnum;
import com.clara.kernel.config.ModelConfig;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.model.configuration.ProviderYamlConfigurationLoader;
import com.clara.orchestration.api.ConversationRequest;
import com.clara.orchestration.api.ConversationResult;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
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
 * 交互式对话入口：从 {@code config/model-providers} 选择模型（可多平台 / 多调用方式），装配各模块后逐行对话。
 *
 * <p>是什么、在哪一层：application 的最小可运行入口（{@code com.clara}）。它把 {@link ApplicationBootstrap}
 * 装配好的对话编排能力接到标准输入上，作为「本地跑通智能体」的最简宿主；真正的产品化运行入口（CLI 参数化、
 * REST / SSE）在其适配层另行提供。
 *
 * <p>模型清单：扫描运行目录下 {@code config/model-providers} 内的 {@code *.yml} / {@code *.yaml}，
 * <b>忽略 {@code examples/} 目录与 {@code *.example.yml} 样例文件</b>（样例不是真实配置）。每个文件对应一个
 * 「平台 + 调用方式（协议）」组合，例如 {@code deepseek-completion.yml} 与 {@code deepseek-response.yml}
 * 分别代表同一平台的两种调用方式。
 *
 * <p>运行前置：
 * <ol>
 *   <li>工作目录为仓库根（{@code config/} 可被定位；非根时按「向上回溯」定位）；</li>
 *   <li>所选配置 {@code credential} 声明的环境变量在<b>启动该进程</b>的环境中可见（如
 *       {@code CLARA_DEEPSEEK_API_KEY}）。进程环境是启动时的快照，系统里设置后需重启 IDE 或显式注入。</li>
 * </ol>
 *
 * <p>使用方法：主（默认）模型由 {@code config/clara.yml} 的 {@code defaultModel} 指定（取
 * {@code config/model-providers} 下的文件名或去扩展名的名字）；未配置或未匹配到时，若存在多个模型配置会
 * 列出编号供选择，也可用首个命令行参数直接指定配置文件。进入对话后逐行输入回车发送，{@code /model}
 * 重新选择模型，{@code exit} / {@code quit} 结束。同一会话标识，多轮上下文会被窗口记忆带入。
 */
public final class ClaraApplication {

    /**
     * 模型配置目录（相对运行目录）。
     */
    private static final Path MODEL_DIRECTORY = Paths.get("config", "model-providers");

    /**
     * 本地运行配置文件（相对运行目录）：其 {@code defaultModel} 指定主（默认）模型。
     */
    private static final Path CLARA_CONFIG = Paths.get("config", "clara.yml");

    /**
     * 主模型配置键名。
     */
    private static final String DEFAULT_MODEL_KEY = "defaultModel";

    /**
     * 定位目录时的向上回溯最大层数（兼容从模块子目录启动）。
     */
    private static final int SEARCH_DEPTH = 6;

    /**
     * 扫描模型配置的最大目录深度。
     */
    private static final int MODEL_SCAN_DEPTH = 3;

    /**
     * 本进程内对话共用的会话标识。
     */
    private static final String CONVERSATION_ID = "cli";

    private ClaraApplication() {
    }

    /**
     * 进程入口：选择模型、装配各模块并进入交互式对话循环。
     *
     * @param args 可选；首个参数为模型配置文件路径（缺省则扫描目录并交互选择）
     * @throws Exception 输出流初始化失败时
     */
    public static void main(String[] args) throws Exception {
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));

        Path modelDirectory = locateDirectory(MODEL_DIRECTORY);
        List<Path> candidates = listModelConfigs(modelDirectory);

        Path configPath;
        String source;
        if (args != null && args.length > 0 && args[0] != null && !args[0].trim().isEmpty()) {
            configPath = locateFile(Paths.get(args[0].trim()));
            source = "命令行参数";
        } else {
            String defaultModel = readDefaultModel(locateFile(CLARA_CONFIG));
            Path preferred = defaultModel == null ? null : resolveDefault(candidates, defaultModel);
            if (preferred != null) {
                configPath = preferred;
                source = "config/clara.yml 指定的主模型（" + defaultModel + "）";
            } else {
                if (defaultModel != null) {
                    out.println("config/clara.yml 的 defaultModel=" + defaultModel
                            + " 未匹配到任何模型配置，转为手动选择。");
                }
                if (candidates.isEmpty()) {
                    out.println("未找到模型配置：请确认工作目录为仓库根，且 " + modelDirectory.toAbsolutePath()
                            + " 下存在 *.yml / *.yaml（examples 样例会被忽略）。");
                    System.exit(1);
                    return;
                }
                configPath = chooseModel(candidates, reader, out);
                source = "交互选择";
            }
        }

        ApplicationBootstrap bootstrap = null;
        ProviderConfiguration providerConfig = null;
        try {
            providerConfig = new ProviderYamlConfigurationLoader().load(configPath);
            bootstrap = new ApplicationBootstrap();
            printBanner(out, bootstrap, providerConfig, configPath, source);
            out.println("输入内容回车发送；/model 切换模型；exit 或 quit 结束。");
            out.println();

            String line;
            while ((line = reader.readLine()) != null) {
                String input = line.trim();
                if (input.isEmpty()) {
                    continue;
                }
                if ("exit".equalsIgnoreCase(input) || "quit".equalsIgnoreCase(input) || "/exit".equals(input)) {
                    out.println("bye.");
                    break;
                }
                if ("/model".equals(input)) {
                    if (candidates.isEmpty()) {
                        out.println("未发现可选模型列表（启动时以参数指定了配置文件）。");
                        continue;
                    }
                    Path next = chooseModel(candidates, reader, out);
                    ProviderConfiguration reloaded = new ProviderYamlConfigurationLoader().load(next);
                    providerConfig.close();
                    providerConfig = reloaded;
                    configPath = next;
                    out.println("已切换模型：" + describe(providerConfig) + "   [" + configPath + "]");
                    out.println();
                    continue;
                }
                converse(out, bootstrap, providerConfig, input);
            }
        } catch (RuntimeException startupFailure) {
            out.println("启动失败：" + startupFailure.getClass().getSimpleName() + ": " + startupFailure.getMessage());
            out.println("请确认：1) 所选模型配置存在且合法（当前 " + configPath.toAbsolutePath() + "）；"
                    + "2) 其 credential 声明的环境变量已设置。");
            System.exit(1);
        } finally {
            if (bootstrap != null) {
                bootstrap.close();
            }
            if (providerConfig != null) {
                providerConfig.close();
            }
        }
    }

    /**
     * 执行一轮对话并打印回复与用量。
     *
     * @param out            输出流，非 null
     * @param bootstrap      已装配的引导器，非 null
     * @param providerConfig 当前模型配置，非 null
     * @param input          用户输入，非空白
     */
    private static void converse(PrintStream out, ApplicationBootstrap bootstrap,
            ProviderConfiguration providerConfig, String input) {
        long started = System.currentTimeMillis();
        try {
            ConversationResult result = bootstrap.orchestrator().converse(ConversationRequest.builder()
                    .conversationId(CONVERSATION_ID)
                    .provider(providerConfig.getProvider())
                    .model(providerConfig.toConfig(CapabilityKindEnum.CHAT).getModelId())
                    .capabilityKind(CapabilityKindEnum.CHAT)
                    .userInput(input)
                    .providerConfiguration(providerConfig)
                    .stream(false)
                    .build());
            out.println("[assistant] " + result.getContent());
            out.println("  (status=" + result.getStatus()
                    + (result.getUsage() == null ? "" : ", tokens=" + result.getUsage())
                    + ", 用时=" + (System.currentTimeMillis() - started) + "ms)");
            if (result.getError() != null) {
                out.println("  error: " + result.getError());
            }
        } catch (RuntimeException turnFailure) {
            out.println("[error] " + turnFailure.getClass().getSimpleName() + ": " + turnFailure.getMessage());
        }
        out.println();
    }

    /**
     * 打印装配结果与当前模型信息。
     */
    private static void printBanner(PrintStream out, ApplicationBootstrap bootstrap,
            ProviderConfiguration providerConfig, Path configPath, String source) {
        out.println("Clara application assembled successfully.");
        out.println("  orchestrator   : " + bootstrap.orchestrator().getClass().getName());
        out.println("  workflowEngine : " + bootstrap.workflowEngine().getClass().getName());
        out.println("  embeddingClient: " + bootstrap.embeddingClient().getClass().getName());
        out.println("  model          : " + describe(providerConfig) + "   [" + configPath + "]");
        out.println("  modelSource    : " + source);
        out.println();
    }

    /**
     * 让用户从候选配置中选择一个。
     *
     * @param candidates 候选配置，非空
     * @param reader     标准输入读取器，非 null
     * @param out        输出流，非 null
     * @return 选中的配置路径
     */
    private static Path chooseModel(List<Path> candidates, BufferedReader reader, PrintStream out) {
        if (candidates.size() == 1) {
            return candidates.get(0);
        }
        out.println("检测到多个模型配置（已忽略 examples 样例）：");
        for (int index = 0; index < candidates.size(); index++) {
            Path candidate = candidates.get(index);
            out.println("  " + (index + 1) + ") " + describe(candidate) + "   [" + candidate + "]");
        }
        while (true) {
            out.print("请选择模型 [1-" + candidates.size() + "]（直接回车默认 1）：");
            out.flush();
            try {
                String line = reader.readLine();
                if (line == null) {
                    return candidates.get(0);
                }
                String value = line.trim();
                if (value.isEmpty()) {
                    return candidates.get(0);
                }
                int index = Integer.parseInt(value);
                if (index >= 1 && index <= candidates.size()) {
                    return candidates.get(index - 1);
                }
            } catch (NumberFormatException ignored) {
                // 非数字输入落回重试
            } catch (IOException failure) {
                return candidates.get(0);
            }
            out.println("输入无效，请重试。");
        }
    }

    /**
     * 读取已加载配置的「平台 / 模型」描述。
     */
    private static String describe(ProviderConfiguration providerConfig) {
        try {
            return providerConfig.getProvider() + " / " + providerConfig.toConfig(CapabilityKindEnum.CHAT).getModelId();
        } catch (RuntimeException failure) {
            return providerConfig.getProvider() + " / (无 CHAT 能力: " + failure.getMessage() + ")";
        }
    }

    /**
     * 读取一个配置文件的「平台 / 模型」描述，用于选择列表展示；加载失败时返回失败原因。
     */
    private static String describe(Path configPath) {
        try (ProviderConfiguration config = new ProviderYamlConfigurationLoader().load(configPath)) {
            return describe(config);
        } catch (RuntimeException failure) {
            return "(加载失败: " + failure.getMessage() + ")";
        }
    }

    /**
     * 扫描模型配置：递归收集 {@code *.yml} / {@code *.yaml}，忽略 {@code examples} 目录与 {@code .example.yml}
     * 样例文件。
     *
     * @param directory 模型配置目录，可为不存在
     * @return 排序后的配置路径列表，非 null
     */
    private static List<Path> listModelConfigs(Path directory) {
        if (directory == null || !Files.isDirectory(directory)) {
            return Collections.emptyList();
        }
        List<Path> result = new ArrayList<Path>();
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
     * 读取 {@code config/clara.yml} 的 {@code defaultModel}（主模型）。
     *
     * <p>该文件只有一个顶层标量键，故按行简单解析（不做通用 YAML 解析）：取首个非注释的
     * {@code defaultModel:} 行，去掉两端引号后返回。文件缺失、键缺失或读取失败时返回 null。
     *
     * @param configFile 配置文件，可为 null 或不存在
     * @return 主模型标识，未配置时 null
     */
    private static String readDefaultModel(Path configFile) {
        if (configFile == null || !Files.isRegularFile(configFile)) {
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
    private static Path resolveDefault(List<Path> candidates, String name) {
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

    /**
     * 定位模型配置目录：先在相对当前工作目录下查找，找不到则逐级向上回溯。
     *
     * @param relative 相对目录，非 null
     * @return 定位到的目录（找不到时返回相对路径本身）
     */
    private static Path locateDirectory(Path relative) {
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
     * @return 定位到的路径（找不到时返回原路径）
     */
    private static Path locateFile(Path configured) {
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
}
