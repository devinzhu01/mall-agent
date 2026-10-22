package com.mall.agent.agent;

import com.mall.agent.config.AgentProperties;
import com.mall.agent.memory.ChatMessage;
import com.mall.agent.tool.ToolBox;
import com.mall.agent.trace.RequestTracer;
import com.mall.agent.trace.TraceStep;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.tool.annotation.Tool;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用假的"千问服务器"(按 OpenAI 流式协议返回 tool_calls)驱动真实的 Spring AI 工具调用流程,
 * 验证:流式工具调用会真的执行我们的 GuardedToolCallback、拿到 toolContext 里的 userId、并记进 Trace。
 */
class ExpertChatStreamingToolsTest {

    static final List<String> seenUserIds = new CopyOnWriteArrayList<>();

    static class Tools {
        @Tool(description = "返回当前用户的暗号")
        String whoAmI(ToolContext ctx) {
            seenUserIds.add(String.valueOf(ctx.getContext().get("userId")));
            return "{\"ok\":true,\"data\":\"pong\"}";
        }
    }

    HttpServer server;
    final List<String> requestBodies = new CopyOnWriteArrayList<>();

    @BeforeEach
    void startFakeQwen() throws Exception {
        seenUserIds.clear();
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(body);
            boolean afterToolResult = body.contains("\"role\":\"tool\"");
            String sse = afterToolResult ? finalAnswer() : toolCallAnswer();
            byte[] bytes = sse.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, bytes.length);
            ex.getResponseBody().write(bytes);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String chunk(String delta, String finish) {
        return "data: {\"id\":\"c1\",\"object\":\"chat.completion.chunk\",\"model\":\"qwen-plus\",\"choices\":[{\"index\":0,\"delta\":"
                + delta + ",\"finish_reason\":" + finish + "}]}\n\n";
    }

    private static String toolCallAnswer() {
        return chunk("{\"role\":\"assistant\",\"content\":\"\",\"tool_calls\":[{\"index\":0,\"id\":\"call_1\",\"type\":\"function\","
                + "\"function\":{\"name\":\"whoAmI\",\"arguments\":\"\"}}]}", "null")
                + chunk("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{}\"}}]}", "null")
                + chunk("{}", "\"tool_calls\"")
                + "data: [DONE]\n\n";
    }

    private static String finalAnswer() {
        return chunk("{\"role\":\"assistant\",\"content\":\"你好\"}", "null")
                + chunk("{\"content\":\",已查到\"}", "null")
                + chunk("{}", "\"stop\"")
                + "data: [DONE]\n\n";
    }

    @Test
    void streamingToolCallExecutesGuardedToolWithUserIdAndIsTraced() {
        OpenAiApi api = OpenAiApi.builder().baseUrl("http://localhost:" + server.getAddress().getPort()).apiKey("k").build();
        OpenAiChatModel model = OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model("qwen-plus").build()).build();
        ExpertChat chat = new ExpertChat(ChatClient.builder(model), "qwen-plus");
        RequestTracer tracer = new RequestTracer(new SimpleMeterRegistry());
        var tools = new ToolBox(new AgentProperties()).build(List.of(new Tools()), tracer);

        List<String> chunks = chat.stream("SYS", List.<ChatMessage>of(), "我是谁", tools, "u1001", tracer)
                .collectList().block();

        assertThat(String.join("", chunks)).isEqualTo("你好,已查到");
        assertThat(seenUserIds).containsExactly("u1001");
        List<TraceStep> toolSteps = tracer.finish().steps().stream().filter(s -> s.type().equals("tool")).toList();
        assertThat(toolSteps).extracting(TraceStep::name).containsExactly("whoAmI");
        assertThat(toolSteps.get(0).ok()).isTrue();
        // 第二次请求把工具结果回灌给了模型
        assertThat(requestBodies).hasSize(2);
        assertThat(requestBodies.get(1)).contains("pong");
    }
}
