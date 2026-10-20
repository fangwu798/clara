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
import java.util.concurrent.atomic.AtomicReference;

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
    private final AtomicReference<Map<String, LoadedWorkflow>> workflows = new AtomicReference<>(Collections.emptyMap());

    /**
     * 定义文件签名（路径 → 修改时间:大小），用于发现运行期新增 / 修改 / 删除的定义文件。
     */
    private final AtomicReference<Map<String, String>> sourceStamps = new AtomicReference<>(Collections.emptyMap());

    /**
     * 最近一次刷新失败的说明（成功时为 null）：既有索引保留可用，失败原因透出给调用方。
     */
    private volatile String lastError;

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
        workflows.set(loadAll());
        lastError = null;
        sourceStamps.set(scanStamps());
    }

    /**
     * 按需刷新：比对定义目录下的文件签名，仅在确有新增 / 修改 / 删除时重新解析。
     *
     * <p>用途：让「运行期写入的自定义工作流」（{@code config/workflow/custom}）在后续调用中直接可用，
     * 无需重启进程；签名一致时只做一次目录遍历与 stat，开销很小。
     *
     * <p>失败语义：解析失败时<b>保留既有索引</b>（已加载的工作流照常可运行），把原因记入
     * {@link #lastError()} 并返回 false；成功替换索引时清空该错误并返回 true。失败时签名不更新，
     * 因此修好文件后下一次刷新会自动重试。
     *
     * @return true 表示索引有效且无解析错误；false 表示本次刷新遇到了非法的定义文件
     */
    public synchronized boolean refresh() {
        if (scanStamps().equals(sourceStamps.get())) {
            return lastError == null;
        }
        Map<String, LoadedWorkflow> previous = workflows.get();
        try {
            workflows.set(loadAll());
        } catch (RuntimeException e) {
            workflows.set(previous);
            lastError = e.getMessage();
            return false;
        }
        lastError = null;
        sourceStamps.set(scanStamps());
        return true;
    }

    /**
     * 最近一次刷新 / 重载失败的说明。
     *
     * @return 失败原因，无失败时为 null
     */
    public String lastError() {
        return lastError;
    }

    /**
     * 解析单个定义文件但不并入索引，供「校验即将发布的自定义工作流」使用。
     *
     * @param file 工作流定义文件，不可为 null
     * @return 解析出的工作流定义，非 null
     * @throws IllegalArgumentException 文件不可读或配置非法时（消息含文件与节点 id）
     */
    public WorkflowDefinition parseDefinition(Path file) {
        return loader.load(file).getDefinition();
    }

    /**
     * 工作流定义目录（自定义工作流写在其 {@code custom} 子目录下）。
     *
     * @return 定义目录，可为 null（构造时未指定）
     */
    public Path directory() {
        return directory;
    }

    /**
     * 扫描并解析目录下全部定义文件，返回按标识索引的不可变映射（目录不存在时返回空映射）。
     */
    private Map<String, LoadedWorkflow> loadAll() {
        if (directory == null || !Files.isDirectory(directory)) {
            return Collections.emptyMap();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(directory)) {
            stream.filter(Files::isRegularFile).forEach(files::add);
        } catch (IOException e) {
            throw new IllegalArgumentException("工作流定义目录扫描失败（" + directory + "）：" + e.getMessage(), e);
        }
        Collections.sort(files);
        Map<String, LoadedWorkflow> loaded = new LinkedHashMap<>();
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
        return Collections.unmodifiableMap(loaded);
    }

    /**
     * 采集定义目录下全部工作流文件的签名（路径 → 修改时间:大小）。
     *
     * @return 签名的有序映射，非 null（目录不存在或不可遍历时为空）
     */
    private Map<String, String> scanStamps() {
        if (directory == null || !Files.isDirectory(directory)) {
            return Collections.emptyMap();
        }
        Map<String, String> stamps = new LinkedHashMap<>();
        try (Stream<Path> stream = Files.walk(directory)) {
            stream.filter(Files::isRegularFile).filter(WorkflowDefinitionRegistry::isWorkflowFile)
                    .sorted().forEach(file -> stamps.put(file.toString(), signature(file)));
        } catch (IOException e) {
            return Collections.emptyMap();
        }
        return stamps;
    }

    /**
     * 单个文件的签名：修改时间与大小，任一变化都视为需要重新解析。
     */
    private static String signature(Path file) {
        try {
            return Files.getLastModifiedTime(file).toMillis() + ":" + Files.size(file);
        } catch (IOException e) {
            return "unknown";
        }
    }

    /**
     * 按标识取回工作流定义。
     *
     * @param workflowId 工作流标识，不可为空白
     * @return 工作流定义，非 null
     * @throws IllegalArgumentException 未命中时（消息含可用标识列表）
     */
    public WorkflowDefinition require(String workflowId) {
        LoadedWorkflow workflow = workflows.get().get(workflowId);
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
        LoadedWorkflow workflow = workflows.get().get(workflowId);
        return workflow == null ? Collections.emptyList() : workflow.getOutputs();
    }

    /**
     * 返回当前已加载的工作流标识列表（升序）。
     *
     * @return 标识列表，非 null，可为空
     */
    public List<String> ids() {
        List<String> ids = new ArrayList<>(workflows.get().keySet());
        Collections.sort(ids);
        return ids;
    }

    private static boolean isWorkflowFile(Path file) {
        Path fileName = file.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }
}
