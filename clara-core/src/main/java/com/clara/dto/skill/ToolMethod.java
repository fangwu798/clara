package com.clara.dto.skill;

import com.clara.enums.skill.ScriptTypeEnum;
import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具方法：技能声明的工具方法的完整定义（标识、名称、运行类型、描述、参数 Schema、脚本引用与元数据）。
 *
 * <p>{@code script} 为脚本引用，可以是文件路径或内联脚本文本；{@code parameters} 为参数 JSON Schema 字符串，
 * 可为 null。元数据在构造时做防御性拷贝，禁止空键或 null 值。
 */
@Data
public final class ToolMethod {

    private final String id;
    private final String name;
    private final ScriptTypeEnum type;
    private final String description;
    private final String parameters;
    private final String script;
    private final Map<String, String> metadata;

    /**
     * 创建工具方法定义。
     *
     * @param id          工具方法标识，不可为空白
     * @param name        工具方法名称，不可为空白
     * @param type        脚本运行类型，不可为 null
     * @param description 工具方法描述，不可为空白
     * @param parameters  参数 JSON Schema 字符串，可为 null
     * @param script      脚本引用（路径或内联），不可为空白
     * @param metadata    元数据，可为 null
     * @throws IllegalArgumentException 任一字段不合法时
     */
    public ToolMethod(String id, String name, ScriptTypeEnum type, String description,
            String parameters, String script, Map<String, String> metadata) {
        if (blank(id) || blank(name) || type == null || blank(description) || blank(script)) {
            throw new IllegalArgumentException("tool method is invalid");
        }
        this.id = id;
        this.name = name;
        this.type = type;
        this.description = description;
        this.parameters = parameters;
        this.script = script;
        LinkedHashMap<String, String> copied = new LinkedHashMap<String, String>();
        if (metadata != null) {
            for (Map.Entry<String, String> value : metadata.entrySet()) {
                if (blank(value.getKey()) || value.getValue() == null) {
                    throw new IllegalArgumentException("metadata is invalid");
                }
                copied.put(value.getKey(), value.getValue());
            }
        }
        this.metadata = Collections.unmodifiableMap(copied);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
