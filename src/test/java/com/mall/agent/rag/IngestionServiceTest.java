package com.mall.agent.rag;

import com.mall.agent.config.AgentProperties;
import com.mall.agent.data.KbDoc;
import com.mall.agent.data.KbDocumentRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.ai.embedding.EmbeddingModel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IngestionServiceTest {

    @TempDir Path dir;
    KbStore store = mock(KbStore.class);
    KbDocumentRepo docs = mock(KbDocumentRepo.class);
    EmbeddingModel embedding = mock(EmbeddingModel.class);
    AgentProperties props = new AgentProperties();
    IngestionService service;

    @BeforeEach
    void setUp() {
        props.getRag().setEmbeddingBatchSize(2);
        when(embedding.embed(anyList())).thenAnswer(inv -> {
            List<String> in = inv.getArgument(0);
            List<float[]> out = new ArrayList<>();
            in.forEach(s -> out.add(new float[]{1, 2, 3}));
            return out;
        });
        service = new IngestionService(new DocParser(), new DocChunker(), store, embedding, docs, props);
    }

    private Path file(String name, String content) throws Exception {
        Path cat = Files.createDirectories(dir.resolve("售后"));
        Path p = cat.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    private static final String V1 = "# 退换货\n## 规则\n七天内可退。\n## 时效\n退款七个工作日。\n## 其他\n运费规则。\n";

    @Test
    void ingestWritesChunksAndRegistersDocument() throws Exception {
        IngestionService.IngestResult r = service.ingest(file("return__v1.md", V1), "售后");
        assertThat(r.status()).isEqualTo(IngestionService.Status.INGESTED);
        assertThat(r.chunks()).isEqualTo(3);
        verify(store).upsert(anyList(), anyList());
        ArgumentCaptor<KbDoc> saved = ArgumentCaptor.forClass(KbDoc.class);
        verify(docs).save(saved.capture());
        assertThat(saved.getValue().docId()).isEqualTo("return");
        assertThat(saved.getValue().chunkCount()).isEqualTo(3);
        assertThat(saved.getValue().effective()).isTrue();
    }

    @Test
    void embeddingIsBatched() throws Exception {
        service.ingest(file("return__v1.md", V1), "售后");   // 3 chunks, batch size 2 → 2 calls
        verify(embedding, times(2)).embed(anyList());
    }

    @Test
    void unchangedFileIsSkipped() throws Exception {
        Path f = file("return__v1.md", V1);
        service.ingest(f, "售后");
        ArgumentCaptor<KbDoc> saved = ArgumentCaptor.forClass(KbDoc.class);
        verify(docs).save(saved.capture());
        when(docs.find("return", 1)).thenReturn(Optional.of(saved.getValue()));

        IngestionService.IngestResult second = service.ingest(f, "售后");

        assertThat(second.status()).isEqualTo(IngestionService.Status.SKIPPED_UNCHANGED);
        verify(store, times(1)).upsert(anyList(), anyList());
    }

    @Test
    void changedContentReplacesOldChunks() throws Exception {
        Path f = file("return__v1.md", V1);
        when(docs.find("return", 1)).thenReturn(Optional.of(
                new KbDoc("return", 1, "退换货", "售后", "return__v1.md", "old-hash", 3, true)));

        service.ingest(f, "售后");

        InOrder order = inOrder(store);
        order.verify(store).deleteByDocVersion("return", 1);
        order.verify(store).upsert(anyList(), anyList());
    }

    @Test
    void newVersionMarksOldIneffective() throws Exception {
        KbDoc v1 = new KbDoc("return", 1, "退换货", "售后", "return__v1.md", "h1", 3, true);
        service.ingest(file("return__v1.md", V1), "售后");
        when(docs.listByDocId("return")).thenReturn(List.of(v1,
                new KbDoc("return", 2, "退换货", "售后", "return__v2.md", "h2", 3, true)));

        service.ingest(file("return__v2.md", V1.replace("七天", "十五天")), "售后");

        verify(store).setEffective("return", 1, false);
        verify(docs).setEffective("return", 1, false);
        verify(store, never()).setEffective("return", 2, false);
    }

    @Test
    void syncAllWalksCategoryDirectoriesAndPassesCategory() throws Exception {
        file("return__v1.md", V1);
        List<IngestionService.IngestResult> results = service.syncAll(dir);
        assertThat(results).hasSize(1);
        ArgumentCaptor<KbDoc> saved = ArgumentCaptor.forClass(KbDoc.class);
        verify(docs).save(saved.capture());
        assertThat(saved.getValue().category()).isEqualTo("售后");
    }

    @Test
    void reindexIgnoresHash() throws Exception {
        file("return__v1.md", V1);
        service.syncAll(dir);
        ArgumentCaptor<KbDoc> saved = ArgumentCaptor.forClass(KbDoc.class);
        verify(docs).save(saved.capture());
        when(docs.find("return", 1)).thenReturn(Optional.of(saved.getValue()));

        service.reindexAll(dir);

        verify(store, times(2)).upsert(anyList(), anyList());
    }

    @Test
    void removeDeletesStoreAndTable() {
        service.remove("return");
        verify(store).deleteByDocId("return");
        verify(docs).delete("return");
    }
}
