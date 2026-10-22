package com.mall.agent.tool;

import com.mall.agent.config.AgentProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/** 模拟"外部物流服务":按概率变慢(触发超时)或返回 500,用来演示容错链路。 */
@RestController
@RequestMapping("/mock/logistics")
public class MockLogisticsController {

    private final AgentProperties props;

    public MockLogisticsController(AgentProperties props) {
        this.props = props;
    }

    @GetMapping("/{orderNo}")
    public LogisticsInfo query(@PathVariable String orderNo) throws InterruptedException {
        if (ThreadLocalRandom.current().nextDouble() < props.getLogistics().getMockFailRate()) {
            if (ThreadLocalRandom.current().nextBoolean()) {
                Thread.sleep(3000);
            } else {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "mock failure");
            }
        }
        return new LogisticsInfo(true, "顺丰速运", "运输中", List.of(
                new LogisticsInfo.Event("2026-10-02 09:00", "快件已揽收"),
                new LogisticsInfo.Event("2026-10-02 21:30", "快件已到达上海转运中心"),
                new LogisticsInfo.Event("2026-10-03 08:10", "快件正在派送途中")));
    }
}
