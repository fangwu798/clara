package com.clara.model.provider.media;

import com.clara.kernel.CancellationToken;
import com.clara.kernel.InvocationContext;
import com.clara.media.MediaInput;
import com.clara.media.MediaType;
import com.clara.media.MediaUpload;
import com.clara.media.job.MediaJobProviderOperation;
import com.clara.media.job.MediaProviderJobSnapshot;
import com.clara.media.job.MediaProviderJobStatus;
import com.clara.model.api.ModelCapability;
import com.clara.model.api.ModelProvider;
import com.clara.model.api.media.AudioTranscriptionRequest;
import com.clara.model.api.media.AudioTranscriptionResult;
import com.clara.model.api.media.ImageGenerationRequest;
import com.clara.model.api.media.ImageGenerationResult;
import com.clara.model.api.media.SpeechSynthesisRequest;
import com.clara.model.api.media.SpeechSynthesisResult;
import com.clara.model.api.media.VideoGenerationRequest;
import com.clara.model.provider.alibaba.AlibabaNativeMediaJobFactory;
import com.clara.model.provider.alibaba.AlibabaNativeTaskConfig;
import com.clara.model.provider.alibaba.AlibabaAudioConfig;
import com.clara.model.provider.alibaba.AlibabaAudioTranscriptionModel;
import com.clara.model.provider.alibaba.AlibabaSpeechSynthesisModel;
import com.clara.model.provider.compatible.image.OpenAiCompatibleImageConfig;
import com.clara.model.provider.compatible.image.OpenAiCompatibleImageModel;
import com.clara.model.provider.http.ProviderCredentialResolver;
import com.clara.model.provider.http.impl.ProviderHttpRequest;
import com.clara.model.provider.http.impl.ProviderHttpResponse;
import com.clara.model.provider.http.ProviderHttpTransport;
import com.clara.model.provider.http.ProviderSseListener;
import com.clara.model.provider.openai.OpenAiAudioTranscriptionModel;
import com.clara.model.provider.openai.OpenAiMediaConfig;
import com.clara.model.provider.openai.OpenAiSpeechSynthesisModel;
import com.clara.model.provider.volcengine.VolcengineImageOptions;
import com.clara.model.provider.volcengine.VolcengineVideoConfig;
import com.clara.model.provider.volcengine.VolcengineVideoJobFactory;
import com.clara.model.runtime.ProviderModelDescriptor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProviderMediaAdaptersTest {
    @Test public void volcengineImageMapsRequestAndPersistsRemoteOutput() throws Exception {
        QueueTransport transport = new QueueTransport(response("{\"request_id\":\"r1\",\"data\":[{\"url\":\"https://output.test/a.png\"}]}"));
        FakeAssets assets = new FakeAssets();
        OpenAiCompatibleImageModel model = new OpenAiCompatibleImageModel(
                new OpenAiCompatibleImageConfig(ModelProvider.VOLCENGINE,
                        uri("https://ark.test/images/generations"), "seedream", "secret", 100, 2, 1, 0),
                new VolcengineImageOptions(true),
                descriptor(ModelProvider.VOLCENGINE, "seedream", ModelCapability.IMAGE_GENERATION),
                assets, transport, credentials());
        ImageGenerationResult result = model.generate(new ImageGenerationRequest("draw", null, null,
                1, Integer.valueOf(1024), Integer.valueOf(1024), null, null, null, null, null, null, context()));
        JsonNode sent = new ObjectMapper().readTree(transport.requests.get(0).getBody());
        assertEquals("seedream", sent.get("model").asText());
        assertEquals("1024x1024", sent.get("size").asText());
        assertEquals("url", sent.get("response_format").asText());
        assertTrue(sent.get("watermark").asBoolean());
        assertEquals("stored-remote", result.getAssetIds().get(0));
        assertEquals("https://output.test/a.png", assets.remoteUrl);
    }

    @Test public void openAiAudioAdaptersUseMultipartAndPersistBinary() throws Exception {
        FakeAssets assets = new FakeAssets();
        QueueTransport transcriptionTransport = new QueueTransport(response(
                "{\"text\":\"hello\",\"language\":\"en\",\"duration\":1.5,\"segments\":[{\"start\":0,\"end\":1.5,\"text\":\"hello\"}]}"));
        OpenAiMediaConfig sttConfig = new OpenAiMediaConfig(uri("https://api.test/audio/transcriptions"),
                "stt", "secret", 100, 1024, 1);
        AudioTranscriptionResult transcript = new OpenAiAudioTranscriptionModel(sttConfig,
                descriptor(ModelProvider.OPENAI, "stt", ModelCapability.AUDIO_TRANSCRIPTION), assets,
                transcriptionTransport, credentials()).transcribe(new AudioTranscriptionRequest(
                        "audio-1", "hint", "en", null, null, null, context()));
        assertEquals("hello", transcript.getText()); assertEquals(Long.valueOf(1500), transcript.getDurationMs());
        assertTrue(transcriptionTransport.requests.get(0).getHeaders().get("Content-Type").startsWith("multipart/form-data"));
        assertTrue(new String(transcriptionTransport.requests.get(0).getBody(), StandardCharsets.ISO_8859_1).contains("audio-bytes"));

        QueueTransport speechTransport = new QueueTransport(new ProviderHttpResponse(200, null, new byte[] {9, 8, 7}));
        OpenAiMediaConfig ttsConfig = new OpenAiMediaConfig(uri("https://api.test/audio/speech"),
                "tts", "secret", 100, 1024, 1);
        SpeechSynthesisResult speech = new OpenAiSpeechSynthesisModel(ttsConfig,
                descriptor(ModelProvider.OPENAI, "tts", ModelCapability.SPEECH_SYNTHESIS), assets,
                speechTransport, credentials()).synthesize(new SpeechSynthesisRequest("hello", "alloy",
                        null, "audio/mpeg", null, Double.valueOf(1), null, context()));
        assertEquals("stored-bytes", speech.getAudioAssetId());
        assertArrayEquals(new byte[] {9, 8, 7}, assets.storedBytes);
    }

    @Test public void volcengineVideoMapsSubmitPollAndRemoteUpload() throws Exception {
        QueueTransport transport = new QueueTransport(response("{\"id\":\"job-1\"}"),
                response("{\"status\":\"succeeded\",\"progress\":100,\"content\":{\"video_url\":\"https://output.test/v.mp4\"}}"));
        FakeAssets assets = new FakeAssets();
        VolcengineVideoJobFactory factory = new VolcengineVideoJobFactory(
                new VolcengineVideoConfig(uri("https://ark.test/tasks"), "seedance", "secret", 100, 2),
                descriptor(ModelProvider.VOLCENGINE, "seedance", ModelCapability.VIDEO_GENERATION,
                        ModelCapability.VIDEO_IMAGE_REFERENCE), assets, transport, credentials());
        MediaJobProviderOperation operation = factory.create(new VideoGenerationRequest("move",
                Collections.singletonList("image-1"), null, null, Long.valueOf(3000), null, null,
                "16:9", Integer.valueOf(24), null, null, null, context()));
        assertEquals("job-1", operation.submit("idem-1"));
        MediaProviderJobSnapshot snapshot = operation.poll("job-1");
        assertEquals(MediaProviderJobStatus.SUCCEEDED, snapshot.getStatus());
        assertEquals("https://output.test/v.mp4", assets.remoteUrl);
        assertEquals("idem-1", transport.requests.get(0).getHeaders().get("X-Idempotency-Key"));
    }

    @Test public void alibabaNativeTaskMapsAsyncHeadersAndNestedStatus() throws Exception {
        QueueTransport transport = new QueueTransport(response("{\"output\":{\"task_id\":\"task-9\"}}"),
                response("{\"output\":{\"task_status\":\"SUCCEEDED\",\"video_url\":\"https://output.test/a.mp4\"}}"));
        FakeAssets assets = new FakeAssets();
        AlibabaNativeTaskConfig config = new AlibabaNativeTaskConfig(uri("https://dash.test/video-synthesis"),
                uri("https://dash.test/tasks"), "wan", "secret", MediaType.VIDEO, "video/mp4", 100, 2);
        AlibabaNativeMediaJobFactory factory = new AlibabaNativeMediaJobFactory(config,
                descriptor(ModelProvider.ALIBABA, "wan", ModelCapability.VIDEO_GENERATION),
                assets, transport, credentials());
        MediaJobProviderOperation operation = factory.create(new VideoGenerationRequest("move", null,
                null, null, null, null, null, null, null, null, null, null, context()));
        assertEquals("task-9", operation.submit("idem-9"));
        assertEquals("enable", transport.requests.get(0).getHeaders().get("X-DashScope-Async"));
        assertEquals(MediaProviderJobStatus.SUCCEEDED, operation.poll("task-9").getStatus());
        assertEquals("https://dash.test/tasks/task-9", transport.requests.get(1).getEndpoint().toString());
    }

    @Test public void alibabaAudioAdaptersMapNativeInputAndRemoteOutput() throws Exception {
        FakeAssets assets = new FakeAssets();
        AlibabaAudioConfig stt = new AlibabaAudioConfig(uri("https://dash.test/asr"), "qwen-asr", "secret", 100);
        QueueTransport sttTransport = new QueueTransport(response(
                "{\"output\":{\"text\":\"你好\",\"language\":\"zh\",\"duration\":1.2,\"segments\":[{\"start_time\":0,\"end_time\":1200,\"text\":\"你好\"}]}}"));
        AudioTranscriptionResult result = new AlibabaAudioTranscriptionModel(stt,
                descriptor(ModelProvider.ALIBABA, "qwen-asr", ModelCapability.AUDIO_TRANSCRIPTION),
                assets, sttTransport, credentials()).transcribe(new AudioTranscriptionRequest(
                        "audio-1", null, "zh", null, null, null, context()));
        assertEquals("你好", result.getText()); assertEquals(Long.valueOf(1200), result.getDurationMs());
        JsonNode sent = new ObjectMapper().readTree(sttTransport.requests.get(0).getBody());
        assertEquals("https://input.test/audio-1", sent.path("input").path("audio").asText());

        AlibabaAudioConfig tts = new AlibabaAudioConfig(uri("https://dash.test/tts"), "qwen-tts", "secret", 100);
        QueueTransport ttsTransport = new QueueTransport(response(
                "{\"output\":{\"audio\":{\"url\":\"https://output.test/s.wav\"},\"duration\":900}}"));
        SpeechSynthesisResult speech = new AlibabaSpeechSynthesisModel(tts,
                descriptor(ModelProvider.ALIBABA, "qwen-tts", ModelCapability.SPEECH_SYNTHESIS),
                assets, ttsTransport, credentials()).synthesize(new SpeechSynthesisRequest(
                        "你好", "voice", "zh", "audio/wav", Integer.valueOf(24000),
                        Double.valueOf(1), null, context()));
        assertEquals("stored-remote", speech.getAudioAssetId());
        assertEquals("https://output.test/s.wav", assets.remoteUrl);
    }

    private static ProviderModelDescriptor descriptor(ModelProvider provider, String model,
            ModelCapability... capabilities) {
        return new ProviderModelDescriptor(provider, model, "alias", "profile", "region", "revision",
                capabilities.length == 0 ? EnumSet.noneOf(ModelCapability.class) : EnumSet.copyOf(Arrays.asList(capabilities)));
    }
    private static InvocationContext context() {
        return new InvocationContext("tenant", null, null, "trace-1", System.currentTimeMillis() + 10000, new CancellationToken());
    }
    private static URI uri(String value) { try { return new URI(value); } catch (Exception e) { throw new AssertionError(e); } }
    private static ProviderCredentialResolver credentials() {
        return new ProviderCredentialResolver() { public char[] resolve(String tenantId, String secretRef) { return "credential".toCharArray(); } };
    }
    private static ProviderHttpResponse response(String json) {
        return new ProviderHttpResponse(200, null, json.getBytes(StandardCharsets.UTF_8));
    }
    private static final class QueueTransport implements ProviderHttpTransport {
        private final List<ProviderHttpResponse> responses = new ArrayList<ProviderHttpResponse>();
        private final List<ProviderHttpRequest> requests = new ArrayList<ProviderHttpRequest>();
        QueueTransport(ProviderHttpResponse... responses) { this.responses.addAll(Arrays.asList(responses)); }
        public ProviderHttpResponse execute(ProviderHttpRequest request, InvocationContext context) {
            requests.add(request); return responses.remove(0);
        }
        public ProviderHttpResponse execute(ProviderHttpRequest request, InvocationContext context,
                ProviderSseListener listener) {
            throw new UnsupportedOperationException("streaming is not used in this test");
        }
    }
    private static final class FakeAssets implements ProviderMediaAssetBridge {
        private String remoteUrl; private byte[] storedBytes;
        public String inputReference(String id, MediaType type, InvocationContext context) { return "https://input.test/" + id; }
        public byte[] inputBytes(String id, MediaType type, int max, InvocationContext context) {
            return "audio-bytes".getBytes(StandardCharsets.UTF_8);
        }
        public String storeRemote(String url, MediaType type, String mime, InvocationContext context) {
            remoteUrl = url; return "stored-remote";
        }
        public String storeBytes(byte[] value, MediaType type, String mime, InvocationContext context) {
            storedBytes = value.clone(); return "stored-bytes";
        }
        public MediaUpload remoteUpload(final String url, MediaType type, String mime, InvocationContext context) {
            remoteUrl = url;
            return new MediaUpload(context.getTenantId(), type, "provider-output.bin", mime, url,
                    null, null, null, null, Collections.<String, String>emptyMap(), new MediaInput() {
                        public InputStream open() { return new ByteArrayInputStream(new byte[] {1}); }
                    });
        }
    }
}
