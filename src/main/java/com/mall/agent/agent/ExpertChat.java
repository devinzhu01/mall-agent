package com.mall.agent.agent;

import com.mall.agent.memory.ChatMessage;
import com.mall.agent.tool.ToolKeys;
import com.mall.agent.trace.Tracer;
import com.mall.agent.trace.Usage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 专家调用模型并流式输出。工具调用循环(模型选工具 → 执行 → 结果回灌 → 继续生成)由 Spring AI 内部完成,
 * 我们提供带保护的 ToolCallback(见 GuardedToolCallback)和 toolContext(userId 就是这样传给工具的)。
 * 单独成类是为了把"真正调用模型"这一步隔离出来,编排逻辑可以用 Mock 测试。
 */
@Component
public class ExpertChat {

    private final ChatClient client;
    private final String model;

    public ExpertChat(ChatClient.Builder builder,
                      @Value("${spring.ai.openai.chat.options.model:qwen-plus}") String model) {
        this.client = builder.build();
        this.model = model;
    }

    public Flux<String> stream(String systemPrompt, List<ChatMessage> history, String userMessage,
                               ToolCallback[] tools, String userId, Tracer tracer) {
        long start = System.currentTimeMillis();
        AtomicReference<Usage> usage = new AtomicReference<>();
        AtomicInteger outChars = new AtomicInteger();

        return client.prompt()
                .system(systemPrompt)
                .messages(toMessages(history))
                .user(userMessage)
                .toolCallbacks(tools)
                .toolContext(Map.of(ToolKeys.USER_ID, userId))
                // 兼容端点默认流式不返回 usage,需要显式要求,才能统计 token
                .options(OpenAiChatOptions.builder().streamUsage(true).build())
                .stream().chatResponse()
                .doOnNext(r -> {
                    var u = r.getMetadata() == null ? null : r.getMetadata().getUsage();
                    if (u != null && u.getTotalTokens() != null && u.getTotalTokens() > 0) {
                        usage.set(new Usage(u.getPromptTokens(), u.getCompletionTokens(), false));
                    }
                })
                .mapNotNull(r -> r.getResult() == null ? null : r.getResult().getOutput().getText())
                .filter(s -> !s.isEmpty())
                .doOnNext(s -> outChars.addAndGet(s.length()))
                .doOnComplete(() -> {
                    Usage u = usage.get() != null ? usage.get()
                            : new Usage((int) ((systemPrompt.length() + userMessage.length()) / 1.5),
                            (int) (outChars.get() / 1.5), true);
                    tracer.llm("expert", model, System.currentTimeMillis() - start, u);
                });
    }

    private static List<Message> toMessages(List<ChatMessage> history) {
        return history.stream()
                .<Message>map(m -> m.role().equals("assistant") ? new AssistantMessage(m.content()) : new UserMessage(m.content()))
                .toList();
    }
}
