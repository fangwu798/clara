package com.clara.skill.registry;

import com.clara.core.memory.IEmbeddingClient;
import com.clara.core.memory.ISemanticMemoryStore;
import com.clara.dto.memory.MemoryEntry;
import com.clara.dto.skill.PromptDefinition;
import com.clara.dto.skill.ToolMethod;
import com.clara.enums.memory.MemorySourceTypeEnum;
import com.clara.skill.SkillErrors;

import java.io.File;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能注册表：按分类扫描 {@code tools/}、{@code prompt/} 与 {@code scripts/} 目录（内置 {@code resource/config}
 * 与运行期 {@code config/skill} 两处来源），识别技能与提示词，支持热加载与同名覆盖，并把描述向量化
 * 写入语义记忆存储（来源 {@code PROMPT} / {@code TOOL_DESCRIPTION}）。
 *
 * <p>工具来源有两种：{@code tools/} 下的 JSON 声明（如内置 {@code BUILTIN} 工具），以及 {@code scripts/} 下
 * 自描述的 Python 脚本（以 {@code --describe} 输出元数据，定义与执行体同文件）；两者以工具标识合并，运行时
 * 目录覆盖内置目录。{@code scripts/} 按用途分子目录组织（如 {@code file-ops/}、{@code compile/}），
 * 扫描为递归进行，目录层级不影响工具发现。
 *
 * <p>运行期改动：每次读取（提示词取值、工具查找、工具与提示词列举）都先比对运行期 {@code prompt/} 与
 * {@code scripts/} 来源文件的签名（修改时间:大小），发现新增 / 修改 / 删除即自动重扫，因此运行过程中修改
 * 提示词或脚本会在下一次调用生效；签名一致时只做一次目录列举与 stat，不重复解析。
 */
public final class SkillRegistry {

    private final File builtinRoot;
    private final File runtimeRoot;
    private final SkillLoader loader;
    private final ScriptToolLoader scriptToolLoader;
    private final IEmbeddingClient embeddingClient;
    private final ISemanticMemoryStore memoryStore;

    private final Map<String, ToolMethod> tools = new ConcurrentHashMap<>();
    private final Map<String, ToolMethod> toolsByName = new ConcurrentHashMap<>();
    private final Map<String, PromptDefinition> prompts = new ConcurrentHashMap<>();

    /** 运行期来源文件签名（绝对路径 → 修改时间:大小），用于发现 prompt / scripts 目录的运行期改动。 */
    private volatile Map<String, String> sourceStamps = Collections.emptyMap();

    /** 重载互斥：签名比对与整体替换串行化，避免并发触发重复重载。 */
    private final Object reloadLock = new Object();

    /**
     * 创建注册表。
     *
     * @param builtinRoot    内置技能根目录（对应 {@code resource/config}），可为 null
     * @param runtimeRoot    运行期技能根目录（对应 {@code config/skill}），可为 null
     * @param loader         文件解析器，可为 null（使用默认解析器）
     * @param embeddingClient 描述向量化客户端，可为 null（null 时跳过语义记忆索引）
     * @param memoryStore    语义记忆存储，可为 null（null 时跳过语义记忆索引）
     */
    public SkillRegistry(File builtinRoot, File runtimeRoot, SkillLoader loader,
            IEmbeddingClient embeddingClient, ISemanticMemoryStore memoryStore) {
        this(builtinRoot, runtimeRoot, loader, new ScriptToolLoader(), embeddingClient, memoryStore);
    }

    /**
     * 显式装配脚本工具加载器构造。
     *
     * @param builtinRoot      内置技能根目录（对应 {@code resource/config}），可为 null
     * @param runtimeRoot      运行期技能根目录（对应 {@code config/skill}），可为 null
     * @param loader           JSON 文件解析器，可为 null（使用默认解析器）
     * @param scriptToolLoader 脚本工具加载器（执行 {@code --describe}），可为 null（使用默认加载器）
     * @param embeddingClient  描述向量化客户端，可为 null（null 时跳过语义记忆索引）
     * @param memoryStore      语义记忆存储，可为 null（null 时跳过语义记忆索引）
     */
    public SkillRegistry(File builtinRoot, File runtimeRoot, SkillLoader loader,
            ScriptToolLoader scriptToolLoader, IEmbeddingClient embeddingClient,
            ISemanticMemoryStore memoryStore) {
        this.builtinRoot = builtinRoot;
        this.runtimeRoot = runtimeRoot;
        this.loader = loader == null ? new SkillLoader() : loader;
        this.scriptToolLoader = scriptToolLoader == null ? new ScriptToolLoader() : scriptToolLoader;
        this.embeddingClient = embeddingClient;
        this.memoryStore = memoryStore;
    }

