package com.clara.orchestration.capability;

import com.clara.enums.CapabilityKindEnum;
import com.clara.kernel.api.IRequest;
import com.clara.kernel.api.IResponse;
import com.clara.kernel.api.IStreamModel;
import com.clara.exception.ClaraException;
import com.clara.kernel.http.HttpRequest;
import com.clara.kernel.http.HttpResponse;
import com.clara.kernel.message.delta.ImageItem;
import com.clara.kernel.stream.IStreamHandle;
import com.clara.kernel.stream.IStreamObserver;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * {@link CapabilityDispatcher} 单元测试：同步 / 流式分派与按能力种类提取结果。
 */
public class CapabilityDispatcherTest {

    @Test
    public void constructorRejectsNullModel() {
        assertThrows(IllegalArgumentException.class, () -> new CapabilityDispatcher(null));
    }

    @Test
    public void invokeSyncUsesSynchronousCall() {
        HttpResponse sync = contentResponse("sync");
        FakeStreamModel model = new FakeStreamModel(sync, null);
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(model);

        HttpResponse result = dispatcher.invoke(request());

        assertSame(sync, result);
        assertFalse(model.streamInvoked);
    }

    @Test
    public void invokeWithNullObserverDegradesToSynchronous() {
        HttpResponse sync = contentResponse("sync");
        FakeStreamModel model = new FakeStreamModel(sync, null);
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(model);

        HttpResponse result = dispatcher.invoke(request(), null);

        assertSame(sync, result);
        assertFalse(model.streamInvoked);
    }

    @Test
    public void invokeWithObserverUsesStreamingAndReturnsHandleResult() {
        HttpResponse streamed = contentResponse("streamed");
        FakeStreamModel model = new FakeStreamModel(null, streamed);
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(model);

        HttpResponse result = dispatcher.invoke(request(), event -> { });

        assertTrue(model.streamInvoked);
        assertSame(streamed, result);
    }

    @Test
    public void extractRejectsNullKind() {
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(new FakeStreamModel(null, null));
        assertThrows(ClaraException.class, () -> dispatcher.extract(null, contentResponse("x")));
    }

    @Test
    public void extractReturnsNullForNullResponse() {
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(new FakeStreamModel(null, null));
        assertNull(dispatcher.extract(CapabilityKindEnum.CHAT, null));
    }

    @Test
    public void extractChatReturnsContent() {
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(new FakeStreamModel(null, null));
        assertEquals("hello", dispatcher.extract(CapabilityKindEnum.CHAT, contentResponse("hello")));
    }

    @Test
    public void extractOtherKindFallsBackToContent() {
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(new FakeStreamModel(null, null));
        HttpResponse response = contentResponse("video-text");
        response.setImages(Collections.singletonList(image("http://a", null)));
        assertEquals("video-text", dispatcher.extract(CapabilityKindEnum.VIDEO, response));
    }

    @Test
    public void extractImageJoinsUrlAndB64JsonInOrder() {
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(new FakeStreamModel(null, null));
        HttpResponse response = new HttpResponse();
        response.setImages(Arrays.asList(image("http://a", null), image(null, "BASE64")));

        assertEquals("http://a\nBASE64", dispatcher.extract(CapabilityKindEnum.IMAGE, response));
    }

    @Test
    public void extractImagePrefersUrlWhenBothPresentAndSkipsNullEntries() {
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(new FakeStreamModel(null, null));
        HttpResponse response = new HttpResponse();
        response.setImages(Arrays.asList(null, image("http://u", "B64"), image(null, null)));

        assertEquals("http://u", dispatcher.extract(CapabilityKindEnum.IMAGE, response));
    }

    @Test
    public void extractImageReturnsNullWhenEmptyOrUnusable() {
        CapabilityDispatcher dispatcher = new CapabilityDispatcher(new FakeStreamModel(null, null));

        HttpResponse empty = new HttpResponse();
        assertNull(dispatcher.extract(CapabilityKindEnum.IMAGE, empty));

        HttpResponse unusable = new HttpResponse();
        unusable.setImages(Collections.singletonList(image(null, null)));
        assertNull(dispatcher.extract(CapabilityKindEnum.IMAGE, unusable));
    }

    // ---- helpers -----------------------------------------------------------

    private static HttpRequest request() {
        return HttpRequest.builder().model("test-model").build();
    }

    private static HttpResponse contentResponse(String content) {
        HttpResponse response = new HttpResponse();
        response.setContent(content);
        return response;
    }

    private static ImageItem image(String url, String b64Json) {
        ImageItem item = new ImageItem();
        item.setUrl(url);
        item.setB64Json(b64Json);
        return item;
    }

    private static final class FakeStreamModel implements IStreamModel {

        private final HttpResponse syncResponse;
        private final HttpResponse streamResponse;
        private boolean streamInvoked;

        FakeStreamModel(HttpResponse syncResponse, HttpResponse streamResponse) {
            this.syncResponse = syncResponse;
            this.streamResponse = streamResponse;
        }

        @Override
        public <R extends IResponse> R chat(IRequest request, Class<R> responseType) {
            return responseType.cast(syncResponse);
        }

        @Override
        public <R extends IResponse> IStreamHandle chat(IRequest request, Class<R> responseType,
                IStreamObserver observer) {
            streamInvoked = true;
            final HttpResponse result = streamResponse;
            return new IStreamHandle() {
                @Override
                public boolean cancel() {
                    return false;
                }

                @Override
                public boolean isDone() {
                    return true;
                }

                @Override
                public IResponse result() {
                    return result;
                }
            };
        }
    }
}
