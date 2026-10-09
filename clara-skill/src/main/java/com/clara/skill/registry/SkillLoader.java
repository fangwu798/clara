package com.clara.skill.registry;

import com.clara.dto.skill.PromptDefinition;
import com.clara.dto.skill.PromptSegment;
import com.clara.dto.skill.ToolMethod;
import com.clara.enums.skill.ScriptTypeEnum;
import com.clara.exception.ClaraException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能 / 提示词文件解析器：把 JSON 文件解析为 {@link ToolMethod} 与 {@link PromptDefinition}。
 *
 * <p>文件格式（JSON）：工具方法含 {@code id / name / type / description / parameters / script / metadata}，
 * {@code type} 取值对应 {@link ScriptTypeEnum}；提示词含 {@code id / description / segments}。
 */
public final class SkillLoader {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 解析工具方法文件。
     *
     * @param file 工具方法 JSON 文件，不可为 null
     * @return 工具方法定义，非 null
     * @throws ClaraException 文件非法或运行类型不受支持时
     */
    public ToolMethod parseTool(File file) {
        JsonNode root = read(file);
        try {
            return toTool(root);
        } catch (IllegalArgumentException e) {
            throw SkillRegistryErrors.invalidFile(file.getPath(), e.getMessage());
        }
    }

    /**
     * 解析提示词文件。
     *
     * @param file 提示词 JSON 文件，不可为 null
     * @return 提示词定义，非 null
     * @throws ClaraException 文件非法时
     */
    public PromptDefinition parsePrompt(File file) {
        JsonNode root = read(file);
        try {
            return toPrompt(root);
        } catch (IllegalArgumentException e) {
            throw SkillRegistryErrors.invalidFile(file.getPath(), e.getMessage());
        }
    }

    private JsonNode read(File file) {
        try {
            return mapper.readTree(file);
        } catch (IOException e) {
            throw SkillRegistryErrors.invalidFile(file.getPath(), e.getMessage());
        }
    }

    private ToolMethod toTool(JsonNode root) {
        String id = text(root, "id");
        String name = text(root, "name");
        ScriptTypeEnum type = type(root);
        String description = text(root, "description");
        String parameters = parameters(root);
        String script = text(root, "script");
        Map<String, String> metadata = metadata(root.get("metadata"));
        return new ToolMethod(id, name, type, description, parameters, script, metadata);
    }

    private PromptDefinition toPrompt(JsonNode root) {
        String id = text(root, "id");
        String description = text(root, "description");
        List<PromptSegment> segments = new ArrayList<>();
        JsonNode segmentsNode = root.get("segments");
        if (segmentsNode != null && segmentsNode.isArray()) {
            for (JsonNode segment : segmentsNode) {
                segments.add(new PromptSegment(text(segment, "role"), text(segment, "content")));
            }
        }
        return new PromptDefinition(id, description, segments);
    }

    private ScriptTypeEnum type(JsonNode root) {
        String value = text(root, "type");
        if (value == null) {
            return null;
        }
        for (ScriptTypeEnum candidate : ScriptTypeEnum.values()) {
            if (candidate.name().equalsIgnoreCase(value)) {
                return candidate;
            }
        }
        throw SkillRegistryErrors.unsupportedType(value);
    }

    private String parameters(JsonNode root) {
        JsonNode node = root.get("parameters");
        if (node == null || node.isNull()) {
            return null;
        }
        return node.isTextual() ? node.textValue() : node.toString();
    }

    private Map<String, String> metadata(JsonNode node) {
        if (node == null || node.isNull() || !node.isObject()) {
            return null;
        }
        Map<String, String> result = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            result.put(field.getKey(), value == null || value.isNull() ? "" : value.asText());
        }
        return result;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value != null && value.isTextual() ? value.textValue() : null;
    }
}
