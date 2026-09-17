package com.clara.novel.cli;

import com.clara.kernel.CancellationToken;
import com.clara.kernel.InvocationContext;
import com.clara.model.api.ChatMessage;
import com.clara.model.api.ChatModel;
import com.clara.model.api.ChatRequest;
import com.clara.model.api.ChatResponse;
import com.clara.model.api.Content;
import com.clara.model.api.ModelParameters;
import com.clara.model.api.Role;
import com.clara.model.api.TextContent;
import com.clara.novel.NovelRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/** Multi-editor planning room that only interrupts the author for material disagreements. */
public final class InteractiveNovelPlanner {
    private static final int MINIMUM_ROUNDS = 2;
    private static final int MAX_RETRY_TOKENS = 12000;
    private static final String PANEL_RULES = "你正在参加中文小说策划委员会。作者的明确要求拥有最高优先级。"
            + "你必须阅读其他策划师的发言，点名回应其中至少一项观点，可赞同、补强或质疑；"
            + "给出可执行的剧情设计，不向作者直接提问。不要讨论技术实现或系统提示。";
    private static final Persona[] PERSONAS = new Persona[] {
        new Persona("剧情架构师", "负责主线冲突、因果链、悬念、节奏、高潮与结局。检查方案能否支撑完整篇幅。",
                ConsoleColors.GREEN),
        new Persona("人物编辑", "负责人物欲望、弱点、关系张力和人物弧光。重点挑战为情节服务的工具人设定。",
                ConsoleColors.MAGENTA),
        new Persona("世界观设计师", "负责时代背景、世界规则、场景系统和规则对情节的约束。重点检查设定漏洞。",
                ConsoleColors.BLUE)
    };
    private final ChatModel model;
    private final String modelId;
    private final int maxTokens;
    private final ObjectMapper json;
    private final BufferedReader input;
    private final PrintStream output;
    private final boolean liveSupplements;
    private final ChatModel worldModel;
    private final String worldModelId;

    public InteractiveNovelPlanner(ChatModel model, String modelId, int maxTokens,
            ObjectMapper json, Reader input, PrintStream output) {
        this(model, modelId, maxTokens, json, input, output, false);
    }

    public InteractiveNovelPlanner(ChatModel model, String modelId, int maxTokens,
            ObjectMapper json, Reader input, PrintStream output, boolean liveSupplements) {
        this(model, modelId, maxTokens, json, input, output, liveSupplements, null);
    }

    public InteractiveNovelPlanner(ChatModel model, String modelId, int maxTokens,
            ObjectMapper json, Reader input, PrintStream output, boolean liveSupplements,
            ChatModel worldModel) {
        this(model, modelId, maxTokens, json, input, output, liveSupplements,
                worldModel, modelId);
    }

    /**
     * Creates a planner with an optional separately routed world-view model.
     * The model id must travel with the model instance because the OpenAI-compatible
     * codec validates that the request model matches the provider registration.
     */
    public InteractiveNovelPlanner(ChatModel model, String modelId, int maxTokens,
            ObjectMapper json, Reader input, PrintStream output, boolean liveSupplements,
            ChatModel worldModel, String worldModelId) {
        if (model == null || blank(modelId) || maxTokens <= 0 || json == null
                || input == null || output == null) {
            throw new IllegalArgumentException("interactive planner dependencies are invalid");
        }
        if (worldModel != null && blank(worldModelId)) {
            throw new IllegalArgumentException("world model id must not be blank");
        }
        this.model = model;
        this.modelId = modelId;
        this.maxTokens = maxTokens;
        this.json = json;
        this.input = new BufferedReader(input);
        this.output = output;
        this.liveSupplements = liveSupplements;
        this.worldModel = worldModel;
        this.worldModelId = worldModel == null ? modelId : worldModelId;
    }

