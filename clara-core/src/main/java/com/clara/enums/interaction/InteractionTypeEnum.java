package com.clara.enums.interaction;

/**
 * 交互类型，驱动问题的渲染与答案校验。
 */
public enum InteractionTypeEnum {
    /** 文本输入。 */
    TEXT_INPUT,
    /** 单选。 */
    SINGLE_SELECT,
    /** 多选。 */
    MULTI_SELECT,
    /** 确认。 */
    CONFIRMATION,
    /** 文件（选择或上传）。 */
    FILE,
    /** 资源上传。 */
    ASSET_UPLOAD,
    /** 变更审批。 */
    CHANGE_APPROVAL
}
