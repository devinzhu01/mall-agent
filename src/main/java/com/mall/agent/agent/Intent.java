package com.mall.agent.agent;

/** Router 的分类结果。CHITCHAT 不进专家,由 Router 直接回复。 */
public enum Intent {
    RECOMMEND,   // 商品推荐、选购
    ORDER,       // 订单、物流、与具体订单相关的售后办理
    KNOWLEDGE,   // 平台规则、政策、流程等知识问答
    CHITCHAT     // 寒暄、与业务无关的闲聊
}
