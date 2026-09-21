//package com.clara.media.provider;
//
//import com.clara.context.CallContext;
//import com.clara.context.CancellationToken;
//import com.clara.kernel.exception.ClaraException;
//import com.clara.context.ConvContext;
//import com.clara.kernel.exception.ErrorCodes;
//import com.clara.media.MediaAsset;
//import com.clara.media.MediaInput;
//import com.clara.media.MediaStore;
//import com.clara.media.MediaType;
//import com.clara.media.MediaUpload;
//import java.io.ByteArrayInputStream;
//import java.io.InputStream;
//import java.util.Collections;
//import org.junit.Test;
//import static org.junit.Assert.*;
//
//public class DefaultProviderMediaAssetBridgeTest {
//    @Test public void readsBoundedBytesAndPublishesOnlyHttpsReferences() {
//        FakeStore store = new FakeStore();
//        DefaultProviderMediaAssetBridge bridge = new DefaultProviderMediaAssetBridge(store,
//                new MediaAssetReferencePublisher() {
//                    public String publish(MediaAsset asset, CallContext call) {
//                        return "https://assets.test/signed/" + asset.getAssetId();
//                    }
//                }, null);
//        assertArrayEquals(new byte[] {1, 2, 3}, bridge.inputBytes("asset", MediaType.AUDIO, 3, conversation(), call()));
//        assertEquals("https://assets.test/signed/asset", bridge.inputReference("asset", MediaType.AUDIO, conversation(), call()));
//        assertCode(ErrorCodes.MEDIA_RESOURCE_LIMIT, new Runnable() { public void run() {
//            bridge.inputBytes("asset", MediaType.AUDIO, 2, conversation(), call());
//        }});
//    }
//    @Test public void remoteOutputIsValidatedBeforePersistence() {
//        FakeStore store = new FakeStore();
//        DefaultProviderMediaAssetBridge bridge = new DefaultProviderMediaAssetBridge(store, null,
//                new RemoteMediaIngestor() {
//                    public MediaUpload fetch(String url, MediaType type, String mime,
//                                             ConvContext conversation, CallContext call) {
//                        return upload("local", type, mime, new byte[] {8});
//                    }
//                });
//        assertEquals("asset", bridge.storeRemote("https://output.test/a.png", MediaType.IMAGE,
//                "image/png", conversation(), call()));
//        assertEquals(MediaType.IMAGE, store.lastUpload.getMediaType());
//        assertCode(ErrorCodes.MEDIA_TRANSPORT_NOT_SUPPORTED, new Runnable() { public void run() {
//            bridge.storeRemote("http://output.test/a.png", MediaType.IMAGE, "image/png", conversation(), call());
//        }});
//    }
//    private static ConvContext conversation() {
//        return new ConvContext(null, "conversation", transportConfig);
//    }
//
//    private static CallContext call() {
//        return new CallContext("trace", System.currentTimeMillis() + 5000, new CancellationToken());
//    }
//    private static MediaUpload upload(String tenant, MediaType type, String mime, final byte[] bytes) {
//        return new MediaUpload(tenant, type, "asset.bin", mime, null, null, null, null, null,
//                Collections.<String, String>emptyMap(), new MediaInput() {
//                    public InputStream open() { return new ByteArrayInputStream(bytes); }
//                });
//    }
//    private static void assertCode(String code, Runnable action) {
//        try { action.run(); fail("expected " + code); }
//        catch (ClaraException failure) { assertEquals(code, failure.getError().getCode()); }
//    }
//    private static final class FakeStore implements MediaStore {
//        private MediaUpload lastUpload;
//        public MediaAsset put(MediaUpload upload) { lastUpload = upload; return asset(upload.getMediaType()); }
//        public MediaAsset get(String tenant, String id) { return asset(MediaType.AUDIO); }
//        public InputStream open(String tenant, String id) { return new ByteArrayInputStream(new byte[] {1, 2, 3}); }
//        public void delete(String tenant, String id) { }
//        private MediaAsset asset(MediaType type) {
//            return new MediaAsset("asset", type,
//                    type == MediaType.AUDIO ? "audio/wav" : "image/png", 3, "hash", "memory://asset",
//                    null, null, null, null, "tenant", 1, null, Collections.<String, String>emptyMap());
//        }
//    }
//}
