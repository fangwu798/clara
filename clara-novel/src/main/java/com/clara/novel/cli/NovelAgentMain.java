package com.clara.novel.cli;

import com.clara.kernel.ClaraException;
import com.clara.model.api.ModelCapability;
import com.clara.model.api.ChatModel;
import com.clara.model.configuration.ProviderCapabilityConfiguration;
import com.clara.model.configuration.ProviderConfiguration;
import com.clara.model.configuration.ProviderYamlConfigurationLoader;
import com.clara.model.provider.compatible.OpenAiCompatibleChatModel;
import com.clara.model.provider.compatible.image.OpenAiCompatibleImageModel;
import com.clara.model.provider.http.impl.Jdk8ProviderHttpTransport;
import com.clara.model.provider.http.ProviderHttpTransport;
import com.clara.model.runtime.ProviderModelDescriptor;
import com.clara.novel.DefaultNovelAgent;
import com.clara.novel.NovelCoverAssetBridge;
import com.clara.novel.NovelProgressListener;
import com.clara.novel.NovelRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

/** Command-line entry point for the runnable novel agent. */
public final class NovelAgentMain {
    private NovelAgentMain() { }

    public static void main(String[] args) {
        try {
            Map<String, String> options = parse(args);
            if (options.containsKey("help")) { usage(); return; }
            boolean interactive = args.length == 0 || options.containsKey("interactive");
            Path configPath = Paths.get(value(options, "config",
                    "config/model-providers/deepseek.yml"));
            Path configDirectory = configPath.getParent() == null
                    ? Paths.get("config/model-providers") : configPath.getParent();
            Path openAiPath = configDirectory.resolve("openai.yml");
            Path volcPath = configDirectory.resolve("volcengine.yml");
            Path outputDirectory = Paths.get(value(options, "output", "output/novel"));
            String imageCapability = value(options, "image-model", "image");
            ProviderYamlConfigurationLoader loader = new ProviderYamlConfigurationLoader();
            try (ProviderConfiguration provider = loader.load(configPath);
                    ProviderConfiguration openAi = loader.load(openAiPath);
                    ProviderConfiguration volcengine = loader.load(volcPath)) {
                ProviderCapabilityConfiguration chat = provider.getCapability("chat");
                ProviderCapabilityConfiguration volcChat = volcengine.getCapability("chat");
                ProviderCapabilityConfiguration image = volcengine.getCapability(imageCapability);
                ProviderHttpTransport transport = new ForbiddenRequestLoggingTransport(
                        new Jdk8ProviderHttpTransport(
                                provider.getTransport().toHttpTransportConfig()), System.err);
                ProviderHttpTransport volcTransport = new ForbiddenRequestLoggingTransport(
                        new Jdk8ProviderHttpTransport(
                                volcengine.getTransport().toHttpTransportConfig()), System.err);
                openAi.toOpenAiCompatibleChatConfig("chat");
                OpenAiCompatibleChatModel providerModel = new OpenAiCompatibleChatModel(
                        provider.toOpenAiCompatibleChatConfig("chat"),
                        transport,
                        provider.credentialResolver());
                ChatModel model = new ConsoleThinkingChatModel(providerModel,
                        chat.requireModelId(), System.out, interactive);
                OpenAiCompatibleChatModel volcProviderModel = new OpenAiCompatibleChatModel(
                        volcengine.toOpenAiCompatibleChatConfig("chat"), volcTransport,
                        volcengine.credentialResolver());
                ChatModel volcChatModel = new ConsoleThinkingChatModel(volcProviderModel,
                        volcChat.requireModelId(), System.out, interactive);
                int maxTokens = parameter(chat, "maxTokens", 6000);
                ObjectMapper objectMapper = new ObjectMapper();
                System.out.println("当前文本模型：" + provider.getProvider().name()
                        + " / " + chat.requireModelId());
                System.out.println("委员会世界观模型：" + volcengine.getProvider().name()
                        + " / " + volcChat.requireModelId());
                System.out.println("当前封面模型：" + volcengine.getProvider().name()
                        + " / " + image.requireModelId() + "（配置：" + imageCapability + "）");
                NovelRequest request;
                if (interactive) {
                    InteractiveNovelPlanner planner = new InteractiveNovelPlanner(model,
                            chat.requireModelId(), Math.min(maxTokens, 6000), objectMapper,
                            new InputStreamReader(System.in, StandardCharsets.UTF_8), System.out, true,
                            volcChatModel, volcChat.requireModelId());
                    request = planner.plan(outputDirectory,
                            integer(options, "chapters", 3), integer(options, "words", 1500));
                } else {
                    request = new NovelRequest(required(options, "title"),
                            value(options, "genre", "现实主义"), required(options, "premise"),
                            integer(options, "chapters", 3), integer(options, "words", 1500),
                            outputDirectory);
                }
                ProviderModelDescriptor imageDescriptor = new ProviderModelDescriptor(
                        volcengine.getProvider(), image.requireModelId(), "novel-cover",
                        image.getApiProfile(), null, null,
                        EnumSet.of(ModelCapability.IMAGE_GENERATION));
                OpenAiCompatibleImageModel imageModel = new OpenAiCompatibleImageModel(
                        volcengine.toVolcengineImageConfig(imageCapability),
                        volcengine.toVolcengineImageOptions(imageCapability), imageDescriptor,
                        new NovelCoverAssetBridge(request.getOutputDirectory()), volcTransport,
                        volcengine.credentialResolver());
                DefaultNovelAgent agent = new DefaultNovelAgent(model, chat.requireModelId(),
                        maxTokens, imageModel, image.requireModelId(), objectMapper,
                        new NovelProgressListener() {
                            public void onProgress(String message) { System.out.println(message); }
                        });
                agent.create(request);
                System.out.println("输出文件：" + request.getOutputDirectory().resolve("novel.md"));
            }
        } catch (ClaraException exception) {
            System.err.println("模型调用失败：" + exception.getError().getCode()
                    + (exception.getError().getProviderCode() == null ? ""
                    : " (HTTP " + exception.getError().getProviderCode() + ")"));
            System.exit(2);
        } catch (Exception exception) {
            System.err.println("运行失败：" + exception.getMessage());
            System.exit(1);
        }
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        for (int i = 0; i < args.length; i++) {
            String option = args[i];
            if ("--help".equals(option) || "-h".equals(option)) {
                result.put("help", "true"); continue;
            }
            if ("--interactive".equals(option)) {
                result.put("interactive", "true"); continue;
            }
            if (!option.startsWith("--") || i + 1 >= args.length || args[i + 1].startsWith("--"))
                throw new IllegalArgumentException("invalid argument: " + option);
            String key = option.substring(2);
            if (result.put(key, args[++i]) != null) throw new IllegalArgumentException("duplicate: " + option);
        }
        return result;
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("--" + key + " is required");
        return value;
    }
    private static String value(Map<String, String> values, String key, String fallback) {
        String value = values.get(key); return value == null ? fallback : value;
    }
    private static int integer(Map<String, String> values, String key, int fallback) {
        String value = values.get(key);
        try { return value == null ? fallback : Integer.parseInt(value); }
        catch (NumberFormatException exception) { throw new IllegalArgumentException("--" + key + " must be an integer"); }
    }
    private static int parameter(ProviderCapabilityConfiguration capability, String key, int fallback) {
        Object value = capability.getParameters().get(key);
        return value instanceof Number ? ((Number) value).intValue() : fallback;
    }
    private static void usage() {
        System.out.println("交互模式（推荐）：run-novel-agent.ps1 --interactive [--output output/novel]"
                + " [--image-model image|image-fast|image-quality] [--chapters 3] [--words 1500]");
        System.out.println("直接模式：run-novel-agent.ps1 --title 书名 --premise 核心创意 [--genre 题材]"
                + " [--image-model image|image-fast|image-quality] [--chapters 3] [--words 1500] [--output output/novel]");
        System.out.println("不传参数时默认进入交互模式；对话中输入 /done 整理任务，输入 /quit 退出。");
        System.out.println("HTTP 403 时会在本机控制台打印完整请求头和请求体，包括 Authorization。 ");
        System.out.println("重复执行相同命令会从 output 目录中的 project.json 继续。");
    }
}
