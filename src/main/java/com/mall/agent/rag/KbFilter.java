package com.mall.agent.rag;

/** 检索过滤条件:onlyEffective 默认只查当前生效版本;category 来自 Router 的分类(可空)。 */
public record KbFilter(boolean onlyEffective, String category) {
}
