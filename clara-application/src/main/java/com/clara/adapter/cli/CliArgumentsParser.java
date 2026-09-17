package com.clara.adapter.cli;

import com.clara.workspace.api.WorkspaceAccessMode;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 将CLI参数数据解析为领域对象。
 */
public final class CliArgumentsParser {
    public CliOptions parse(String[] args) {
        if (args == null || args.length == 0) {
            throw new IllegalArgumentException("command is required");
        }
        CliCommand command = "run".equals(args[0]) ? CliCommand.RUN : "resume".equals(args[0]) ? CliCommand.RESUME : null;
        if (command == null) {
            throw new IllegalArgumentException("unknown command");
        }
        Map<String, String> values = new LinkedHashMap<String, String>();
        Set<String> flags = new HashSet<String>();
        for (int i = 1; i < args.length; i++) {
            String key = args[i];
            if ("--approve-changes".equals(key) || "--no-color".equals(key)) {
                if (!flags.add(key)) {
                    throw new IllegalArgumentException("duplicate option");
                }
                continue;
            }
            if (!key.startsWith("--") || i + 1 >= args.length || args[i + 1].startsWith("--") || values.put(key, args[++i]) != null) {
                throw new IllegalArgumentException("invalid option");
            }
        }
        Set<String> allowed = new HashSet<String>(Arrays.asList("--workspace", "--goal", "--skill", "--write-mode", "--ui", "--output", "--config", "--tenant", "--user", "--run-id", "--interaction-id", "--answer-file"));
        for (String key : values.keySet())
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("unknown option " + key);
            }
        Path workspace = real(values.get("--workspace"), true), answer = real(values.get("--answer-file"), false), config = real(values.get("--config"), false);
        String tenant = value(values, "--tenant", "local"), user = value(values, "--user", "console"), run = value(values, "--run-id", command == CliCommand.RUN ? UUID.randomUUID().toString() : null);
        WorkspaceAccessMode mode = mode(value(values, "--write-mode", "preview"));
        CliUiMode ui = ui(value(values, "--ui", "auto"));
        CliOutputFormat output = output(value(values, "--output", "text"));
        boolean approve = flags.contains("--approve-changes");
        if (approve && mode != WorkspaceAccessMode.APPLY_WRITE) {
            throw new IllegalArgumentException("approval requires apply mode");
        }
        String interaction = values.get("--interaction-id");
        if (command == CliCommand.RESUME && (blank(run) || blank(interaction) || answer == null)) {
            throw new IllegalArgumentException("resume fields are required");
        }
        return new CliOptions(command, workspace, answer, config, tenant, user, run, values.get("--goal"), values.get("--skill"), interaction, mode, ui, output, approve, flags.contains("--no-color"));
    }

    private static Path real(String value, boolean directory) {
        if (blank(value)) {
            if (directory) {
                throw new IllegalArgumentException("workspace is required");
            }
            return null;
        }
        try {
            Path path = Paths.get(value).toAbsolutePath().normalize().toRealPath();
            if (directory && !Files.isDirectory(path) || !directory && !Files.isRegularFile(path)) {
                throw new IllegalArgumentException("path type is invalid");
            }
            return path;
        } catch (IOException e) {
            throw new IllegalArgumentException("path is not accessible");
        }
    }

    private static String value(Map<String, String> map, String key, String fallback) {
        String value = map.get(key);
        return value == null ? fallback : value;
    }

    private static WorkspaceAccessMode mode(String v) {
        if ("read-only".equals(v)) return WorkspaceAccessMode.READ_ONLY;
        if ("preview".equals(v)) return WorkspaceAccessMode.PREVIEW_WRITE;
        if ("apply".equals(v)) return WorkspaceAccessMode.APPLY_WRITE;
        throw new IllegalArgumentException("invalid write mode");
    }

    private static CliUiMode ui(String v) {
        if ("auto".equals(v)) return CliUiMode.AUTO;
        if ("tui".equals(v)) return CliUiMode.TUI;
        if ("plain".equals(v)) return CliUiMode.PLAIN;
        if ("json-stream".equals(v)) return CliUiMode.JSON_STREAM;
        if ("json-detached".equals(v)) return CliUiMode.JSON_DETACHED;
        throw new IllegalArgumentException("invalid ui mode");
    }

    private static CliOutputFormat output(String v) {
        if ("text".equals(v)) return CliOutputFormat.TEXT;
        if ("json".equals(v)) return CliOutputFormat.JSON;
        throw new IllegalArgumentException("invalid output");
    }

    private static boolean blank(String v) {
        return v == null || v.trim().isEmpty();
    }
}
