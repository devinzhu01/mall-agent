package com.mall.agent.api;

import com.mall.agent.agent.AgentOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean AgentOrchestrator orchestrator;

    private static String body(String sessionId, String message) {
        return "{" + (sessionId == null ? "" : "\"sessionId\":\"" + sessionId + "\",")
                + "\"message\":\"" + message + "\"}";
    }

    private void expectBadRequest(String json) throws Exception {
        mvc.perform(post("/chat").header("X-User-Id", "u1001").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("BAD_REQUEST"));
        verify(orchestrator, never()).open(any(), any(), any());
    }

    @Test
    void missingUserIdIs401() throws Exception {
        mvc.perform(post("/chat").contentType(MediaType.APPLICATION_JSON).content(body("s1", "hi")))
                .andExpect(status().isUnauthorized());
        verify(orchestrator, never()).open(any(), any(), any());
    }

    @Test
    void blankMessageIs400() throws Exception {
        expectBadRequest(body("s1", "   "));
    }

    @Test
    void messageOver2000CharsIs400() throws Exception {
        expectBadRequest(body("s1", "字".repeat(2001)));
    }

    @Test
    void messageOf2000CharsIsAccepted() throws Exception {
        when(orchestrator.open(anyString(), anyString(), anyString()))
                .thenReturn(new AgentOrchestrator.Run("t1", Flux.just("ok"), null, null));
        mvc.perform(post("/chat").header("X-User-Id", "u1001").contentType(MediaType.APPLICATION_JSON)
                .content(body("s1", "字".repeat(2000)))).andExpect(request().asyncStarted());
    }

    @Test
    void missingSessionIdIs400() throws Exception {
        expectBadRequest(body(null, "你好"));
    }

    @Test
    void sessionIdOver64CharsIs400() throws Exception {
        expectBadRequest(body("s".repeat(65), "你好"));
    }

    @Test
    void malformedJsonIs400() throws Exception {
        expectBadRequest("{not json");
    }

    @Test
    void validRequestStreamsSse() throws Exception {
        when(orchestrator.open("u1001", "s1", "你好"))
                .thenReturn(new AgentOrchestrator.Run("trace-1", Flux.just("你好", ",请问"), null, null));

        var result = mvc.perform(post("/chat").header("X-User-Id", "u1001")
                        .contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                        .content(body("s1", "你好")))
                .andExpect(request().asyncStarted()).andReturn();

        var response = mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse();
        String sse = response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(sse).contains("event:trace").contains("trace-1").contains("你好")
                .contains("请问").contains("event:done");
    }
}
