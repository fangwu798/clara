package com.clara.core.interaction;

/**
 * 时间源接口：提供当前时间。
 *
 * <p>core 定义的契约接口，由调用方实现；时间可注入，便于过期判定与测试。
 */
public interface ITimeSource {

    /**
     * 获取当前时间。
     *
     * @return 当前时间（Unix epoch 毫秒）
     */
    long currentTimeMillis();
}
