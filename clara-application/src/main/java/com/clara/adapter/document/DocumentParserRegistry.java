package com.clara.adapter.document;

import com.clara.kernel.*;
import com.clara.workspace.api.*;

import java.util.*;

/**
 * 负责注册并查找文档解析器。
 */
public final class DocumentParserRegistry {
    private final List<DocumentParser> parsers;

    public DocumentParserRegistry(List<DocumentParser> parsers) {
        if (parsers == null || parsers.isEmpty() || parsers.contains(null)) {
            throw new IllegalArgumentException();
        }
        this.parsers = Collections.unmodifiableList(new ArrayList<DocumentParser>(parsers));
    }

    public ParsedDocument parse(FileDocument file) {
        if (file == null) {
            throw new IllegalArgumentException();
        }
        DocumentParser selected = null;
        for (DocumentParser parser : parsers)
            if (parser.supports(file.getDescriptor())) {
                if (selected != null) {
                    throw DocumentParsingErrors.error(ErrorCodes.DOCUMENT_PARSE_FAILED, ErrorCategory.CONFIGURATION);
                }
                selected = parser;
            }
        if (selected == null) {
            throw DocumentParsingErrors.error(ErrorCodes.DOCUMENT_FORMAT_UNSUPPORTED, ErrorCategory.VALIDATION);
        }
        return selected.parse(file);
    }
}
