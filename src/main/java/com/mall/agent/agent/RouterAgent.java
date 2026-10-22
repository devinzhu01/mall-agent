package com.mall.agent.agent;

import com.mall.agent.config.AgentProperties;
import com.mall.agent.llm.Llm;
import com.mall.agent.memory.ChatMessage;
import com.mall.agent.memory.MemoryContext;
import com.mall.agent.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 请求链路第 2 步:意图路由。一次轻量的模型调用(用便宜的小模型,模型名见 agent.router-model),
 * 只输出结构化的 {intent, category, reply}。只看摘要和最近 4 条消息,不看偏好,保持便宜、快。
 * 任何失败都降级到 KNOWLEDGE:知识问答专家只读、风险最低。
 */
@Component
public class RouterAgent {

    public record Route(String intent, String category, String reply) {
    }

    static final Set<String> CATEGORIES = Set.of("售后", "物流", "会员营销", "支付账户", "选购指南", "活动规则");
    private static final int RECENT = 4;
    private static final String DEFAULT_CHITCHAT = "您好,我是商城助手,可以帮您推荐商品、查询订单、解答售后和平台规则等问题。";
    private static final Logger log = LoggerFactory.getLogger(RouterAgent.class);

    private static final String SYSTEM = """
            你是电商助手的意图路由器。根据对话上下文判断用户最新一句话的意图,只能选一个:
            RECOMMEND:想买东西、要推荐商品、选购建议、比较商品;
            ORDER:查询订单、物流、订单状态,或针对某个具体订单办理退换货/售后;
            KNOWLEDGE:询问平台规则、政策、流程(退换货规则、运费、发票、会员积分、优惠券、支付账户、活动规则等一般性问题);
            CHITCHAT:寒暄、感谢、与购物无关的闲聊。
            如果一句话包含多个意图,选最主要、最先要解决的那个。
            category 仅在意图是 KNOWLEDGE 且非常确定属于某一类时填写,可选:售后/物流/会员营销/支付账户/选购指南/活动规则;拿不准就填 null。
            意图是 CHITCHAT 时在 reply 里给出一句简短友好的回复,否则 reply 填 null。
            只输出 JSON:{"intent":"...","category":null,"reply":null}""";

    private final Llm llm;
    private final AgentProperties props;

    public RouterAgent(Llm llm, AgentProperties props) {
        this.llm = llm;
        this.props = props;
    }

    public RouteDecision route(MemoryContext ctx, String message, Tracer tracer) {
        try {
            Route r = llm.json(tracer, "route", props.getRouterModel(), SYSTEM, userPrompt(ctx, message), Route.class);
            Intent intent = parseIntent(r.intent());
            String category = r.category() != null && CATEGORIES.contains(r.category()) ? r.category() : null;
            String reply = null;
            if (intent == Intent.CHITCHAT) {
                reply = r.reply() == null || r.reply().isBlank() ? DEFAULT_CHITCHAT : r.reply();
            }
            return new RouteDecision(intent, category, reply);
        } catch (Exception e) {
            log.warn("routing failed, falling back to KNOWLEDGE: {}", e.toString());
            return new RouteDecision(Intent.KNOWLEDGE, null, null);
        }
    }

    private static Intent parseIntent(String raw) {
        try {
            return Intent.valueOf(raw == null ? "" : raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Intent.KNOWLEDGE;
        }
    }

    private static String userPrompt(MemoryContext ctx, String message) {
        StringBuilder sb = new StringBuilder();
        if (ctx.summary() != null && !ctx.summary().isBlank()) {
            sb.append("对话摘要:").append(ctx.summary()).append("\n\n");
        }
        List<ChatMessage> recent = ctx.recent();
        sb.append("最近对话:\n");
        for (ChatMessage m : recent.subList(Math.max(0, recent.size() - RECENT), recent.size())) {
            sb.append(m.role()).append(": ").append(m.content()).append('\n');
        }
        return sb.append("\n用户最新一句话:").append(message).toString();
    }
}
