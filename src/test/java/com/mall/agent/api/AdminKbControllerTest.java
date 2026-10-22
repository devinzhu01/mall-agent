package com.mall.agent.api;

import com.mall.agent.config.AgentProperties;
import com.mall.agent.rag.IngestionService;
import com.mall.agent.rag.IngestionService.IngestResult;
import com.mall.agent.rag.IngestionService.Status;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminKbController.class)
@EnableConfigurationProperties(AgentProperties.class)
@TestPropertySource(properties = {"agent.admin-token=secret", "agent.rag.upload-dir=target/test-uploads"})
class AdminKbControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean IngestionService ingestion;

    private final MockMultipartFile file =
            new MockMultipartFile("file", "return-policy__v1.md", "text/markdown", "# 标题\n正文".getBytes());

    @Test
    void uploadWithoutTokenIs401() throws Exception {
        mvc.perform(multipart("/admin/kb/upload").file(file).param("category", "售后"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void uploadWithWrongTokenIs401() throws Exception {
        mvc.perform(multipart("/admin/kb/upload").file(file).param("category", "售后").header("X-Admin-Token", "nope"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void uploadWithTokenIngests() throws Exception {
        when(ingestion.ingest(any(), eq("售后"))).thenReturn(new IngestResult("return-policy", 1, Status.INGESTED, 4));
        mvc.perform(multipart("/admin/kb/upload").file(file).param("category", "售后").header("X-Admin-Token", "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.docId").value("return-policy"))
                .andExpect(jsonPath("$.status").value("INGESTED"));
    }

    @Test
    void uploadRejectsPathTraversalInCategory() throws Exception {
        mvc.perform(multipart("/admin/kb/upload").file(file).param("category", "../etc").header("X-Admin-Token", "secret"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deleteReturnsOk() throws Exception {
        mvc.perform(delete("/admin/kb/return-policy").header("X-Admin-Token", "secret")).andExpect(status().isOk());
        verify(ingestion).remove("return-policy");
    }

    @Test
    void reindexReturnsCount() throws Exception {
        when(ingestion.reindexAll(any())).thenReturn(List.of(new IngestResult("a", 1, Status.INGESTED, 2)));
        mvc.perform(post("/admin/kb/reindex").header("X-Admin-Token", "secret"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.documents").value(2));
    }
}
