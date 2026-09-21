package com.clara.enums;

/**
 * 模型能力种类。
 *
 * <p>用途：描述一次模型调用属于哪一类能力，既用于选择配置解析器与配置类型，也随线程上下文
 * （{@code ConvContext}）传给编解码层——字段上的适配注解按本枚举决定该字段在当前调用中是否下发、
 * 从哪条路径取值，从而无需为每个平台或模型写分支。
 *
 * <p>使用方法：
 * <ol>
 *   <li>实例由调用方在建立会话上下文时给出，并作为 YAML 能力节点的键，例如
 *       {@code providerConfiguration.toConfig(CapabilityKindEnum.IMAGE)} 读取 {@code capabilities.IMAGE}；</li>
 *   <li>同一平台每种能力最多配置一个节点，节点名即本枚举的常量名；{@code parameters} 的键名随种类而定
 *       （对话用 {@code maxTokens}，图片用 {@code maxPromptChars} / {@code maxOutputs} 等）；</li>
 *   <li>枚举不可变、天然线程安全，可直接用 {@code ==} 比较，无需释放。</li>
 * </ol>
 *
 * <p>失败语义：本枚举自身不抛异常；调用方传入平台与种类组合没有对应解析器，或该平台未配置对应能力
 * 节点时，分别由 {@code ConfigResolvers} 与 {@code ProviderConfiguration} 抛配置类异常。
 */
public enum CapabilityKindEnum {
    /**
     * 文本对话：同步与流式共用同一份配置。对应 YAML 能力节点 {@code capabilities.CHAT}。
     */
    CHAT,
    /**
     * 图片生成与图片编辑：{@code maxInputBytes > 0} 时表示支持 multipart 上传源图。
     * 对应 YAML 能力节点 {@code capabilities.IMAGE}。
     */
    IMAGE,
    /**
     * 视频生成：平台侧为异步任务，需要提交端点与任务查询端点。对应 YAML 能力节点 {@code capabilities.VIDEO}。
     */
    VIDEO,
    /**
     * 语音识别（音频转写）：输入音频、输出文本。对应 YAML 能力节点 {@code capabilities.AUDIO_TRANSCRIPTION}。
     */
    AUDIO_TRANSCRIPTION,
    /**
     * 语音合成：输入文本、输出音频。对应 YAML 能力节点 {@code capabilities.SPEECH}。
     */
    SPEECH
}
