package com.mall.agent.agent;

/**
 * category 是知识库类别(可空,用于检索的元数据过滤);reply 仅 CHITCHAT 时有值。
 * intent 为 null 表示"没有确定意图"(单 Agent 模式、或路由失败/无法识别):交给拥有全部工具的专家处理。
 */
public record RouteDecision(Intent intent, String category, String reply) {

    public static RouteDecision allTools() {
        return new RouteDecision(null, null, null);
    }
}
