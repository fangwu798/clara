package com.clara.skill.registry;

import com.clara.core.skill.IToolCatalog;
import com.clara.core.skill.IToolDescriptionDecorator;
import com.clara.dto.skill.ToolMethod;
import com.clara.dto.tool.ToolContext;
import com.clara.dto.tool.ToolSpecification;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 基于技能注册表的可用工具目录：把注册表中全部工具方法转换为模型请求的工具声明。
 *
 * <p>是什么、在哪一层：{@code clara-skill} 对 core {@link IToolCatalog} 的实现（包
 * {@code com.clara.skill.registry}），让编排层把「已注册的全部工具」下发，而不依赖语义匹配的召回。
 *
 * <p>转换规则：函数名取工具方法名并规整为 API 允许的字符集（非法字符替换为 {@code _}、超长截断）；
 * 功能描述取工具方法描述（装配了 {@link IToolDescriptionDecorator} 时经其装饰，用于补充运行期信息，
 * 如当前可用的工作流清单）；入参 Schema 解析工具方法的 {@code parameters} JSON 字符串，缺失或解析失败时
 * 退化为空对象 Schema（仅 {@code type/properties}），不影响下发。
 *
 * <p>生命周期与线程安全：只读引用注册表，注册表可热加载，故每次调用都重新读取，实例可跨线程共享。
 */
public final class RegistryToolCatalog implements IToolCatalog {

    private static final int MAX_TOOL_NAME_LENGTH = 128;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SkillRegistry registry;
    private final IToolDescriptionDecorator decorator;

    /**
     * 创建工具目录。
     *
     * @param registry 技能注册表，不可为 null
     * @throws IllegalArgumentException registry 为 null 时
     */
    public RegistryToolCatalog(SkillRegistry registry) {
        this(registry, null);
    }

    /**
     * 创建工具目录并装配描述装饰器。
     *
     * @param registry  技能注册表，不可为 null
     * @param decorator 工具描述装饰器，可为 null（null 时直接用声明描述）
     * @throws IllegalArgumentException registry 为 null 时
     */
    public RegistryToolCatalog(SkillRegistry registry, IToolDescriptionDecorator decorator) {
        if (registry == null) {
            throw new IllegalArgumentException("registry must not be null");
        }
        this.registry = registry;
        this.decorator = decorator;
    }

    @Override
    public List<ToolContext> availableTools() {
        List<ToolContext> tools = new ArrayList<>();
        for (ToolMethod method : registry.allTools()) {
            tools.add(new ToolContext(new ToolSpecification(
                    toolName(method.getName()), description(method), toolParameters(method.getParameters()))));
        }
        return tools;
    }

    /**
     * 取工具描述：装配了装饰器时先装饰，装饰结果为空则回退为声明描述。
     */
    private String description(ToolMethod method) {
        if (decorator == null) {
            return method.getDescription();
        }
        String decorated = decorator.decorate(method.getName(), method.getDescription());
        return decorated == null || decorated.trim().isEmpty() ? method.getDescription() : decorated;
    }

    /**
     * 把入参 Schema JSON 字符串解析为映射；缺失或解析失败时退化为空对象 Schema。
     */
    private static Map<String, Object> toolParameters(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return emptySchema();
        }
        try {
            Map<String, Object> parsed = MAPPER.readValue(raw, new TypeReference<Map<String, Object>>() { });
            return parsed == null || parsed.isEmpty() ? emptySchema() : parsed;
        } catch (IOException exception) {
            return emptySchema();
        }
    }

    private static Map<String, Object> emptySchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", new LinkedHashMap<>());
        return schema;
    }

    /**
     * 规整工具名：仅保留 API 允许的 {@code a-z A-Z 0-9 _ -}，其余替换为 {@code _}，并按上限截断。
     */
    private static String toolName(String sourceId) {
        StringBuilder name = new StringBuilder();
        for (int index = 0; index < sourceId.length() && name.length() < MAX_TOOL_NAME_LENGTH; index++) {
            char value = sourceId.charAt(index);
            boolean allowed = value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z'
                    || value >= '0' && value <= '9' || value == '_' || value == '-';
            name.append(allowed ? value : '_');
        }
        return name.length() == 0 ? "skill_tool" : name.toString();
    }
}
