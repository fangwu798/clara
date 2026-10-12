package com.clara.orchestration.api;

import com.clara.context.CancellationToken;
import com.clara.dto.memory.ContextControlConfig;
import com.clara.enums.CapabilityKindEnum;
import com.clara.enums.ModelProviderEnum;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.kernel.stream.IStreamObserver;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 一次对话请求：编排入口的入参载体，屏蔽客户端调用细节。
 *
 * <p>会话标识与 clara-memory 的会话标识同源同值；供应商与模型共同决定窗口记忆实例的归属；
 * 能力种类决定模型调用的请求构建与结果提取方式；control 配置承载窗口限制、压缩、向量化与检索设置。
 */
@Data
@Builder
public final class ConversationRequest {

    /** 会话所属用户或服务主体标识，可为 null。 */
    private String userId;
    /** 会话标识，不可为空白，与 clara-memory 的会话标识同源同值。 */
    private String conversationId;
    /** 模型供应商标识，用于窗口记忆隔离与调用记录。 */
    private ModelProviderEnum provider;
    /** 模型标识，用于窗口记忆隔离与请求下发。 */
    private String model;
    /** 本次调用的能力种类（对话 / 图片 / 视频 / 语音等）。 */
    private CapabilityKindEnum capabilityKind;
    /** 本轮用户输入文本。 */
    private String userInput;
    /** 本轮随消息下发的图片引用（公网 URL 或 data URI）；非空时以多模态消息下发，用于图片识别。 */
    private List<String> attachments;
    /** 本会话所属平台配置，用于绑定 {@code ConvContext}；可为 null。 */
    private ProviderConfiguration providerConfiguration;
    /** 上下文历史处理控制配置；为 null 时使用默认配置。 */
    private ContextControlConfig control;
    /** 是否以流式方式调用模型。 */
    private boolean stream;
    /** 流式调用的领域事件接收方；为 null 时退化为同步调用。 */
    private IStreamObserver observer;
    /** 单轮对话允许的最大工具往返次数；非正时使用编排默认值。 */
    private int maxToolRoundTrips;
    /** 本会话的取消令牌；非空时编排层复用它，外部置位即可取消本轮模型调用与工具执行；为 null 时编排层自建。 */
    private CancellationToken cancellationToken;
}
