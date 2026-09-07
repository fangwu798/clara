package com.clara.adapter.rest;
/**
 * 表示SSE产生的事件。
 */
public final class SseEvent {private final String id,type,data;public SseEvent(String id,String type,String data){if(id==null||type==null||data==null) {throw new IllegalArgumentException(); }this.id=id;this.type=type;this.data=data;}public String getId(){return id;}public String getType(){return type;}public String getData(){return data;}public String encode(){return "id: "+clean(id)+"\nevent: "+clean(type)+"\ndata: "+data.replace("\r","").replace("\n","\\n")+"\n\n";}private static String clean(String value){return value.replace("\r","").replace("\n","");}}
