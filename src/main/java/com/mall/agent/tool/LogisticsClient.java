package com.mall.agent.tool;

import com.mall.agent.config.AgentProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 外部物流接口客户端。容错三层:HTTP 超时 → 有限重试(只读,幂等)→ 熔断。
 * 任何失败都降级成 LogisticsInfo.unavailable(),由工具层如实告诉用户。
 */
@Component
public class LogisticsClient {

    private static final Logger log = LoggerFactory.getLogger(LogisticsClient.class);

    private final Function<String, LogisticsInfo> fetcher;
    private final CircuitBreaker breaker = CircuitBreaker.of("logistics", CircuitBreakerConfig.custom()
            .slidingWindowSize(5).minimumNumberOfCalls(5).failureRateThreshold(50)
            .waitDurationInOpenState(Duration.ofSeconds(10)).build());
    private final Retry retry = Retry.of("logistics", RetryConfig.custom()
            .maxAttempts(2).waitDuration(Duration.ofMillis(100))
            .ignoreExceptions(CallNotPermittedException.class).build());

    @Autowired
    public LogisticsClient(AgentProperties props) {
        this(httpFetcher(props.getLogistics()));
    }

    /** 测试用:直接注入取数函数。 */
    LogisticsClient(Function<String, LogisticsInfo> fetcher) {
        this.fetcher = fetcher;
    }

    public LogisticsInfo query(String orderNo) {
        Supplier<LogisticsInfo> call = () -> fetcher.apply(orderNo);
        try {
            return Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(breaker, call)).get();
        } catch (Exception e) {
            log.warn("logistics query degraded for order ending {}: {}", tail(orderNo), e.toString());
            return LogisticsInfo.unavailable();
        }
    }

    private static Function<String, LogisticsInfo> httpFetcher(AgentProperties.Logistics cfg) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(cfg.getTimeoutMs());
        factory.setReadTimeout(cfg.getTimeoutMs());
        RestClient client = RestClient.builder().requestFactory(factory).baseUrl(cfg.getBaseUrl()).build();
        return orderNo -> client.get().uri("/{no}", orderNo).retrieve().body(LogisticsInfo.class);
    }

    private static String tail(String s) {
        return s.length() <= 4 ? s : s.substring(s.length() - 4);
    }
}
