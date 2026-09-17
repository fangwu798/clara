package com.clara.novel.cli;

import com.clara.kernel.InvocationContext;
import com.clara.model.provider.http.impl.ProviderHttpRequest;
import com.clara.model.provider.http.impl.ProviderHttpResponse;
import com.clara.model.provider.http.ProviderHttpTransport;
import com.clara.model.provider.http.ProviderSseListener;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Prints the exact provider request, with credentials visible, when the provider returns 403. */
public final class ForbiddenRequestLoggingTransport implements ProviderHttpTransport {
    private static final int MAX_BODY_CHARS = 65536;
    private final ProviderHttpTransport delegate;
    private final PrintStream console;

    public ForbiddenRequestLoggingTransport(ProviderHttpTransport delegate, PrintStream console) {
        if (delegate == null || console == null) {
            throw new IllegalArgumentException("delegate and console must not be null");
        }
        this.delegate = delegate;
        this.console = console;
    }

    public ProviderHttpResponse execute(ProviderHttpRequest request, InvocationContext context,
                                        ProviderSseListener listener) {
        ProviderHttpResponse response = delegate.execute(request, context, listener);
        if (response != null && response.getStatusCode() == 403) log(request);
        return response;
    }

    private void log(ProviderHttpRequest request) {
        console.println();
        console.println("========== HTTP 403 REQUEST (credentials visible) ==========");
        console.println(request.getMethod() + " " + request.getEndpoint());
        for (Map.Entry<String, String> header : request.getHeaders().entrySet()) {
            console.println(header.getKey() + ": " + header.getValue());
        }
        byte[] bytes = request.getBody();
        if (bytes.length > 0) {
            String body = new String(bytes, StandardCharsets.UTF_8);
            console.println();
            if (body.length() > MAX_BODY_CHARS) {
                console.println(body.substring(0, MAX_BODY_CHARS));
                console.println("[REQUEST BODY TRUNCATED, " + bytes.length + " bytes total]");
            } else {
                console.println(body);
            }
        }
        console.println("========== END HTTP 403 REQUEST ==========");
        console.println();
    }

}
