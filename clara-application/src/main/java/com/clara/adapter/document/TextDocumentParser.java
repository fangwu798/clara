package com.clara.adapter.document;

import com.clara.exception.ErrorCategory;
import com.clara.exception.ErrorCodes;
import com.clara.workspace.api.*;

import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/**
 * 将文本文档数据解析为领域对象。
 */
public final class TextDocumentParser implements DocumentParser {
    private static final Set<String> EXT = new HashSet<String>(Arrays.asList(".txt", ".md", ".java", ".xml", ".json", ".yaml", ".yml", ".properties", ".csv", ".sql", ".js", ".ts", ".css", ".html"));

    public boolean supports(FileDescriptor file) {
        return EXT.contains(extension(file.getRelativePath()));
    }

    public ParsedDocument parse(FileDocument file) {
        try {
            byte[] bytes = file.getContent();
            int offset = bytes.length >= 3 && (bytes[0] & 255) == 239 && (bytes[1] & 255) == 187 && (bytes[2] & 255) == 191 ? 3 : 0;
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT);
            String text = decoder.decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset)).toString();
            String[] lines = text.split("\\r?\\n", -1);
            List<DocumentNode> nodes = new ArrayList<DocumentNode>();
            for (int i = 0; i < lines.length; i++)
                nodes.add(new DocumentNode("line/" + (i + 1), DocumentNodeType.LINE, lines[i], Collections.<String, String>emptyMap()));
            String ext = extension(file.getDescriptor().getRelativePath());
            DocumentFormat format = ".txt".equals(ext) || ".md".equals(ext) ? DocumentFormat.TEXT : DocumentFormat.CODE;
            return new ParsedDocument(format, nodes, Collections.singletonMap("charset", "UTF-8"), true, Collections.<String>emptyList());
        } catch (CharacterCodingException e) {
            throw DocumentParsingErrors.error(ErrorCodes.DOCUMENT_PARSE_FAILED, ErrorCategory.VALIDATION);
        }
    }

    private static String extension(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot).toLowerCase(Locale.ROOT);
    }
}
