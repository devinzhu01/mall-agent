package com.mall.agent.api;

import com.mall.agent.data.Preference;
import com.mall.agent.data.PreferenceRepo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(PreferenceController.class)
class PreferenceControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean PreferenceRepo repo;

    @Test
    void getReturnsOnlyCallersPreferences() throws Exception {
        when(repo.list("u1001")).thenReturn(List.of(new Preference("brand", "华为", 0.9)));
        mvc.perform(get("/memory/preferences").header("X-User-Id", "u1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].key").value("brand"))
                .andExpect(jsonPath("$[0].value").value("华为"));
        verify(repo).list("u1001");
    }

    @Test
    void deleteClearsCallersPreferences() throws Exception {
        mvc.perform(delete("/memory/preferences").header("X-User-Id", "u1002")).andExpect(status().isOk());
        verify(repo).deleteAll("u1002");
    }

    @Test
    void missingUserIdIs401() throws Exception {
        mvc.perform(get("/memory/preferences")).andExpect(status().isUnauthorized());
    }
}
