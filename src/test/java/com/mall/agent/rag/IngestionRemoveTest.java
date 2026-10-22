package com.mall.agent.rag;

import com.mall.agent.config.AgentProperties;
import com.mall.agent.data.KbDocumentRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.embedding.EmbeddingModel;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** 删除要"删得掉":上传目录里的源文件一并删除,否则下次启动同步又会把文档加回来。 */
class IngestionRemoveTest {

    @TempDir Path dir;
    KbStore store = mock(KbStore.class);
    KbDocumentRepo docs = mock(KbDocumentRepo.class);
    IngestionService service;

    @BeforeEach
    void setUp() {
        AgentProperties props = new AgentProperties();
        props.getRag().setUploadDir(dir.resolve("uploads").toString());
        props.getRag().setKbDir(dir.resolve("builtin").toString());
        service = new IngestionService(new DocParser(), new DocChunker(), store, mock(EmbeddingModel.class), docs, props);
    }

    @Test
    void removeAlsoDeletesUploadedFiles() throws Exception {
        Path up = Files.createDirectories(dir.resolve("uploads/售后"));
        Files.writeString(up.resolve("return__v1.md"), "# a");
        Files.writeString(up.resolve("return__v2.md"), "# a");
        Files.writeString(up.resolve("other__v1.md"), "# a");

        service.remove("return");

        assertThat(up.resolve("return__v1.md")).doesNotExist();
        assertThat(up.resolve("return__v2.md")).doesNotExist();
        assertThat(up.resolve("other__v1.md")).exists();
        verify(store).deleteByDocId("return");
        verify(docs).delete("return");
    }

    @Test
    void removeRejectsBuiltInDocuments() throws Exception {
        Path built = Files.createDirectories(dir.resolve("builtin/售后"));
        Files.writeString(built.resolve("return__v1.md"), "# a");

        assertThatThrownBy(() -> service.remove("return"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("内置");
        verify(store, never()).deleteByDocId("return");
        assertThat(built.resolve("return__v1.md")).exists();
    }

    @Test
    void removeOfUnknownDocIsHarmless() {
        service.remove("nothing");
        verify(store).deleteByDocId("nothing");
    }
}
