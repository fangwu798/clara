package com.clara.dto.workflow;

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
        this(copyMap(inputs), new LinkedHashMap<>(),
                new LinkedHashSet<>(), new LinkedHashMap<>(), 0L);
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
            copy.put(entry.getKey(),
                    Collections.unmodifiableMap(new LinkedHashMap<>(entry.getValue())));
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
        Map<String, Object> values = copyMap(outputs);
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
        variables.putAll(copyMap(values));
        version++;
    }

    public synchronized void merge(ExecutionScope base, ExecutionScope branch) {
        for (Map.Entry<String, Object> entry : branch.variables.entrySet()) {
            if (!base.variables.containsKey(entry.getKey())
                    || !equal(base.variables.get(entry.getKey()), entry.getValue())) {
                variables.put(entry.getKey(), entry.getValue());
            }
        }
        nodeResults.putAll(branch.nodeResults);
        completedExecutions.addAll(branch.completedExecutions);
        controls.putAll(branch.controls);
        version++;
    }

    private static Map<String, Object> copyMap(Map<String, Object> values) {
        return values == null ? new LinkedHashMap<>()
                : new LinkedHashMap<>(values);
    }

    private static boolean equal(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }
}
