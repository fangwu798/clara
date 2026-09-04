package com.clara.media.validation;

import com.clara.kernel.ClaraException;
import com.clara.kernel.ErrorCodes;
import com.clara.media.MediaInput;
import com.clara.media.MediaMetadata;
import com.clara.media.MediaType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public class WaveAudioMetadataProbeTest {
    @Test public void calculatesDurationFromFmtAndDataChunks() throws Exception {
        WaveAudioMetadataProbe probe = new WaveAudioMetadataProbe(8, 1024);
        MediaMetadata metadata = probe.probe(MediaType.AUDIO, "audio/wav", input(wav(8000, 1, 16, 16000)));
        assertEquals(1000L, metadata.getDurationMs().longValue());
        assertTrue(probe.supports(MediaType.AUDIO, "audio/x-wav"));
        assertFalse(probe.supports(MediaType.VIDEO, "audio/wav"));
    }

    @Test public void handlesUnknownOddSizedChunksAndRejectsInvalidFmt() throws Exception {
        WaveAudioMetadataProbe probe = new WaveAudioMetadataProbe(8, 1024);
        MediaMetadata metadata = probe.probe(MediaType.AUDIO, "audio/wav", input(wavWithOddJunk()));
        assertEquals(500L, metadata.getDurationMs().longValue());
        final byte[] invalid = wav(8000, 1, 16, 10);
        invalid[28] = 0; invalid[29] = 0; invalid[30] = 0; invalid[31] = 0;
        assertCode(ErrorCodes.MEDIA_VALIDATION_FAILED, new Runnable() { public void run() {
            try { new WaveAudioMetadataProbe(8, 1024).probe(MediaType.AUDIO, "audio/wav", input(invalid)); }
            catch (IOException e) { throw new AssertionError(e); }
        }});
    }

    private static byte[] wav(int sampleRate, int channels, int bits, int dataBytes) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ascii(output, "RIFF"); le32(output, 36L + dataBytes); ascii(output, "WAVE");
        ascii(output, "fmt "); le32(output, 16); le16(output, 1); le16(output, channels);
        le32(output, sampleRate); long byteRate = (long) sampleRate * channels * bits / 8;
        le32(output, byteRate); le16(output, channels * bits / 8); le16(output, bits);
        ascii(output, "data"); le32(output, dataBytes); output.write(new byte[dataBytes]);
        return output.toByteArray();
    }
    private static byte[] wavWithOddJunk() throws IOException {
        byte[] base = wav(8000, 1, 16, 8000);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(base, 0, 36); ascii(output, "JUNK"); le32(output, 1); output.write(7); output.write(0);
        output.write(base, 36, base.length - 36);
        byte[] value = output.toByteArray(); long riffSize = value.length - 8L;
        value[4] = (byte) riffSize; value[5] = (byte) (riffSize >>> 8); value[6] = (byte) (riffSize >>> 16); value[7] = (byte) (riffSize >>> 24);
        return value;
    }
    private static MediaInput input(final byte[] bytes) {
        return new MediaInput() { public InputStream open() { return new ByteArrayInputStream(bytes); } };
    }
    private static void ascii(ByteArrayOutputStream output, String value) throws IOException {
        output.write(value.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }
    private static void le16(ByteArrayOutputStream output, int value) { output.write(value); output.write(value >>> 8); }
    private static void le32(ByteArrayOutputStream output, long value) {
        output.write((int) value); output.write((int) (value >>> 8)); output.write((int) (value >>> 16)); output.write((int) (value >>> 24));
    }
    private static void assertCode(String code, Runnable action) {
        try { action.run(); fail("expected " + code); }
        catch (ClaraException e) { assertEquals(code, e.getError().getCode()); }
    }
}
