package com.clara.utils;

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
        meta.setInstallation_id(installationId);
        meta.setSession_id(sessionId);
        meta.setThread_id(threadId);
        meta.setAgent_name(AGENT_NAME);
        meta.setTurn_id(turnId);
        meta.setWindow_id(windowId);
        meta.setWindow_number(WINDOW_NUMBER);
        meta.setContext_window_id(contextWindowId);
        meta.setRequest_kind(REQUEST_KIND);
        meta.setRoot_turn_id(rootTurnId);
        meta.setThread_source(THREAD_SOURCE);
        meta.setSandbox(SANDBOX);
        meta.setSandbox_mode(SANDBOX_MODE);
        meta.setAuto_review_enabled(false);
        meta.setNode_repl_auto_review_required(false);
        meta.setNode_repl_disabled(false);
        meta.setTurn_started_at_unix_ms(turnStartedAtUnixMs);

        String turnMetadataJson = objectMapper.writeValueAsString(meta);

        Map<String, String> headers = new HashMap<>();
        headers.put("x-codex-beta-features", BETA_FEATURES);
        headers.put("x-codex-window-id", windowId);
        headers.put("x-codex-turn-metadata", turnMetadataJson);
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
        String turnMetadata = headers == null ? null : headers.get("x-codex-turn-metadata");
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
        putIfPresent(meta, "x-codex-window-id", headers.get("x-codex-window-id"));
        putIfPresent(meta, "x-codex-turn-metadata", turnMetadata);
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
     * turn‑metadata JSON实体，无lombok，手写get/set
     */
    public static class TurnMetadata {
        private String installation_id;
        private String session_id;
        private String thread_id;
        private String agent_name;
        private String turn_id;
        private String window_id;
        private Integer window_number;
        private String context_window_id;
        private String request_kind;
        private String root_turn_id;
        private String thread_source;
        private String sandbox;
        private String sandbox_mode;
        private Boolean auto_review_enabled;
        private Boolean node_repl_auto_review_required;
        private Boolean node_repl_disabled;
        private Long turn_started_at_unix_ms;

        public String getInstallation_id() {
            return installation_id;
        }

        public void setInstallation_id(String installation_id) {
            this.installation_id = installation_id;
        }

        public String getSession_id() {
            return session_id;
        }

        public void setSession_id(String session_id) {
            this.session_id = session_id;
        }

        public String getThread_id() {
            return thread_id;
        }

        public void setThread_id(String thread_id) {
            this.thread_id = thread_id;
        }

        public String getAgent_name() {
            return agent_name;
        }

        public void setAgent_name(String agent_name) {
            this.agent_name = agent_name;
        }

        public String getTurn_id() {
            return turn_id;
        }

        public void setTurn_id(String turn_id) {
            this.turn_id = turn_id;
        }

        public String getWindow_id() {
            return window_id;
        }

        public void setWindow_id(String window_id) {
            this.window_id = window_id;
        }

        public Integer getWindow_number() {
            return window_number;
        }

        public void setWindow_number(Integer window_number) {
            this.window_number = window_number;
        }

        public String getContext_window_id() {
            return context_window_id;
        }

        public void setContext_window_id(String context_window_id) {
            this.context_window_id = context_window_id;
        }

        public String getRequest_kind() {
            return request_kind;
        }

        public void setRequest_kind(String request_kind) {
            this.request_kind = request_kind;
        }

        public String getRoot_turn_id() {
            return root_turn_id;
        }

        public void setRoot_turn_id(String root_turn_id) {
            this.root_turn_id = root_turn_id;
        }

        public String getThread_source() {
            return thread_source;
        }

        public void setThread_source(String thread_source) {
            this.thread_source = thread_source;
        }

        public String getSandbox() {
            return sandbox;
        }

        public void setSandbox(String sandbox) {
            this.sandbox = sandbox;
        }

        public String getSandbox_mode() {
            return sandbox_mode;
        }

        public void setSandbox_mode(String sandbox_mode) {
            this.sandbox_mode = sandbox_mode;
        }

        public Boolean getAuto_review_enabled() {
            return auto_review_enabled;
        }

        public void setAuto_review_enabled(Boolean auto_review_enabled) {
            this.auto_review_enabled = auto_review_enabled;
        }

        public Boolean getNode_repl_auto_review_required() {
            return node_repl_auto_review_required;
        }

        public void setNode_repl_auto_review_required(Boolean node_repl_auto_review_required) {
            this.node_repl_auto_review_required = node_repl_auto_review_required;
        }

        public Boolean getNode_repl_disabled() {
            return node_repl_disabled;
        }

        public void setNode_repl_disabled(Boolean node_repl_disabled) {
            this.node_repl_disabled = node_repl_disabled;
        }

        public Long getTurn_started_at_unix_ms() {
            return turn_started_at_unix_ms;
        }

        public void setTurn_started_at_unix_ms(Long turn_started_at_unix_ms) {
            this.turn_started_at_unix_ms = turn_started_at_unix_ms;
        }
    }
}
