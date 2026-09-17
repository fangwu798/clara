package com.clara.workspace.runtime;

import com.clara.workspace.api.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/**
 * 表示。
 */
final class WorkspaceDigests {
    private WorkspaceDigests() {
    }

    static String sha(byte[] value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value);
            StringBuilder out = new StringBuilder(64);
            for (byte b : digest) out.append(String.format("%02x", b & 255));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String query(TextSearchRequest request) {
        return sha(frame(request.getMode().name(), request.getExpression(), Boolean.toString(request.isCaseInsensitive()), Integer.toString(request.getMaxFiles()), Integer.toString(request.getMaxResults())));
    }

    static String changes(List<FileChange> values) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(1);
            for (FileChange value : values) {
                write(out, value.getRelativePath());
                write(out, value.getBaseHash());
                write(out, value.getResultHash());
                byte[] content = value.getContent();
                out.writeInt(content.length);
                out.write(content);
            }
            out.flush();
            return sha(bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String preview(List<FileChange> values) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(1);
            for (FileChange value : values) {
                write(out, value.getRelativePath());
                write(out, value.getBaseHash());
                write(out, value.getResultHash());
                out.writeLong(value.getContent().length);
            }
            out.flush();
            return sha(bytes.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] frame(String... values) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(1);
            for (String value : values) write(out, value);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void write(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
