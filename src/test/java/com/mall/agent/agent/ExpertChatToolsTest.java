package com.mall.agent.agent;

import com.mall.agent.memory.ChatMessage;
import com.mall.agent.tool.GuardedToolCallback;
import com.mall.agent.tool.ToolBox;
import com.mall.agent.config.AgentProperties;
import com.mall.agent.trace.Tracer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.annotation.Tool;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 模型调用的接缝:工具回调和 toolContext 必须真的出现在发给模型的 Prompt 选项里,否则模型永远不会调用工具。 */
class ExpertChatToolsTest {

    static class Dummy {
        @Tool(description = "测试工具")
        String ping() {
            return "pong";
        }
    }

    @Test
    void toolCallbacksAndContextReachTheModelRequest() {
        ChatModel model = mock(ChatModel.class);
        when(model.stream(any(Prompt.class))).thenReturn(
                Flux.just(new ChatResponse(List.of(new Generation(new AssistantMessage("好的"))))));
        ExpertChat chat = new ExpertChat(ChatClient.builder(model), "qwen-plus");
        ToolCallback[] tools = new ToolBox(new AgentProperties()).build(List.of(new Dummy()), Tracer.NOOP);

        chat.stream("SYS", List.of(ChatMessage.user("hi")), "推荐一部手机", tools, "u1001", Tracer.NOOP).blockLast();

        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(model).stream(prompt.capture());
        assertThat(prompt.getValue().getOptions()).isInstanceOf(ToolCallingChatOptions.class);
        var options = (ToolCallingChatOptions) prompt.getValue().getOptions();
        assertThat(options.getToolCallbacks()).hasSize(1);
        assertThat(options.getToolCallbacks().get(0)).isInstanceOf(GuardedToolCallback.class);
        assertThat(options.getToolContext()).containsEntry("userId", "u1001");
    }
}
