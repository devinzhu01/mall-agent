package com.mall.agent.trace;

/**
 * 链路追踪的最小接口:工具层和检索层只依赖它,具体记录实现见 RequestTracer。
 * 放在接口后面是为了单测里可以传 NOOP。
 */
public interface Tracer {

    Tracer NOOP = new Tracer() {
    };

    /** 一次工具调用:名称、已脱敏参数、耗时、是否成功。 */
    default void tool(String name, String argsJson, long millis, boolean ok) {
    }

    /** 链路里的一个普通步骤(如改写后的 query、召回数量)。 */
    default void step(String name, String detail) {
    }
}
