package com.mall.agent.agent;

/**
 * category 是知识库类别(可空,用于检索的元数据过滤);reply 仅 CHITCHAT 时有值。
 * intent 为 null 表示单 Agent 模式(没有经过路由)。
 */
public record RouteDecision(Intent intent, String category, String reply) {

    public static RouteDecision single() {
        return new RouteDecision(null, null, null);
    }
}
