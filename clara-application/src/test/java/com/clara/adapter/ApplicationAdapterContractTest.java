package com.clara.adapter;

import com.clara.adapter.cli.*;
import com.clara.adapter.rest.*;
import com.clara.core.interaction.IAnswerSchemaValidator;
import com.clara.core.interaction.IInteractionResumeCoordinator;
import com.clara.core.interaction.ITimeSource;
import com.clara.dto.interaction.InteractionContext;
import com.clara.dto.interaction.InteractionOption;
import com.clara.dto.interaction.InteractionRequest;
import com.clara.dto.interaction.InteractionResponse;
import com.clara.dto.interaction.Question;
import com.clara.dto.interaction.ResumeOutcome;
import com.clara.enums.interaction.InteractionTypeEnum;
import com.clara.exception.ClaraException;
import com.clara.exception.ClaraError;
import com.clara.exception.ErrorCategory;
import com.clara.workflow.interaction.InMemoryInteractionService;
import com.clara.workspace.application.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import org.junit.*;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

public class ApplicationAdapterContractTest {
    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();
    private Path workspace;
    private ObjectMapper json;
    private InteractionRequest interaction;

    @Before
    public void setUp() throws Exception {
        workspace = folder.newFolder("workspace").toPath().toRealPath();
        json = new ObjectMapper();
        InMemoryInteractionService service = new InMemoryInteractionService(new IInteractionResumeCoordinator() {
            public ResumeOutcome resumeAndRecord(InteractionRequest request, List<InteractionResponse> responses, Map<String, Object> updates) {
                return null;
            }

            public void interactionCancelled(InteractionRequest request, String reason) {
            }
        }, new IAnswerSchemaValidator() {
            public boolean isValid(String schema, Map<String, Object> answer) {
                return true;
            }
        }, new ITimeSource() {
            public long currentTimeMillis() {
                return 100L;
            }
        }, 10);
        interaction = service.request(new InteractionContext("i1", "run", "instance", "task", 2L, 10L, 1000L), new Question(InteractionTypeEnum.TEXT_INPUT, "Name?", Collections.<InteractionOption>emptyList(), "{}", Collections.<String, Object>emptyMap(), true, "name", 100));
    }

    @Test
    public void restAndSsePreserveWaitingContract() throws Exception {
        WorkspaceRunResult waiting = waiting();
        WorkspaceRunController controller = new WorkspaceRunController(new FakeRunner(waiting, success()));
        RestResponse<WorkspaceRunResult> response = controller.run(runRequest());
        assertEquals(202, response.getStatus());
        List<SseEvent> events = new WorkspaceRunSseAdapter(json).events(waiting);
        assertEquals(1, events.size());
        assertEquals("interaction.required", events.get(0).getType());
        assertTrue(events.get(0).encode().contains("event: interaction.required"));
        assertTrue(events.get(0).getData().contains("\"protocolVersion\":1"));
        assertTrue(events.get(0).getData().contains("\"interactionId\":\"i1\""));
    }

