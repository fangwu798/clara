package com.clara.media.job.callback;

import com.clara.kernel.exception.ClaraException;
import com.clara.kernel.exception.ErrorCodes;
import com.clara.media.MediaAsset;
import com.clara.media.MediaStore;
import com.clara.media.MediaUpload;
import com.clara.media.job.DefaultMediaJobScheduler;
import com.clara.media.job.InMemoryMediaJobStore;
import com.clara.media.job.MediaJob;
import com.clara.media.job.MediaJobProviderOperation;
import com.clara.media.job.MediaJobSchedulerConfig;
import com.clara.media.job.MediaJobStatus;
import com.clara.media.job.MediaProviderJobSnapshot;
import com.clara.media.job.MediaProviderJobStatus;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class HmacSha256MediaCallbackVerifierTest {
    private static final byte[] SECRET = bytes(32, (byte) 7);

    @Test public void verifiedHandlerAdvancesOnlyTheSignedJobVersion() {
        InMemoryMediaJobStore jobs = new InMemoryMediaJobStore();
        DefaultMediaJobScheduler scheduler = new DefaultMediaJobScheduler(jobs,
                new NoopMediaStore(), new MediaJobSchedulerConfig(10, 100, 2, 5));
        MediaJob running = scheduler.submit("tenant", "job", "request", 1000,
                new SubmitOperation(), 0);
        HmacSha256MediaCallbackVerifier verifier = verifier(policy(1024, 10));
        VerifiedMediaJobCallbackHandler handler = new VerifiedMediaJobCallbackHandler(scheduler, verifier);
        final byte[] payload = "running:60".getBytes(StandardCharsets.UTF_8);
        MediaJobCallbackEnvelope envelope = signed("tenant", "job", running.getVersion(),
                "key-1", 10, "nonce-1", payload);

        MediaJob updated = handler.handle(envelope, new MediaProviderCallbackParser() {
            public MediaProviderJobSnapshot parse(byte[] received) {
                assertArrayEquals(payload, received);
                return new MediaProviderJobSnapshot(MediaProviderJobStatus.RUNNING, 60, null, null);
            }
        }, 10);
        assertEquals(MediaJobStatus.RUNNING, updated.getStatus());
        assertEquals(60, updated.getProgress());
        assertEquals(running.getVersion() + 1, updated.getVersion());
    }

    @Test public void rejectsTamperedPayloadUnknownKeyAndMalformedSignature() {
        final HmacSha256MediaCallbackVerifier verifier = verifier(policy(1024, 10));
        MediaJobCallbackEnvelope valid = signed("tenant", "job", 2, "key-1", 100,
                "nonce-a", new byte[] {1, 2, 3});
        final MediaJobCallbackEnvelope tampered = new MediaJobCallbackEnvelope("tenant", "job", 2,
                "key-1", 100, "nonce-a", new byte[] {1, 2, 4}, valid.getSignature());
        assertCode(ErrorCodes.MEDIA_CALLBACK_AUTHENTICATION_FAILED, new Runnable() { public void run() {
            verifier.verifyAndReserve(tampered, 100);
        }});
        final MediaJobCallbackEnvelope unknown = new MediaJobCallbackEnvelope("tenant", "job", 2,
                "missing", 100, "nonce-b", new byte[] {1}, repeat('0', 64));
        assertCode(ErrorCodes.MEDIA_CALLBACK_AUTHENTICATION_FAILED, new Runnable() { public void run() {
            verifier.verifyAndReserve(unknown, 100);
        }});
        final MediaJobCallbackEnvelope malformed = new MediaJobCallbackEnvelope("tenant", "job", 2,
                "key-1", 100, "nonce-c", new byte[] {1}, "not-hex");
        assertCode(ErrorCodes.MEDIA_CALLBACK_AUTHENTICATION_FAILED, new Runnable() { public void run() {
            verifier.verifyAndReserve(malformed, 100);
        }});
    }

    @Test public void rejectsExpiredFutureAndReplayedCallbacks() {
        final HmacSha256MediaCallbackVerifier verifier = verifier(policy(1024, 10));
        final MediaJobCallbackEnvelope expired = signed("tenant", "job", 1, "key-1", 89,
                "expired", new byte[0]);
        assertCode(ErrorCodes.MEDIA_CALLBACK_EXPIRED, new Runnable() { public void run() {
            verifier.verifyAndReserve(expired, 100);
        }});
        final MediaJobCallbackEnvelope future = signed("tenant", "job", 1, "key-1", 111,
                "future", new byte[0]);
        assertCode(ErrorCodes.MEDIA_CALLBACK_EXPIRED, new Runnable() { public void run() {
            verifier.verifyAndReserve(future, 100);
        }});
        final MediaJobCallbackEnvelope accepted = signed("tenant", "job", 1, "key-1", 100,
                "same", new byte[0]);
        verifier.verifyAndReserve(accepted, 100);
        assertCode(ErrorCodes.MEDIA_CALLBACK_REPLAYED, new Runnable() { public void run() {
            verifier.verifyAndReserve(accepted, 100);
        }});
    }

    @Test public void boundsPayloadNonceAndReplayMemory() {
        final HmacSha256MediaCallbackVerifier verifier = verifier(
                new MediaCallbackSecurityPolicy(10, 2, 100, 4, 1));
        final MediaJobCallbackEnvelope large = signed("tenant", "job", 1, "key-1", 100,
                "one", new byte[] {1, 2, 3});
        assertCode(ErrorCodes.MEDIA_CALLBACK_RESOURCE_LIMIT, new Runnable() { public void run() {
            verifier.verifyAndReserve(large, 100);
        }});
        final MediaJobCallbackEnvelope longNonce = signed("tenant", "job", 1, "key-1", 100,
                "12345", new byte[0]);
        assertCode(ErrorCodes.MEDIA_CALLBACK_RESOURCE_LIMIT, new Runnable() { public void run() {
            verifier.verifyAndReserve(longNonce, 100);
        }});
        verifier.verifyAndReserve(signed("tenant", "job", 1, "key-1", 100,
                "one", new byte[0]), 100);
        final MediaJobCallbackEnvelope second = signed("tenant", "job", 1, "key-1", 100,
                "two", new byte[0]);
        assertCode(ErrorCodes.MEDIA_CALLBACK_RESOURCE_LIMIT, new Runnable() { public void run() {
            verifier.verifyAndReserve(second, 100);
        }});
        verifier.verifyAndReserve(signed("tenant", "job", 1, "key-1", 121,
                "two", new byte[0]), 121);
    }

    @Test public void convertsProviderParserFailureToStableError() {
        InMemoryMediaJobStore jobs = new InMemoryMediaJobStore();
        DefaultMediaJobScheduler scheduler = new DefaultMediaJobScheduler(jobs,
                new NoopMediaStore(), new MediaJobSchedulerConfig(10, 100, 2, 5));
        MediaJob running = scheduler.submit("tenant", "job", "request", 1000,
                new SubmitOperation(), 0);
        VerifiedMediaJobCallbackHandler handler = new VerifiedMediaJobCallbackHandler(scheduler,
                verifier(policy(1024, 10)));
        final MediaJobCallbackEnvelope envelope = signed("tenant", "job", running.getVersion(),
                "key-1", 10, "bad-json", new byte[] {1});
        assertCode(ErrorCodes.MEDIA_CALLBACK_INVALID, new Runnable() { public void run() {
            handler.handle(envelope, new MediaProviderCallbackParser() {
                public MediaProviderJobSnapshot parse(byte[] payload) {
                    throw new IllegalArgumentException("provider payload details");
                }
            }, 10);
        }});
        assertEquals(running.getVersion(), jobs.get("tenant", "job").getVersion());
    }

    private static HmacSha256MediaCallbackVerifier verifier(MediaCallbackSecurityPolicy policy) {
        return new HmacSha256MediaCallbackVerifier(new MediaCallbackSecretResolver() {
            public byte[] resolve(String tenantId, String keyId) {
                return "tenant".equals(tenantId) && "key-1".equals(keyId) ? SECRET.clone() : null;
            }
        }, policy, new InMemoryMediaCallbackReplayStore());
    }

    private static MediaCallbackSecurityPolicy policy(int maxPayload, int maxSkew) {
        return new MediaCallbackSecurityPolicy(maxSkew, maxPayload, 100, 100, 100);
    }

    private static MediaJobCallbackEnvelope signed(String tenant, String job, long version,
            String keyId, long timestamp, String nonce, byte[] payload) {
        MediaJobCallbackEnvelope unsigned = new MediaJobCallbackEnvelope(tenant, job, version,
                keyId, timestamp, nonce, payload, repeat('0', 64));
        String signature = HmacSha256MediaCallbackVerifier.sign(SECRET, unsigned);
        return new MediaJobCallbackEnvelope(tenant, job, version, keyId, timestamp, nonce,
                payload, signature);
    }

    private static byte[] bytes(int count, byte value) {
        byte[] result = new byte[count];
        Arrays.fill(result, value);
        return result;
    }

    private static String repeat(char value, int count) {
        char[] result = new char[count];
        Arrays.fill(result, value);
        return new String(result);
    }

    private static void assertCode(String code, Runnable action) {
        try { action.run(); fail("expected " + code); }
        catch (ClaraException exception) { assertEquals(code, exception.getError().getCode()); }
    }

    private static final class SubmitOperation implements MediaJobProviderOperation {
        public boolean supportsIdempotentSubmission() { return true; }
        public String submit(String idempotencyKey) { return "provider-job"; }
        public MediaProviderJobSnapshot poll(String providerJobId) { throw new UnsupportedOperationException(); }
        public void cancel(String providerJobId) { }
    }

    private static final class NoopMediaStore implements MediaStore {
        public MediaAsset put(MediaUpload upload) { throw new UnsupportedOperationException(); }
        public MediaAsset get(String tenantId, String assetId) { throw new UnsupportedOperationException(); }
        public InputStream open(String tenantId, String assetId) { throw new UnsupportedOperationException(); }
        public void delete(String tenantId, String assetId) { throw new UnsupportedOperationException(); }
    }
}
