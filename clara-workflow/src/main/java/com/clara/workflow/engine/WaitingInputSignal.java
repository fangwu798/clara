package com.clara.workflow.engine;

/**
 * 人工输入等待信号：节点所需的输入尚未提供时抛出，由引擎捕获并转换为等待状态结果。
 */
final class WaitingInputSignal extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 等待输入的节点标识。 */
    final String nodeId;

    /** 等待的输入键。 */
    final String inputKey;

    /**
     * @param nodeId 等待输入的节点标识
     * @param inputKey 等待的输入键
     */
    WaitingInputSignal(String nodeId, String inputKey) {
        super("waiting input: " + inputKey + " for node " + nodeId);
        this.nodeId = nodeId;
        this.inputKey = inputKey;
    }
}
