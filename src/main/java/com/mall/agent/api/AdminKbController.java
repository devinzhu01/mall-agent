package com.mall.agent.api;

import com.mall.agent.config.AgentProperties;
import com.mall.agent.rag.IngestionService;
import com.mall.agent.rag.IngestionService.IngestResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;

/** 知识库维护接口:上传/更新文档、删除文档、全量重建。 */
@RestController
@RequestMapping("/admin/kb")
public class AdminKbController {

    private static final Pattern CATEGORY = Pattern.compile("^[\\p{L}\\p{N}_-]{1,32}$");

    private final IngestionService ingestion;
    private final AgentProperties props;

    public AdminKbController(IngestionService ingestion, AgentProperties props) {
        this.ingestion = ingestion;
        this.props = props;
    }

    /** 文件名必须是 <docId>__v<N>.<ext>;同 docId 上传更高版本即为"新版本上线"。 */
    @PostMapping("/upload")
    public IngestResult upload(@RequestParam("file") MultipartFile file, @RequestParam String category)
            throws IOException {
        if (!CATEGORY.matcher(category).matches()) {
            throw new IllegalArgumentException("category 非法");
        }
        String name = Path.of(file.getOriginalFilename() == null ? "" : file.getOriginalFilename())
                .getFileName().toString();
        Path dir = Files.createDirectories(Path.of(props.getRag().getUploadDir()).resolve(category));
        Path target = dir.resolve(name);
        file.transferTo(target.toAbsolutePath());
        return ingestion.ingest(target, category);
    }

    @DeleteMapping("/{docId}")
    public Map<String, String> delete(@PathVariable String docId) {
        ingestion.remove(docId);
        return Map.of("deleted", docId);
    }

    @PostMapping("/reindex")
    public Map<String, Integer> reindex() {
        int n = ingestion.reindexAll(Path.of(props.getRag().getKbDir())).size()
                + ingestion.reindexAll(Path.of(props.getRag().getUploadDir())).size();
        return Map.of("documents", n);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("code", "BAD_REQUEST", "message", e.getMessage()));
    }
}
