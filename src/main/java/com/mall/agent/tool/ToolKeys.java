package com.mall.agent.tool;

/** ToolContext 里的键。userId 只能从这里取,绝不作为模型可传的工具参数(防越权)。 */
public final class ToolKeys {
    public static final String USER_ID = "userId";

    private ToolKeys() {
    }
}
