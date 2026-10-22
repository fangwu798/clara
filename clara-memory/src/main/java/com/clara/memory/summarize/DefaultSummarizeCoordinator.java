package com.clara.memory.summarize;

import com.clara.context.ConvContext;
import com.clara.core.memory.ISummarizeCoordinator;
import com.clara.core.telemetry.Telemetry;
import com.clara.core.telemetry.TelemetrySpan;
import com.clara.core.telemetry.TelemetrySpanKindEnum;
import com.clara.enums.CapabilityKindEnum;
import com.clara.core.skill.IPromptProvider;
import com.clara.core.model.IStreamModel;
import com.clara.core.message.IContent;
import com.clara.kernel.http.HttpRequest;
import com.clara.kernel.http.HttpResponse;
import com.clara.kernel.message.delta.SystemContent;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认压缩编排：把被淘汰的消息组经模型总结为一条 system 摘要。
 *
 * <p>流程：取总结提示词 + 被淘汰消息 → 用当前上下文使用的模型生成摘要 → 以 system 指令形态返回。
 * 压缩是一次性独立任务、不走 RAG 递归；本类只编排，不实现模型与提示词。
 */
public final class DefaultSummarizeCoordinator implements ISummarizeCoordinator {

    private final IStreamModel model;
    private final IPromptProvider promptProvider;
    /** 链路追踪入口；未接入时为空实现。 */
    private final Telemetry telemetry;

    /**
     * 创建压缩编排。
     *
     * @param model          模型调用入口，不可为 null
     * @param promptProvider 提示词提供方，不可为 null
     * @throws IllegalArgumentException 任一参数为 null 时
     */
    public DefaultSummarizeCoordinator(IStreamModel model, IPromptProvider promptProvider) {
        this(model, promptProvider, Telemetry.noop());
    }

    /**
     * 创建压缩编排并接入链路追踪。
     *
     * @param model          模型调用入口，不可为 null
     * @param promptProvider 提示词提供方，不可为 null
     * @param telemetry      链路追踪入口，可为 null（按空实现处理）
     * @throws IllegalArgumentException model / promptProvider 为 null 时
     */
    public DefaultSummarizeCoordinator(IStreamModel model, IPromptProvider promptProvider, Telemetry telemetry) {
        if (model == null) {
            throw new IllegalArgumentException("model must not be null");
        }
        if (promptProvider == null) {
            throw new IllegalArgumentException("promptProvider must not be null");
        }
        this.model = model;
        this.promptProvider = promptProvider;
        this.telemetry = telemetry == null ? Telemetry.noop() : telemetry;
    }

    @Override
    public IContent summarize(List<IContent> evicted) {
        if (evicted == null) {
            throw new IllegalArgumentException("evicted must not be null");
        }
        String prompt = promptProvider.summarizePrompt();
        List<IContent> messages = new ArrayList<>(evicted.size() + 1);
        messages.add(new SystemContent(prompt));
        for (IContent message : evicted) {
            if (message != null) {
                messages.add(message);
            }
        }
        HttpRequest request = HttpRequest.builder()
                .messages(messages)
                .stream(Boolean.FALSE)
                .build();
        try (TelemetrySpan span = telemetry.startSpan("model.call", TelemetrySpanKindEnum.MODEL)) {
            span.attribute("stage", "compression")
                    .attribute("capability", "chat")
                    .attribute("messages", messages.size());
            ConvContext context = ConvContext.current();
            if (context != null && context.getProviderConfiguration() != null) {
                span.attribute("provider", context.getProviderConfiguration().getProvider() == null
                        ? null : context.getProviderConfiguration().getProvider().name());
                span.attribute("model", context.getProviderConfiguration()
                        .toConfig(CapabilityKindEnum.CHAT).getModelId());
            }
            try {
                HttpResponse response = model.chat(request, HttpResponse.class);
                if (response != null) {
                    span.attribute("input_tokens", response.getInputTokens())
                            .attribute("output_tokens", response.getOutputTokens())
                            .attribute("total_tokens", response.getTotalTokens());
                }
                String content = response == null ? null : response.getContent();
                return new SystemContent(content == null ? "" : content);
            } catch (RuntimeException failure) {
                span.error(failure);
                throw failure;
            }
        }
    }
}
