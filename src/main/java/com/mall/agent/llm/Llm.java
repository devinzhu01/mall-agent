package com.mall.agent.llm;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mall.agent.eval.ChatCache;
import com.mall.agent.trace.Tracer;
import com.mall.agent.trace.Usage;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 非流式的"一问一答"模型调用,供改写、路由、摘要、偏好抽取、评测裁判等内部步骤使用。
 * 统一做三件事:温度固定为 0、从响应里取 token 用量记入 Trace、把 JSON 输出解析成对象。
 * (面向用户的流式回答不走这里,见 ExpertChat。)评测配置下会经过 ChatCache,线上没有缓存。
 */
@Component
public class Llm {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final ChatClient client;
    private final ObjectProvider<ChatCache> cache;

    public Llm(ChatClient.Builder builder, ObjectProvider<ChatCache> cache) {
        this.client = builder.build();
        this.cache = cache;
    }

    /** model 为空则用 yml 里配置的默认对话模型。 */
    public String text(Tracer tracer, String stage, String model, String system, String user) {
        ChatCache cached = cache.getIfAvailable();
        String key = cached == null ? null : ChatCache.key(String.valueOf(model), system, user);
        if (key != null) {
            var hit = cached.get(key);
            if (hit.isPresent()) {
                tracer.step("llm.cache-hit", stage);
                return hit.get();
            }
        }

        long start = System.currentTimeMillis();
        var prompt = client.prompt().system(system).user(user);
        OpenAiChatOptions.Builder options = OpenAiChatOptions.builder().temperature(0.0);
        if (model != null) {
            options.model(model);
        }
        ChatResponse response = prompt.options(options.build()).call().chatResponse();
        String content = response.getResult().getOutput().getText();
        tracer.llm(stage, model == null ? "default" : model, System.currentTimeMillis() - start,
                usage(response, system + user, content));
        if (key != null) {
            cached.put(key, content);
        }
        return content;
    }

    public <T> T json(Tracer tracer, String stage, String model, String system, String user, Class<T> type) {
        String raw = text(tracer, stage, model, system, user);
        try {
            return MAPPER.readValue(extractJson(raw), type);
        } catch (Exception e) {
            throw new IllegalStateException("模型没有返回合法 JSON(" + stage + "): " + abbreviate(raw), e);
        }
    }

    /** 模型常把 JSON 包在 ```json 围栏里,或前后带说明文字:取第一个 { 到最后一个 }。 */
    static String extractJson(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end < start) {
            throw new IllegalArgumentException("no json object");
        }
        return raw.substring(start, end + 1);
    }

    private static Usage usage(ChatResponse response, String prompt, String completion) {
        var u = response.getMetadata() == null ? null : response.getMetadata().getUsage();
        if (u != null && u.getTotalTokens() != null && u.getTotalTokens() > 0) {
            return new Usage(u.getPromptTokens(), u.getCompletionTokens(), false);
        }
        // 兼容端点没返回 usage 时按字符数粗估(中文约 1.5 字符/token),并标记 estimated
        return new Usage((int) (prompt.length() / 1.5), (int) (completion.length() / 1.5), true);
    }

    private static String abbreviate(String s) {
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