    /**
     * 解析内置技能根目录（classpath 下 {@code config}，仅文件协议可定位）。
     *
     * @return 内置技能根目录，无法定位时返回 null
     */
    public static File resolveBuiltinRoot() {
        try {
            URL url = SkillRegistry.class.getClassLoader().getResource("config");
            if (url != null && "file".equalsIgnoreCase(url.getProtocol())) {
                return new File(url.toURI());
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 解析运行期技能根目录（当前运行目录下 {@code config/skill}）。
     *
     * @return 运行期技能根目录，非 null
     */
    public static File resolveRuntimeRoot() {
        return new File("config", "skill");
    }

    /**
     * 全量重载：先扫内置目录、再扫运行期目录（同名同标识覆盖），并同步语义记忆索引。
     */
    public void reload() {
        synchronized (reloadLock) {
            rescan();
        }
    }

    /**
     * 发现运行期改动后按需重载：比对 {@code prompt/} 与 {@code scripts/} 来源文件签名，仅在确有改动时重扫。
     *
     * <p>因此「运行过程中修改提示词或脚本文件」会在下一次读取（提示词取值、工具查找、工具目录下发）时生效；
     * 签名一致时只做一次目录列举与 stat，开销很小。改动后调用 {@code --describe} 的开销由
     * {@link ScriptToolLoader} 的签名缓存兜底，未改动的脚本不会重复启动 Python。
     */
    private void refreshChanged() {
        synchronized (reloadLock) {
            if (!scanStamps().equals(sourceStamps)) {
                rescan();
            }
        }
    }

    /**
     * 重扫内置与运行期目录并整体替换索引，随后增量同步语义记忆（仅在调用方持锁时执行）。
     */
    private void rescan() {
        Map<String, ToolMethod> previousTools = new LinkedHashMap<>(tools);
        Map<String, PromptDefinition> previousPrompts = new LinkedHashMap<>(prompts);

        Map<String, ToolMethod> newTools = new ConcurrentHashMap<>();
        Map<String, PromptDefinition> newPrompts = new ConcurrentHashMap<>();
        scanDirectory(newTools, newPrompts, builtinRoot);
        scanDirectory(newTools, newPrompts, runtimeRoot);

        tools.clear();
        tools.putAll(newTools);
        prompts.clear();
        prompts.putAll(newPrompts);
        rebuildToolNames();
        sourceStamps = scanStamps();

        syncSemanticMemory(previousTools, previousPrompts);
    }

    /**
     * 采集运行期来源文件签名：{@code prompt/} 下的 JSON 与 {@code scripts/} 下递归的非下划线 {@code .py}。
     *
     * @return 路径 → 签名（修改时间:大小），非 null
     */
    private Map<String, String> scanStamps() {
        if (runtimeRoot == null) {
            return Collections.emptyMap();
        }
        Map<String, String> stamps = new LinkedHashMap<>();
        collectStamps(stamps, listFiles(new File(runtimeRoot, "prompt")));
        collectStamps(stamps, listScriptFiles(new File(runtimeRoot, "scripts")));
        return Collections.unmodifiableMap(stamps);
    }

    private static void collectStamps(Map<String, String> target, List<File> files) {
        for (File file : files) {
            target.put(file.getAbsolutePath(), file.lastModified() + ":" + file.length());
        }
    }

    /**
     * 按工具名取回工具方法，不存在时抛出技能不存在异常。
     */
    public ToolMethod requireToolByName(String name) {
        refreshChanged();
        ToolMethod method = toolsByName.get(name);
        if (method == null) {
            throw SkillErrors.skillNotFound(name);
        }
        return method;
    }

    /**
     * 按工具名查找工具方法。
     *
     * @return 对应工具方法，未命中时返回 null
     */
    public ToolMethod findToolByName(String name) {
        refreshChanged();
        return toolsByName.get(name);
    }

    /**
     * 按提示词标识查找提示词定义。
     *
     * @return 对应提示词定义，未命中时返回 null
     */
    public PromptDefinition findPrompt(String id) {
        refreshChanged();
        return prompts.get(id);
    }

    /**
     * 按提示词标识取回提示词定义，不存在时抛出技能不存在异常。
     */
    public PromptDefinition requirePrompt(String id) {
        refreshChanged();
        PromptDefinition definition = prompts.get(id);
        if (definition == null) {
            throw SkillErrors.skillNotFound(id);
        }
        return definition;
    }

    /**
     * 读取全部已注册工具方法。
     */
    public List<ToolMethod> allTools() {
        refreshChanged();
        return new ArrayList<>(tools.values());
    }

    /**
     * 读取全部已注册提示词。
     */
    public List<PromptDefinition> allPrompts() {
        refreshChanged();
        return new ArrayList<>(prompts.values());
    }

    private void rebuildToolNames() {
        toolsByName.clear();
        for (ToolMethod method : tools.values()) {
            toolsByName.put(method.getName(), method);
        }
    }

    private void scanDirectory(Map<String, ToolMethod> targetTools, Map<String, PromptDefinition> targetPrompts,
            File root) {
        if (root == null || !root.isDirectory()) {
            return;
        }
        scanTools(targetTools, new File(root, "tools"));
        scanPrompts(targetPrompts, new File(root, "prompt"));
        scanScriptTools(targetTools, new File(root, "scripts"));
    }

    private void scanTools(Map<String, ToolMethod> target, File dir) {
        for (File file : listFiles(dir)) {
            ToolMethod method = loader.parseTool(file);
            target.put(method.getId(), method);
        }
    }

    /**
     * 扫描自描述脚本工具：对 {@code scripts/} 下（含各用途子目录）非下划线开头的 {@code .py} 执行
     * {@code --describe} 取回工具声明；脚本非工具或加载失败时跳过，不影响其余工具。
     */
    private void scanScriptTools(Map<String, ToolMethod> target, File dir) {
        for (File file : listScriptFiles(dir)) {
            ToolMethod method = scriptToolLoader.load(file);
            if (method != null) {
                target.put(method.getId(), method);
            }
        }
    }

    private void scanPrompts(Map<String, PromptDefinition> target, File dir) {
        for (File file : listFiles(dir)) {
            PromptDefinition definition = loader.parsePrompt(file);
            target.put(definition.getId(), definition);
        }
    }

    private List<File> listFiles(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return Collections.emptyList();
        }
        List<File> result = new ArrayList<>();
        File[] children = dir.listFiles();
        if (children == null) {
            return result;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                result.addAll(listFiles(child));
            } else if (child.isFile() && child.getName().endsWith(".json")) {
                result.add(child);
            }
        }
        return result;
    }

    /**
     * 递归列出 {@code scripts/} 下的工具脚本：任意层级的 {@code .py}，跳过下划线开头的目录与文件。
     *
     * <p>跳过下划线开头既是共享辅助模块（如 {@code _textio.py}、{@code _toolmeta.py}）的约定，也顺带排除
     * Python 的 {@code __pycache__} 等缓存目录。递归是为了支持按用途分子目录组织脚本。
     */
    private static List<File> listScriptFiles(File dir) {
        List<File> result = new ArrayList<>();
        collectScriptFiles(result, dir);
        return result;
    }

    private static void collectScriptFiles(List<File> target, File dir) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            String name = child.getName();
            if (name.startsWith("_")) {
                continue;
            }
            if (child.isDirectory()) {
                collectScriptFiles(target, child);
            } else if (child.isFile() && name.endsWith(".py")) {
                target.add(child);
            }
        }
    }

    /**
     * 增量同步语义记忆：只索引「新增或内容变化」的工具与提示词，并删除已消失的条目。
     *
     * <p>运行期改动通常只涉及个别文件，增量同步避免每次改动都重新向量化全部描述。
     */
    private void syncSemanticMemory(Map<String, ToolMethod> previousTools,
            Map<String, PromptDefinition> previousPrompts) {
        if (embeddingClient == null || memoryStore == null) {
            return;
        }
        List<MemoryEntry> entries = new ArrayList<>();
        for (ToolMethod method : tools.values()) {
            ToolMethod before = previousTools.get(method.getId());
            if (before == null || !before.equals(method)) {
                entries.add(indexTool(method));
            }
        }
        for (PromptDefinition prompt : prompts.values()) {
            PromptDefinition before = previousPrompts.get(prompt.getId());
            if (before == null || !before.equals(prompt)) {
                entries.add(indexPrompt(prompt));
            }
        }
        if (!entries.isEmpty()) {
            memoryStore.upsert(entries);
        }
        for (String id : previousTools.keySet()) {
            if (!tools.containsKey(id)) {
                memoryStore.deleteById(entryId(MemorySourceTypeEnum.TOOL_DESCRIPTION, id));
            }
        }
        for (String id : previousPrompts.keySet()) {
            if (!prompts.containsKey(id)) {
                memoryStore.deleteById(entryId(MemorySourceTypeEnum.PROMPT, id));
            }
        }
    }

    private MemoryEntry indexTool(ToolMethod method) {
        Map<String, String> metadata = new LinkedHashMap<>();
        if (method.getParameters() != null && !method.getParameters().trim().isEmpty()) {
            metadata.put(MemoryEntry.TOOL_PARAMETERS_METADATA_KEY, method.getParameters());
        }
        return entry(MemorySourceTypeEnum.TOOL_DESCRIPTION, method.getId(), method.getDescription(), metadata);
    }

    private MemoryEntry indexPrompt(PromptDefinition prompt) {
        String content = prompt.getDescription() == null || prompt.getDescription().trim().isEmpty()
                ? prompt.getId()
                : prompt.getDescription();
        return entry(MemorySourceTypeEnum.PROMPT, prompt.getId(), content,
                Collections.<String, String>emptyMap());
    }

    private MemoryEntry entry(MemorySourceTypeEnum sourceType, String sourceId, String content,
            Map<String, String> metadata) {
        float[] vector = embeddingClient.embed(content);
        long now = System.currentTimeMillis();
        return new MemoryEntry(entryId(sourceType, sourceId), sourceType, sourceId, null, content,
                sha256(content), vector, now, 0L, metadata);
    }

    private String entryId(MemorySourceTypeEnum sourceType, String sourceId) {
        return sourceType.name() + ":" + sourceId;
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                String hex = Integer.toHexString(value & 0xff);
                if (hex.length() == 1) {
                    builder.append('0');
                }
                builder.append(hex);
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
