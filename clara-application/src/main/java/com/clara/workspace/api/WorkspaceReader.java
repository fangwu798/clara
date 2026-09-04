package com.clara.workspace.api;
import java.util.List;
public interface WorkspaceReader {WorkspaceSnapshot scan(WorkspaceRef workspace,WorkspacePolicy policy);FileDocument read(WorkspaceRef workspace,String relativePath);List<FileDescriptor> findFiles(WorkspaceRef workspace,FileQuery query);}
