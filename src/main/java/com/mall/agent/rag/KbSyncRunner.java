package com.mall.agent.rag;

import com.mall.agent.config.AgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/** 启动时把内置知识库目录和上传目录增量同步一次(文件没变就全部跳过)。Qdrant 不可用时只告警,不阻止启动。 */
@Component
public class KbSyncRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KbSyncRunner.class);

    private final IngestionService ingestion;
    private final AgentProperties props;

    public KbSyncRunner(IngestionService ingestion, AgentProperties props) {
        this.ingestion = ingestion;
        this.props = props;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!props.getRag().isSyncOnStartup()) {
            return;
        }
        try {
            var built = ingestion.syncAll(Path.of(props.getRag().getKbDir()));
            var uploaded = ingestion.syncAll(Path.of(props.getRag().getUploadDir()));
            log.info("kb sync done: {} built-in docs, {} uploaded docs", built.size(), uploaded.size());
        } catch (Exception e) {
            log.error("kb sync failed (is Qdrant up?): {}", e.toString());
        }
    }
}
