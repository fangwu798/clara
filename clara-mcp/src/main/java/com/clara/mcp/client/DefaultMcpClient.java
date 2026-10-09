package com.clara.mcp.client;

import com.clara.exception.ClaraException;
import com.clara.exception.ErrorCategory;
import com.clara.exception.ErrorCodes;
import com.clara.mcp.registry.capability.McpPromptArgument;
import com.clara.mcp.registry.capability.McpPromptDescriptor;
import com.clara.mcp.registry.capability.McpResourceDescriptor;
import com.clara.mcp.registry.capability.McpResourceTemplateDescriptor;
import com.clara.mcp.registry.capability.McpToolDescriptor;
import com.clara.mcp.client.call.McpPromptRequest;
import com.clara.mcp.client.call.McpPromptResult;
import com.clara.mcp.client.call.McpResourceContent;
import com.clara.mcp.client.call.McpResourceRequest;
import com.clara.mcp.client.call.McpToolCall;
import com.clara.mcp.client.call.McpToolResult;
import com.clara.mcp.transport.McpTransport;
import com.clara.mcp.transport.McpTransportListener;
import com.clara.mcp.transport.jsonrpc.JsonRpcNotification;
import com.clara.mcp.transport.jsonrpc.JsonRpcRequest;
import com.clara.mcp.transport.jsonrpc.JsonRpcResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 基于 {@link McpTransport} 发送 JSON-RPC 请求并解析领域结果的 MCP 客户端实现。
 *
 * <p>是什么、在哪一层：{@code clara-mcp} 客户端层对 {@link McpClient} 的协议实现，由
 * {@code McpClientFactory} 按服务端注册项的传输方式（stdio / streamable http）创建，把 MCP 的 JSON-RPC
 * 交互（initialize / tools 列表与调用 / resources 列表与读取 / resourceTemplates 列表 / prompts 列表与
 * 获取）集中在客户端，屏蔽传输细节。
 *
 * <p>协议映射：请求方法名为 {@code initialize}、{@code tools/list}、{@code tools/call}、
 * {@code resources/list}、{@code resources/read}、{@code resources/templates/list}、{@code prompts/list}、
 * {@code prompts/get}；初始化完成后再发送 {@code notifications/initialized} 通知。结果字段按 MCP 常规命名
 * 解析（{@code protocolVersion} / {@code serverInfo} / {@code capabilities}、{@code tools}、
 * {@code resources}、{@code resourceTemplates}、{@code prompts}、{@code content} / {@code contents}、
 * {@code isError}、{@code messages}、{@code nextCursor}）。传输返回的 {@code result} 已是解码后的 map，
 * 客户端用 Jackson 做结构转换，不直接依赖具体字段的运行时类型。
 *
 * <p>生命周期与线程安全：一个实例绑定一条传输连接；首次请求时才启动传输（注册监听），使用完毕由调用方
 * {@link #close()} 释放传输资源。请求超时抛 {@code MCP_REQUEST_TIMEOUT}，服务端返回 JSON-RPC 错误抛
 * {@code MCP_DISCOVERY_FAILED}，传输层异常原样上抛。
 */
public final class DefaultMcpClient implements McpClient {
    private static final String CLIENT_NAME = "clara-mcp";
    private static final String CLIENT_VERSION = "0.1.0";
    private static final String INITIALIZE = "initialize";
    private static final String INITIALIZED_NOTIFICATION = "notifications/initialized";
    private static final String LIST_TOOLS = "tools/list";
    private static final String CALL_TOOL = "tools/call";
    private static final String LIST_RESOURCES = "resources/list";
    private static final String READ_RESOURCE = "resources/read";
    private static final String LIST_RESOURCE_TEMPLATES = "resources/templates/list";
    private static final String LIST_PROMPTS = "prompts/list";
    private static final String GET_PROMPT = "prompts/get";
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() { };
    private static final TypeReference<List<Map<String, Object>>> MAP_LIST_TYPE =
            new TypeReference<List<Map<String, Object>>>() { };

    private final McpTransport transport;
    private final ObjectMapper mapper;
    private final long requestTimeoutMillis;
    private final AtomicLong requestSequence = new AtomicLong();
    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object startLock = new Object();
    private final McpTransportListener listener = new McpTransportListener() {
        public void onNotification(JsonRpcNotification notification) { }
        public void onServerRequest(JsonRpcRequest request) { }
        public void onFailure(Throwable failure) { }
    };

    /**
     * 创建绑定到给定传输的客户端。
     *
     * @param transport            承载 JSON-RPC 收发的传输，不可为 null
     * @param requestTimeoutMillis 单次请求等待响应的超时毫秒数，必须为正
     */
    public DefaultMcpClient(McpTransport transport, long requestTimeoutMillis) {
        if (transport == null) { throw new IllegalArgumentException("transport must not be null"); }
        if (requestTimeoutMillis <= 0L) {
            throw new IllegalArgumentException("requestTimeoutMillis must be positive");
        }
        this.transport = transport;
        this.requestTimeoutMillis = requestTimeoutMillis;
        this.mapper = new ObjectMapper();
    }

    public McpInitialization initialize(List<String> supportedProtocolVersions) {
        if (supportedProtocolVersions == null || supportedProtocolVersions.isEmpty()
                || supportedProtocolVersions.contains(null)) {
            throw new IllegalArgumentException("supportedProtocolVersions must not be empty");
        }
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("protocolVersion", supportedProtocolVersions.get(0));
        Map<String, Object> clientInfo = new LinkedHashMap<String, Object>();
        clientInfo.put("name", CLIENT_NAME);
        clientInfo.put("version", CLIENT_VERSION);
        params.put("clientInfo", clientInfo);
        params.put("capabilities", Collections.<String, Object>emptyMap());

        Map<String, Object> result = send(INITIALIZE, params);
        String protocolVersion = requiredString(result, "protocolVersion");
        Map<String, Object> serverInfo = requiredMap(result, "serverInfo");
        McpServerIdentity identity = new McpServerIdentity(requiredString(serverInfo, "name"),
                string(serverInfo, "version"));
        McpServerCapabilities capabilities = capabilities(map(result, "capabilities"));
        transport.sendNotification(new JsonRpcNotification(INITIALIZED_NOTIFICATION,
                Collections.<String, Object>emptyMap()));
        return new McpInitialization(protocolVersion, identity, capabilities);
    }

    public McpPage<McpToolDescriptor> listTools(String cursor) {
        Map<String, Object> result = send(LIST_TOOLS, cursorParams(cursor));
        List<McpToolDescriptor> items = new ArrayList<McpToolDescriptor>();
        for (Map<String, Object> item : mapList(result.get("tools"))) {
            items.add(new McpToolDescriptor(requiredString(item, "name"), string(item, "description"),
                    map(item, "inputSchema")));
        }
        return new McpPage<McpToolDescriptor>(items, string(result, "nextCursor"));
    }

    public McpPage<McpResourceDescriptor> listResources(String cursor) {
        Map<String, Object> result = send(LIST_RESOURCES, cursorParams(cursor));
        List<McpResourceDescriptor> items = new ArrayList<McpResourceDescriptor>();
        for (Map<String, Object> item : mapList(result.get("resources"))) {
            items.add(new McpResourceDescriptor(requiredString(item, "uri"), string(item, "name"),
                    string(item, "mimeType")));
        }
        return new McpPage<McpResourceDescriptor>(items, string(result, "nextCursor"));
    }

    public McpPage<McpResourceTemplateDescriptor> listResourceTemplates(String cursor) {
        Map<String, Object> result = send(LIST_RESOURCE_TEMPLATES, cursorParams(cursor));
        List<McpResourceTemplateDescriptor> items = new ArrayList<McpResourceTemplateDescriptor>();
        for (Map<String, Object> item : mapList(result.get("resourceTemplates"))) {
            items.add(new McpResourceTemplateDescriptor(requiredString(item, "uriTemplate"),
                    string(item, "name"), string(item, "mimeType")));
        }
        return new McpPage<McpResourceTemplateDescriptor>(items, string(result, "nextCursor"));
    }

    public McpPage<McpPromptDescriptor> listPrompts(String cursor) {
        Map<String, Object> result = send(LIST_PROMPTS, cursorParams(cursor));
        List<McpPromptDescriptor> items = new ArrayList<McpPromptDescriptor>();
        for (Map<String, Object> item : mapList(result.get("prompts"))) {
            List<McpPromptArgument> arguments = new ArrayList<McpPromptArgument>();
            for (Map<String, Object> argument : mapList(item.get("arguments"))) {
                arguments.add(new McpPromptArgument(requiredString(argument, "name"),
                        string(argument, "description"), booleanValue(argument.get("required"))));
            }
            items.add(new McpPromptDescriptor(requiredString(item, "name"),
                    string(item, "description"), arguments));
        }
        return new McpPage<McpPromptDescriptor>(items, string(result, "nextCursor"));
    }

    public McpToolResult callTool(McpToolCall call) {
        if (call == null) { throw new IllegalArgumentException("call must not be null"); }
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("name", call.getToolName());
        params.put("arguments", call.getArguments());
        Map<String, Object> result = send(CALL_TOOL, params);
        return new McpToolResult(mapList(result.get("content")), booleanValue(result.get("isError")));
    }

    public McpResourceContent readResource(McpResourceRequest request) {
        if (request == null) { throw new IllegalArgumentException("request must not be null"); }
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("uri", request.getUri());
        Map<String, Object> result = send(READ_RESOURCE, params);
        Object contents = result.containsKey("contents") ? result.get("contents") : result.get("content");
        return new McpResourceContent(mapList(contents));
    }

    public McpPromptResult getPrompt(McpPromptRequest request) {
        if (request == null) { throw new IllegalArgumentException("request must not be null"); }
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("name", request.getPromptName());
        params.put("arguments", request.getArguments());
        Map<String, Object> result = send(GET_PROMPT, params);
        return new McpPromptResult(string(result, "description"), mapList(result.get("messages")), false);
    }

    public void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            transport.close();
        }
    }

    private Map<String, Object> send(String method, Map<String, Object> params) {
        ensureOpen();
        ensureStarted();
        JsonRpcRequest request = new JsonRpcRequest(nextId(), method, params);
        CompletableFuture<JsonRpcResponse> future = transport.sendRequest(request, requestTimeoutMillis);
        JsonRpcResponse response;
        try {
            response = future.get(requestTimeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            transport.cancel(request.getId());
            throw McpClientErrors.error(ErrorCodes.MCP_REQUEST_TIMEOUT, ErrorCategory.TIMEOUT);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw McpClientErrors.error(ErrorCodes.INVOCATION_CANCELLED, ErrorCategory.CANCELLED);
        } catch (ExecutionException exception) {
            throw unwrap(exception.getCause());
        }
        if (response == null) {
            throw McpClientErrors.error(ErrorCodes.MCP_INVALID_MESSAGE, ErrorCategory.EXTERNAL_SERVICE);
        }
        if (response.getError() != null) {
            throw McpClientErrors.error(ErrorCodes.MCP_DISCOVERY_FAILED, ErrorCategory.EXTERNAL_SERVICE);
        }
        Map<String, Object> result = response.getResult();
        return result == null ? Collections.<String, Object>emptyMap() : result;
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw McpClientErrors.error(ErrorCodes.MCP_TRANSPORT_CLOSED, ErrorCategory.CANCELLED);
        }
    }

    private void ensureStarted() {
        if (started.get()) { return; }
        synchronized (startLock) {
            if (!started.get()) {
                transport.start(listener);
                started.set(true);
            }
        }
    }

    private String nextId() { return "clara-mcp-" + requestSequence.incrementAndGet(); }

    private static RuntimeException unwrap(Throwable cause) {
        if (cause instanceof ClaraException) { return (ClaraException) cause; }
        if (cause instanceof RuntimeException) { return (RuntimeException) cause; }
        return McpClientErrors.error(ErrorCodes.MCP_TRANSPORT_IO_ERROR, ErrorCategory.EXTERNAL_SERVICE);
    }

    private static Map<String, Object> cursorParams(String cursor) {
        if (cursor == null || cursor.trim().isEmpty()) { return Collections.emptyMap(); }
        Map<String, Object> params = new LinkedHashMap<String, Object>();
        params.put("cursor", cursor);
        return params;
    }

    private static McpServerCapabilities capabilities(Map<String, Object> value) {
        Map<String, Object> caps = value == null ? Collections.<String, Object>emptyMap() : value;
        return new McpServerCapabilities(caps.containsKey("tools"), caps.containsKey("resources"),
                caps.containsKey("prompts"), listChanged(caps, "tools"), listChanged(caps, "resources"),
                listChanged(caps, "prompts"));
    }

    private static boolean listChanged(Map<String, Object> caps, String key) {
        Object value = caps.get(key);
        if (!(value instanceof Map)) { return false; }
        return booleanValue(((Map<?, ?>) value).get("listChanged"));
    }

    private static String string(Map<String, Object> source, String key) {
        if (source == null) { return null; }
        Object value = source.get(key);
        return value instanceof String ? (String) value : null;
    }

    private static String requiredString(Map<String, Object> source, String key) {
        String value = string(source, key);
        if (value == null || value.trim().isEmpty()) {
            throw McpClientErrors.error(ErrorCodes.MCP_INVALID_MESSAGE, ErrorCategory.EXTERNAL_SERVICE);
        }
        return value;
    }

    private Map<String, Object> map(Map<String, Object> source, String key) {
        if (source == null || !(source.get(key) instanceof Map)) { return Collections.emptyMap(); }
        return mapper.convertValue(source.get(key), MAP_TYPE);
    }

    private Map<String, Object> requiredMap(Map<String, Object> source, String key) {
        if (source == null || !(source.get(key) instanceof Map)) {
            throw McpClientErrors.error(ErrorCodes.MCP_INVALID_MESSAGE, ErrorCategory.EXTERNAL_SERVICE);
        }
        return mapper.convertValue(source.get(key), MAP_TYPE);
    }

    private List<Map<String, Object>> mapList(Object value) {
        if (value == null) { return Collections.emptyList(); }
        return mapper.convertValue(value, MAP_LIST_TYPE);
    }

    private static boolean booleanValue(Object value) {
        return value instanceof Boolean && ((Boolean) value).booleanValue();
    }
}
