package com.clara.novel.cli;

import com.clara.model.api.ChatMessage;
import com.clara.model.api.ChatModel;
import com.clara.model.api.ChatRequest;
import com.clara.model.api.ChatResponse;
import com.clara.model.api.Content;
import com.clara.model.api.FinishReason;
import com.clara.model.api.Role;
import com.clara.model.api.TextContent;
import com.clara.novel.NovelRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class InteractiveNovelPlannerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void committeeDiscussesTwoRoundsWithoutInterrogatingAuthor() throws Exception {
        FakeModel model = new FakeModel(
                "提出时间循环主线", "回应剧情架构师并补足人物动机", "回应前两者并约束世界规则",
                "{\"status\":\"CONSENSUS\",\"summary\":\"第一轮形成基础方案\",\"question\":\"\"}",
                "回应人物与世界观并优化高潮", "回应剧情与世界观并完成弧光", "回应前两者并封闭规则漏洞",
                "{\"status\":\"CONSENSUS\",\"summary\":\"委员会达成最终共识\",\"question\":\"\"}",
                "{\"title\":\"第七次黎明\",\"genre\":\"科幻悬疑\",\"premise\":\"主角困在七日循环中寻找妹妹。\",\"chapterCount\":8,\"wordsPerChapter\":1200}");
        Path output = temporary.newFolder("committee").toPath();
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        InteractiveNovelPlanner planner = new InteractiveNovelPlanner(model, "deepseek-chat", 2000,
                new ObjectMapper(), new StringReader("七日时间循环，寻找妹妹\n"),
                new PrintStream(console, true, "UTF-8"));
        NovelRequest request = planner.plan(output, 3, 1500);
        assertEquals("第七次黎明", request.getTitle());
        assertEquals(8, request.getChapterCount());
        assertEquals(9, model.calls);
        String transcript = new String(Files.readAllBytes(output.resolve("planning-chat.md")), StandardCharsets.UTF_8);
        assertTrue(transcript.contains("## 剧情架构师"));
        assertTrue(transcript.contains("## 人物编辑"));
        assertTrue(transcript.contains("## 世界观设计师"));
        assertTrue(transcript.contains("## 总编"));
        assertFalse(console.toString("UTF-8").contains("你的决定："));
    }

    @Test public void onlyAsksAuthorWhenChiefEditorFindsMaterialDisagreement() throws Exception {
        FakeModel model = new FakeModel(
                "剧情方案一", "人物方案一", "世界方案一",
                "{\"status\":\"DISAGREEMENT\",\"summary\":\"结局路线冲突\",\"question\":\"结局应救回妹妹还是接受失去？\"}",
                "剧情依据作者裁决修订", "人物依据作者裁决修订", "世界规则修订",
                "{\"status\":\"CONSENSUS\",\"summary\":\"已按作者裁决统一结局\",\"question\":\"\"}",
                "{\"title\":\"回声尽头\",\"genre\":\"科幻悬疑\",\"premise\":\"主角最终接受失去。\",\"chapterCount\":6,\"wordsPerChapter\":1000}");
        Path output = temporary.newFolder("disagreement").toPath();
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        InteractiveNovelPlanner planner = new InteractiveNovelPlanner(model, "deepseek-chat", 2000,
                new ObjectMapper(), new StringReader("时间循环\n接受失去\n"),
                new PrintStream(console, true, "UTF-8"));
        NovelRequest request = planner.plan(output, 3, 1500);
        assertEquals("回声尽头", request.getTitle());
        assertTrue(console.toString("UTF-8").contains("结局应救回妹妹还是接受失去"));
        String transcript = new String(Files.readAllBytes(output.resolve("planning-chat.md")), StandardCharsets.UTF_8);
        assertTrue(transcript.contains("## 作者裁决"));
        assertTrue(transcript.contains("接受失去"));
    }

    @Test public void worldViewEditorUsesItsOwnModelId() throws Exception {
        FakeModel primary = new FakeModel(
                "剧情一", "人物一",
                "{\"status\":\"CONSENSUS\",\"summary\":\"第一轮\",\"question\":\"\"}",
                "剧情二", "人物二",
                "{\"status\":\"CONSENSUS\",\"summary\":\"第二轮\",\"question\":\"\"}",
                "{\"title\":\"双模型\",\"genre\":\"科幻\",\"premise\":\"委员会共创。\",\"chapterCount\":2,\"wordsPerChapter\":500}");
        FakeModel world = new FakeModel("世界观一", "世界观二");
        Path output = temporary.newFolder("model-routing").toPath();
        InteractiveNovelPlanner planner = new InteractiveNovelPlanner(primary, "deepseek-v4-pro", 2000,
                new ObjectMapper(), new StringReader("一个世界\n"),
                new PrintStream(new ByteArrayOutputStream(), true, "UTF-8"), false,
                world, "doubao-seed-character-260628");
        planner.plan(output, 2, 500);
        assertEquals(2, world.requestedModels.size());
        for (String model : world.requestedModels)
            assertEquals("doubao-seed-character-260628", model);
        for (String model : primary.requestedModels)
            assertEquals("deepseek-v4-pro", model);
    }

    private static final class FakeModel implements ChatModel {
        private final List<String> responses = new ArrayList<String>();
        private final List<String> requestedModels = new ArrayList<String>();
        private int calls;
        FakeModel(String... values) { Collections.addAll(responses, values); }
        public ChatResponse chat(ChatRequest request) {
            requestedModels.add(request.getParameters().getModel());
            String value = responses.get(calls++);
            return new ChatResponse(new ChatMessage(Role.ASSISTANT,
                    Collections.<Content>singletonList(new TextContent(value)), null, null), null,
                    FinishReason.STOP, null);
        }
    }
}
