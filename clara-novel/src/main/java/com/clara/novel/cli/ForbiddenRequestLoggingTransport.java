//package com.clara.novel.cli;
//
//import com.clara.context.CallContext;
//import com.clara.kernel.http.HttpRequest;
//import com.clara.kernel.http.HttpResponse;
//import com.clara.model.http.IHttpTransport;
//import com.clara.model.http.sse.ISseListener;
//import java.io.PrintStream;
//import java.nio.charset.StandardCharsets;
//import java.util.Map;
//
///** Prints the exact provider request, with credentials visible, when the provider returns 403. */
//public final class ForbiddenRequestLoggingTransport implements IHttpTransport {
//    private static final int MAX_BODY_CHARS = 65536;
//    private final IHttpTransport delegate;
//    private final PrintStream console;
//
//    public ForbiddenRequestLoggingTransport(IHttpTransport delegate, PrintStream console) {
//        if (delegate == null || console == null) {
//            throw new IllegalArgumentException("delegate and console must not be null");
//        }
//        this.delegate = delegate;
//        this.console = console;
//    }
//
//    public HttpResponse execute(HttpRequest request, CallContext call,
//                                        ISseListener listener) {
//        HttpResponse response = delegate.execute(request, call, listener);
//        if (response != null && response.getStatusCode() == 403) log(request);
//        return response;
//    }
//
//    private void log(HttpRequest request) {
//        console.println();
//        console.println("========== HTTP 403 REQUEST (credentials visible) ==========");
//        console.println(request.getMethod() + " " + request.getEndpoint());
//        for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
//            console.println(header.getKey() + ": " + header.getValue());
//        }
//        byte[] bytes = request.getBody();
//        if (bytes.length > 0) {
//            String body = new String(bytes, StandardCharsets.UTF_8);
//            console.println();
//            if (body.length() > MAX_BODY_CHARS) {
//                console.println(body.substring(0, MAX_BODY_CHARS));
//                console.println("[REQUEST BODY TRUNCATED, " + bytes.length + " bytes total]");
//            } else {
//                console.println(body);
//            }
//        }
//        console.println("========== END HTTP 403 REQUEST ==========");
//        console.println();
//    }
//
//}
