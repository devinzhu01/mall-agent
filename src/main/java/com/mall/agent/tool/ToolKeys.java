package com.mall.agent.tool;

import org.springframework.ai.chat.model.ToolContext;

/** ToolContext 里的键。userId 只能从这里取,绝不作为模型可传的工具参数(防越权)。 */
public final class ToolKeys {
    public static final String USER_ID = "userId";

    private ToolKeys() {
    }

    public static String userId(ToolContext ctx) {
        Object v = ctx == null ? null : ctx.getContext().get(USER_ID);
        if (v == null || v.toString().isBlank()) {
            throw new ToolException("UNAUTHORIZED", "无法确认当前用户", false, null);
        }
        return v.toString();
    }
}
