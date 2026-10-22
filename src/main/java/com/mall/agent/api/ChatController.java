package com.mall.agent.api;

import com.mall.agent.agent.AgentOrchestrator;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * 对话入口。SSE 事件:trace(traceId,第一个事件)→ message(回答片段,多个)→ done。
 * 校验失败在这里直接 400,此时还没有调用任何模型。
 */
@RestController
public class ChatController {

    public record ChatRequest(String sessionId, String message) {
    }

    static final int MAX_MESSAGE_CHARS = 2000;
    static final int MAX_SESSION_ID_CHARS = 64;

    private final AgentOrchestrator orchestrator;

    public ChatController(AgentOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chat(@RequestHeader("X-User-Id") String userId,
                                              @RequestBody ChatRequest request) {
        validate(request);
        AgentOrchestrator.Run run = orchestrator.open(userId, request.sessionId(), request.message().trim());
        return Flux.concat(
                Flux.just(event("trace", run.traceId())),
                run.chunks().map(chunk -> event("message", chunk)),
                Flux.just(event("done", "[DONE]")));
    }

    private static void validate(ChatRequest r) {
        if (r == null || r.sessionId() == null || r.sessionId().isBlank() || r.sessionId().length() > MAX_SESSION_ID_CHARS) {
            throw new IllegalArgumentException("sessionId 不能为空,且不超过 " + MAX_SESSION_ID_CHARS + " 个字符");
        }
        if (r.message() == null || r.message().isBlank() || r.message().length() > MAX_MESSAGE_CHARS) {
            throw new IllegalArgumentException("message 不能为空,且不超过 " + MAX_MESSAGE_CHARS + " 个字符");
        }
    }

    private static ServerSentEvent<String> event(String name, String data) {
        return ServerSentEvent.<String>builder().event(name).data(data).build();
    }
}
