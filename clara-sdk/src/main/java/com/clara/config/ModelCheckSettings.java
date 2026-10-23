package com.clara.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 启动模型可用性检查的策略：{@code config/clara.yml} 的 {@code modelCheck} 节点。
 *
 * <p>配置形态（节点缺省时全部走默认值）：
 * <pre>
 * modelCheck:
 *   timeoutMillis: 60000   # 等待首个流式事件的超时；中转站排队慢时调大
 *   retries: 1             # 超时后的重试次数；0 表示不重试
 * </pre>
 *
 * <p>为什么需要：不同平台的首字延迟差异很大——直连官方接口通常 1~3 秒，而经中转站或排队时可能到几十秒。
 * 30 秒硬超时会把偶发排队的可用模型误判为不可用；把超时与重试做成配置，启动检查即可按实际网络调整，
 * 不必改代码重新打包。
 */
public final class ModelCheckSettings {

    /** 配置节点名。 */
    private static final String NODE = "modelCheck";

    /** 默认等待首字超时：60 秒，覆盖中转站排队场景。 */
    private static final long DEFAULT_TIMEOUT_MILLIS = 60_000L;

    /** 默认超时重试次数：1 次，用于吸收偶发排队。 */
    private static final int DEFAULT_RETRIES = 1;

    /** 允许的最小超时：小于 5 秒会把正常网络往返也判为超时。 */
    private static final long MIN_TIMEOUT_MILLIS = 5_000L;

    /** 允许的最大重试次数：避免网络异常时启动被拖得过久。 */
    private static final int MAX_RETRIES = 3;

    /** YAML 读取器。 */
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** 单个模型等待首个响应事件的最长时间，单位为毫秒。 */
    private final long timeoutMillis;

    /** 超时后重新探测的次数。 */
    private final int retries;

    private ModelCheckSettings(long timeoutMillis, int retries) {
        this.timeoutMillis = timeoutMillis;
        this.retries = retries;
    }

    /**
     * 读取配置；文件缺失、节点缺失或取值非法时使用默认值。
     *
     * @param configFile 本地配置文件，可为 null
     * @return 检查策略，非 null
     */
    public static ModelCheckSettings load(Path configFile) {
        if (configFile == null || !Files.isReadable(configFile)) {
            return defaults();
        }
        JsonNode node;
        try {
            node = YAML.readTree(Files.readAllBytes(configFile)).get(NODE);
        } catch (IOException | RuntimeException failure) {
            return defaults();
        }
        if (node == null || !node.isObject()) {
            return defaults();
        }
        long timeout = node.path("timeoutMillis").asLong(DEFAULT_TIMEOUT_MILLIS);
        int retries = node.path("retries").asInt(DEFAULT_RETRIES);
        return new ModelCheckSettings(Math.max(MIN_TIMEOUT_MILLIS, timeout),
                Math.max(0, Math.min(MAX_RETRIES, retries)));
    }

    /**
     * 默认策略：60 秒超时、超时重试 1 次。
     *
     * @return 默认策略，非 null
     */
    public static ModelCheckSettings defaults() {
        return new ModelCheckSettings(DEFAULT_TIMEOUT_MILLIS, DEFAULT_RETRIES);
    }

    /**
     * 单个模型等待首个响应事件的最长时间。
     *
     * @return 超时毫秒数
     */
    public long getTimeoutMillis() {
        return timeoutMillis;
    }

    /**
     * 超时后重新探测的次数。
     *
     * @return 重试次数
     */
    public int getRetries() {
        return retries;
    }
}