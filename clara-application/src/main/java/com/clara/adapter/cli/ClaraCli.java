package com.clara.adapter.cli;
import com.clara.enums.cli.CliCommand;
import com.clara.enums.cli.CliUiMode;
import com.clara.enums.cli.CliOutputFormat;
import com.clara.enums.workspace.WorkspaceRunStatus;

import com.clara.exception.ClaraException;
import com.clara.exception.ClaraError;
import com.clara.dto.interaction.InteractionRequest;
import com.clara.workspace.api.WorkspaceBusinessRunner;
import com.clara.workspace.application.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * 提供 Clara 命令行应用的启动入口。
 */
public final class ClaraCli {
    private static final int MAX_LINE_BYTES = 1048576;
    private final WorkspaceBusinessRunner runner;
    private final CliArgumentsParser parser;
    private final ObjectMapper json;

    public ClaraCli(WorkspaceBusinessRunner runner, ObjectMapper json) {
        if (runner == null || json == null) {
            throw new IllegalArgumentException();
        }
        this.runner = runner;
        this.json = json;
        this.parser = new CliArgumentsParser();
    }

    public int execute(String[] args, InputStream input, PrintStream output, PrintStream error) {
        CliOptions options;
        try {
            options = parser.parse(args);
        } catch (IllegalArgumentException e) {
            error.println(e.getMessage());
            return CliExitCodes.INVALID;
        }
        try {
            if (options.getUi() == CliUiMode.TUI) {
                error.println("TUI unavailable; using plain mode");
            }
            if (options.getCommand() == CliCommand.RESUME) {
                return render(options, resume(options), input, output);
            }
            WorkspaceRunRequest request = new WorkspaceRunRequest(options.getUser(), options.getRunId(), options.getWorkspace(), options.getGoal(), options.getSkill(), options.getMode(), options.isApprove(), Collections.<String, Object>emptyMap(), options.getProvider(), options.getModel());
            return render(options, runner.run(request), input, output);
        } catch (ClaraException e) {
            writeError(options, output, error, e.getError());
            return CliExitCodes.from(e.getError());
        } catch (IllegalArgumentException e) {
            error.println(e.getMessage());
            return CliExitCodes.INVALID;
        } catch (IOException e) {
            error.println("input/output failure");
            return CliExitCodes.INVALID;
        }
    }

    private WorkspaceRunResult resume(CliOptions options) throws IOException {
        Map<String, Object> value = json.readValue(Files.readAllBytes(options.getAnswerFile()), new TypeReference<Map<String, Object>>() {});
        return runner.resume(resumeRequest(options, value));
    }

    private WorkspaceResumeRequest resumeRequest(CliOptions options, Map<String, Object> value) {
        Object version = value.get("expectedInstanceVersion");
        Object key = value.get("idempotencyKey");
        Object answer = value.get("answer");
        if (!(version instanceof Number) || !(key instanceof String) || !(answer instanceof Map)) {
            throw new IllegalArgumentException("answer file is invalid");
        }
        return new WorkspaceResumeRequest(options.getUser(), options.getRunId(), options.getWorkspace(), options.getInteractionId(), ((Number) version).longValue(), (String) key, stringMap((Map<?, ?>) answer), options.getProvider(), options.getModel());
    }

    private int render(CliOptions options, WorkspaceRunResult result, InputStream input, PrintStream out) throws IOException {
        if (options.getUi() == CliUiMode.JSON_STREAM) return stream(options, result, input, out);
        if (options.getUi() == CliUiMode.JSON_DETACHED) {
            json.writeValue(out, result);
            out.println();
            out.flush();
        } else if (options.getOutput() == CliOutputFormat.JSON) {
            json.writeValue(out, result);
            out.println();
            out.flush();
        } else {
            out.println(result.getStatus().name() + " run=" + result.getRunId());
            for (InteractionRequest interaction : result.getInteractions())
                out.println("INPUT REQUIRED: " + interaction.getQuestion().getText());
        }
        return exit(result);
    }

