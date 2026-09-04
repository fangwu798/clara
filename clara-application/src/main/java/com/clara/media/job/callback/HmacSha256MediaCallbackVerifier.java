package com.clara.media.job.callback;

import com.clara.kernel.ClaraError;
import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCategory;
import com.clara.kernel.ErrorCodes;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class HmacSha256MediaCallbackVerifier {
    private static final byte[] DOMAIN = "clara-media-callback-v1".getBytes(StandardCharsets.US_ASCII);
    private static final int SHA256_HEX_CHARS = 64;
    private static final int MIN_SECRET_BYTES = 32;

    private final MediaCallbackSecretResolver secretResolver;
    private final MediaCallbackSecurityPolicy policy;
    private final MediaCallbackReplayStore replayStore;

    public HmacSha256MediaCallbackVerifier(MediaCallbackSecretResolver secretResolver,
            MediaCallbackSecurityPolicy policy, MediaCallbackReplayStore replayStore) {
        if (secretResolver == null || policy == null || replayStore == null)
            throw new IllegalArgumentException("dependencies must not be null");
        this.secretResolver = secretResolver;
        this.policy = policy;
        this.replayStore = replayStore;
    }

    public void verifyAndReserve(MediaJobCallbackEnvelope envelope, long now) {
        if (envelope == null) throw new IllegalArgumentException("envelope must not be null");
        if (now < 0L) throw new IllegalArgumentException("now must not be negative");
        byte[] payload = envelope.getPayload();
        if (payload.length > policy.getMaxPayloadBytes()
                || envelope.getTenantId().length() > policy.getMaxIdentityChars()
                || envelope.getJobId().length() > policy.getMaxIdentityChars()
                || envelope.getKeyId().length() > policy.getMaxIdentityChars()
                || envelope.getNonce().length() > policy.getMaxNonceChars()) resourceLimit();
        long lower = subtractFloor(now, policy.getMaxClockSkewMillis());
        long upper = addCeiling(now, policy.getMaxClockSkewMillis());
        if (envelope.getTimestampEpochMillis() < lower
                || envelope.getTimestampEpochMillis() > upper) expired();

        byte[] resolved = secretResolver.resolve(envelope.getTenantId(), envelope.getKeyId());
        if (resolved == null || resolved.length < MIN_SECRET_BYTES) authenticationFailed();
        byte[] secret = resolved.clone();
        try {
            byte[] supplied = decodeHex(envelope.getSignature());
            byte[] expected = hmac(secret, canonical(envelope, payload));
            if (supplied == null || !MessageDigest.isEqual(supplied, expected)) authenticationFailed();
        } finally {
            Arrays.fill(secret, (byte) 0);
        }

        MediaCallbackReplayDecision decision = replayStore.reserve(envelope.getTenantId(),
                envelope.getKeyId(), envelope.getNonce(),
                addCeiling(envelope.getTimestampEpochMillis(), policy.getMaxClockSkewMillis()),
                now, policy.getMaxTrackedNonces());
        if (decision == null || decision == MediaCallbackReplayDecision.CAPACITY_EXCEEDED)
            resourceLimit();
        if (decision == MediaCallbackReplayDecision.REPLAYED) replayed();
    }

    static String sign(byte[] secret, MediaJobCallbackEnvelope envelope) {
        if (secret == null || envelope == null) throw new IllegalArgumentException("signing values are required");
        byte[] payload = envelope.getPayload();
        return encodeHex(hmac(secret.clone(), canonical(envelope, payload)));
    }

    private static byte[] canonical(MediaJobCallbackEnvelope envelope, byte[] payload) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream output = new DataOutputStream(bytes);
            writeBytes(output, DOMAIN);
            writeString(output, envelope.getTenantId());
            writeString(output, envelope.getJobId());
            output.writeLong(envelope.getExpectedVersion());
            writeString(output, envelope.getKeyId());
            output.writeLong(envelope.getTimestampEpochMillis());
            writeString(output, envelope.getNonce());
            writeBytes(output, payload);
            output.flush();
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new IllegalStateException("in-memory callback encoding failed", exception);
        }
    }

    private static byte[] hmac(byte[] secret, byte[] input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(input);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HmacSHA256 is unavailable", exception);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        writeBytes(output, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytes(DataOutputStream output, byte[] value) throws IOException {
        output.writeInt(value.length);
        output.write(value);
    }

    private static byte[] decodeHex(String value) {
        if (value.length() != SHA256_HEX_CHARS) return null;
        byte[] decoded = new byte[SHA256_HEX_CHARS / 2];
        for (int i = 0; i < decoded.length; i++) {
            int high = Character.digit(value.charAt(i * 2), 16);
            int low = Character.digit(value.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) return null;
            decoded[i] = (byte) ((high << 4) | low);
        }
        return decoded;
    }

    private static String encodeHex(byte[] value) {
        char[] result = new char[value.length * 2];
        char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < value.length; i++) {
            int item = value[i] & 0xff;
            result[i * 2] = alphabet[item >>> 4];
            result[i * 2 + 1] = alphabet[item & 0x0f];
        }
        return new String(result);
    }

    private static long subtractFloor(long value, long amount) {
        return value < amount ? 0L : value - amount;
    }

    private static long addCeiling(long value, long amount) {
        return value > Long.MAX_VALUE - amount ? Long.MAX_VALUE : value + amount;
    }

    private static void authenticationFailed() {
        fail(ErrorCodes.MEDIA_CALLBACK_AUTHENTICATION_FAILED, ErrorCategory.AUTHENTICATION);
    }
    private static void expired() {
        fail(ErrorCodes.MEDIA_CALLBACK_EXPIRED, ErrorCategory.AUTHENTICATION);
    }
    private static void replayed() {
        fail(ErrorCodes.MEDIA_CALLBACK_REPLAYED, ErrorCategory.CONFLICT);
    }
    private static void resourceLimit() {
        fail(ErrorCodes.MEDIA_CALLBACK_RESOURCE_LIMIT, ErrorCategory.RESOURCE_LIMIT);
    }
    private static void fail(String code, ErrorCategory category) {
        throw new ClaraException(new ClaraError(code, category, code, false, null,
                null, null, null, Collections.<String, String>emptyMap()));
    }
}
