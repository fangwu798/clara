package com.clara.dto.workflow;

import com.clara.utils.MapUtils;
import com.clara.utils.ObjectUtils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 执行作用域：工作流实例执行期间累积的变量、节点结果、已完成执行标识与控制状态。
 *
 * <p>线程安全：所有读写方法同步，副本通过 {@link #copy()} 获取，用于并行分支隔离。
 */
public final class ExecutionScope {

    /**
     * 作用域控制键：已有 HUMAN 节点收到用户输入。
     *
     * <p>视频生成等高价能力据此要求「先人工确认再调用」；值为 human 节点 id。
     */
    public static final String CONTROL_HUMAN_CONFIRMED = "human:confirmed";

    /**
     * 作用域控制键：已有 HUMAN 节点收到明确的批准输入。
     *
     * <p>与 {@link #CONTROL_HUMAN_CONFIRMED} 的区别：后者只表示「用户回复过」，本键只在回复为
     * {@code approve / approved / yes / true / ok / 确认 / 同意} 时写入。视频生成这类高价能力要求本键存在，
     * 避免「用户只提了修改意见」也被当成批准。
     */
    public static final String CONTROL_HUMAN_APPROVED = "human:approved";

    private static final int SCHEMA_VERSION = 1;

    private final Map<String, Object> variables;
    private final Map<String, Map<String, Object>> nodeResults;
    private final Set<String> completedExecutions;
    private final Map<String, String> controls;
    private long version;

    /**
     * 以初始输入创建作用域。
     */
    public ExecutionScope(Map<String, Object> inputs) {
        this(MapUtils.copyMap(inputs), new LinkedHashMap<>(), new LinkedHashSet<>(), new LinkedHashMap<>(), 0L);
    }

    private ExecutionScope(Map<String, Object> variables,
            Map<String, Map<String, Object>> nodeResults, Set<String> completedExecutions,
            Map<String, String> controls, long version) {
        this.variables = variables;
        this.nodeResults = nodeResults;
        this.completedExecutions = completedExecutions;
        this.controls = controls;
        this.version = version;
    }

    public int getSchemaVersion() {
        return SCHEMA_VERSION;
    }

    public synchronized long getVersion() {
        return version;
    }

    public synchronized boolean contains(String key) {
        return variables.containsKey(key);
    }

    public synchronized Object get(String key) {
        return variables.get(key);
    }

    public synchronized Map<String, Object> getVariables() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

    public synchronized Map<String, Map<String, Object>> getNodeResults() {
        Map<String, Map<String, Object>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : nodeResults.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    public synchronized ExecutionScope copy() {
        Map<String, Map<String, Object>> results = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : nodeResults.entrySet()) {
            results.put(entry.getKey(), new LinkedHashMap<>(entry.getValue()));
        }
        return new ExecutionScope(new LinkedHashMap<>(variables), results,
                new LinkedHashSet<>(completedExecutions),
                new LinkedHashMap<>(controls), version);
    }

    public synchronized boolean isCompleted(String executionKey) {
        return completedExecutions.contains(executionKey);
    }

    public synchronized void complete(String executionKey, String nodeId, Map<String, Object> outputs) {
        Map<String, Object> values = MapUtils.copyMap(outputs);
        variables.putAll(values);
        nodeResults.put(nodeId, values);
        completedExecutions.add(executionKey);
        version++;
    }

    public synchronized String control(String key) {
        return controls.get(key);
    }

    public synchronized void control(String key, String value) {
        controls.put(key, value);
        version++;
    }

    public synchronized void addInputs(Map<String, Object> values) {
        variables.putAll(MapUtils.copyMap(values));
        version++;
    }

    public synchronized void merge(ExecutionScope base, ExecutionScope branch) {
        for (Map.Entry<String, Object> entry : branch.variables.entrySet()) {
            if (!base.variables.containsKey(entry.getKey())
                    || !ObjectUtils.areEqual(base.variables.get(entry.getKey()), entry.getValue())) {
                variables.put(entry.getKey(), entry.getValue());
            }
        }
        nodeResults.putAll(branch.nodeResults);
        completedExecutions.addAll(branch.completedExecutions);
        controls.putAll(branch.controls);
        version++;
    }

}
