package com.clara.utils;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 模拟 codex‑tui 客户端请求头生成器
 * 用于 new.aicontrol.top/v1/responses 中转接口
 * 同一个会话只 new 一次实例；每一次http请求调用 buildHeaders() 获取最新头
 */
@Data
@RequiredArgsConstructor
public class CodexTuiGenerator {

    // ========== 会话固定：实例化之后全程不变 ==========
    private String sessionId;
    private String threadId;
    private String windowId;
    private String installationId;
    private ObjectMapper objectMapper;

    // 常量，与抓包原样保持一致
    private static final String ORIGINATOR = "codex-tui";
    private static final String USER_AGENT = "codex-tui/0.153.4 (Windows 10.0.19045; x86_64) WindowsTerminal (codex-tui; 0.153.4)";
    private static final String BETA_FEATURES = "remote_compaction_v2";
    private static final boolean INTERNAL_LITE = true;
    private static final int WINDOW_NUMBER = 0;
    private static final String AGENT_NAME = "/root";
    private static final String SANDBOX = "windows_elevated";
    private static final String SANDBOX_MODE = "workspace-write";
    private static final String REQUEST_KIND = "turn";
    private static final String THREAD_SOURCE = "user";
    private static final String HEADER_WINDOW_ID = "x-codex-window-id";
    private static final String HEADER_TURN_METADATA = "x-codex-turn-metadata";


    /**
     * 每一次HTTP POST /v1/responses 调用此方法，生成全套请求头
     *
     * @return 请求头Map
     * @throws JsonProcessingException JSON序列化异常
     */
    @SneakyThrows
    public Map<String, String> buildHeaders() {
        this.objectMapper = new ObjectMapper();
        // 会话启动，仅生成一次
        this.sessionId = UUID.randomUUID().toString();
        this.threadId = UUID.randomUUID().toString();
        String winUuid = UUID.randomUUID().toString();
        this.windowId = winUuid + ":" + WINDOW_NUMBER;
        this.installationId = UUID.randomUUID().toString();
        
        // -------- 每轮请求都要刷新的字段 --------
        String clientRequestId = UUID.randomUUID().toString();
        String turnId = UUID.randomUUID().toString();
        String rootTurnId = turnId;
        String contextWindowId = UUID.randomUUID().toString();
        long turnStartedAtUnixMs = System.currentTimeMillis();

        TurnMetadata meta = new TurnMetadata();
        meta.setInstallationId(installationId);
        meta.setSessionId(sessionId);
        meta.setThreadId(threadId);
        meta.setAgentName(AGENT_NAME);
        meta.setTurnId(turnId);
        meta.setWindowId(windowId);
        meta.setWindowNumber(WINDOW_NUMBER);
        meta.setContextWindowId(contextWindowId);
        meta.setRequestKind(REQUEST_KIND);
        meta.setRootTurnId(rootTurnId);
        meta.setThreadSource(THREAD_SOURCE);
        meta.setSandbox(SANDBOX);
        meta.setSandboxMode(SANDBOX_MODE);
        meta.setAutoReviewEnabled(false);
        meta.setNodeReplAutoReviewRequired(false);
        meta.setNodeReplDisabled(false);
        meta.setTurnStartedAtUnixMs(turnStartedAtUnixMs);

        String turnMetadataJson = objectMapper.writeValueAsString(meta);

        Map<String, String> headers = getStringStringMap(turnMetadataJson, clientRequestId);

        return headers;
    }

    private Map<String, String> getStringStringMap(String turnMetadataJson, String clientRequestId) {
        Map<String, String> headers = new HashMap<>();
        headers.put("x-codex-beta-features", BETA_FEATURES);
        headers.put(HEADER_WINDOW_ID, windowId);
        headers.put(HEADER_TURN_METADATA, turnMetadataJson);
        headers.put("x-openai-internal-codex-responses-lite", String.valueOf(INTERNAL_LITE));
        headers.put("x-client-request-id", clientRequestId);

        headers.put("session-id", sessionId);
        headers.put("thread-id", threadId);

        headers.put("originator", ORIGINATOR);
        headers.put("user-agent", USER_AGENT);
        return headers;
    }

