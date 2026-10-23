package com.clara.workflow;

import com.clara.core.memory.IWindowMemory;
import com.clara.core.message.IContent;
import com.clara.core.model.IRequest;
import com.clara.core.model.IResponse;
import com.clara.core.model.IStreamHandle;
import com.clara.core.model.IStreamModel;
import com.clara.core.model.IStreamObserver;
import com.clara.core.skill.IPromptProvider;
import com.clara.core.skill.IToolInvoker;
import com.clara.dto.memory.WindowPolicy;
import com.clara.kernel.http.HttpRequest;
import com.clara.kernel.http.HttpResponse;
import com.clara.kernel.message.delta.UserContent;
import com.clara.kernel.message.delta.UserVisionContent;
import com.clara.orchestration.DefaultConversationOrchestrator;
import com.clara.orchestration.capability.CapabilityDispatcher;
import com.clara.orchestration.context.ContextAssembler;
import com.clara.orchestration.context.SemanticRecallCoordinator;
import com.clara.orchestration.context.WindowCompressionCoordinator;
import com.clara.orchestration.record.CallRecordPublisher;
import com.clara.orchestration.skill.SkillMatchCoordinator;
import com.clara.orchestration.tool.ToolRoundTripCoordinator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 工作流动作测试夹具：用预置响应的假模型入口装配真实的 {@link DefaultConversationOrchestrator}，
 * 让 AGENT / REVIEW 动作走完整调用链路而不访问网络。
 */
final class WorkflowActionTestSupport {

    private WorkflowActionTestSupport() {
    }

    /**
     * 使用给定响应装配编排器，并返回可读取「实际下发请求」的入口。
     */
    static FakeModel orchestrator(HttpResponse response) {
        FakeModel model = new FakeModel(response);
        ContextAssembler assembler = new ContextAssembler(
                new WindowCompressionCoordinator(null),
                new SemanticRecallCoordinator(null, null),
                new SkillMatchCoordinator(null, null, null));
        IToolInvoker invoker = invocation -> null;
        DefaultConversationOrchestrator orchestrator = new DefaultConversationOrchestrator(
                (conversationId, provider, modelName) -> new Memory(conversationId),
                assembler,
                new CapabilityDispatcher(model),
                new ToolRoundTripCoordinator(invoker),
                new CallRecordPublisher(null),
                null);
        model.orchestrator = orchestrator;
        return model;
    }

    /**
     * 提示词提供器：返回带前缀的固定文本，便于断言动作确实取了提示词。
     */
    static IPromptProvider prompts(final String prefix) {
        return new IPromptProvider() {
            @Override
            public String prompt(String promptId) {
                return prefix + "(" + promptId + ")";
            }

            @Override
            public String summarizePrompt() {
                return prefix + "(summarize)";
            }
        };
    }

    /**
     * 取实际下发的用户输入：对话能力在 messages 末条，非对话能力在 prompt 字段。
     */
    static String userInput(HttpRequest request) {
        List<IContent> messages = request.getMessages();
        if (messages == null || messages.isEmpty()) {
            return request.getPrompt();
        }
        IContent last = messages.get(messages.size() - 1);
        if (last instanceof UserContent) {
            return ((UserContent) last).getContent();
        }
        if (last instanceof UserVisionContent) {
            return ((UserVisionContent) last).getText();
        }
        return String.valueOf(last);
    }

    static HttpResponse response(String content, Integer input, Integer output) {
        HttpResponse response = new HttpResponse();
        response.setContent(content);
        response.setInputTokens(input);
        response.setOutputTokens(output);
        response.setTotalTokens(input == null || output == null ? null : input + output);
        return response;
    }

    /**
     * 写一份包含 CHAT / IMAGE / VIDEO 能力的最小平台配置，返回文件路径。
     */
    static Path providerConfig(Path directory, String modelId) throws Exception {
        String yaml = "provider: DEEPSEEK\n"
                + "credential:\n"
                + "  secretRef: deepseek\n"
                + "  environmentVariable: CLARA_DEEPSEEK_API_KEY\n"
                + "transport:\n"
                + "  allowedOrigins:\n"
                + "    - https://api.deepseek.com\n"
                + "  connectTimeoutMillis: 10000\n"
                + "  readTimeoutMillis: 180000\n"
                + "  maxRequestBytes: 8388608\n"
                + "  maxResponseBytes: 33554432\n"
                + "  allowHttp: false\n"
                + "capabilities:\n"
                + "  CHAT:\n"
                + "    endpoint: https://api.deepseek.com/responses\n"
                + "    modelId: " + modelId + "\n"
                + "    apiProfile: RESPONSE\n"
                + "    parameters:\n"
                + "      maxTokens: 1000\n"
                + "  IMAGE:\n"
                + "    endpoint: https://api.deepseek.com/images/generations\n"
                + "    modelId: image-model\n"
                + "    apiProfile: ark-v3-images\n"
                + "    parameters:\n"
                + "      maxPromptChars: 32000\n"
                + "      maxOutputs: 1\n"
                + "      maxSourceImages: 2\n"
                + "  VIDEO:\n"
                + "    endpoint: https://api.deepseek.com/videos\n"
                + "    taskQueryEndpoint: https://api.deepseek.com/videos/{id}\n"
                + "    modelId: video-model\n"
                + "    apiProfile: ark-v3-content-tasks\n"
                + "    parameters:\n"
                + "      pollIntervalMillis: 10\n"
                + "      maxPollAttempts: 1\n";
        Path file = directory.resolve(modelId + ".yml");
        Files.write(file, yaml.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /**
     * 假模型入口：同步与流式调用都返回同一份预置响应，并记录收到的请求。
     */
    static final class FakeModel implements IStreamModel {

        private final HttpResponse response;
        private final List<IRequest> requests = new ArrayList<>();
        private DefaultConversationOrchestrator orchestrator;
        private RuntimeException firstFailure;

        private FakeModel(HttpResponse response) {
            this.response = response;
        }

        DefaultConversationOrchestrator orchestrator() {
            return orchestrator;
        }

        List<IRequest> requests() {
            return requests;
        }

        void failOnce(RuntimeException failure) {
            this.firstFailure = failure;
        }

        private void failIfNeeded() {
            if (firstFailure != null) {
                RuntimeException failure = firstFailure;
                firstFailure = null;
                throw failure;
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R extends IResponse> R chat(IRequest request, Class<R> responseType) {
            requests.add(request);
            failIfNeeded();
            return (R) response;
        }

        @Override
        public <R extends IResponse> IStreamHandle chat(IRequest request, Class<R> responseType,
                IStreamObserver observer) {
            requests.add(request);
            failIfNeeded();
            return new IStreamHandle() {
                @Override
                public boolean cancel() {
                    return false;
                }

                @Override
                public boolean isDone() {
                    return true;
                }

                @Override
                public IResponse result() {
                    return response;
                }
            };
        }
    }

    /**
     * 内存窗口：只需保存消息，窗口策略不参与断言。
     */
    private static final class Memory implements IWindowMemory {

        private final String conversationId;
        private final List<IContent> history = new ArrayList<>();

        private Memory(String conversationId) {
            this.conversationId = conversationId;
        }

        @Override
        public String conversationId() {
            return conversationId;
        }

        @Override
        public void append(List<IContent> items) {
            history.addAll(items);
        }

        @Override
        public List<IContent> messages() {
            return history;
        }

        @Override
        public List<IContent> window(WindowPolicy policy, com.clara.core.memory.ITokenCountEstimator estimator) {
            return history;
        }

        @Override
        public void clear() {
            history.clear();
        }
    }
}