package com.clara.core.memory;

import java.util.List;

/**
 * 上下文补充信息提供者：装配本轮提示词前先向外部知识源取一次知识，命中就注入 system 指令。
 *
 * <p>是什么、在哪一层：core 定义的契约接口（包 {@code com.clara.core.memory}），与
 * {@link ISemanticMemoryStore} 并列——后者找的是「本会话历史」，本接口找的是「代码库的既有事实」
 * （当前落地实现是代码知识图谱：符号、文件大纲、调用关系）。编排层只负责「何时问、怎么注入」，
 * 具体向谁问、怎么问由宿主装配时注入。
 *
 * <p>为什么要有它：模型回答代码相关问题时，先给一份「从图谱查出来的事实」比让它自己翻文件更省 token、
 * 也更准；图谱里没有的（未索引、新文件），返回空列表，模型自然会改用工具查本地文件。
 *
 * <p>失败语义：实现方必须自行兜底——外部知识源不可用、超时、未索引等情况一律返回空列表，不得抛异常，
 * 否则会阻断整轮对话。
 */
public interface IContextSupplementProvider {

    /**
     * 按当前用户输入取补充信息。
     *
     * @param userInput 当前用户输入，可为 null
     * @return 补充信息文本块（每项一段，注入 system 指令）；没有命中时返回空列表，非 null
     */
    List<String> supplement(String userInput);
}
