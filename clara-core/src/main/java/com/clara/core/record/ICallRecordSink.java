package com.clara.core.record;

import com.clara.dto.record.CallRecord;

/**
 * 调用记录接收方：接收每次模型调用结束后的调用记录。
 *
 * <p>core 定义的契约接口，由宿主应用提供实现；未配置接收方时不产生记录、也不影响调用本身。
 */
public interface ICallRecordSink {

    /**
     * 接收一条调用记录。
     *
     * @param record 调用记录，非 null
     */
    void accept(CallRecord record);
}
