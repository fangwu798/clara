package com.clara.workspace.api;

import com.clara.workspace.application.FileDescriptor;
import com.clara.workspace.application.FileDocument;
import com.clara.workspace.application.ParsedDocument;

/**
 * 将文档数据解析为领域对象。
 */
public interface DocumentParser {
    boolean supports(FileDescriptor file);

    ParsedDocument parse(FileDocument file);
}
