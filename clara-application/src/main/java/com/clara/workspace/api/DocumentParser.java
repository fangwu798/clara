package com.clara.workspace.api;
public interface DocumentParser {boolean supports(FileDescriptor file);ParsedDocument parse(FileDocument file);}
