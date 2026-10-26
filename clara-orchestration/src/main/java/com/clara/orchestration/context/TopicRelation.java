package com.clara.orchestration.context;

/**
 * 当前用户输入与上一话题的关系。
 */
enum TopicRelation {

    /** 延续当前话题。 */
    CONTINUE,

    /** 明确切换到新话题。 */
    SHIFT,

    /** 话题不明确，可能是追问，也可能是新话题。 */
    AMBIGUOUS,

    /** 用户显式引用之前的某个话题。 */
    EXPLICIT_REFERENCE
}
