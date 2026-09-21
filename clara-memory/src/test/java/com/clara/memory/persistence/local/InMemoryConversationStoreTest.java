//package com.clara.memory.persistence.local;
//
//import com.clara.kernel.exception.ClaraException;
//import com.clara.kernel.exception.ErrorCodes;
//import com.clara.memory.api.ConversationSnapshot;
//import com.clara.kernel.ChatMessage;
//import com.clara.kernel.message.IContent;
//import com.clara.enums.RoleEnum;
//import com.clara.kernel.message.impl.UserContent;
//import java.util.Arrays;
//import java.util.Collections;
//import org.junit.Test;
//
//import static org.junit.Assert.assertEquals;
//import static org.junit.Assert.fail;
//
//public class InMemoryConversationStoreTest {
//    @Test
//    public void appendsUsingOptimisticVersion() {
//        InMemoryConversationStore store = new InMemoryConversationStore();
//        ChatMessage first = message("first");
//        ChatMessage second = message("second");
//        store.append("conversation", Arrays.asList(first), 0L);
//        store.append("conversation", Arrays.asList(second), 1L);
//
//        ConversationSnapshot snapshot = store.loadSnapshot("conversation");
//        assertEquals(2L, snapshot.getVersion());
//        assertEquals(Arrays.asList(first, second), snapshot.getMessages());
//    }
//
//    @Test
//    public void staleAppendUsesStableConflictCode() {
//        InMemoryConversationStore store = new InMemoryConversationStore();
//        store.append("conversation", Arrays.asList(message("first")), 0L);
//        try {
//            store.append("conversation", Arrays.asList(message("stale")), 0L);
//            fail("expected ClaraException");
//        } catch (ClaraException exception) {
//            assertEquals(ErrorCodes.CONVERSATION_VERSION_CONFLICT,
//                    exception.getError().getCode());
//        }
//    }
//
//    @Test
//    public void loadReturnsAnImmutableSnapshot() {
//        InMemoryConversationStore store = new InMemoryConversationStore();
//        store.append("conversation", Arrays.asList(message("first")), 0L);
//        try {
//            store.load("conversation").clear();
//            fail("expected immutable messages");
//        } catch (UnsupportedOperationException expected) {
//            assertEquals(1, store.load("conversation").size());
//        }
//    }
//
//    private static ChatMessage message(String text) {
//        return new ChatMessage(RoleEnum.USER, Arrays.<IContent>asList(new UserContent(text)), null,
//                Collections.<String, String>emptyMap());
//    }
//}