    public NovelRequest plan(Path outputDirectory, int defaultChapters, int defaultWords) {
        if (outputDirectory == null || defaultChapters <= 0 || defaultWords <= 0) {
            throw new IllegalArgumentException("interactive planning defaults are invalid");
        }
        try {
            Files.createDirectories(outputDirectory);
            List<Turn> transcript = new ArrayList<Turn>();
            output.println("小说策划委员会已就位：剧情架构师、人物编辑、世界观设计师、总编。 ");
            output.println("请先给出大概要求。委员会将自行讨论，只有关键分歧才会询问你。 ");
            printUserPrompt("初始要求：");
            String initial = readUserRequired();
            if ("/quit".equalsIgnoreCase(initial)) throw new IllegalStateException("已退出小说策划");
            transcript.add(new Turn("作者", initial));
            saveTranscript(outputDirectory, transcript);

            InputInbox inbox = liveSupplements ? new InputInbox(input) : null;
            if (inbox != null) {
                inbox.start();
                output.println(ConsoleColors.CYAN
                        + "讨论期间可直接输入补充或纠偏并按回车；系统会在下一次发言前吸收。"
                        + ConsoleColors.RESET);
            }

            int round = 0;
            boolean finishRequested = false;
            String consensus = "";
            while (true) {
                round++;
                output.println("\n" + ConsoleColors.YELLOW + "--- 策划委员会第 " + round
                        + " 轮讨论 ---" + ConsoleColors.RESET);
                for (Persona persona : PERSONAS) {
                    finishRequested |= absorbSupplements(inbox, transcript, outputDirectory);
                    ChatModel speakerModel = persona.name.equals("世界观设计师") && worldModel != null
                            ? worldModel : model;
                    String speakerModelId = persona.name.equals("世界观设计师") && worldModel != null
                            ? worldModelId : modelId;
                    output.println("\n" + persona.color + persona.name + "正在阅读委员会记录并提出方案"
                            + "（模型：" + speakerModelId + "）..." + ConsoleColors.RESET);
                    output.flush();
                    String reply = call(speakerModel, speakerModelId, persona.systemPrompt(),
                            panelContext(initial, transcript, round, consensus),
                            "planning-" + round + "-" + persona.stageName());
                    transcript.add(new Turn(persona.name, reply));
                    printTurn(persona, reply);
                    saveTranscript(outputDirectory, transcript);
                }
                finishRequested |= absorbSupplements(inbox, transcript, outputDirectory);
                output.println("\n" + ConsoleColors.BRIGHT_GREEN
                        + "总编正在比较三位策划师的观点并判断是否存在关键分歧..."
                        + ConsoleColors.RESET);
                output.flush();
                Decision decision = moderate(initial, transcript, round);
                transcript.add(new Turn("总编", decision.summary));
                output.println("\n" + ConsoleColors.BRIGHT_GREEN + "总编："
                        + ConsoleColors.RESET + decision.summary);
                saveTranscript(outputDirectory, transcript);
                consensus = decision.summary;

                if ("DISAGREEMENT".equals(decision.status)) {
                    String question = blank(decision.question)
                            ? "委员会存在无法代替作者决定的关键分歧，请给出你的取舍。"
                            : decision.question;
                    output.println(ConsoleColors.YELLOW + "委员会需要你裁决：" + question
                            + ConsoleColors.RESET);
                    printUserPrompt("你的决定：");
                    String answer = awaitAuthorDecision(inbox);
                    if ("/quit".equalsIgnoreCase(answer)) throw new IllegalStateException("已退出小说策划");
                    transcript.add(new Turn("作者裁决", answer));
                    saveTranscript(outputDirectory, transcript);
                    continue;
                }
                if (round >= MINIMUM_ROUNDS || finishRequested) break;
            }

            absorbSupplements(inbox, transcript, outputDirectory);
            Specification specification = finalizeSpecification(initial, transcript, consensus,
                    defaultChapters, defaultWords);
            printSpecification(specification);
            writeBrief(outputDirectory, specification);
            output.println(ConsoleColors.BRIGHT_GREEN
                    + "委员会已达成共识，开始进入故事圣经和正文创作。" + ConsoleColors.RESET);
            return new NovelRequest(specification.title, specification.genre,
                    specification.premise, specification.chapterCount,
                    specification.wordsPerChapter, outputDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot persist interactive planning session", exception);
        }
    }

    private Decision moderate(String initial, List<Turn> transcript, int round) throws IOException {
        String system = "你是小说策划委员会总编。判断各方是否存在必须由作者本人决定、"
                + "且会显著改变主题、主角、核心冲突或结局的关键分歧。普通创作分歧由你裁决，不能打扰作者。"
                + "只输出 JSON：{\"status\":\"CONSENSUS或DISAGREEMENT\","
                + "\"summary\":\"本轮共识与裁决\",\"question\":\"仅分歧时给作者的单一选择题，否则为空\"}。";
        String raw = stripFence(call(model, modelId, system,
                panelContext(initial, transcript, round, ""),
                "planning-" + round + "-chief-editor"));
        JsonNode root = json.readTree(raw);
        String status = requiredText(root, "status").toUpperCase(java.util.Locale.ROOT);
        if (!"CONSENSUS".equals(status) && !"DISAGREEMENT".equals(status))
            throw new IllegalStateException("chief editor returned invalid status");
        return new Decision(status, requiredText(root, "summary"), optionalText(root, "question"));
    }

    private Specification finalizeSpecification(String initial, List<Turn> transcript,
            String consensus, int defaultChapters, int defaultWords) throws IOException {
        output.println("\n" + ConsoleColors.BRIGHT_GREEN
                + "总编正在把讨论记录整理为最终创作任务..." + ConsoleColors.RESET);
        output.flush();
        String system = "你是小说策划委员会总编。把委员会讨论结果整理成最终创作任务。"
                + "只输出一个 JSON 对象，不要 Markdown 或解释。字段必须为 title、genre、premise、"
                + "chapterCount、wordsPerChapter。premise 必须完整记录人物、世界规则、主线冲突、"
                + "叙事视角、风格、人物弧光和结局方向，并遵守作者的全部裁决。";
        String prompt = panelContext(initial, transcript, 0, consensus)
                + "\n章节数未指定时使用 " + defaultChapters + "；每章字数未指定时使用 "
                + defaultWords + "。chapterCount 范围 1-100，wordsPerChapter 范围 300-10000。";
        JsonNode root = json.readTree(stripFence(call(model, modelId, system, prompt, "planning-finalize")));
        return new Specification(requiredText(root, "title"), requiredText(root, "genre"),
                requiredText(root, "premise"), requiredInt(root, "chapterCount", 1, 100),
                requiredInt(root, "wordsPerChapter", 300, 10000));
    }

    private String call(ChatModel activeModel, String activeModelId, String system, String prompt,
            String stage) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(message(Role.SYSTEM, system));
        messages.add(message(Role.USER, prompt));
        long now = System.currentTimeMillis();
        InvocationContext context = new InvocationContext("local", "novel-cli", stage,
                UUID.randomUUID().toString(), now + 180000L, new CancellationToken());
        ChatResponse response = request(activeModel, activeModelId, messages, context, maxTokens);
        StringBuilder text = responseText(response);
        int attemptedTokens = maxTokens;
        if (blank(text.toString()) && response.getFinishReason()
                == com.clara.model.api.FinishReason.LENGTH && maxTokens < MAX_RETRY_TOKENS) {
            attemptedTokens = Math.min(MAX_RETRY_TOKENS, maxTokens * 2);
            output.println("\n" + ConsoleColors.YELLOW + "模型输出达到长度上限，正在以 "
                    + attemptedTokens + " tokens 自动重试..." + ConsoleColors.RESET);
            output.flush();
            response = request(activeModel, activeModelId, messages, context, attemptedTokens);
            text = responseText(response);
        }
        if (blank(text.toString())) {
            String finish = response.getFinishReason() == null ? "unknown"
                    : response.getFinishReason().name();
            String providerModel = response.getProviderMetadata().get("model");
            throw new IllegalStateException("model returned empty planning response"
                    + " (finishReason=" + finish + ", responseModel="
                    + (blank(providerModel) ? "unknown" : providerModel)
                    + ", maxTokens=" + attemptedTokens + ")");
        }
        return text.toString().trim();
    }

