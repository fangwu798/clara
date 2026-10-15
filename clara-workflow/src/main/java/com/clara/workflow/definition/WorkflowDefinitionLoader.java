package com.clara.workflow.definition;

import com.clara.core.workflow.IWorkflowAction;
import com.clara.core.workflow.IWorkflowActionFactory;
import com.clara.core.workflow.IWorkflowNode;
import com.clara.core.workflow.IScopeCondition;
import com.clara.dto.workflow.WorkflowActionSpec;
import com.clara.dto.workflow.WorkflowDefinition;
import com.clara.enums.workflow.WorkflowNodeTypeEnum;
import com.clara.workflow.node.WorkflowNodes;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工作流定义加载器：把 YAML 工作流文件解析为 {@link WorkflowDefinition}。
 *
 * <p>是什么、在哪一层：clara-workflow 的配置化入口（包 {@code com.clara.workflow.definition}），
 * 用 Jackson {@link YAMLFactory} 读取 YAML，递归构建节点树。AGENT / TOOL / REVIEW 节点的动作由注入的
 * {@link IWorkflowActionFactory} 按类型生成，加载器不写死具体动作。
 *
 * <p>YAML 约定：顶层含 {@code workflowId}（必填）、{@code version}（缺省 {@code 1.0.0}）、
 * {@code inputSchema} / {@code outputSchema}（可选）、{@code outputs}（可选，对外输出变量键列表）与
 * {@code root}（必填对象）；节点含 {@code id}（必填）、{@code type}（必填）、{@code outputKeys}、
 * {@code idempotencyKey}（缺省为 id）、{@code children}，其余字段（如 {@code prompt} / {@code tool} /
 * {@code arguments}）作为动作属性透传。动作节点必须且只能声明一个输出键，返回 {@code {"output": 值}} 的
 * 动作会被 {@link OutputKeyedAction} 重键为该输出键。
 *
 * <p>失败语义：配置非法（缺字段、类型未知、AGENT / TOOL 缺动作工厂、outputKeys 为空等）一律抛
 * {@link IllegalArgumentException}，消息包含文件路径与节点 id 便于定位；CONDITION / LOOP 暂不支持，
 * 同样显式失败。
 */
public final class WorkflowDefinitionLoader {

    private static final String DEFAULT_VERSION = "1.0.0";

    /**
     * 节点中不参与动作属性透传的结构化字段。
     */
    private static final Set<String> STRUCTURAL_FIELDS = Collections.unmodifiableSet(new HashSet<String>(
            Arrays.asList("id", "type", "outputKeys", "idempotencyKey", "children", "action")));

    private final List<IWorkflowActionFactory> actionFactories;
    private final ObjectMapper mapper = new ObjectMapper(new YAMLFactory());

    /**
     * 创建加载器。
     *
     * @param actionFactories 动作工厂列表（按顺序取首个命中者），不可为 null
     * @throws IllegalArgumentException actionFactories 为 null 时
     */
    public WorkflowDefinitionLoader(List<IWorkflowActionFactory> actionFactories) {
        if (actionFactories == null) {
            throw new IllegalArgumentException("actionFactories must not be null");
        }
        this.actionFactories = Collections.unmodifiableList(
                new ArrayList<IWorkflowActionFactory>(actionFactories));
    }

    /**
     * 加载一个 YAML 工作流定义文件。
     *
     * @param file 工作流定义文件，不可为 null
     * @return 已加载的工作流（定义 + 对外输出键），非 null
     * @throws IllegalArgumentException 文件不可读或配置非法时
     */
    public LoadedWorkflow load(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        JsonNode root = read(file);
        if (root == null || !root.isObject()) {
            throw invalid(file, null, "工作流定义根必须是对象");
        }
        String workflowId = text(root, "workflowId");
        if (blank(workflowId)) {
            throw invalid(file, null, "缺少 workflowId");
        }
        String version = text(root, "version");
        if (blank(version)) {
            version = DEFAULT_VERSION;
        }
        String inputSchema = schema(root.get("inputSchema"));
        String outputSchema = schema(root.get("outputSchema"));
        JsonNode rootNode = root.get("root");
        if (rootNode == null || !rootNode.isObject()) {
            throw invalid(file, null, "缺少 root 节点");
        }
        IWorkflowNode node = buildNode(rootNode, file);
        WorkflowDefinition definition;
        try {
            definition = new WorkflowDefinition(workflowId, version, inputSchema, outputSchema, node);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("工作流定义非法（" + file + "）：" + e.getMessage(), e);
        }
        return new LoadedWorkflow(definition, declaredOutputs(root));
    }

