package com.clara.workflow.definition;

import com.clara.dto.workflow.WorkflowDefinition;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * 工作流定义注册表：递归加载目录下的 YAML 工作流定义，并按工作流标识建立只读索引。
 *
 * <p>是什么、在哪一层：clara-workflow 的定义来源抽象（包 {@code com.clara.workflow.definition}），
 * 供运行器按标识取回定义；目录不存在时视为无可用定义，不报错。
 *
 * <p>线程安全：{@link #reload()} 与读取通过「不可变映射整体替换」实现，读取无需加锁。
 *
 * <p>失败语义：{@link #reload()} 时某文件配置非法按加载器约定抛 {@link IllegalArgumentException}；
 * 同一工作流标识重复定义抛 {@link IllegalArgumentException}；{@link #require(String)} 未命中时抛
 * {@link IllegalArgumentException}（消息含可用标识列表）。
 */
public final class WorkflowDefinitionRegistry {

    private final Path directory;
    private final WorkflowDefinitionLoader loader;
    private volatile Map<String, LoadedWorkflow> workflows = Collections.emptyMap();

    /**
     * 创建工作流定义注册表。
     *
     * @param directory 定义目录（相对运行目录即可），可为 null 或不存在
     * @param loader    YAML 定义加载器，不可为 null
     * @throws IllegalArgumentException loader 为 null 时
     */
    public WorkflowDefinitionRegistry(Path directory, WorkflowDefinitionLoader loader) {
        if (loader == null) {
            throw new IllegalArgumentException("loader must not be null");
        }
        this.directory = directory;
        this.loader = loader;
    }

    /**
     * 递归重载目录下 {@code *.yml} / {@code *.yaml} 定义并按标识建索引；目录不存在时清空索引、不报错。
     *
     * @throws IllegalArgumentException 定义非法或标识重复时
     */
    public synchronized void reload() {
        if (directory == null || !Files.isDirectory(directory)) {
            workflows = Collections.emptyMap();
            return;
        }
        List<Path> files = new ArrayList<Path>();
        try (Stream<Path> stream = Files.walk(directory)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        } catch (IOException e) {
            throw new IllegalArgumentException("工作流定义目录扫描失败（" + directory + "）：" + e.getMessage(), e);
        }
        Collections.sort(files);
        Map<String, LoadedWorkflow> loaded = new LinkedHashMap<String, LoadedWorkflow>();
        for (Path file : files) {
            if (!isWorkflowFile(file)) {
                continue;
            }
            LoadedWorkflow workflow = loader.load(file);
            String workflowId = workflow.getDefinition().getWorkflowId();
            LoadedWorkflow previous = loaded.put(workflowId, workflow);
            if (previous != null) {
                throw new IllegalArgumentException("工作流标识重复：" + workflowId + "（" + file + "）");
            }
        }
        workflows = Collections.unmodifiableMap(loaded);
    }

    /**
     * 按标识取回工作流定义。
     *
     * @param workflowId 工作流标识，不可为空白
     * @return 工作流定义，非 null
     * @throws IllegalArgumentException 未命中时（消息含可用标识列表）
     */
    public WorkflowDefinition require(String workflowId) {
        LoadedWorkflow workflow = workflows.get(workflowId);
        if (workflow == null) {
            throw new IllegalArgumentException("工作流不存在：" + workflowId + "，可用：" + ids());
        }
        return workflow.getDefinition();
    }

    /**
     * 返回某工作流对外声明的输出变量键。
     *
     * @param workflowId 工作流标识，可为 null
     * @return 输出键列表，非 null，可为空（未声明或标识不存在时）
     */
    public List<String> outputs(String workflowId) {
        LoadedWorkflow workflow = workflows.get(workflowId);
        return workflow == null ? Collections.<String>emptyList() : workflow.getOutputs();
    }

    /**
     * 返回当前已加载的工作流标识列表（升序）。
     *
     * @return 标识列表，非 null，可为空
     */
    public List<String> ids() {
        List<String> ids = new ArrayList<String>(workflows.keySet());
        Collections.sort(ids);
        return ids;
    }

    private static boolean isWorkflowFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }
}
