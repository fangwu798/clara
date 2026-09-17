package com.clara.novel.cli;

import com.clara.kernel.CancellationToken;
import com.clara.kernel.InvocationContext;
import com.clara.model.provider.http.impl.ProviderHttpRequest;
import com.clara.model.provider.http.impl.ProviderHttpResponse;
import com.clara.model.provider.http.ProviderHttpTransport;
import com.clara.model.provider.http.ProviderSseListener;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

public class ForbiddenRequestLoggingTransportTest {
    @Test public void logsActualRequestOn403IncludingCredential() throws Exception {
        final ProviderHttpResponse forbidden = new ProviderHttpResponse(403,
                Collections.<String, java.util.List<String>>emptyMap(), new byte[0]);
        ProviderHttpTransport delegate = new ProviderHttpTransport() {
            public ProviderHttpResponse execute(ProviderHttpRequest request,
                    InvocationContext context, ProviderSseListener listener) { return forbidden; }
        };
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        ForbiddenRequestLoggingTransport transport = new ForbiddenRequestLoggingTransport(
                delegate, new PrintStream(captured, true, "UTF-8"));
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer secret-key-value");
        headers.put("Content-Type", "application/json");
        ProviderHttpRequest request = new ProviderHttpRequest("POST",
                URI.create("https://example.test/v1/chat/completions"), headers,
                "{\"model\":\"gpt-6-astra\"}".getBytes(StandardCharsets.UTF_8));

        assertSame(forbidden, transport.execute(request, context()));

        String log = captured.toString("UTF-8");
        assertTrue(log.contains("POST https://example.test/v1/chat/completions"));
        assertTrue(log.contains("Authorization: Bearer secret-key-value"));
        assertTrue(log.contains("\"model\":\"gpt-6-astra\""));
    }

    private static InvocationContext context() {
        return new InvocationContext("local", "test", "run", "trace",
                System.currentTimeMillis() + 10000L, new CancellationToken());
    }
}