    /**
     * 读取顶层 {@code outputs} 声明（对外输出变量键）。
     */
    private static List<String> declaredOutputs(JsonNode root) {
        JsonNode node = root.get("outputs");
        List<String> outputs = new ArrayList<String>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual() && !item.textValue().trim().isEmpty()) {
                    outputs.add(item.textValue().trim());
                }
            }
        }
        return outputs;
    }

    private IWorkflowNode buildNode(JsonNode node, Path file) {
        String id = text(node, "id");
        if (blank(id)) {
            throw invalid(file, null, "存在缺少 id 的节点");
        }
        WorkflowNodeTypeEnum type = resolveType(text(node, "type"));
        if (type == null) {
            throw invalid(file, id, "缺少或未知的节点类型");
        }
        switch (type) {
            case SEQUENCE:
                return WorkflowNodes.sequence(id, buildChildren(node, file, id));
            case PARALLEL:
                return WorkflowNodes.parallel(id, buildChildren(node, file, id));
            case AGENT:
            case TOOL:
            case REVIEW:
                return buildActionNode(node, file, id, type);
            case HUMAN:
                String inputKey = text(node, "inputKey");
                if (blank(inputKey)) {
                    throw invalid(file, id, "HUMAN 节点缺少 inputKey");
                }
                return WorkflowNodes.human(id, inputKey);
            case CONDITION:
                return buildConditionNode(node, file, id);
            case LOOP:
                throw invalid(file, id, "当前配置化工作流暂不支持该节点类型：" + type.name());
            default:
                throw invalid(file, id, "未知节点类型：" + type.name());
        }
    }

    private IWorkflowNode buildConditionNode(JsonNode node, Path file, String id) {
        JsonNode condition = node.get("condition");
        if (condition == null || !condition.isObject()) {
            throw invalid(file, id, "CONDITION 节点缺少 condition 对象");
        }
        String key = text(condition, "key");
        if (blank(key)) {
            throw invalid(file, id, "CONDITION 节点缺少 condition.key");
        }
        String operator = text(condition, "operator");
        String expected = condition.has("value") && !condition.get("value").isNull()
                ? condition.get("value").asText() : null;
        JsonNode whenTrue = node.get("whenTrue");
        if (whenTrue == null || !whenTrue.isObject()) {
            throw invalid(file, id, "CONDITION 节点缺少 whenTrue 分支");
        }
        IWorkflowNode trueNode = buildNode(whenTrue, file);
        JsonNode whenFalse = node.get("whenFalse");
        IWorkflowNode falseNode = whenFalse == null || whenFalse.isNull()
                ? null : buildNode(whenFalse, file);
        IScopeCondition predicate = new ConfigScopeCondition(key, operator, expected);
        return WorkflowNodes.condition(id, predicate, trueNode, falseNode);
    }

    private IWorkflowNode buildActionNode(JsonNode node, Path file, String id, WorkflowNodeTypeEnum type) {
        Set<String> outputKeys = outputKeys(node, file, id);
        if (outputKeys.size() != 1) {
            throw invalid(file, id, "动作节点 outputKeys 必须且只能声明一个输出键");
        }
        String outputKey = outputKeys.iterator().next();
        String idempotencyKey = text(node, "idempotencyKey");
        if (blank(idempotencyKey)) {
            idempotencyKey = id;
        }
        WorkflowActionSpec spec = new WorkflowActionSpec(id, type.name(), attributes(node));
        IWorkflowAction action = null;
        for (IWorkflowActionFactory factory : actionFactories) {
            if (factory.supports(type.name())) {
                action = factory.create(spec);
                break;
            }
        }
        if (action == null) {
            throw invalid(file, id, "缺少可处理的动作工厂：" + type.name());
        }
        // 动作按通用约定返回 {"output": 值}，此处重键为节点声明的输出键，满足引擎「返回值键 ⊆ 输出键」校验
        IWorkflowAction keyed = new OutputKeyedAction(action, outputKey);
        try {
            return WorkflowNodes.action(id, type, outputKeys, idempotencyKey, keyed);
        } catch (IllegalArgumentException e) {
            throw invalid(file, id, e.getMessage());
        }
    }

    private List<IWorkflowNode> buildChildren(JsonNode node, Path file, String id) {
        JsonNode childrenNode = node.get("children");
        if (childrenNode == null || !childrenNode.isArray() || childrenNode.size() == 0) {
            throw invalid(file, id, "组合节点缺少 children");
        }
        List<IWorkflowNode> children = new ArrayList<IWorkflowNode>();
        for (JsonNode child : childrenNode) {
            if (!child.isObject()) {
                throw invalid(file, id, "children 元素必须是对象");
            }
            children.add(buildNode(child, file));
        }
        return children;
    }

    private Set<String> outputKeys(JsonNode node, Path file, String id) {
        Set<String> keys = new LinkedHashSet<String>();
        JsonNode keysNode = node.get("outputKeys");
        if (keysNode != null && keysNode.isArray()) {
            for (JsonNode key : keysNode) {
                if (key.isTextual() && !key.textValue().trim().isEmpty()) {
                    keys.add(key.textValue().trim());
                }
            }
        }
        if (keys.isEmpty()) {
            throw invalid(file, id, "动作节点 outputKeys 不能为空");
        }
        return keys;
    }

    private Map<String, Object> attributes(JsonNode node) {
        Map<String, Object> attributes = new LinkedHashMap<String, Object>();
        collectAttributes(node, attributes);
        JsonNode actionNode = node.get("action");
        if (actionNode != null && actionNode.isObject()) {
            collectAttributes(actionNode, attributes);
        }
        return attributes;
    }

    private void collectAttributes(JsonNode source, Map<String, Object> target) {
        Iterator<Map.Entry<String, JsonNode>> fields = source.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (STRUCTURAL_FIELDS.contains(field.getKey())) {
                continue;
            }
            target.put(field.getKey(), mapper.convertValue(field.getValue(), Object.class));
        }
    }

    private WorkflowNodeTypeEnum resolveType(String value) {
        if (blank(value)) {
            return null;
        }
        for (WorkflowNodeTypeEnum candidate : WorkflowNodeTypeEnum.values()) {
            if (candidate.name().equalsIgnoreCase(value.trim())) {
                return candidate;
            }
        }
        return null;
    }

    private JsonNode read(Path file) {
        try {
            return mapper.readTree(file.toFile());
        } catch (IOException e) {
            throw new IllegalArgumentException("工作流定义读取失败（" + file + "）：" + e.getMessage(), e);
        }
    }

    private static String schema(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.textValue() : node.toString();
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static IllegalArgumentException invalid(Path file, String nodeId, String message) {
        String suffix = nodeId == null ? "" : "，节点 " + nodeId;
        return new IllegalArgumentException("工作流定义非法（" + file + "）" + suffix + "：" + message);
    }
}
