//package com.clara.memory.persistence;
//
//import com.clara.memory.api.ConversationSnapshot;
//import com.clara.kernel.ChatMessage;
//import com.clara.kernel.message.IContent;
//import com.clara.kernel.message.MediaContent;
//import com.clara.enums.RoleEnum;
//import com.clara.kernel.message.impl.UserContent;
//import com.clara.kernel.message.impl.ToolCallContent;
//import java.io.ByteArrayInputStream;
//import java.io.ByteArrayOutputStream;
//import java.io.DataInputStream;
//import java.io.DataOutputStream;
//import java.io.EOFException;
//import java.io.IOException;
//import java.nio.charset.StandardCharsets;
//import java.util.ArrayList;
//import java.util.LinkedHashMap;
//import java.util.List;
//import java.util.Map;
//
///**
// * 负责会话数据的编码与解码。
// */
//public final class ConversationCodec {
//    private static final int MAGIC = 0x434C5241;
//    private static final int FORMAT_VERSION = 1;
//    private static final int MAX_MESSAGES = 100000;
//    private static final int MAX_CONTENTS = 1000;
//    private static final int MAX_METADATA = 1000;
//    private static final int MAX_STRING_BYTES = 16 * 1024 * 1024;
//
//    public byte[] encode(String conversationId, ConversationSnapshot snapshot) throws IOException {
//        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
//        DataOutputStream output = new DataOutputStream(bytes);
//        output.writeInt(MAGIC);
//        output.writeInt(FORMAT_VERSION);
//        writeString(output, conversationId);
//        output.writeLong(snapshot.getVersion());
//        output.writeInt(snapshot.getMessages().size());
//        for (ChatMessage message : snapshot.getMessages()) writeMessage(output, message);
//        output.flush();
//        return bytes.toByteArray();
//    }
//
//    public ConversationSnapshot decode(String expectedConversationId, byte[] encoded) throws IOException {
//        DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
//        try {
//            if (input.readInt() != MAGIC) { throw new IOException("invalid conversation file magic"); }
//            if (input.readInt() != FORMAT_VERSION) { throw new IOException("unsupported conversation format"); }
//            if (!expectedConversationId.equals(readString(input))) {
//                throw new IOException("conversation identifier mismatch");
//            }
//            long version = input.readLong();
//            int count = readCount(input, MAX_MESSAGES, "message count");
//            List<ChatMessage> messages = new ArrayList<ChatMessage>(count);
//            for (int i = 0; i < count; i++) messages.add(readMessage(input));
//            if (input.read() != -1) { throw new IOException("trailing conversation data"); }
//            return new ConversationSnapshot(version, messages);
//        } catch (EOFException exception) {
//            throw new IOException("truncated conversation data", exception);
//        } catch (IllegalArgumentException exception) {
//            throw new IOException("invalid conversation data", exception);
//        }
//    }
//
//    private static void writeMessage(DataOutputStream output, ChatMessage message) throws IOException {
////        output.writeByte(message.getRole().ordinal());
////        writeNullableString(output, message.getName());
////        output.writeInt(message.getContents().size());
////        for (IContent content : message.getContents()) {
////            if (content instanceof UserContent) {
////                output.writeByte(1);
////                writeString(output, ((UserContent) content).getText());
////            } else if (content instanceof MediaContent) {
////                output.writeByte(2);
////                writeString(output, content.getRoleType());
////                writeString(output, ((MediaContent) content).getAssetId());
////            } else if (content instanceof ToolCallContent) {
////                ToolCallContent call = (ToolCallContent) content;
////                output.writeByte(3);
////                writeString(output, call.getId());
////                writeString(output, call.getName());
////                writeString(output, call.getArguments());
////            } else {
////                throw new IOException("unsupported content type: " + content.getClass().getName());
////            }
////        }
////        output.writeInt(message.getMetadata().size());
////        for (Map.Entry<String, String> entry : message.getMetadata().entrySet()) {
////            writeString(output, entry.getKey());
////            writeNullableString(output, entry.getValue());
////        }
//    }
//
//    private static ChatMessage readMessage(DataInputStream input) throws IOException {
//        int role = input.readUnsignedByte();
//        if (role >= RoleEnum.values().length) { throw new IOException("invalid role"); }
//        String name = readNullableString(input);
//        int contentCount = readCount(input, MAX_CONTENTS, "content count");
//        List<IContent> contents = new ArrayList<IContent>(contentCount);
//        for (int i = 0; i < contentCount; i++) {
//            int type = input.readUnsignedByte();
//            if (type == 1) contents.add(new UserContent(readString(input)));
//            else if (type == 2) contents.add(new MediaContent(readString(input), readString(input)));
//            else if (type == 3) {
//                contents.add(new ToolCallContent(readString(input), readString(input), readString(input)));
//            } else throw new IOException("unsupported content type");
//        }
//        int metadataCount = readCount(input, MAX_METADATA, "metadata count");
//        Map<String, String> metadata = new LinkedHashMap<String, String>();
//        for (int i = 0; i < metadataCount; i++) {
//            metadata.put(readString(input), readNullableString(input));
//        }
//        return new ChatMessage(RoleEnum.values()[role], contents, name, metadata);
//    }
//
//    private static void writeNullableString(DataOutputStream output, String value) throws IOException {
//        output.writeBoolean(value != null);
//        if (value != null) writeString(output, value);
//    }
//
//    private static String readNullableString(DataInputStream input) throws IOException {
//        return input.readBoolean() ? readString(input) : null;
//    }
//
//    private static void writeString(DataOutputStream output, String value) throws IOException {
//        if (value == null) { throw new IOException("required string is null"); }
//        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
//        if (bytes.length > MAX_STRING_BYTES) { throw new IOException("string is too large"); }
//        output.writeInt(bytes.length);
//        output.write(bytes);
//    }
//
//    private static String readString(DataInputStream input) throws IOException {
//        int length = readCount(input, MAX_STRING_BYTES, "string length");
//        byte[] bytes = new byte[length];
//        input.readFully(bytes);
//        return new String(bytes, StandardCharsets.UTF_8);
//    }
//
//    private static int readCount(DataInputStream input, int maximum, String field) throws IOException {
//        int value = input.readInt();
//        if (value < 0 || value > maximum) { throw new IOException("invalid " + field); }
//        return value;
//    }
//}
