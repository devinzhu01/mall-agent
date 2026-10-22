package com.mall.agent.trace;

/** 一次模型调用的 token 用量。estimated=true 表示响应里没有 usage,是按字符数估算的。 */
public record Usage(int promptTokens, int completionTokens, boolean estimated) {

    public int total() {
        return promptTokens + completionTokens;
    }
}
