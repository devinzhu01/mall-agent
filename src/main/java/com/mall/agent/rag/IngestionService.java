package com.mall.agent.rag;

import com.mall.agent.config.AgentProperties;
import com.mall.agent.data.KbDoc;
import com.mall.agent.data.KbDocumentRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 摄入链路:文件 → 解析 → 切块 → 向量化 → 写 Qdrant → 登记到 kb_document。
 * 更新维护的三个机制:
 *  1) 增量:文件 SHA-256 没变就跳过(不重复花 embedding 费用);
 *  2) 替换:内容变了先删该版本旧块再写,避免新旧块混在一起;
 *  3) 版本:同 docId 只有最高版本 effective=true,旧版本保留但检索默认过滤。
 */
@Service
public class IngestionService {

    public enum Status { INGESTED, SKIPPED_UNCHANGED }

    public record IngestResult(String docId, int version, Status status, int chunks) {
    }

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);
    private static final Pattern KB_FILE = Pattern.compile("^.+__v\\d+\\.(md|docx|pdf)$");

    private final DocParser parser;
    private final DocChunker chunker;
    private final KbStore store;
    private final EmbeddingModel embedding;
    private final KbDocumentRepo docs;
    private final AgentProperties props;

    public IngestionService(DocParser parser, DocChunker chunker, KbStore store, EmbeddingModel embedding,
                            KbDocumentRepo docs, AgentProperties props) {
        this.parser = parser;
        this.chunker = chunker;
        this.store = store;
        this.embedding = embedding;
        this.docs = docs;
        this.props = props;
    }

    public IngestResult ingest(Path file, String category) {
        return ingest(file, category, false);
    }

    /** force=true 忽略 hash 强制重建(用于换了 embedding 模型或切分策略之后)。 */
    public IngestResult ingest(Path file, String category, boolean force) {
        store.ensureCollection();
        String hash = sha256(file);
        ParsedDoc doc = parser.parse(file, category);

        Optional<KbDoc> existing = docs.find(doc.docId(), doc.version());
        if (!force && existing.isPresent() && existing.get().contentHash().equals(hash)) {
            return new IngestResult(doc.docId(), doc.version(), Status.SKIPPED_UNCHANGED,
                    existing.get().chunkCount());
        }

        List<KbDoc> versions = docs.listByDocId(doc.docId());
        boolean effective = versions.stream().noneMatch(d -> d.version() > doc.version());

        store.deleteByDocVersion(doc.docId(), doc.version());
        List<Chunk> chunks = chunker.chunk(doc, hash, effective);
        store.upsert(chunks, embedAll(chunks));
        docs.save(new KbDoc(doc.docId(), doc.version(), doc.title(), category,
                file.getFileName().toString(), hash, chunks.size(), effective));

        if (effective) {
            for (KbDoc old : versions) {
                if (old.version() < doc.version() && old.effective()) {
                    store.setEffective(old.docId(), old.version(), false);
                    docs.setEffective(old.docId(), old.version(), false);
                }
            }
        }
        log.info("ingested doc={} version={} chunks={} effective={}", doc.docId(), doc.version(), chunks.size(), effective);
        return new IngestResult(doc.docId(), doc.version(), Status.INGESTED, chunks.size());
    }

    /** 扫描 <dir>/<类别>/<docId>__v<N>.<ext>,增量同步。 */
    public List<IngestResult> syncAll(Path dir) {
        return walk(dir, false);
    }

    public List<IngestResult> reindexAll(Path dir) {
        return walk(dir, true);
    }

    /**
     * 删除文档:向量、登记行、上传目录里的源文件一起删,否则下次启动同步又会把它加回来。
     * 内置知识库(kb-dir)里的文档属于代码仓库内容,不允许通过接口删除。
     */
    public void remove(String docId) {
        if (!findFiles(Path.of(props.getRag().getKbDir()), docId).isEmpty()) {
            throw new IllegalArgumentException("内置知识库文档不能通过接口删除,请从 kb-dir 中移除文件后重新部署: " + docId);
        }
        store.deleteByDocId(docId);
        docs.delete(docId);
        for (Path file : findFiles(Path.of(props.getRag().getUploadDir()), docId)) {
            try {
                Files.delete(file);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private static List<Path> findFiles(Path dir, String docId) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(dir, 2)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith(docId + "__v")
                            && KB_FILE.matcher(p.getFileName().toString()).matches())
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private List<IngestResult> walk(Path dir, boolean force) {
        List<IngestResult> results = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return results;
        }
        try (Stream<Path> files = Files.walk(dir, 2)) {
            List<Path> sorted = files.filter(Files::isRegularFile)
                    .filter(p -> KB_FILE.matcher(p.getFileName().toString()).matches())
                    .sorted().toList();
            for (Path p : sorted) {
                results.add(ingest(p, p.getParent().getFileName().toString(), force));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return results;
    }

    /** 向量化的文本带上标题链:"退货 > 例外情况\n定制类不支持…" 比孤立的条款更容易被问题命中。 */
    private List<float[]> embedAll(List<Chunk> chunks) {
        int batch = props.getRag().getEmbeddingBatchSize();
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i += batch) {
            List<String> texts = chunks.subList(i, Math.min(i + batch, chunks.size())).stream()
                    .map(c -> c.titlePath() + "\n" + c.text()).toList();
            out.addAll(embedding.embed(texts));
        }
        return out;
    }

    private static String sha256(Path file) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (Exception e) {
            throw new IllegalStateException("hash failed: " + file, e);
        }
    }
}