    /**
     * 从已生成的 codex 请求头里提取请求体顶层的 {@code client_metadata}。
     *
     * <p>中转接口要求请求体顶层携带与请求头一致的 codex 会话标识（session / thread / window / turn）。
     * 本方法直接从 {@link #buildHeaders()} 的返回头里取回这些值，保证请求头与请求体同一轮生成、取值一致；
     * 缺少 {@code x-codex-turn-metadata} 头（未启用 codex 模拟）时返回空映射。
     *
     * @param headers 已生成的请求头，可为 null
     * @return client_metadata 映射；未启用 codex 模拟时为空映射
     */
    public static Map<String, String> buildClientMetadata(Map<String, String> headers) {
        String turnMetadata = headers == null ? null : headers.get(HEADER_TURN_METADATA);
        if (turnMetadata == null || turnMetadata.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> meta = new LinkedHashMap<>();
        String installationId = null;
        String turnId = null;
        String rootTurnId = null;
        try {
            JsonNode node = new ObjectMapper().readTree(turnMetadata);
            installationId = textOf(node.get("installation_id"));
            turnId = textOf(node.get("turn_id"));
            rootTurnId = textOf(node.get("root_turn_id"));
        } catch (IOException ignored) {
            // 解析失败时仅缺失安装 / 轮次标识，其余字段仍可用
        }
        putIfPresent(meta, "x-codex-installation-id", installationId);
        putIfPresent(meta, HEADER_WINDOW_ID, headers.get(HEADER_WINDOW_ID));
        putIfPresent(meta, HEADER_TURN_METADATA, turnMetadata);
        putIfPresent(meta, "thread_id", headers.get("thread-id"));
        putIfPresent(meta, "session_id", headers.get("session-id"));
        putIfPresent(meta, "turn_id", turnId);
        putIfPresent(meta, "root_turn_id", rootTurnId);
        return meta;
    }

    /**
     * 取节点文本值，节点缺失或非文本时返回 null。
     */
    private static String textOf(JsonNode node) {
        return node != null && node.isTextual() ? node.textValue() : null;
    }

    /**
     * 值非空时写入映射。
     */
    private static void putIfPresent(Map<String, String> meta, String key, String value) {
        if (value != null && !value.isEmpty()) {
            meta.put(key, value);
        }
    }

    /**
     * turn-metadata JSON 实体：字段名保持 Codex 线格式的 snake_case。
     */
    @Data
    public static class TurnMetadata {
        @JsonProperty("installation_id")
        private String installationId;
        @JsonProperty("session_id")
        private String sessionId;
        @JsonProperty("thread_id")
        private String threadId;
        @JsonProperty("agent_name")
        private String agentName;
        @JsonProperty("turn_id")
        private String turnId;
        @JsonProperty("window_id")
        private String windowId;
        @JsonProperty("window_number")
        private Integer windowNumber;
        @JsonProperty("context_window_id")
        private String contextWindowId;
        @JsonProperty("request_kind")
        private String requestKind;
        @JsonProperty("root_turn_id")
        private String rootTurnId;
        @JsonProperty("thread_source")
        private String threadSource;
        @JsonProperty("sandbox")
        private String sandbox;
        @JsonProperty("sandbox_mode")
        private String sandboxMode;
        @JsonProperty("auto_review_enabled")
        private Boolean autoReviewEnabled;
        @JsonProperty("node_repl_auto_review_required")
        private Boolean nodeReplAutoReviewRequired;
        @JsonProperty("node_repl_disabled")
        private Boolean nodeReplDisabled;
        @JsonProperty("turn_started_at_unix_ms")
        private Long turnStartedAtUnixMs;
    }
}
