package com.mall.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 所有可调参数集中在这里,对应 application.yml 的 agent.* 。 */
@Data
@ConfigurationProperties("agent")
public class AgentProperties {
    private String mode = "multi";
    private String routerModel = "qwen-plus";
    private String adminToken = "";
    private Tool tool = new Tool();
    private Memory memory = new Memory();
    private Rag rag = new Rag();
    private Logistics logistics = new Logistics();
    private Eval eval = new Eval();

    @Data public static class Tool {
        private int maxFailures = 3;
    }

    @Data public static class Memory {
        private int windowMessages = 20;
        private int summarizeThreshold = 16;
        private int keepRecent = 6;
        private int ttlMinutes = 120;
        private int maxChars = 6000;   // 注入上下文的历史字符上限(token 的粗略代理)
    }

    /** 开关可在评测里临时改写,用来做消融对比。 */
    @Data public static class Rag {
        private String collection = "mall_kb";
        private String qdrantHost = "localhost";
        private int qdrantPort = 6334;
        private int denseDim = 1024;
        private int denseTopN = 20;
        private int sparseTopN = 20;
        private int rerankTopK = 4;
        private double refuseThreshold = 0.3;
        private boolean rewriteEnabled = true;
        private boolean hybridEnabled = true;
        private boolean sparseEnabled = true;
        private boolean rerankEnabled = true;
        private String rerankModel = "gte-rerank";
        private String rerankUrl = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
        private int embeddingBatchSize = 10;
        private boolean syncOnStartup = true;
        private String uploadDir = "./kb-uploads";
        private String kbDir = "src/main/resources/kb";
    }

    @Data public static class Logistics {
        private String baseUrl = "http://localhost:8080/mock/logistics";
        private int timeoutMs = 1500;
        private double mockFailRate = 0.3;
    }

    @Data public static class Eval {
        private String datasetDir = "src/test/resources/eval";
        private boolean useCache = true;
        private String cacheDir = "eval-cache";
        private String judgeModel;   // 为空则用默认对话模型
    }
}