    private int stream(CliOptions options, WorkspaceRunResult initial, InputStream input, PrintStream out) throws IOException {
        NdjsonProtocolWriter protocol = new NdjsonProtocolWriter(json, out, MAX_LINE_BYTES);
        WorkspaceRunResult current = initial;
        emit(protocol, current);
        if (current.getStatus() != WorkspaceRunStatus.WAITING_INPUT) return exit(current);
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.getBytes(StandardCharsets.UTF_8).length > MAX_LINE_BYTES) {
                protocol.event("error", current.getRunId(), null, error("INVALID_ARGUMENT", "command line exceeds limit"));
                continue;
            }
            Map<String, Object> command;
            try {
                command = json.readValue(line, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception malformed) {
                protocol.event("error", current.getRunId(), null, error("INVALID_ARGUMENT", "invalid command"));
                continue;
            }
            String type = text(command.get("type")), commandId = text(command.get("commandId"));
            if (type == null || commandId == null) {
                protocol.event("error", current.getRunId(), null, error("INVALID_ARGUMENT", "command envelope is invalid"));
                continue;
            }
            try {
                if ("run.cancel".equals(type)) {
                    runner.cancel(current.getRunId(), "cli command");
                    protocol.event("ack", current.getRunId(), null, Collections.singletonMap("commandId", commandId));
                    return CliExitCodes.FAILED;
                }
                if (!"interaction.response".equals(type)) {
                    throw new IllegalArgumentException("unknown command");
                }
                String interaction = text(command.get("interactionId")), key = text(command.get("idempotencyKey"));
                Object expected = command.get("expectedInstanceVersion"), answer = command.get("answer");
                if (interaction == null || key == null || !(expected instanceof Number) || !(answer instanceof Map)) {
                    throw new IllegalArgumentException("response command is invalid");
                }
                WorkspaceResumeRequest resume = new WorkspaceResumeRequest(options.getUser(), current.getRunId(), options.getWorkspace(), interaction, ((Number) expected).longValue(), key, stringMap((Map<?, ?>) answer), options.getProvider(), options.getModel());
                current = runner.resume(resume);
                protocol.event("ack", current.getRunId(), interaction, Collections.singletonMap("commandId", commandId));
                emit(protocol, current);
                if (current.getStatus() != WorkspaceRunStatus.WAITING_INPUT) return exit(current);
            } catch (ClaraException e) {
                protocol.event("error", current.getRunId(), text(command.get("interactionId")), e.getError());
            } catch (IllegalArgumentException e) {
                protocol.event("error", current.getRunId(), text(command.get("interactionId")), error("INVALID_ARGUMENT", e.getMessage()));
            }
        }
        return CliExitCodes.WAITING_INPUT;
    }

    private static void emit(NdjsonProtocolWriter protocol, WorkspaceRunResult result) {
        if (result.getStatus() == WorkspaceRunStatus.WAITING_INPUT) {
            for (InteractionRequest interaction : result.getInteractions())
                protocol.event("interaction.required", result.getRunId(), interaction.getContext().getInteractionId(), interaction);
        } else protocol.event("run.completed", result.getRunId(), null, result);
    }

    private void writeError(CliOptions options, PrintStream out, PrintStream err, ClaraError failure) {
        if (options.getUi() == CliUiMode.JSON_DETACHED) {
            try {
                Map<String, Object> value = new LinkedHashMap<String, Object>();
                value.put("status", "ERROR");
                value.put("error", failure);
                json.writeValue(out, value);
                out.println();
            } catch (IOException ignored) {
                err.println(failure.getCode());
            }
        } else if (options.getUi() == CliUiMode.JSON_STREAM)
            new NdjsonProtocolWriter(json, out, MAX_LINE_BYTES).event("error", options.getRunId(), null, failure);
        else err.println(failure.getCode() + ": " + failure.getSafeMessage());
    }

    private static int exit(WorkspaceRunResult result) {
        if (result.getStatus() == WorkspaceRunStatus.SUCCEEDED) return CliExitCodes.SUCCESS;
        if (result.getStatus() == WorkspaceRunStatus.WAITING_INPUT) return CliExitCodes.WAITING_INPUT;
        return CliExitCodes.FAILED;
    }

    private static Map<String, Object> stringMap(Map<?, ?> value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException("object key is invalid");
            }
            result.put((String) entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static String text(Object value) {
        return value instanceof String && !((String) value).trim().isEmpty() ? (String) value : null;
    }

    private static Map<String, Object> error(String code, String message) {
        Map<String, Object> value = new LinkedHashMap<String, Object>();
        value.put("code", code);
        value.put("message", message);
        return value;
    }
}
