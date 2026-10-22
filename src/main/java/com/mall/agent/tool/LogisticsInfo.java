package com.mall.agent.tool;

import java.util.List;

public record LogisticsInfo(boolean available, String carrier, String status, List<Event> events) {

    public record Event(String time, String desc) {
    }

    /** 下游超时/失败/熔断时的降级结果:明确"拿不到",绝不编造轨迹。 */
    public static LogisticsInfo unavailable() {
        return new LogisticsInfo(false, null, null, List.of());
    }
}
