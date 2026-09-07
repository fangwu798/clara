package com.clara.workflow;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 表示。
 */
public final class ExecutionScope {
    private static final int SCHEMA_VERSION = 1;
    private final Map<String, Object> variables;
    private final Map<String, Map<String, Object>> nodeResults;
    private final Set<String> completedExecutions;
    private final Map<String, String> controls;
    private long version;

    public ExecutionScope(Map<String, Object> inputs) {
        this(copyMap(inputs), new LinkedHashMap<String, Map<String, Object>>(),
                new LinkedHashSet<String>(), new LinkedHashMap<String, String>(), 0L);
    }

    private ExecutionScope(Map<String, Object> variables,
            Map<String, Map<String, Object>> nodeResults, Set<String> completedExecutions,
            Map<String, String> controls, long version) {
        this.variables = variables; this.nodeResults = nodeResults;
        this.completedExecutions = completedExecutions; this.controls = controls; this.version = version;
    }

    public int getSchemaVersion() { return SCHEMA_VERSION; }
    public synchronized long getVersion() { return version; }
    public synchronized boolean contains(String key) { return variables.containsKey(key); }
    public synchronized Object get(String key) { return variables.get(key); }
    public synchronized Map<String, Object> getVariables() {
        return Collections.unmodifiableMap(new LinkedHashMap<String, Object>(variables));
    }
    public synchronized Map<String, Map<String, Object>> getNodeResults() {
        Map<String, Map<String, Object>> copy = new LinkedHashMap<String, Map<String, Object>>();
        for (Map.Entry<String, Map<String, Object>> entry : nodeResults.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableMap(
                    new LinkedHashMap<String, Object>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    synchronized ExecutionScope copy() {
        Map<String, Map<String, Object>> results = new LinkedHashMap<String, Map<String, Object>>();
        for (Map.Entry<String, Map<String, Object>> entry : nodeResults.entrySet()) {
            results.put(entry.getKey(), new LinkedHashMap<String, Object>(entry.getValue()));
        }
        return new ExecutionScope(new LinkedHashMap<String, Object>(variables), results,
                new LinkedHashSet<String>(completedExecutions),
                new LinkedHashMap<String, String>(controls), version);
    }

    synchronized boolean isCompleted(String executionKey) {
        return completedExecutions.contains(executionKey);
    }
    synchronized void complete(String executionKey, String nodeId, Map<String, Object> outputs) {
        Map<String, Object> values = copyMap(outputs);
        variables.putAll(values); nodeResults.put(nodeId, values);
        completedExecutions.add(executionKey); version++;
    }
    synchronized String control(String key) { return controls.get(key); }
    synchronized void control(String key, String value) { controls.put(key, value); version++; }
    synchronized void addInputs(Map<String, Object> values) { variables.putAll(copyMap(values)); version++; }
    synchronized void merge(ExecutionScope base, ExecutionScope branch) {
        for (Map.Entry<String, Object> entry : branch.variables.entrySet()) {
            if (!base.variables.containsKey(entry.getKey())
                    || !equal(base.variables.get(entry.getKey()), entry.getValue())) {
                variables.put(entry.getKey(), entry.getValue());
            }
        }
        nodeResults.putAll(branch.nodeResults); completedExecutions.addAll(branch.completedExecutions);
        controls.putAll(branch.controls); version++;
    }

    private static Map<String, Object> copyMap(Map<String, Object> values) {
        return values == null ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(values);
    }
    private static boolean equal(Object left, Object right) {
        return left == null ? right == null : left.equals(right);
    }
}
