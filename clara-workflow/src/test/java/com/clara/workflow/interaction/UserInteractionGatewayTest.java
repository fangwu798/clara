package com.clara.workflow.interaction;

import com.clara.core.interaction.IAnswerSchemaValidator;
import com.clara.core.interaction.IInteractionResumeCoordinator;
import com.clara.core.interaction.ITimeSource;
import com.clara.dto.interaction.InteractionContext;
import com.clara.dto.interaction.InteractionRequest;
import com.clara.dto.interaction.InteractionResponse;
import com.clara.dto.interaction.Question;
import com.clara.dto.interaction.ResumeOutcome;
import com.clara.enums.interaction.InteractionStateEnum;
import com.clara.enums.interaction.InteractionTypeEnum;
import org.junit.Test;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * {@link UserInteractionGateway} 的单元测试：发起交互请求落入底层服务。
 */
public class UserInteractionGatewayTest {

    @Test
    public void delegatesRequestToService() {
        IInteractionResumeCoordinator coordinator = new IInteractionResumeCoordinator() {
            @Override
            public ResumeOutcome resumeAndRecord(InteractionRequest trigger,
                    List<InteractionResponse> responses, Map<String, Object> scopeUpdates) {
                return new ResumeOutcome("SUCCEEDED", 1L);
            }

            @Override
            public void interactionCancelled(InteractionRequest request, String reason) {
            }
        };
        ITimeSource timeSource = new ITimeSource() {
            @Override
            public long currentTimeMillis() {
                return 100L;
            }
        };
        IAnswerSchemaValidator schemaValidator = new IAnswerSchemaValidator() {
            @Override
            public boolean isValid(String schema, Map<String, Object> answer) {
                return true;
            }
        };
        InMemoryInteractionService service = new InMemoryInteractionService(coordinator, schemaValidator,
                timeSource, 5);
        UserInteractionGateway gateway = new UserInteractionGateway(service);

        InteractionContext context = new InteractionContext("i1", "r1", "inst-1", "node-1", 0L, 100L, 10000L);
        Question question = Question.builder(InteractionTypeEnum.TEXT_INPUT, "Q")
                .required(true).scopeKey("answer").maxTextLength(50).build();

        InteractionRequest request = gateway.request(context, question);

        assertEquals(InteractionStateEnum.PENDING, request.getState());
        assertEquals(1, service.pending("r1").size());
        assertEquals("i1", service.pending("r1").get(0).getContext().getInteractionId());
    }
}