    @Test
    public void detachedWritesExactlyOneJsonObjectAndWaitingExitCode() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(), errors = new ByteArrayOutputStream();
        ClaraCli cli = new ClaraCli(new FakeRunner(waiting(), success()), json);
        int exit = cli.execute(new String[]{"run", "--workspace", workspace.toString(), "--run-id", "run", "--goal", "goal", "--ui", "json-detached", "--output", "json"}, new ByteArrayInputStream(new byte[0]), stream(bytes), stream(errors));
        assertEquals(CliExitCodes.WAITING_INPUT, exit);
        String output = utf8(bytes).trim();
        assertEquals(1, output.split("\\r?\\n").length);
        Map<String, Object> value = json.readValue(output, new TypeReference<Map<String, Object>>() {
        });
        assertEquals("WAITING_INPUT", value.get("status"));
        assertEquals("", utf8(errors));
    }

    @Test
    public void jsonStreamUsesStrictSequenceAckAndCompletion() throws Exception {
        String command = "not-json\n{\"type\":\"interaction.response\",\"commandId\":\"c1\",\"interactionId\":\"i1\",\"expectedInstanceVersion\":2,\"idempotencyKey\":\"key\",\"answer\":{\"text\":\"Ada\"}}\n";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ClaraCli cli = new ClaraCli(new FakeRunner(waiting(), success()), json);
        int exit = cli.execute(new String[]{"run", "--workspace", workspace.toString(), "--run-id", "run", "--goal", "goal", "--ui", "json-stream"}, new ByteArrayInputStream(command.getBytes(StandardCharsets.UTF_8)), stream(bytes), stream(new ByteArrayOutputStream()));
        assertEquals(0, exit);
        String[] lines = utf8(bytes).trim().split("\\r?\\n");
        assertEquals(4, lines.length);
        long expected = 1;
        List<String> types = new ArrayList<String>();
        for (String line : lines) {
            Map<String, Object> event = json.readValue(line, new TypeReference<Map<String, Object>>() {
            });
            assertEquals(expected++, ((Number) event.get("sequence")).longValue());
            types.add((String) event.get("type"));
        }
        assertEquals(Arrays.asList("interaction.required", "error", "ack", "run.completed"), types);
    }

    @Test
    public void mapsAuthorizationFailureToStableRestAndCliCodes() throws Exception {
        final ClaraException denied = new ClaraException(new ClaraError("DENIED", ErrorCategory.AUTHORIZATION, "denied", false, null, null, null, null, Collections.<String, String>emptyMap()));
        WorkspaceBusinessRunner runner = new WorkspaceBusinessRunner() {
            public WorkspaceRunResult run(WorkspaceRunRequest r) {
                throw denied;
            }

            public WorkspaceRunResult resume(WorkspaceResumeRequest r) {
                throw denied;
            }

            public void cancel(String r, String why) {
                throw denied;
            }
        };
        assertEquals(403, new WorkspaceRunController(runner).run(runRequest()).getStatus());
        int exit = new ClaraCli(runner, json).execute(new String[]{"run", "--workspace", workspace.toString(), "--run-id", "run", "--goal", "goal"}, new ByteArrayInputStream(new byte[0]), stream(new ByteArrayOutputStream()), stream(new ByteArrayOutputStream()));
        assertEquals(CliExitCodes.DENIED, exit);
    }

    @Test
    public void tuiFallsBackToPlainAndApprovalRequiresApplyMode() throws Exception {
        ClaraCli cli = new ClaraCli(new FakeRunner(success(), success()), json);
        ByteArrayOutputStream output = new ByteArrayOutputStream(), errors = new ByteArrayOutputStream();
        int exit = cli.execute(new String[]{"run", "--workspace", workspace.toString(), "--run-id", "run", "--goal", "goal", "--ui", "tui"}, new ByteArrayInputStream(new byte[0]), stream(output), stream(errors));
        assertEquals(0, exit);
        assertTrue(utf8(output).contains("SUCCEEDED run=run"));
        assertTrue(utf8(errors).contains("using plain mode"));
        assertEquals(CliExitCodes.INVALID, cli.execute(new String[]{"run", "--workspace", workspace.toString(), "--approve-changes"}, new ByteArrayInputStream(new byte[0]), stream(new ByteArrayOutputStream()), stream(new ByteArrayOutputStream())));
    }

    private WorkspaceRunRequest runRequest() {
        return new WorkspaceRunRequest("user", "run", workspace, "goal", null, com.clara.workspace.api.WorkspaceAccessMode.PREVIEW_WRITE, false, null, null, null);
    }

    private WorkspaceRunResult waiting() {
        return new WorkspaceRunResult("run", WorkspaceRunStatus.WAITING_INPUT, 2L, Collections.<String, Object>emptyMap(), Collections.singletonList(interaction));
    }

    private static WorkspaceRunResult success() {
        return new WorkspaceRunResult("run", WorkspaceRunStatus.SUCCEEDED, 3L, Collections.<String, Object>singletonMap("result", "done"), null);
    }

    private static PrintStream stream(ByteArrayOutputStream bytes) throws Exception {
        return new PrintStream(bytes, true, "UTF-8");
    }

    private static String utf8(ByteArrayOutputStream bytes) throws Exception {
        return bytes.toString("UTF-8");
    }

    private static final class FakeRunner implements WorkspaceBusinessRunner {
        private final WorkspaceRunResult initial, resumed;

        private FakeRunner(WorkspaceRunResult initial, WorkspaceRunResult resumed) {
            this.initial = initial;
            this.resumed = resumed;
        }

        public WorkspaceRunResult run(WorkspaceRunRequest request) {
            return initial;
        }

        public WorkspaceRunResult resume(WorkspaceResumeRequest request) {
            assertEquals("i1", request.getInteractionId());
            return resumed;
        }

        public void cancel(String run, String reason) {
        }
    }
}
