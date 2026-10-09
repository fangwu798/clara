package com.clara.mcp.registry.config;

import com.clara.mcp.registry.McpTransportProfile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表示从外部配置文件（Claude Desktop / VS Code 风格 {@code mcp.json}）加载出的单个 MCP 服务端配置。
 *
 * <p>是什么、在哪一层：{@code clara-mcp} 注册层的配置值对象，是「配置加载」与「注册」之间的载体；
 * {@link McpServerConfigLoader} 产出它，{@link McpServerRegistrar} 把它转换为 {@link McpServerRegistration}。
 *
 * <p>字段与传输方式的对应：stdio 形态使用 {@code command}（首个元素为可执行文件，其后为参数）与 {@code env}；
 * http 形态使用 {@code url} 与 {@code headers}。两者互斥：{@link #of} 会从原始字段推断
 * {@link McpTransportProfile}（有 {@code command} → {@link McpTransportProfile#STDIO}；有 {@code url} →
 * {@link McpTransportProfile#STREAMABLE_HTTP}）。
 *
 * <p>不可变值对象 + 构造期校验：集合均做只读拷贝，禁止 null / 空白元素；同一配置不得同时定义 command 与 url。
 */
public final class McpServerConfig {
    private final String serverKey;
    private final McpTransportProfile transportProfile;
    private final List<String> command;
    private final Map<String, String> env;
    private final String url;
    private final Map<String, String> headers;

    /**
     * 从原始字段构建配置并推断传输方式。
     *
     * <p>推断规则：有 {@code command} 且无 {@code url} → {@link McpTransportProfile#STDIO}；
     * 有 {@code url} 且无 {@code command} → {@link McpTransportProfile#STREAMABLE_HTTP}；
     * 两者皆有或皆无时抛 {@link IllegalArgumentException}。
     *
     * @param serverKey 服务端标识，不可为空白
     * @param command   stdio 命令行，可为 null 或空
     * @param env       stdio 环境变量，可为 null
     * @param url       http 端点，可为 null 或空白
     * @param headers   http 请求头，可为 null
     * @return 配置值对象，非 null
     */
    public static McpServerConfig of(String serverKey, List<String> command,
            Map<String, String> env, String url, Map<String, String> headers) {
        return new McpServerConfig(serverKey, inferTransportProfile(command, url), command, env, url, headers);
    }

    /**
     * 推断传输方式。
     *
     * @param command stdio 命令行，可为 null 或空
     * @param url     http 端点，可为 null 或空白
     * @return 推断出的传输方式，非 null
     */
    public static McpTransportProfile inferTransportProfile(List<String> command, String url) {
        boolean hasCommand = command != null && !command.isEmpty();
        boolean hasUrl = !isBlank(url);
        if (hasCommand && hasUrl) {
            throw new IllegalArgumentException("mcp server config must not define both command and url");
        }
        if (!hasCommand && !hasUrl) {
            throw new IllegalArgumentException("mcp server config must define either command (stdio) or url (http)");
        }
        return hasCommand ? McpTransportProfile.STDIO : McpTransportProfile.STREAMABLE_HTTP;
    }

    /**
     * 创建配置值对象。
     *
     * @param serverKey        服务端标识，不可为空白
     * @param transportProfile 传输方式，不可为 null，且与所提供字段一致
     * @param command          stdio 命令行，可为 null 或空
     * @param env              stdio 环境变量，可为 null
     * @param url              http 端点，可为 null 或空白
     * @param headers          http 请求头，可为 null
     */
    public McpServerConfig(String serverKey, McpTransportProfile transportProfile,
            List<String> command, Map<String, String> env, String url, Map<String, String> headers) {
        if (isBlank(serverKey)) { throw new IllegalArgumentException("serverKey must not be blank"); }
        if (transportProfile == null) { throw new IllegalArgumentException("transportProfile must not be null"); }
        List<String> commandLine = immutableCommand(command);
        Map<String, String> environment = immutableMap(env, "env");
        Map<String, String> headerMap = immutableMap(headers, "headers");
        String endpoint = isBlank(url) ? null : url.trim();
        if (transportProfile == McpTransportProfile.STDIO) {
            if (commandLine.isEmpty()) { throw new IllegalArgumentException("stdio config requires a command"); }
            if (endpoint != null) { throw new IllegalArgumentException("stdio config must not define url"); }
            if (!headerMap.isEmpty()) { throw new IllegalArgumentException("stdio config must not define headers"); }
        } else if (transportProfile == McpTransportProfile.STREAMABLE_HTTP) {
            if (endpoint == null) { throw new IllegalArgumentException("http config requires a url"); }
            if (!commandLine.isEmpty()) { throw new IllegalArgumentException("http config must not define command"); }
            if (!environment.isEmpty()) { throw new IllegalArgumentException("http config must not define env"); }
        } else {
            throw new IllegalArgumentException("unsupported transportProfile: " + transportProfile);
        }
        this.serverKey = serverKey;
        this.transportProfile = transportProfile;
        this.command = commandLine;
        this.env = environment;
        this.url = endpoint;
        this.headers = headerMap;
    }

    public String getServerKey() { return serverKey; }
    public McpTransportProfile getTransportProfile() { return transportProfile; }
    public List<String> getCommand() { return command; }
    public Map<String, String> getEnv() { return env; }
    public String getUrl() { return url; }
    public Map<String, String> getHeaders() { return headers; }

    private static List<String> immutableCommand(List<String> source) {
        if (source == null) { return Collections.emptyList(); }
        List<String> result = new ArrayList<String>(source.size());
        for (String token : source) {
            if (isBlank(token)) { throw new IllegalArgumentException("command must not contain blank tokens"); }
            result.add(token);
        }
        return Collections.unmodifiableList(result);
    }

    private static Map<String, String> immutableMap(Map<String, String> source, String field) {
        if (source == null) { return Collections.emptyMap(); }
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (isBlank(entry.getKey()) || entry.getValue() == null) {
                throw new IllegalArgumentException(field + " must not contain blank keys or null values");
            }
            result.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(result);
    }

    private static boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }
}
