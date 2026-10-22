package com.mall.agent.trace;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/** 每个请求开始时创建 RequestTracer,并把 traceId 放进日志 MDC,使同一请求的所有日志可以串起来。 */
@Component
public class TraceService {

    private final MeterRegistry meters;

    public TraceService(MeterRegistry meters) {
        this.meters = meters;
    }

    public RequestTracer start() {
        RequestTracer tracer = new RequestTracer(meters);
        MDC.put("traceId", tracer.traceId());
        return tracer;
    }

    public void end() {
        MDC.remove("traceId");
    }
}
