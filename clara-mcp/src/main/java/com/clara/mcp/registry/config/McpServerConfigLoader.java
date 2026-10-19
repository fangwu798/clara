package com.clara.mcp.registry.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把 Claude Desktop / VS Code 风格的 {@code mcp.json} 文本或文件解析为 {@link McpServerConfig} 列表。
 *
 * <p>是什么、在哪一层：{@code clara-mcp} 注册层的配置加载器。读取顶层 {@code mcpServers} 映射，逐个服务端
 * 解析其 {@code command} / {@code args} / {@code env}（stdio）与 {@code url} / {@code headers}（http），
 * 并推断传输方式。
 *
 * <p>配置格式：顶层必须含对象 {@code mcpServers}；其键为服务端标识，值为对象。示例见技术设计文档第 5.10 节。
 *
 * <p>变量插值：{@code env} 与 {@code headers} 的值支持 {@code ${VAR}} 占位，从系统环境变量解析。
 * <b>策略：变量未定义时抛 {@link IllegalArgumentException} 并在消息中指明变量名</b>（不静默保留原样），
 * 以避免把未解析的占位符当作真实密钥发送出去。
 */
public final class McpServerConfigLoader {

    private static final String FIELD_PREFIX = "field '";
    private static final String SERVER_SEPARATOR = "' of server '";
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([A-Za-z_]\\w*)}");

    private final ObjectMapper mapper;
    private final UnaryOperator<String> variableResolver;

    /** 创建使用默认 {@link ObjectMapper} 与系统环境变量的加载器。 */
    public McpServerConfigLoader() {
        this(new ObjectMapper(), System::getenv);
    }

    /**
     * 创建加载器。
     *
     * @param mapper           解析 JSON 的 Jackson 映射器，不可为 null
     * @param variableResolver {@code ${VAR}} 的解析函数，不可为 null；返回 null 视为未定义
     */
    public McpServerConfigLoader(ObjectMapper mapper, UnaryOperator<String> variableResolver) {
        if (mapper == null) { throw new IllegalArgumentException("mapper must not be null"); }
        if (variableResolver == null) { throw new IllegalArgumentException("variableResolver must not be null"); }
        this.mapper = mapper;
        this.variableResolver = variableResolver;
    }

    /**
     * 解析单个 JSON 文本。
     *
     * @param jsonText JSON 文本，不可为空白
     * @return 服务端配置列表，非 null；顺序与 {@code mcpServers} 中出现的顺序一致
     */
    public List<McpServerConfig> load(String jsonText) {
        if (jsonText == null || jsonText.trim().isEmpty()) {
            throw new IllegalArgumentException("mcp config json must not be blank");
        }
        final JsonNode root;
        try {
            root = mapper.readTree(jsonText);
        } catch (IOException exception) {
            throw new IllegalArgumentException("invalid mcp config json: " + exception.getMessage(), exception);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("mcp config root must be a json object");
        }
        JsonNode servers = root.get("mcpServers");
        if (servers == null) { throw new IllegalArgumentException("mcp config must contain an 'mcpServers' object"); }
        if (!servers.isObject()) { throw new IllegalArgumentException("'mcpServers' must be a json object"); }
        List<McpServerConfig> result = new ArrayList<>();
        Iterator<Map.Entry<String, JsonNode>> fields = servers.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            result.add(parseServer(field.getKey(), field.getValue()));
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * 读取并解析一个 JSON 文件（UTF-8）。
     *
     * @param file 文件路径，不可为 null
     * @return 服务端配置列表，非 null
     */
    public List<McpServerConfig> loadFile(Path file) {
        if (file == null) { throw new IllegalArgumentException("file must not be null"); }
        final String text;
        try {
            text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalArgumentException("cannot read mcp config file: " + file, exception);
        }
        return load(text);
    }

    private McpServerConfig parseServer(String serverKey, JsonNode node) {
        if (serverKey == null || serverKey.trim().isEmpty()) {
            throw new IllegalArgumentException("mcp server key must not be blank");
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("mcp server '" + serverKey + "' must be a json object");
        }
        List<String> command = command(node.get("command"), node.get("args"), serverKey);
        Map<String, String> env = interpolatedMap(node.get("env"), "env", serverKey);
        String url = text(node.get("url"));
        Map<String, String> headers = interpolatedMap(node.get("headers"), "headers", serverKey);
        return McpServerConfig.of(serverKey, command, env, url, headers);
    }

    private static List<String> command(JsonNode commandNode, JsonNode argsNode, String serverKey) {
        List<String> parts = commandParts(commandNode, serverKey);
        appendArguments(parts, argsNode, serverKey);
        return parts;
    }

    private static List<String> commandParts(JsonNode commandNode, String serverKey) {
        List<String> parts = new ArrayList<>();
        if (commandNode == null || commandNode.isNull()) {
            return parts;
        }
        if (commandNode.isArray()) {
            for (JsonNode element : commandNode) {
                parts.add(requireText(element, "command", serverKey));
            }
        } else if (commandNode.isTextual()) {
            String value = commandNode.asText();
            if (!value.trim().isEmpty()) {
                parts.add(value);
            }
        } else {
            throw new IllegalArgumentException("field 'command' of server '" + serverKey
                    + "' must be a string or an array of strings");
        }
        return parts;
    }

    private static void appendArguments(List<String> parts, JsonNode argsNode, String serverKey) {
        if (argsNode == null || argsNode.isNull()) {
            return;
        }
        if (!argsNode.isArray()) {
            throw new IllegalArgumentException("field 'args' of server '" + serverKey + "' must be an array");
        }
        for (JsonNode element : argsNode) {
            parts.add(requireText(element, "args", serverKey));
        }
    }

    private Map<String, String> interpolatedMap(JsonNode node, String field, String serverKey) {
        Map<String, String> result = new LinkedHashMap<>();
        if (node == null || node.isNull()) { return result; }
        if (!node.isObject()) {
            throw new IllegalArgumentException(FIELD_PREFIX + field + SERVER_SEPARATOR + serverKey + "' must be a json object");
        }
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            putInterpolated(result, fields.next(), field, serverKey);
        }
        return result;
    }

    private void putInterpolated(Map<String, String> result, Map.Entry<String, JsonNode> entry,
            String field, String serverKey) {
        String key = entry.getKey();
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException(FIELD_PREFIX + field + SERVER_SEPARATOR + serverKey
                    + "' must not contain blank keys");
        }
        JsonNode value = entry.getValue();
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("value of '" + field + "." + key + SERVER_SEPARATOR
                    + serverKey + "' must be a string");
        }
        result.put(key, interpolate(value.asText(), field, key, serverKey));
    }

    private String interpolate(String raw, String field, String key, String serverKey) {
        Matcher matcher = VARIABLE.matcher(raw);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = variableResolver.apply(name);
            if (value == null) {
                throw new IllegalArgumentException("environment variable '" + name + "' used by '" + field + "."
                        + key + SERVER_SEPARATOR + serverKey + "' is not defined");
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }

    private static String requireText(JsonNode node, String field, String serverKey) {
        if (node == null || !node.isTextual()) {
            throw new IllegalArgumentException(FIELD_PREFIX + field + SERVER_SEPARATOR + serverKey
                    + "' must contain strings only");
        }
        return node.asText();
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull()) { return null; }
        if (!node.isTextual()) { throw new IllegalArgumentException("field 'url' must be a string"); }
        return node.asText();
    }
}
