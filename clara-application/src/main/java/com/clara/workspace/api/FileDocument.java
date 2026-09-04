package com.clara.workspace.api;
public final class FileDocument {
 private final FileDescriptor descriptor;private final byte[] content;
 public FileDocument(FileDescriptor descriptor,byte[] content){if(descriptor==null||content==null)throw new IllegalArgumentException();this.descriptor=descriptor;this.content=content.clone();}
 public FileDescriptor getDescriptor(){return descriptor;}public byte[] getContent(){return content.clone();}
}
