package com.clara.orchestration.record;

import com.clara.core.record.ICallRecordSink;
import com.clara.dto.record.CallRecord;
import com.clara.enums.ModelProviderEnum;
import com.clara.kernel.api.IRequest;
import com.clara.kernel.api.IResponse;

/**
 * 调用记录发布者：每次模型响应结束后，用本次实际请求与响应对象构造 {@link CallRecord} 交给接收方。
 *
 * <p>记录内容严格四项：请求对象、响应对象、供应商类型、模型类型；请求对象与本次实际执行的请求对象保持
 * 一致（不产生第二份）。未配置 {@link ICallRecordSink} 时不产生记录，也不影响调用本身。
 */
public final class CallRecordPublisher {

    private final ICallRecordSink callRecordSink;

    /**
     * 创建调用记录发布者。
     *
     * @param callRecordSink 调用记录接收方，可为 null（此时不产生记录）
     */
    public CallRecordPublisher(ICallRecordSink callRecordSink) {
        this.callRecordSink = callRecordSink;
    }

    /**
     * 发布一条调用记录。
     *
     * @param request  本次实际执行的请求对象，可为 null
     * @param response 本次实际执行的响应对象，可为 null
     * @param provider 供应商类型，可为 null
     * @param model    调用模型标识，可为 null
     */
    public void publish(IRequest request, IResponse response, ModelProviderEnum provider, String model) {
        if (callRecordSink == null || request == null || response == null || provider == null) {
            return;
        }
        callRecordSink.accept(new CallRecord(request, response, provider, model));
    }
}
