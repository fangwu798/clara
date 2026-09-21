package com.clara.workspace.runtime;

import com.clara.kernel.exception.ErrorCategory;
import com.clara.kernel.exception.ErrorCodes;
import com.clara.workspace.api.*;

import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/**
 * 在工作区读取策略内执行文本搜索，并生成带基线哈希的替换或删除变更集。
 *
 * <p>常规用法：调用 {@link #search} 定位文本，再用 {@link #replace} 或 {@link #deleteText} 生成
 * {@link ChangeSet}；生成结果只描述变更，仍需由工作区写入器预览、审批和应用。
 *
 * <p>注意：当前只支持字面量匹配，不支持正则表达式。生成变更前会重新读取文件并计算基线哈希，
 * 后续文件发生变化时写入器会报告冲突；搜索和编辑均受文件数、大小及匹配数量限制。
 */
public final class DefaultWorkspaceTextService implements WorkspaceTextService {
    private static final String NODE_ID = "file";
    private final WorkspaceReader reader;
    private final WorkspacePolicy policy;

    public DefaultWorkspaceTextService(WorkspaceReader reader, WorkspacePolicy policy) {
        if (reader == null || policy == null) {
            throw new IllegalArgumentException();
        }
        this.reader = reader;
        this.policy = policy;
    }

    public TextSearchResult search(WorkspaceRef workspace, TextSearchRequest request) {
        if (workspace == null || request == null) {
            throw new IllegalArgumentException();
        }
        if (request.getMode() != TextMatchMode.LITERAL) {
            throw WorkspaceErrors.error(ErrorCodes.REGEX_UNSUPPORTED, ErrorCategory.CAPABILITY);
        }
        WorkspaceSnapshot snapshot = reader.scan(workspace, policy);
        String query = WorkspaceDigests.query(request);
        List<TextMatch> matches = new ArrayList<TextMatch>();
        int scanned = 0;
        boolean truncated = snapshot.isTruncated();
        for (FileDescriptor file : snapshot.getFiles()) {
            if (!isText(file)) continue;
            if (scanned >= request.getMaxFiles()) {
                truncated = true;
                break;
            }
            scanned++;
            FileDocument document = reader.read(workspace, file.getRelativePath());
            if (!file.getSha256().equals(document.getDescriptor().getSha256())) conflict();
            Decoded decoded = decode(document.getContent());
            if (decoded == null) continue;
            int from = 0;
            while (from <= decoded.text.length() - request.getExpression().length()) {
                int start = indexOf(decoded.text, request.getExpression(), from, request.isCaseInsensitive());
                if (start < 0) break;
                int end = start + request.getExpression().length();
                if (matches.size() >= request.getMaxResults()) {
                    truncated = true;
                    break;
                }
                String id = WorkspaceDigests.sha(frame(file.getRelativePath(), file.getSha256(), NODE_ID, Integer.toString(start), Integer.toString(end), query));
                int[] lc = lineColumn(decoded.text, start);
                matches.add(new TextMatch(id, file.getRelativePath(), file.getSha256(), NODE_ID, start, end, lc[0], lc[1], preview(decoded.text, start, end), start, end));
                from = end;
            }
            if (matches.size() >= request.getMaxResults()) {
                if (hasFurtherMatch(decoded.text, request, from) || hasMoreTextFiles(snapshot.getFiles(), file))
                    truncated = true;
                break;
            }
        }
        return new TextSearchResult(matches, scanned, query, snapshot.getFingerprint(), truncated);
    }

    public ChangeSet replace(WorkspaceRef workspace, TextEditSpec edit) {
        if (edit == null || edit.getAction() != TextEditAction.REPLACE_MATCH) {
            throw new IllegalArgumentException("replace action required");
        }
        return edit(workspace, edit, edit.getReplacement());
    }

    public ChangeSet deleteText(WorkspaceRef workspace, TextEditSpec edit) {
        if (edit == null || edit.getAction() != TextEditAction.DELETE_MATCH) {
            throw new IllegalArgumentException("delete match action required");
        }
        return edit(workspace, edit, "");
    }

