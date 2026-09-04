package com.clara.workspace.api;
public final class FileDescriptor {
 private final String relativePath,mimeType,sha256;private final long size,lastModified;
 public FileDescriptor(String path,String mime,long size,long modified,String sha){if(path==null||path.trim().isEmpty()||mime==null||size<0L||modified<0L||sha==null)throw new IllegalArgumentException("file descriptor is invalid");relativePath=path;mimeType=mime;this.size=size;lastModified=modified;sha256=sha;}
 public String getRelativePath(){return relativePath;}public String getMimeType(){return mimeType;}public long getSize(){return size;}
 public long getLastModifiedEpochMillis(){return lastModified;}public String getSha256(){return sha256;}
}
