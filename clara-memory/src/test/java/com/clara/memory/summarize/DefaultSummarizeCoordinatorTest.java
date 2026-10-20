package com.clara.memory.summarize;

import com.clara.core.message.IContent;
import com.clara.core.model.IStreamHandle;
import com.clara.core.model.IStreamModel;
import com.clara.core.model.IStreamObserver;
import com.clara.core.skill.IPromptProvider;
import com.clara.kernel.http.HttpResponse;
import com.clara.kernel.message.delta.SystemContent;
import com.clara.kernel.message.delta.UserContent;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Summarisation coordinator wiring without a real model call.
 */
public class DefaultSummarizeCoordinatorTest {

    @Test
    public void summarisesEvictedMessagesWithModelOutput() {
        RecordingModel model = new RecordingModel("summary");
        DefaultSummarizeCoordinator coordinator = new DefaultSummarizeCoordinator(model, prompt("PROMPT"));

        IContent result = coordinator.summarize(Arrays.<IContent>asList(
                new UserContent("one"), null, new UserContent("two")));

        assertTrue(result instanceof SystemContent);
        assertEquals("summary", ((SystemContent) result).getContent());
        assertEquals(3, model.request.getMessages().size());
        assertEquals("PROMPT", ((SystemContent) model.request.getMessages().get(0)).getContent());
    }

    @Test
    public void returnsEmptySystemContentWhenModelReturnsNull() {
        RecordingModel model = new RecordingModel(null);
        IContent result = new DefaultSummarizeCoordinator(model, prompt("P"))
                .summarize(java.util.Collections.<IContent>emptyList());
        assertEquals("", ((SystemContent) result).getContent());
        assertEquals(1, model.request.getMessages().size());
    }

    @Test
    public void rejectsNullDependenciesAndInput() {
        assertIllegalArgument(() -> new DefaultSummarizeCoordinator(null, prompt("p")));
        assertIllegalArgument(() -> new DefaultSummarizeCoordinator(new RecordingModel("x"), null));
        assertIllegalArgument(() -> new DefaultSummarizeCoordinator(new RecordingModel("x"), prompt("p"))
                .summarize(null));
    }

    private static IPromptProvider prompt(String text) {
        return new IPromptProvider() {
            @Override
            public String prompt(String promptId) {
                return text;
            }

            @Override
            public String summarizePrompt() {
                return text;
            }
        };
    }

    private static void assertIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    private static final class RecordingModel implements IStreamModel {
        private final String content;
        private com.clara.kernel.http.HttpRequest request;

        private RecordingModel(String content) {
            this.content = content;
        }

        @Override
        public <R extends com.clara.core.model.IResponse> R chat(
                com.clara.core.model.IRequest request, Class<R> responseType) {
            this.request = (com.clara.kernel.http.HttpRequest) request;
            HttpResponse response = new HttpResponse();
            response.setContent(content);
            return responseType.cast(response);
        }

        @Override
        public <R extends com.clara.core.model.IResponse> IStreamHandle chat(
                com.clara.core.model.IRequest request, Class<R> responseType, IStreamObserver observer) {
            throw new UnsupportedOperationException();
        }
    }
}