    private ChatResponse request(ChatModel activeModel, String activeModelId,
            List<ChatMessage> messages, InvocationContext context, int tokenLimit) {
        ModelParameters parameters = new ModelParameters(activeModelId, Double.valueOf(0.7d), null,
                Integer.valueOf(tokenLimit), null, false);
        return activeModel.chat(new ChatRequest(messages, parameters,
                Collections.emptyList(), null, context));
    }

    private static StringBuilder responseText(ChatResponse response) {
        StringBuilder text = new StringBuilder();
        for (Content content : response.getMessage().getContents())
            if (content instanceof TextContent) text.append(((TextContent) content).getText());
        return text;
    }

    private static String panelContext(String initial, List<Turn> transcript, int round,
            String previousConsensus) {
        StringBuilder result = new StringBuilder("<作者初始要求>\n").append(initial)
                .append("\n</作者初始要求>\n");
        if (!blank(previousConsensus)) result.append("<上一轮总编裁决>\n")
                .append(previousConsensus).append("\n</上一轮总编裁决>\n");
        result.append("<委员会记录>\n");
        for (Turn turn : transcript) result.append(turn.speaker).append("：")
                .append(turn.text).append("\n\n");
        result.append("</委员会记录>\n当前轮次：").append(round)
                .append("。请推进方案，避免重复已有发言。 ");
        String value = result.toString();
        return value.length() <= 30000 ? value : value.substring(value.length() - 30000);
    }

