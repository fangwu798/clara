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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 技能注册表：按分类扫描 {@code tools/}、{@code prompt/} 与 {@code scripts/} 目录（内置 {@code resource/config}
 * 与运行期 {@code config/skill} 两处来源），识别技能与提示词，支持热加载与同名覆盖，并把描述向量化
 * 写入语义记忆存储（来源 {@code PROMPT} / {@code TOOL_DESCRIPTION}）。
 *
 * <p>工具来源有两种：{@code tools/} 下的 JSON 声明（如内置 {@code BUILTIN} 工具），以及 {@code scripts/} 下
 * 自描述的 Python 脚本（以 {@code --describe} 输出元数据，定义与执行体同文件）；两者以工具标识合并，运行时
 * 目录覆盖内置目录。
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
        Set<String> oldToolIds = new HashSet<>(tools.keySet());
        Set<String> oldPromptIds = new HashSet<>(prompts.keySet());

        Map<String, ToolMethod> newTools = new ConcurrentHashMap<>();
        Map<String, PromptDefinition> newPrompts = new ConcurrentHashMap<>();
        scanDirectory(newTools, newPrompts, builtinRoot);
        scanDirectory(newTools, newPrompts, runtimeRoot);

        tools.clear();
        tools.putAll(newTools);
        prompts.clear();
        prompts.putAll(newPrompts);
        rebuildToolNames();

        syncSemanticMemory(oldToolIds, oldPromptIds);
    }

    /**
     * 按工具名取回工具方法，不存在时抛出技能不存在异常。
     */
    public ToolMethod requireToolByName(String name) {
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
        return toolsByName.get(name);
    }

    /**
     * 按提示词标识查找提示词定义。
     *
     * @return 对应提示词定义，未命中时返回 null
     */
    public PromptDefinition findPrompt(String id) {
        return prompts.get(id);
    }

    /**
     * 按提示词标识取回提示词定义，不存在时抛出技能不存在异常。
     */
    public PromptDefinition requirePrompt(String id) {
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
        return new ArrayList<>(tools.values());
    }

    /**
     * 读取全部已注册提示词。
     */
    public List<PromptDefinition> allPrompts() {
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
     * 扫描自描述脚本工具：对 {@code scripts/} 下非下划线开头的 {@code .py} 执行 {@code --describe}
     * 取回工具声明；脚本非工具或加载失败时跳过，不影响其余工具。
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
     * 列出 {@code scripts/} 下的工具脚本：仅顶层 {@code .py}，跳过下划线开头的共享辅助模块。
     */
    private List<File> listScriptFiles(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return Collections.emptyList();
        }
        List<File> result = new ArrayList<>();
        File[] children = dir.listFiles();
        if (children == null) {
            return result;
        }
        for (File child : children) {
            String name = child.getName();
            if (child.isFile() && name.endsWith(".py") && !name.startsWith("_")) {
                result.add(child);
            }
        }
        return result;
    }

    private void syncSemanticMemory(Set<String> oldToolIds, Set<String> oldPromptIds) {
        if (embeddingClient == null || memoryStore == null) {
            return;
        }
        List<MemoryEntry> entries = new ArrayList<>();
        for (ToolMethod method : tools.values()) {
            entries.add(indexTool(method));
        }
        for (PromptDefinition prompt : prompts.values()) {
            entries.add(indexPrompt(prompt));
        }
        if (!entries.isEmpty()) {
            memoryStore.upsert(entries);
        }
        for (String id : oldToolIds) {
            if (!tools.containsKey(id)) {
                memoryStore.deleteById(entryId(MemorySourceTypeEnum.TOOL_DESCRIPTION, id));
            }
        }
        for (String id : oldPromptIds) {
            if (!prompts.containsKey(id)) {
                memoryStore.deleteById(entryId(MemorySourceTypeEnum.PROMPT, id));
            }
        }
    }

    private MemoryEntry indexTool(ToolMethod method) {
        Map<String, String> metadata = new LinkedHashMap<String, String>();
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
