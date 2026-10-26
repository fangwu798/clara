package com.clara.adapter.console;

import com.clara.dto.model.StreamEvent;
import com.clara.enums.StreamEventEnum;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

/**
 * 多角色展示测试：同一角色颜色稳定、不同角色颜色不同，底部状态行随发言人变化。
 */
public class ConsoleViewRoleColorTest {

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    private ConsoleView view;

    @Before
    public void setUp() throws Exception {
        view = new ConsoleView(folder.newFolder("logs-role").toPath(), false, "never");
    }

    @After
    public void tearDown() {
        if (view != null) {
            view.close();
        }
    }

    @Test
    public void keepsOneColorPerSpeakerAndSeparatesDifferentSpeakers() {
        String architect = view.roleColor("architect");
        String architectAgain = view.roleColor(" architect ");
        String reviewer = view.roleColor("reviewer");

        assertEquals("同一角色（忽略首尾空白）必须保持同一颜色", architect, architectAgain);
        assertNotEquals("不同角色应使用不同颜色", architect, reviewer);
    }

    @Test
    public void speakerStatusRefreshesAndResets() {
        AtomicInteger refreshes = new AtomicInteger();
        view.setStatusRefresh(refreshes::incrementAndGet);
        refreshes.set(0);

        view.setSpeakerStatus("architect", "deepseek-v4-pro");
        assertEquals("architect · deepseek-v4-pro", view.speakerStatus());
        assertEquals(1, refreshes.get());

        view.setSpeakerStatus(null, null);
        assertNull("讨论结束后恢复会话默认模型", view.speakerStatus());
        assertEquals(2, refreshes.get());
    }

    @Test
    public void keepsWorkingVisibleWhileThinkingAndAnswering() {
        view.setStatusRefresh(() -> {
        });
        view.startWorking();
        assertNotNull("执行开始后底部应展示 Working 计时", view.workingElapsedLabel());

        ConsoleView.ConsoleStreamObserver observer = view.newObserver(null);
        observer.onEvent(new StreamEvent(StreamEventEnum.REASONING_DELTA, 0L, "分析中"));
        assertNotNull("思考流式输出期间不能停掉 Working", view.workingElapsedLabel());

        observer.onEvent(new StreamEvent(StreamEventEnum.TEXT_DELTA, 1L, "回答"));
        assertNotNull("正文流式输出期间仍需保持 Working 计时", view.workingElapsedLabel());

        view.stopWorking();
        assertNull("本轮结束后 Working 消失", view.workingElapsedLabel());
    }
}
