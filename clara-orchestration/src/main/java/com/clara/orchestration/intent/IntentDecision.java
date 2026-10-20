package com.clara.orchestration.intent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 意图路由模型的一次输出：解析成功时带 JSON 节点，失败时带可反馈给模型的错误。
 */
final class IntentDecision {

    /** 解析出的 JSON 节点；解析失败时为 null。 */
    final JsonNode node;

    /** 模型原始输出，用于修正时回填给模型。 */
    final String raw;

    /** 解析失败原因；成功时为 null。 */
    final String error;

    IntentDecision(JsonNode node, String raw, String error) {
        this.node = node;
        this.raw = raw;
        this.error = error;
    }
}