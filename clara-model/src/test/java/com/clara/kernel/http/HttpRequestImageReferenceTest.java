package com.clara.kernel.http;

import com.clara.context.ConvContext;
import com.clara.enums.CapabilityKindEnum;
import com.clara.kernel.config.ProviderConfiguration;
import com.clara.model.configuration.ProviderYamlConfigurationLoader;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 图片生成能力请求线格式的单元测试：{@code prompt} 与参考图 {@code image}（图生图）字段的下发。
 *
 * <p>用内联密钥的临时 VOLCENGINE 配置做离线编码，不访问网络。参考图字段当前只为 VOLCENGINE 声明适配。
 */
public class HttpRequestImageReferenceTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void encodesPromptAndReferenceImages() throws Exception {
        try (ProviderConfiguration provider = loadVolcengineImage()) {
            String body = encode(provider, Arrays.asList("data:image/png;base64,AAAA"), "把参考图改成油画风格");

            assertTrue(body.contains("把参考图改成油画风格"));
            assertTrue(body.contains("\"image\""));
            assertTrue(body.contains("data:image/png;base64,AAAA"));
        }
    }

    @Test
    public void omitsReferenceImagesWhenAbsent() throws Exception {
        try (ProviderConfiguration provider = loadVolcengineImage()) {
            String body = encode(provider, null, "一只在雪地里的猫");

            assertTrue(body.contains("一只在雪地里的猫"));
            assertFalse(body.contains("\"image\""));
        }
    }

    private static String encode(ProviderConfiguration provider, java.util.List<String> images, String prompt) {
        ConvContext context = new ConvContext(null, "c1", provider, CapabilityKindEnum.IMAGE);
        try (ConvContext.Scope ignored = ConvContext.open(context)) {
            HttpRequest request = HttpRequest.builder()
                    .model(provider.toConfig(CapabilityKindEnum.IMAGE).getModelId())
                    .prompt(prompt)
                    .image(images)
                    .stream(Boolean.FALSE)
                    .build();
            return new String(request.toRequest().getBody(), StandardCharsets.UTF_8);
        }
    }

    private ProviderConfiguration loadVolcengineImage() throws Exception {
        Path file = folder.newFile("volcengine-image.yml").toPath();
        Files.write(file, volcengineImageYaml().getBytes(StandardCharsets.UTF_8));
        return new ProviderYamlConfigurationLoader().load(file);
    }

    private static String volcengineImageYaml() {
        return "provider: VOLCENGINE\n"
                + "credential:\n"
                + "  secretRef: volcengine\n"
                + "  apiKey: test-key\n"
                + "transport:\n"
                + "  allowedOrigins:\n"
                + "    - https://ark.cn-beijing.volces.com\n"
                + "  connectTimeoutMillis: 10000\n"
                + "  readTimeoutMillis: 180000\n"
                + "  maxRequestBytes: 16777216\n"
                + "  maxResponseBytes: 33554432\n"
                + "  allowHttp: false\n"
                + "capabilities:\n"
                + "  IMAGE:\n"
                + "    endpoint: https://ark.cn-beijing.volces.com/api/v3/images/generations\n"
                + "    modelId: doubao-seedream-5-0-pro-260628\n"
                + "    apiProfile: ark-v3-images\n"
                + "    parameters:\n"
                + "      maxPromptChars: 32000\n";
    }
}
