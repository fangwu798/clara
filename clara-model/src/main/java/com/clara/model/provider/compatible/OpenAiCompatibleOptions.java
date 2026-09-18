package com.clara.model.provider.compatible;

import com.clara.model.api.ChatRequest;
import com.clara.model.api.ProviderOptions;

import java.util.List;
import java.util.Map;

/**
 * OpenAI 兼容请求中「平台专属选项」的扩展点。
 *
 * <p>用途：{@link OpenAiCompatibleChatCodec} 只负责 OpenAI 兼容协议通用的消息、工具与采样参数编码，
 * 各平台基于该协议扩展的特有请求字段由平台自己的 {@link ProviderOptions} 实现类通过本接口写入请求体。
 * 例如 DeepSeek 前缀补全需要在最后一条助手消息上附加 {@code prefix} 与 {@code reasoning_content}，
 * 该业务规则与字段名由 deepseek 包实现本接口来承载，协议包不再认识任何具体平台，
 * 从而避免协议包反向依赖平台包。
 *
 * <p>使用规范：
 * <ul>
 *   <li>实现类同时实现 {@link ProviderOptions}，且 {@code getProvider()} 必须返回该字段真正归属的平台；
 *       协议包会先校验「选项平台 == 配置平台」，不一致时直接拒绝，不会调用本方法；</li>
 *   <li>本方法在消息已全部编码、写入请求体根对象之前调用，且每个请求最多调用一次；</li>
 *   <li>{@code messages} 就是最终请求体中的 {@code messages}，元素为可变 {@code Map}，下标与
 *       {@link ChatRequest#getMessages()} 一一对应，改写元素即为改写最终请求体；</li>
 *   <li>只能写入本平台线格式认识的字段，不得覆盖通用编码已写入的
 *       {@code role}/{@code content}/{@code tool_calls} 等字段；</li>
 *   <li>选项与请求上下文不匹配时应抛 {@link IllegalArgumentException} 本地拒绝，不要静默忽略，
 *       避免把必然失败的请求发给平台。</li>
 * </ul>
 */
public interface OpenAiCompatibleOptions {
    /**
     * 把平台专属字段写入已编码的消息。
     *
     * @param request  对话请求，用于读取消息等上下文，不可为 null
     * @param messages 已编码的消息列表，元素为可变 Map，可直接增删字段
     * @throws IllegalArgumentException 选项与请求上下文不匹配时
     */
    void applyTo(ChatRequest request, List<Map<String, Object>> messages);
}
