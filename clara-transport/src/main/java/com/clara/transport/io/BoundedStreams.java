package com.clara.transport.io;

import com.clara.exception.HttpTransportException;
import com.clara.core.transport.IExecutionGuard;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 带体积上限与取消检查的流读取工具。
 */
public final class BoundedStreams {

    private BoundedStreams() {
        // 工具类，禁止实例化。
    }

    /**
     * 读取一行文本，同时限制字符数与 UTF-8 字节数。
     *
     * @param reader 消息来源，按 {@code \n} 分行，忽略 {@code \r}
     * @param maxBytes 单行允许的最大 UTF-8 字节数
     * @return 读到的行；流结束时返回 null
     * @throws IOException 读取失败
     * @throws HttpTransportException 超出行长度限制时
     */
    public static String readBoundedLine(BufferedReader reader, int maxBytes) throws IOException {
        StringBuilder result = new StringBuilder();
        int value;
        while ((value = reader.read()) >= 0) {
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                result.append((char) value);
            }
            if (result.length() > maxBytes) {
                throw HttpTransportException.responseTooLarge();
            }
        }
        if (value < 0 && result.length() == 0) {
            return null;
        }
        if (result.toString().getBytes(StandardCharsets.UTF_8).length > maxBytes) {
            throw HttpTransportException.responseTooLarge();
        }
        return result.toString();
    }

    /**
     * 读取完整输入流，并检查体积上限与取消状态。
     *
     * @param input 输入流，可为 null（返回空数组）
     * @param maximum 最大字节数，必须为正
     * @param guard 执行守卫，不可为 null
     * @return 读取到的字节，非 null
     * @throws IOException 读取失败
     * @throws HttpTransportException 超限、取消或超时时
     */
    public static byte[] readAll(InputStream input, int maximum, IExecutionGuard guard) throws IOException {
        if (input == null) {
            return new byte[0];
        }
        try (InputStream source = input) {
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(maximum, 8192));
            byte[] buffer = new byte[8192];
            int count;
            int total = 0;
            while ((count = source.read(buffer)) >= 0) {
                guard.check();
                if (count == 0) {
                    continue;
                }
                if (total > maximum - count) {
                    throw HttpTransportException.responseTooLarge();
                }
                output.write(buffer, 0, count);
                total += count;
            }
            return output.toByteArray();
        }
    }
}