    private boolean absorbSupplements(InputInbox inbox, List<Turn> transcript, Path directory)
            throws IOException {
        if (inbox == null) return false;
        boolean finish = false;
        for (String value : inbox.drain()) {
            if ("/quit".equalsIgnoreCase(value)) throw new IllegalStateException("已退出小说策划");
            if ("/done".equalsIgnoreCase(value)) { finish = true; continue; }
            if (blank(value)) continue;
            transcript.add(new Turn("作者补充", value));
            output.println("\n" + ConsoleColors.CYAN + "已吸收作者补充：" + value
                    + ConsoleColors.RESET);
            saveTranscript(directory, transcript);
        }
        return finish;
    }

    private String awaitAuthorDecision(InputInbox inbox) throws IOException {
        if (inbox == null) return readUserRequired();
        String value = inbox.await();
        output.print(ConsoleColors.RESET);
        if (blank(value)) throw new IllegalArgumentException("输入不能为空");
        return value.trim();
    }
    private void printTurn(Persona persona, String reply) {
        output.println("\n" + persona.color + persona.name + "：" + ConsoleColors.RESET + reply);
    }
    private void printSpecification(Specification value) {
        output.println("\n" + ConsoleColors.BRIGHT_GREEN + "委员会最终创作任务：" + ConsoleColors.RESET);
        output.println("书名：" + value.title);
        output.println("题材：" + value.genre);
        output.println("章节：" + value.chapterCount + "，每章约 " + value.wordsPerChapter + " 字");
        output.println("完整设定：" + value.premise);
    }
    private void printUserPrompt(String label) {
        output.print(ConsoleColors.CYAN + label);
        output.flush();
    }
    private String readUserRequired() throws IOException {
        try {
            String value = input.readLine();
            if (value == null) throw new IllegalStateException("输入已结束");
            if (blank(value)) throw new IllegalArgumentException("输入不能为空");
            return value.trim();
        } finally {
            output.print(ConsoleColors.RESET);
            output.flush();
        }
    }

