package com.clara.novel;

import com.clara.model.api.ChatModel;
import com.clara.model.api.ChatRequest;
import com.clara.model.api.ChatResponse;
import com.clara.model.api.Content;
import com.clara.model.api.FinishReason;
import com.clara.model.api.Role;
import com.clara.model.api.TextContent;
import com.clara.model.api.media.ImageGenerationModel;
import com.clara.model.api.media.ImageGenerationRequest;
import com.clara.model.api.media.ImageGenerationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class DefaultNovelAgentTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void createsArtifactsAndResumesWithoutRepeatedCalls() throws Exception {
        FakeModel model = new FakeModel(
                "# 故事圣经\n设定与两章大纲",
                "<chapter># 第一章 雾起\n初稿</chapter><summary>主角收到信。</summary>",
                "<chapter># 第一章 雾起\n修订正文一</chapter><summary>主角收到来自未来的信。</summary>",
                "<chapter># 第二章 回声\n初稿</chapter><summary>主角进入气象站。</summary>",
                "<chapter># 第二章 回声\n修订正文二</chapter><summary>主角进入气象站并发现设备启动。</summary>");
        Path output = temporary.newFolder("novel").toPath();
        NovelRequest request = new NovelRequest("雾港来信", "悬疑", "来自未来的新闻", 2, 800, output);
        DefaultNovelAgent agent = new DefaultNovelAgent(model, "gpt-6-astra", 2000,
                new ObjectMapper(), silent());

        NovelProjectState result = agent.create(request);

        assertTrue(result.completed);
        assertEquals(2, result.chapters.size());
        assertEquals(5, model.calls);
        assertTrue(Files.exists(output.resolve("project.json")));
        assertTrue(Files.exists(output.resolve("story-bible.md")));
        String novel = new String(Files.readAllBytes(output.resolve("novel.md")), StandardCharsets.UTF_8);
        assertTrue(novel.contains("修订正文一"));
        assertTrue(novel.contains("修订正文二"));

        agent.create(request);
        assertEquals(5, model.calls);
    }

    @Test(expected = IllegalArgumentException.class)
    public void refusesToResumeWithDifferentRequirements() throws Exception {
        Path output = temporary.newFolder("mismatch").toPath();
        FakeModel model = new FakeModel("大纲", "<chapter>正文</chapter><summary>摘要</summary>",
                "<chapter>修订</chapter><summary>摘要</summary>");
        DefaultNovelAgent agent = new DefaultNovelAgent(model, "gpt-6-astra", 1000,
                new ObjectMapper(), silent());
        agent.create(new NovelRequest("A", "科幻", "创意", 1, 500, output));
        agent.create(new NovelRequest("B", "科幻", "创意", 1, 500, output));
    }

    @Test public void generatesCoverOnceAndPersistsItsStatus() throws Exception {
        Path output = temporary.newFolder("cover").toPath();
        FakeImageModel image = new FakeImageModel(output, false);
        DefaultNovelAgent agent = new DefaultNovelAgent(oneChapterModel(), "gpt-6-astra", 1000,
                image, "gpt-image-2", new ObjectMapper(), silent());
        NovelRequest request = new NovelRequest("封面测试", "科幻", "月球城市", 1, 500, output);

        NovelProjectState result = agent.create(request);

        assertEquals("GENERATED", result.coverStatus);
        assertEquals("cover.png", result.coverPath);
        assertEquals("gpt-image-2", result.coverModel);
        assertTrue(Files.isRegularFile(output.resolve("cover.png")));
        agent.create(request);
        assertEquals(1, image.calls);
    }

    @Test public void coverFailureDoesNotStopNovelCreationOrRetryOnResume() throws Exception {
        Path output = temporary.newFolder("cover-failure").toPath();
        FakeImageModel image = new FakeImageModel(output, true);
        DefaultNovelAgent agent = new DefaultNovelAgent(oneChapterModel(), "gpt-6-astra", 1000,
                image, "gpt-image-2", new ObjectMapper(), silent());
        NovelRequest request = new NovelRequest("失败测试", "悬疑", "消失的车站", 1, 500, output);

        NovelProjectState result = agent.create(request);

        assertTrue(result.completed);
        assertEquals("FAILED", result.coverStatus);
        assertNotNull(result.coverError);
        assertTrue(Files.isRegularFile(output.resolve("novel.md")));
        agent.create(request);
        assertEquals(1, image.calls);
    }

    private static FakeModel oneChapterModel() {
        return new FakeModel("# 故事圣经\n一章大纲",
                "<chapter># 第一章 开端\n初稿</chapter><summary>开始。</summary>",
                "<chapter># 第一章 开端\n修订正文</chapter><summary>开始。</summary>");
    }

    private static NovelProgressListener silent() {
        return new NovelProgressListener() { public void onProgress(String message) { } };
    }

    private static final class FakeModel implements ChatModel {
        private final List<String> responses = new ArrayList<String>();
        private int calls;
        FakeModel(String... values) { Collections.addAll(responses, values); }
        public ChatResponse chat(ChatRequest request) {
            String value = responses.get(calls++);
            return new ChatResponse(new com.clara.model.api.ChatMessage(Role.ASSISTANT,
                    Collections.<Content>singletonList(new TextContent(value)), null, null),
                    null, FinishReason.STOP, null);
        }
    }

    private static final class FakeImageModel implements ImageGenerationModel {
        private final Path output;
        private final boolean fail;
        private int calls;
        FakeImageModel(Path output, boolean fail) { this.output = output; this.fail = fail; }
        public ImageGenerationResult generate(ImageGenerationRequest request) {
            calls++;
            if (fail) throw new IllegalStateException("image endpoint unavailable");
            try {
                Files.write(output.resolve("cover.png"), new byte[] { 1, 2, 3 });
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            return new ImageGenerationResult(Arrays.asList("cover.png"), "request-1",
                    Collections.<String, String>emptyMap());
        }
    }
}
