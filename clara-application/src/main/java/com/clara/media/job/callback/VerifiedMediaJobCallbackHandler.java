package com.clara.media.job.callback;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import com.clara.media.job.MediaJob;
import com.clara.media.job.MediaJobScheduler;
import com.clara.media.job.MediaProviderJobSnapshot;
import java.util.Collections;

public final class VerifiedMediaJobCallbackHandler {
    private final MediaJobScheduler scheduler;
    private final HmacSha256MediaCallbackVerifier verifier;

    public VerifiedMediaJobCallbackHandler(MediaJobScheduler scheduler,
            HmacSha256MediaCallbackVerifier verifier) {
        if (scheduler == null || verifier == null)
            throw new IllegalArgumentException("dependencies must not be null");
        this.scheduler = scheduler;
        this.verifier = verifier;
    }

    public MediaJob handle(MediaJobCallbackEnvelope envelope,
            MediaProviderCallbackParser parser, long now) {
        if (parser == null) throw new IllegalArgumentException("parser must not be null");
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