    private static void writeBrief(Path directory, Specification value) throws IOException {
        String markdown = "# " + value.title + "\n\n- 题材：" + value.genre
                + "\n- 章节数：" + value.chapterCount + "\n- 每章目标字数："
                + value.wordsPerChapter + "\n\n## 创作设定\n\n" + value.premise + "\n";
        Files.write(directory.resolve("creative-brief.md"), markdown.getBytes(StandardCharsets.UTF_8));
    }
    private static void saveTranscript(Path directory, List<Turn> turns) throws IOException {
        StringBuilder markdown = new StringBuilder("# 小说策划委员会记录\n\n");
        for (Turn turn : turns) markdown.append("## ").append(turn.speaker).append("\n\n")
                .append(turn.text).append("\n\n");
        Files.write(directory.resolve("planning-chat.md"),
                markdown.toString().getBytes(StandardCharsets.UTF_8));
    }
    private static String stripFence(String value) {
        String trimmed = value.trim();
        if (trimmed.startsWith("```")) {
            int firstLine = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine)
                return trimmed.substring(firstLine + 1, lastFence).trim();
        }
        return trimmed;
    }
    private static String requiredText(JsonNode root, String field) {
        JsonNode value = root == null ? null : root.get(field);
        if (value == null || !value.isTextual() || blank(value.asText()))
            throw new IllegalStateException("planning response is missing " + field);
        return value.asText().trim();
    }
    private static String optionalText(JsonNode root, String field) {
        JsonNode value = root == null ? null : root.get(field);
        return value == null || value.isNull() ? "" : value.asText("").trim();
    }
    private static int requiredInt(JsonNode root, String field, int minimum, int maximum) {
        JsonNode value = root == null ? null : root.get(field);
        if (value == null || !value.isIntegralNumber())
            throw new IllegalStateException("planning response is missing " + field);
        int number = value.asInt();
        if (number < minimum || number > maximum)
            throw new IllegalStateException("planning response has invalid " + field);
        return number;
    }
    private static ChatMessage message(Role role, String text) {
        return new ChatMessage(role, Collections.<Content>singletonList(new TextContent(text)), null, null);
    }
    private static boolean blank(String value) { return value == null || value.trim().isEmpty(); }

    static final class ConsoleColors {
        static final String RESET = "\u001b[0m";
        static final String CYAN = "\u001b[36m";
        static final String GREEN = "\u001b[32m";
        static final String MAGENTA = "\u001b[35m";
        static final String BLUE = "\u001b[34m";
        static final String YELLOW = "\u001b[33m";
        static final String BRIGHT_GREEN = "\u001b[92m";
        private ConsoleColors() { }
    }
    private static final class Persona {
        final String name;
        final String focus;
        final String color;
        Persona(String name, String focus, String color) {
            this.name = name; this.focus = focus; this.color = color;
        }
        String systemPrompt() { return PANEL_RULES + "你的身份是" + name + "。" + focus; }
        String stageName() { return name.equals("剧情架构师") ? "plot"
                : name.equals("人物编辑") ? "character" : "world"; }
    }
    private static final class Turn {
        final String speaker;
        final String text;
        Turn(String speaker, String text) { this.speaker = speaker; this.text = text; }
    }
    private static final class Decision {
        final String status;
        final String summary;
        final String question;
        Decision(String status, String summary, String question) {
            this.status = status; this.summary = summary; this.question = question;
        }
    }
    private static final class Specification {
        final String title;
        final String genre;
        final String premise;
        final int chapterCount;
        final int wordsPerChapter;
        Specification(String title, String genre, String premise, int chapterCount,
                int wordsPerChapter) {
            this.title = title; this.genre = genre; this.premise = premise;
            this.chapterCount = chapterCount; this.wordsPerChapter = wordsPerChapter;
        }
    }
    private static final class InputInbox {
        private static final String END = "\u0000CLARA_INPUT_END\u0000";
        private final BufferedReader reader;
        private final BlockingQueue<String> queue = new LinkedBlockingQueue<String>();
        private volatile IOException failure;
        InputInbox(BufferedReader reader) { this.reader = reader; }
        void start() {
            Thread thread = new Thread(new Runnable() {
                public void run() {
                    try {
                        String line;
                        while ((line = reader.readLine()) != null) queue.offer(line);
                    } catch (IOException exception) {
                        failure = exception;
                    } finally { queue.offer(END); }
                }
            }, "clara-author-input");
            thread.setDaemon(true);
            thread.start();
        }
        List<String> drain() throws IOException {
            if (failure != null) throw failure;
            List<String> values = new ArrayList<String>();
            while (true) {
                String value = queue.poll();
                if (value == null) break;
                if (END.equals(value)) { queue.offer(END); break; }
                values.add(value.trim());
            }
            return values;
        }
        String await() throws IOException {
            try {
                String value = queue.take();
                if (END.equals(value)) {
                    if (failure != null) throw failure;
                    throw new IllegalStateException("输入已结束");
                }
                return value;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("等待作者输入时被中断", exception);
            }
        }
    }
}
