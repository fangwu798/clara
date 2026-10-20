package com.clara.enums;

/**
 * 可用性检查行的状态级别：只表达语义，供调用方决定呈现方式（如终端着色）。
 */
public enum AvailabilityLevelEnum {

    /** 普通信息行（进度提示、表头）。 */
    INFO,

    /** 检查通过：模型可用。 */
    OK,

    /** 未发起真实请求或未取得正文的中间状态：跳过检查。 */
    WARN,

    /** 检查未通过：模型不可用。 */
    FAIL
}
