package com.clara.skill.tool.script;

import com.clara.skill.SkillErrors;
import com.clara.skill.tool.IScriptExecutor;
import com.clara.skill.tool.ScriptExecutionSpec;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 脚本执行器的公共骨架：独立进程 + stdin/stdout JSON + 超时 / 取消 / 失败归类。
 *
 * <p>各运行环境差异（解释器路径、命令行、环境变量）由子类通过 {@link #extension()} 与
 * {@link #buildCommand(ScriptExecutionSpec, Path)} 表达，进程执行协议保持一致：
 * 入参经 stdin 以 JSON 下发，结果从 stdout 读取，stderr 单独捕获用于错误定位。
 */
abstract class AbstractScriptExecutor implements IScriptExecutor {

    private static final long CANCEL_POLL_MILLIS = 100L;
    private static final long STREAM_JOIN_MILLIS = 2000L;

    @Override
    public final String execute(ScriptExecutionSpec spec) {
        if (spec == null) {
            throw new IllegalArgumentException("spec must not be null");
        }
        throwIfCancelled(spec);

        Path scriptFile;
        File workingDir;
        Path tempDir = null;
        File direct = new File(spec.getScript());
        if (direct.isFile()) {
            scriptFile = direct.toPath().toAbsolutePath();
            workingDir = scriptFile.getParent() == null ? new File(".") : scriptFile.getParent().toFile();
        } else {
            try {
                tempDir = Files.createTempDirectory("clara-skill");
            } catch (IOException e) {
                throw SkillErrors.executionFailed("无法创建脚本工作目录：" + e.getMessage());
            }
            workingDir = tempDir.toFile();
            scriptFile = tempDir.resolve("script" + extension());
            try {
                Files.write(scriptFile, spec.getScript().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                cleanup(tempDir);
                throw SkillErrors.executionFailed("无法写入内联脚本：" + e.getMessage());
            }
        }

        Process process = null;
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        Thread stdoutThread = null;
        Thread stderrThread = null;
        try {
            process = new ProcessBuilder(buildCommand(spec, scriptFile)).directory(workingDir).start();
            stdoutThread = drain(process.getInputStream(), stdout);
            stderrThread = drain(process.getErrorStream(), stderr);
            writeStdin(process, spec.getArgumentsJson());
            int exit = await(process, spec);
            join(stdoutThread);
            join(stderrThread);
            if (exit != 0) {
                throw SkillErrors.executionFailed("脚本退出码非零：" + exit + stderrMessage(stderr));
            }
            return stdout.toString().trim();
        } catch (IOException e) {
            throw SkillErrors.executionFailed("脚本进程启动或读写失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw SkillErrors.executionFailed("脚本执行被中断");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (tempDir != null) {
                cleanup(tempDir);
            }
        }
    }

    /**
     * 内联脚本临时文件扩展名。
     *
     * @return 以点开头的扩展名，非 null
     */
    protected abstract String extension();

    /**
     * 组装命令行。
     *
     * @param spec       执行描述，非 null
     * @param scriptFile 已落盘的脚本文件，非 null
     * @return 命令行参数列表，非 null 且非空
     */
    protected abstract List<String> buildCommand(ScriptExecutionSpec spec, Path scriptFile);

    /**
     * 等待进程结束，期间轮询取消信号与硬超时；超时或取消时销毁进程（含子进程）。
     */
    private int await(Process process, ScriptExecutionSpec spec) throws InterruptedException {
        long deadline = System.currentTimeMillis() + spec.getTimeoutMillis();
        while (true) {
            if (spec.getCancellationToken() != null && spec.getCancellationToken().isCancellationRequested()) {
                process.destroyForcibly();
                spec.getCancellationToken().throwIfCancellationRequested(null);
            }
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) {
                process.destroyForcibly();
                throw SkillErrors.timeout(spec.getType().name());
            }
            long waitMillis = Math.min(remaining, CANCEL_POLL_MILLIS);
            if (process.waitFor(waitMillis, TimeUnit.MILLISECONDS)) {
                return process.exitValue();
            }
        }
    }

    /**
     * 在后台线程中排空输出流，避免进程因管道缓冲写满而死锁。
     */
    private Thread drain(InputStream in, final StringBuilder target) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
                    char[] buffer = new char[1024];
                    int read;
                    while ((read = reader.read(buffer)) != -1) {
                        target.append(buffer, 0, read);
                    }
                    reader.close();
                } catch (IOException ignored) {
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * 把入参 JSON 写入 stdin 后关闭，作为脚本的输入结束信号。
     */
    private void writeStdin(Process process, String argumentsJson) throws IOException {
        String payload = argumentsJson == null || argumentsJson.trim().isEmpty() ? "{}" : argumentsJson;
        OutputStream out = process.getOutputStream();
        out.write(payload.getBytes(StandardCharsets.UTF_8));
        out.close();
    }

    private void throwIfCancelled(ScriptExecutionSpec spec) {
        if (spec.getCancellationToken() != null) {
            spec.getCancellationToken().throwIfCancellationRequested(null);
        }
    }

    private void join(Thread thread) throws InterruptedException {
        if (thread != null) {
            thread.join(STREAM_JOIN_MILLIS);
        }
    }

    private String stderrMessage(StringBuilder stderr) {
        return stderr.length() == 0 ? "" : "，" + stderr.toString().trim();
    }

    private void cleanup(Path tempDir) {
        try {
            Files.deleteIfExists(tempDir.resolve("script" + extension()));
        } catch (IOException ignored) {
        }
        try {
            Files.deleteIfExists(tempDir);
        } catch (IOException ignored) {
        }
    }
}
