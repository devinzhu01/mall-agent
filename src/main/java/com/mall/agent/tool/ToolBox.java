package com.mall.agent.tool;

import com.mall.agent.config.AgentProperties;
import com.mall.agent.trace.Tracer;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** 把一组 @Tool 对象变成带保护的回调。每次 build 对应一个请求,共享同一个失败预算。 */
@Component
public class ToolBox {

    private final AgentProperties props;

    public ToolBox(AgentProperties props) {
        this.props = props;
    }

    public ToolCallback[] build(List<Object> toolBeans, Tracer tracer) {
        AtomicInteger failures = new AtomicInteger();
        int max = props.getTool().getMaxFailures();
        ToolCallback[] raw = ToolCallbacks.from(toolBeans.toArray());
        ToolCallback[] guarded = new ToolCallback[raw.length];
        for (int i = 0; i < raw.length; i++) {
            guarded[i] = new GuardedToolCallback(raw[i], failures, max, tracer);
        }
        return guarded;
    }
}
