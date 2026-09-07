package com.clara.workspace.api;
/**
 * 将文档数据解析为领域对象。
 */
public interface DocumentParser {boolean supports(FileDescriptor file);ParsedDocument parse(FileDocument file);}
