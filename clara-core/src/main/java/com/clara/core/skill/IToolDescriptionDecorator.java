package com.clara.core.skill;

/**
 * 工具描述装饰器：在把工具声明下发给模型前，按工具名补充运行期信息。
 *
 * <p>是什么、在哪一层：core 定义的契约接口（包 {@code com.clara.core.skill}），由宿主（application）实现、
 * 由 clara-skill 的工具目录在组装工具声明时调用。典型用途是把「当前可用的工作流清单」拼进内置工具
 * {@code run_workflow} 的描述，使运行期新生成的自定义工作流无需重启即可被模型看到并按名调用。
 *
 * <p>约定：实现应为只读、可并发调用；对不需要装饰的工具原样返回入参描述即可。
 */
public interface IToolDescriptionDecorator {

    /**
     * 装饰工具描述。
     *
     * @param toolName    工具名，非 null
     * @param description 工具声明的原始描述，可为 null
     * @return 装饰后的描述；返回 null 或空白表示保持原始描述
     */
    String decorate(String toolName, String description);
}
