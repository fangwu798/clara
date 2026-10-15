package com.clara.model.availability;

import lombok.Data;
import lombok.Getter;

import java.nio.file.Path;

/**
 * 单个模型的启动可用性检查结果。
 *
 * <p>结果对象不可变，可表示“可用”“不可用”和“因缺少凭据而跳过”三种状态。可用状态记录首字符响应时间；
 * 其他状态以 {@code -1} 表示没有取得首字符响应时间，并通过详情文本说明原因。
 */
@Data
public final class ModelCheckResult {

    /**
     * 被检查模型的供应商配置文件路径。
     * -- GETTER --
     * 获取供应商配置文件路径。
     *
     * @return 配置文件路径
     */
    private final Path path;

    /**
     * 模型别名，通常为配置文件名去掉 {@code .yml} 或 {@code .yaml} 扩展名后的值。
     */
    private final String alias;

    /**
     * 模型供应商名称；配置尚未成功解析或检查被跳过时可为 null。
     */
    private final String provider;

    /**
     * 供应商接口使用的模型 ID；配置尚未成功解析或检查被跳过时可为 null。
     */
    private final String model;

    /**
     * 模型是否成功返回了非空正文字符。
     */
    private final boolean available;

    /**
     * 从发起流式请求到收到首个非空正文字符的耗时，单位为毫秒；未取得首字符时为 -1。
     */
    private final long firstCharacterMillis;

    /**
     * 不可用或跳过检查的原因，也可为可用状态补充说明；无需说明时为 null。
     *
     * <p>可用状态下为什么会带说明：推理模型可能只返回思维链而正文为空，此时模型确实可用，
     * 但响应时间的口径需要解释，说明文本会追加到控制台输出。
     */
    private final String detail;

    /**
     * 是否因为检查前置条件不满足而跳过请求，而不是请求执行后判定为不可用。
     */
    private final boolean skipped;

    /**
     * 创建完整检查结果。对外调用方应使用具名工厂方法表达具体状态。
     *
     * @param path                 供应商配置文件路径
     * @param alias                模型别名
     * @param provider             供应商名称，可为 null
     * @param model                模型 ID，可为 null
     * @param available            模型是否可用
     * @param firstCharacterMillis 首字符响应时间；未取得时为 -1
     * @param detail               不可用或跳过原因，可为 null
     * @param skipped              是否跳过了真实模型请求
     */
    private ModelCheckResult(Path path, String alias, String provider, String model, boolean available, long firstCharacterMillis, String detail, boolean skipped) {
        this.path = path;
        this.alias = alias;
        this.provider = provider;
        this.model = model;
        this.available = available;
        this.firstCharacterMillis = firstCharacterMillis;
        this.detail = detail;
        this.skipped = skipped;
    }

    /**
     * 创建模型可用的检查结果。
     *
     * @param path     供应商配置文件路径
     * @param alias    模型别名
     * @param provider 供应商名称
     * @param model    模型 ID
     * @param latency  首字符响应时间，单位为毫秒
     * @return 可用状态的检查结果
     */
    public static ModelCheckResult available(Path path, String alias, String provider, String model, long latency) {
        return available(path, alias, provider, model, latency, null);
    }

    /**
     * 创建模型可用并附带说明的检查结果。
     *
     * @param path     供应商配置文件路径
     * @param alias    模型别名
     * @param provider 供应商名称
     * @param model    模型 ID
     * @param latency  首字符响应时间，单位为毫秒
     * @param detail   可用状态说明，可为 null
     * @return 可用状态的检查结果
     */
    public static ModelCheckResult available(Path path, String alias, String provider, String model, long latency, String detail) {
        return new ModelCheckResult(path, alias, provider, model, true, latency, detail, false);
    }

    /**
     * 创建模型不可用的检查结果。
     *
     * @param path     供应商配置文件路径
     * @param alias    模型别名
     * @param provider 供应商名称；配置解析失败时可为 null
     * @param model    模型 ID；配置解析失败时可为 null
     * @param detail   不可用原因
     * @return 不可用状态的检查结果
     */
    public static ModelCheckResult unavailable(Path path, String alias, String provider, String model, String detail) {
        return new ModelCheckResult(path, alias, provider, model, false, -1L, detail, false);
    }

    /**
     * 创建因缺少环境变量而跳过真实请求的检查结果。
     *
     * @param path     供应商配置文件路径
     * @param alias    模型别名
     * @param variable 缺失或值为空的环境变量名
     * @return 跳过状态的检查结果
     */
    public static ModelCheckResult missingCredential(Path path, String alias, String variable) {
        return new ModelCheckResult(path, alias, null, null, false, -1L, "缺少环境变量 " + variable, true);
    }

    /**
     * 创建因模型能力不适用等前置条件而跳过真实请求的检查结果。
     *
     * @param path   供应商配置文件路径
     * @param alias  模型别名
     * @param detail 跳过检查的原因
     * @return 跳过状态的检查结果
     */
    public static ModelCheckResult skipped(Path path, String alias, String detail) {
        return new ModelCheckResult(path, alias, null, null, false, -1L, detail, true);
    }

}
