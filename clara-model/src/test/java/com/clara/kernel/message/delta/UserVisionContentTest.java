package com.clara.kernel.message.delta;

import com.clara.enums.RoleEnum;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * {@link UserVisionContent} 的单元测试：Chat Completions 与 Responses 两种协议下的「文本 + 图片」渲染。
 */
public class UserVisionContentTest {

    @Test
    public void rendersTextAndImageForCompletion() {
        UserVisionContent content = new UserVisionContent("描述这张图", Arrays.asList("https://example.com/a.png"));

        Map<String, Object> message = content.toCompletionMessage();

        assertEquals("user", message.get("role"));
        List<?> parts = (List<?>) message.get("content");
        assertEquals(2, parts.size());
        Map<?, ?> textPart = (Map<?, ?>) parts.get(0);
        assertEquals("text", textPart.get("type"));
        assertEquals("描述这张图", textPart.get("text"));
        Map<?, ?> imagePart = (Map<?, ?>) parts.get(1);
        assertEquals("image_url", imagePart.get("type"));
        Map<?, ?> reference = (Map<?, ?>) imagePart.get("image_url");
        assertEquals("https://example.com/a.png", reference.get("url"));
    }

    @Test
    public void rendersTextAndImageForResponses() {
        UserVisionContent content = new UserVisionContent("描述这张图",
                Arrays.asList("data:image/png;base64,AAAA"));

        List<Map<String, Object>> items = content.toResponseInputItems();

        assertEquals(1, items.size());
        Map<String, Object> item = items.get(0);
        assertEquals("message", item.get("type"));
        assertEquals("user", item.get("role"));
        List<?> parts = (List<?>) item.get("content");
        assertEquals(2, parts.size());
        assertEquals("input_text", ((Map<?, ?>) parts.get(0)).get("type"));
        Map<?, ?> imagePart = (Map<?, ?>) parts.get(1);
        assertEquals("input_image", imagePart.get("type"));
        assertEquals("data:image/png;base64,AAAA", imagePart.get("image_url"));
    }

    @Test
    public void omitsBlankTextForBothProtocols() {
        UserVisionContent content = new UserVisionContent("   ", Arrays.asList("https://example.com/a.png"));

        List<?> completionParts = (List<?>) content.toCompletionMessage().get("content");
        assertEquals(1, completionParts.size());
        assertEquals("image_url", ((Map<?, ?>) completionParts.get(0)).get("type"));

        List<?> responseParts = (List<?>) content.toResponseInputItems().get(0).get("content");
        assertEquals(1, responseParts.size());
        assertEquals("input_image", ((Map<?, ?>) responseParts.get(0)).get("type"));
    }

    @Test
    public void roleTypeAndImagesDefaultsAreSafe() {
        UserVisionContent content = new UserVisionContent(null, null);

        assertEquals(RoleEnum.USER, content.getRoleType());
        assertEquals("user", content.getRole());
        assertEquals(0, content.getImages().size());
    }
}