    private ChangeSet edit(WorkspaceRef workspace, TextEditSpec spec, String replacement) {
        if (workspace.getMode() == WorkspaceAccessMode.READ_ONLY) {
            throw WorkspaceErrors.error(ErrorCodes.WORKSPACE_ACCESS_DENIED, ErrorCategory.AUTHORIZATION);
        }
        TextSearchResult result = search(workspace, spec.getSearch());
        if (result.isTruncated()) conflict();
        if (result.getMatches().size() != spec.getExpectedMatches()) conflict();
        Set<String> requested = spec.getMatchIds();
        List<TextMatch> selected = new ArrayList<TextMatch>();
        for (TextMatch match : result.getMatches())
            if (requested.isEmpty() || requested.contains(match.getMatchId())) selected.add(match);
        if (!requested.isEmpty() && selected.size() != requested.size()) conflict();
        if (selected.isEmpty() || selected.size() > spec.getMaxReplacements()) conflict();
        for (TextMatch match : selected) {
            if (match.getStart() == match.getEnd()) {
                throw WorkspaceErrors.error(ErrorCodes.ZERO_LENGTH_EDIT_DENIED, ErrorCategory.VALIDATION);
            }
            String expected = spec.getExpectedFileHashes().get(match.getRelativePath());
            if (expected != null && !expected.equals(match.getFileHash())) conflict();
        }
        Map<String, List<TextMatch>> byFile = new TreeMap<String, List<TextMatch>>();
        for (TextMatch match : selected) {
            List<TextMatch> list = byFile.get(match.getRelativePath());
            if (list == null) {
                list = new ArrayList<TextMatch>();
                byFile.put(match.getRelativePath(), list);
            }
            list.add(match);
        }
        List<FileChange> changes = new ArrayList<FileChange>();
        for (Map.Entry<String, List<TextMatch>> entry : byFile.entrySet()) {
            FileDocument file = reader.read(workspace, entry.getKey());
            String hash = file.getDescriptor().getSha256();
            if (!hash.equals(entry.getValue().get(0).getFileHash())) conflict();
            Decoded decoded = decode(file.getContent());
            if (decoded == null) conflict();
            List<TextMatch> edits = entry.getValue();
            Collections.sort(edits, new Comparator<TextMatch>() {
                public int compare(TextMatch a, TextMatch b) {
                    return b.getAbsoluteStart() - a.getAbsoluteStart();
                }
            });
            int right = decoded.text.length();
            StringBuilder text = new StringBuilder(decoded.text);
            for (TextMatch match : edits) {
                if (match.getAbsoluteEnd() > right || match.getAbsoluteStart() < 0 || match.getAbsoluteEnd() > text.length())
                    conflict();
                text.replace(match.getAbsoluteStart(), match.getAbsoluteEnd(), replacement);
                right = match.getAbsoluteStart();
            }
            byte[] content = decoded.encode(text.toString());
            changes.add(new FileChange(entry.getKey(), hash, WorkspaceDigests.sha(content), content));
        }
        String digest = WorkspaceDigests.changes(changes);
        String preview = WorkspaceDigests.preview(changes);
        String id = "cs-" + WorkspaceDigests.sha(frame(workspace.getRunId(), digest)).substring(0, 24);
        return new ChangeSet(id, workspace.getRunId(), 1, digest, preview, changes);
    }

    private static boolean isText(FileDescriptor file) {
        return "text/plain".equals(file.getMimeType());
    }

    private static int indexOf(String text, String expression, int from, boolean insensitive) {
        if (!insensitive) return text.indexOf(expression, from);
        int maximum = text.length() - expression.length();
        for (int i = from; i <= maximum; i++)
            if (text.regionMatches(true, i, expression, 0, expression.length())) return i;
        return -1;
    }

    private static boolean hasFurtherMatch(String text, TextSearchRequest request, int from) {
        return from <= text.length() && indexOf(text, request.getExpression(), from, request.isCaseInsensitive()) >= 0;
    }

    private static boolean hasMoreTextFiles(List<FileDescriptor> files, FileDescriptor current) {
        boolean found = false;
        for (FileDescriptor file : files) {
            if (found && isText(file)) return true;
            if (file == current) found = true;
        }
        return false;
    }

    private static int[] lineColumn(String text, int offset) {
        int line = 1, column = 1;
        for (int i = 0; i < offset; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                column = 1;
            } else column++;
        }
        return new int[]{line, column};
    }

    private static String preview(String text, int start, int end) {
        int left = Math.max(0, start - 32), right = Math.min(text.length(), end + 32);
        return text.substring(left, right);
    }

    private static byte[] frame(String... values) {
        StringBuilder b = new StringBuilder();
        for (String value : values) b.append(value.length()).append(':').append(value);
        return b.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static Decoded decode(byte[] bytes) {
        boolean bom = bytes.length >= 3 && (bytes[0] & 255) == 239 && (bytes[1] & 255) == 187 && (bytes[2] & 255) == 191;
        try {
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            String text = decoder.decode(ByteBuffer.wrap(bytes, bom ? 3 : 0, bytes.length - (bom ? 3 : 0))).toString();
            return new Decoded(text, bom);
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static void conflict() {
        throw WorkspaceErrors.error(ErrorCodes.WORKSPACE_CONFLICT, ErrorCategory.CONFLICT);
    }

    /**
     * 表示。
     */
    private static final class Decoded {
        private final String text;
        private final boolean bom;

        private Decoded(String text, boolean bom) {
            this.text = text;
            this.bom = bom;
        }

        private byte[] encode(String value) {
            byte[] body = value.getBytes(StandardCharsets.UTF_8);
            if (!bom) return body;
            byte[] result = new byte[body.length + 3];
            result[0] = (byte) 239;
            result[1] = (byte) 187;
            result[2] = (byte) 191;
            System.arraycopy(body, 0, result, 3, body.length);
            return result;
        }
    }
}
