//package com.clara.memory.persistence.local;
//
//import com.clara.kernel.exception.ClaraException;
//import com.clara.kernel.exception.ErrorCodes;
//import com.clara.memory.api.ConversationSnapshot;
//import com.clara.kernel.ChatMessage;
//import com.clara.kernel.message.IContent;
//import com.clara.kernel.message.MediaContent;
//import com.clara.enums.RoleEnum;
//import com.clara.kernel.message.impl.UserContent;
//import com.clara.kernel.message.impl.ToolCallContent;
//import java.io.IOException;
//import java.nio.file.DirectoryStream;
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.util.Arrays;
//import java.util.Collections;
//import org.junit.Rule;
//import org.junit.Test;
//import org.junit.rules.TemporaryFolder;
//
//import static org.junit.Assert.assertEquals;
//import static org.junit.Assert.assertFalse;
//import static org.junit.Assert.fail;
//
//public class LocalConversationStoreTest {
//    @Rule
//    public final TemporaryFolder temporaryFolder = new TemporaryFolder();
//
//    @Test
//    public void survivesRestartAndRoundTripsSupportedContent() throws Exception {
//        Path workspace = temporaryWorkspace();
//        ChatMessage message = new ChatMessage(RoleEnum.ASSISTANT, Arrays.<IContent>asList(
//                new UserContent("answer"), new MediaContent("image", "asset-1"),
//                new ToolCallContent("call-1", "search", "{\"q\":\"clara\"}")),
//                "assistant", Collections.singletonMap("source", "test"));
//        LocalConversationStore first = new LocalConversationStore(workspace);
//        first.append("conversation/../safe", Arrays.asList(message), 0L);
//        first.close();
//
//        LocalConversationStore reopened = new LocalConversationStore(workspace);
//        try {
//            ConversationSnapshot snapshot = reopened.loadSnapshot("conversation/../safe");
//            assertEquals(1L, snapshot.getVersion());
//            assertEquals(Arrays.asList(message), snapshot.getMessages());
//            assertFalse(Files.exists(workspace.resolve("safe")));
//        } finally {
//            reopened.close();
//        }
//    }
//
//    @Test
//    public void rejectsSecondWriterForSameWorkspace() throws Exception {
//        Path workspace = temporaryWorkspace();
//        LocalConversationStore first = new LocalConversationStore(workspace);
//        try {
//            try {
//                new LocalConversationStore(workspace);
//                fail("expected store lock failure");
//            } catch (ClaraException exception) {
//                assertEquals(ErrorCodes.CONVERSATION_STORE_LOCKED, exception.getError().getCode());
//            }
//        } finally {
//            first.close();
//        }
//    }
//
//    @Test
//    public void reportsCorruptConversationFile() throws Exception {
//        Path workspace = temporaryWorkspace();
//        LocalConversationStore store = new LocalConversationStore(workspace);
//        try {
//            store.append("conversation", Arrays.asList(text("hello")), 0L);
//            Path file = onlyConversationFile(workspace);
//            Files.write(file, new byte[] { 1, 2, 3 });
//            try {
//                store.load("conversation");
//                fail("expected corruption failure");
//            } catch (ClaraException exception) {
//                assertEquals(ErrorCodes.CONVERSATION_STORE_CORRUPTED,
//                        exception.getError().getCode());
//            }
//        } finally {
//            store.close();
//        }
//    }
//
//    private static ChatMessage text(String value) {
//        return new ChatMessage(RoleEnum.USER, Arrays.<IContent>asList(new UserContent(value)), null,
//                Collections.<String, String>emptyMap());
//    }
//
//    private Path temporaryWorkspace() throws IOException {
//        return temporaryFolder.newFolder("workspace").toPath();
//    }
//
//    private static Path onlyConversationFile(Path workspace) throws IOException {
//        Path directory = workspace.resolve(".clara").resolve("state").resolve("conversations");
//        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.bin")) {
//            Path result = null;
//            for (Path path : stream) {
//                if (result != null) throw new AssertionError("expected one conversation file");
//                result = path;
//            }
//            if (result == null) throw new AssertionError("conversation file not found");
//            return result;
//        }
//    }
//}
