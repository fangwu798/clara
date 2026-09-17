package com.clara.media.job.callback;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import com.clara.media.job.MediaJob;
import com.clara.media.job.MediaJobScheduler;
import com.clara.media.job.MediaProviderJobSnapshot;

import java.util.Collections;

/**
 * 串联回调验签、防重放、载荷解析和媒体任务状态更新。
 *
 * <p>常规用法：HTTP 接入层将原始回调构造成 {@link MediaJobCallbackEnvelope} 后调用 {@link #handle}，
 * 并传入当前时间；方法返回更新后的媒体任务。
 *
 * <p>注意：必须通过本处理器进入调度器，不能直接应用外部回调。信封中的租户、任务 ID、期望版本和签名载荷
 * 必须相互一致；nonce 会在验签成功后预留，重复投递将被拒绝。
 */
public final class VerifiedMediaJobCallbackHandler {
    private final MediaJobScheduler scheduler;
    private final HmacSha256MediaCallbackVerifier verifier;

    public VerifiedMediaJobCallbackHandler(MediaJobScheduler scheduler,
                                           HmacSha256MediaCallbackVerifier verifier) {
        if (scheduler == null || verifier == null) {
            throw new IllegalArgumentException("dependencies must not be null");
        }
        this.scheduler = scheduler;
        this.verifier = verifier;
    }

    public MediaJob handle(MediaJobCallbackEnvelope envelope,
                           MediaProviderCallbackParser parser, long now) {
        if (parser == null) {
            throw new IllegalArgumentException("parser must not be null");
        }
        verifier.verifyAndReserve(envelope, now);
        MediaProviderJobSnapshot snapshot;
        try {
            snapshot = parser.parse(envelope.getPayload());
        } catch (ClaraException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            invalid();
            return null;
        }
        if (snapshot == null) invalid();
        return scheduler.applyVerifiedCallback(envelope.getTenantId(), envelope.getJobId(),
                envelope.getExpectedVersion(), snapshot, now);
    }

    private static void invalid() {
        String code = ErrorCodes.MEDIA_CALLBACK_INVALID;
        throw new ClaraException(new ClaraError(code, ErrorCategory.VALIDATION, code, false,
                null, null, null, null, Collections.<String, String>emptyMap()));
    }
}
