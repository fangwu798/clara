package com.clara.workspace.api;
public final class FileQuery {private final String nameContains;private final int maximum;
 public FileQuery(String nameContains,int maximum){if(maximum<=0)throw new IllegalArgumentException();this.nameContains=nameContains;this.maximum=maximum;}
 public String getNameContains(){return nameContains;}public int getMaximum(){return maximum;}}
