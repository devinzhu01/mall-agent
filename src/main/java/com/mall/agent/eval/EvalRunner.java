package com.mall.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.mall.agent.agent.AgentOrchestrator;
import com.mall.agent.agent.RouteDecision;
import com.mall.agent.agent.RouterAgent;
import com.mall.agent.config.AgentProperties;
import com.mall.agent.eval.EvalCases.E2eCase;
import com.mall.agent.eval.EvalCases.RetrievalCase;
import com.mall.agent.eval.EvalCases.RoutingCase;
import com.mall.agent.eval.EvalCases.ToolCase;
import com.mall.agent.eval.EvalReport.Failure;
import com.mall.agent.eval.LlmJudge.JudgeScore;
import com.mall.agent.memory.MemoryContext;
import com.mall.agent.rag.Hit;
import com.mall.agent.rag.RetrievalResult;
import com.mall.agent.rag.RetrievalService;
import com.mall.agent.trace.Masker;
import com.mall.agent.trace.Tracer;
import com.mall.agent.trace.TraceStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * 评测入口:  mvn spring-boot:run -Dspring-boot.run.profiles=eval
 * 可选 --eval.sections=retrieval,routing,tools,e2e(默认全部)。跑完输出 eval-reports/<时间>.md/.json 并退出。
 * 四类评测:
 *  retrieval — 检索消融(只稠密 → +稀疏 → +改写 → +重排),看每一步优化带来多少提升;
 *  routing   — Router 意图分类准确率;
 *  tools     — 工具选择/参数正确率,multi 与 single 两种架构对比(含平均 token 与耗时);
 *  e2e       — 端到端问答,LLM 裁判按 rubric 打分。
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
@Profile("eval")
public class EvalRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(EvalRunner.class);
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private static final Path REPORT_DIR = Path.of("eval-reports");

    private final RetrievalService retrieval;
    private final RouterAgent router;
    private final AgentOrchestrator orchestrator;
    private final LlmJudge judge;
    private final AgentProperties props;
    private final ConfigurableApplicationContext context;
    private final String model;

    private final Map<String, Double> metrics = new LinkedHashMap<>();
    private final List<Failure> failures = new ArrayList<>();

    public EvalRunner(RetrievalService retrieval, RouterAgent router, AgentOrchestrator orchestrator, LlmJudge judge,
                      AgentProperties props, ConfigurableApplicationContext context,
                      @Value("${spring.ai.openai.chat.options.model:qwen-plus}") String model) {
        this.retrieval = retrieval;
        this.router = router;
        this.orchestrator = orchestrator;
        this.judge = judge;
        this.props = props;
        this.context = context;
        this.model = model;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Path dir = Path.of(props.getEval().getDatasetDir());
        Set<String> sections = args.containsOption("eval.sections")
                ? Set.copyOf(List.of(args.getOptionValues("eval.sections").get(0).split(",")))
                : Set.of("retrieval", "routing", "tools", "e2e");
        try {
            if (sections.contains("retrieval")) retrievalEval(EvalCases.load(dir.resolve("retrieval.jsonl"), RetrievalCase.class));
            if (sections.contains("routing")) routingEval(EvalCases.load(dir.resolve("routing.jsonl"), RoutingCase.class));
            if (sections.contains("tools")) toolsEval(EvalCases.load(dir.resolve("tools.jsonl"), ToolCase.class));
            if (sections.contains("e2e")) e2eEval(EvalCases.load(dir.resolve("e2e.jsonl"), E2eCase.class));
            writeReport();
        } finally {
            System.exit(SpringApplication.exit(context));
        }
    }

    // ---------- 检索消融 ----------

    private void retrievalEval(List<RetrievalCase> cases) {
        record Config(String name, boolean rewrite, boolean hybrid, boolean rerank) {
        }
        List<Config> configs = List.of(
                new Config("dense-only", false, false, false),
                new Config("+sparse", false, true, false),
                new Config("+rewrite", true, true, false),
                new Config("+rerank(full)", true, true, true));

        AgentProperties.Rag rag = props.getRag();
        boolean[] saved = {rag.isRewriteEnabled(), rag.isHybridEnabled(), rag.isRerankEnabled()};
        int k = rag.getRerankTopK();
        try {
            for (Config c : configs) {
                rag.setRewriteEnabled(c.rewrite());
                rag.setHybridEnabled(c.hybrid());
                rag.setRerankEnabled(c.rerank());
                boolean isFull = c == configs.get(configs.size() - 1);

                List<Double> recalls = new ArrayList<>(), mrrs = new ArrayList<>(), contentHits = new ArrayList<>(),
                        leaks = new ArrayList<>(), falseRefusals = new ArrayList<>(), refusalOk = new ArrayList<>();
                for (RetrievalCase tc : cases) {
                    RetrievalResult r = retrieval.retrieve(tc.query(), List.of(), null, Tracer.NOOP);
                    if (!tc.answerable()) {
                        refusalOk.add(r.refused() ? 1.0 : 0.0);
                        if (isFull && !r.refused()) failures.add(new Failure("retrieval", tc.query(), "应拒答", "返回了 " + docIds(r)));
                        continue;
                    }
                    List<String> retrieved = docIds(r);
                    double recall = EvalMetrics.recallAtK(tc.expectedDocIds(), retrieved, k);
                    recalls.add(recall);
                    mrrs.add(EvalMetrics.mrr(tc.expectedDocIds(), retrieved));
                    falseRefusals.add(r.refused() ? 1.0 : 0.0);
                    if (tc.mustContain() != null) {
                        boolean hit = r.hits().stream().anyMatch(h -> h.text().contains(tc.mustContain()));
                        contentHits.add(hit ? 1.0 : 0.0);
                        if (isFull && !hit) failures.add(new Failure("retrieval", tc.query(), "命中内容含:" + tc.mustContain(), docIds(r).toString()));
                    }
                    if (tc.mustNotContain() != null) {
                        leaks.add(r.hits().stream().anyMatch(h -> h.text().contains(tc.mustNotContain())) ? 1.0 : 0.0);
                    }
                    if (isFull && recall == 0) failures.add(new Failure("retrieval", tc.query(), tc.expectedDocIds().toString(), retrieved.toString()));
                }
                String p = "retrieval." + c.name() + ".";
                metrics.put(p + "recall@" + k, EvalMetrics.average(recalls));
                metrics.put(p + "mrr", EvalMetrics.average(mrrs));
                metrics.put(p + "contentHit", EvalMetrics.average(contentHits));
                metrics.put(p + "staleLeakRate", EvalMetrics.average(leaks));
                metrics.put(p + "falseRefusalRate", EvalMetrics.average(falseRefusals));
                metrics.put(p + "refusalAccuracy", EvalMetrics.average(refusalOk));
            }
        } finally {
            rag.setRewriteEnabled(saved[0]);
            rag.setHybridEnabled(saved[1]);
            rag.setRerankEnabled(saved[2]);
        }
    }

    private static List<String> docIds(RetrievalResult r) {
        return r.hits().stream().map(Hit::docId).distinct().toList();
    }

    // ---------- 路由 ----------

    private void routingEval(List<RoutingCase> cases) {
        int correct = 0;
        MemoryContext empty = new MemoryContext("", List.of(), List.of());
        for (RoutingCase c : cases) {
            RouteDecision d = router.route(empty, c.query(), Tracer.NOOP);
            if (d.intent() != null && d.intent().name().equals(c.expectedIntent())) {
                correct++;
            } else {
                failures.add(new Failure("routing", c.query(), c.expectedIntent(), String.valueOf(d.intent())));
            }
        }
        metrics.put("routing.accuracy", EvalMetrics.accuracy(correct, cases.size()));
    }

    // ---------- 工具选择:multi vs single ----------

    private void toolsEval(List<ToolCase> cases) {
        String savedMode = props.getMode();
        try {
            for (String mode : List.of("multi", "single")) {
                props.setMode(mode);
                int selected = 0, argsOk = 0, argsTotal = 0;
                List<Double> tokens = new ArrayList<>(), millis = new ArrayList<>();
                for (ToolCase c : cases) {
                    try {
                        var reply = orchestrator.ask(c.userId(), "eval-" + UUID.randomUUID(), c.query());
                        List<TraceStep> calls = reply.trace().steps().stream()
                                .filter(s -> s.type().equals("tool") && s.name().equals(c.expectedTool())).toList();
                        boolean picked = !calls.isEmpty();
                        if (picked) selected++;
                        if (picked && c.expectedArgs() != null && !c.expectedArgs().isEmpty()) {
                            argsTotal++;
                            boolean ok = calls.stream().anyMatch(s -> argsMatch(s.detail(), c.expectedArgs()));
                            if (ok) argsOk++;
                            else if (mode.equals("multi")) failures.add(new Failure("tools", c.query(), c.expectedArgs().toString(), calls.get(0).detail()));
                        }
                        if (!picked && mode.equals("multi")) {
                            failures.add(new Failure("tools", c.query(), c.expectedTool(),
                                    reply.trace().steps().stream().filter(s -> s.type().equals("tool")).map(TraceStep::name).toList().toString()));
                        }
                        tokens.add((double) reply.trace().totalTokens());
                        millis.add((double) reply.trace().totalMillis());
                    } catch (Exception e) {
                        if (mode.equals("multi")) failures.add(new Failure("tools", c.query(), c.expectedTool(), "ERROR " + e));
                    }
                }
                metrics.put("tools." + mode + ".selection", EvalMetrics.accuracy(selected, cases.size()));
                metrics.put("tools." + mode + ".args", EvalMetrics.accuracy(argsOk, argsTotal));
                metrics.put("tools." + mode + ".avgTokens", EvalMetrics.average(tokens));
                metrics.put("tools." + mode + ".avgMillis", EvalMetrics.average(millis));
            }
        } finally {
            props.setMode(savedMode);
        }
    }

    /** Trace 里的参数是脱敏过的,所以期望值也先脱敏再比较。 */
    private static boolean argsMatch(String maskedArgsJson, Map<String, String> expected) {
        return expected.entrySet().stream().allMatch(e ->
                maskedArgsJson.contains("\"" + e.getKey() + "\"") && maskedArgsJson.contains(Masker.mask(e.getValue())));
    }

    // ---------- 端到端 ----------

    private void e2eEval(List<E2eCase> cases) {
        List<Double> correctness = new ArrayList<>(), faithfulness = new ArrayList<>(), citation = new ArrayList<>(),
                pass = new ArrayList<>(), tokens = new ArrayList<>(), millis = new ArrayList<>();
        for (E2eCase c : cases) {
            try {
                var reply = orchestrator.ask(c.userId(), "eval-" + UUID.randomUUID(), c.query());
                List<String> tools = reply.trace().steps().stream().filter(s -> s.type().equals("tool")).map(TraceStep::name).toList();
                JudgeScore s = judge.judge(c.query(), reply.text(), c.reference(), tools);
                correctness.add((double) s.correctness());
                faithfulness.add((double) s.faithfulness());
                citation.add(s.hasCitation() ? 1.0 : 0.0);
                pass.add(s.correctness() >= 4 ? 1.0 : 0.0);
                tokens.add((double) reply.trace().totalTokens());
                millis.add((double) reply.trace().totalMillis());
                if (s.correctness() < 4) {
                    failures.add(new Failure("e2e", c.query(), c.reference(),
                            "得分" + s.correctness() + ":" + s.reason() + " | 回答:" + abbreviate(reply.text())));
                }
            } catch (Exception e) {
                correctness.add(0.0);
                pass.add(0.0);
                failures.add(new Failure("e2e", c.query(), c.reference(), "ERROR " + e));
            }
        }
        metrics.put("e2e.correctness", EvalMetrics.average(correctness));
        metrics.put("e2e.faithfulness", EvalMetrics.average(faithfulness));
        metrics.put("e2e.citationRate", EvalMetrics.average(citation));
        metrics.put("e2e.passRate", EvalMetrics.average(pass));
        metrics.put("e2e.avgTokens", EvalMetrics.average(tokens));
        metrics.put("e2e.avgMillis", EvalMetrics.average(millis));
    }

    private static String abbreviate(String s) {
        return s.length() > 160 ? s.substring(0, 160) + "..." : s;
    }

    // ---------- 报告 ----------

    private void writeReport() throws IOException {
        Files.createDirectories(REPORT_DIR);
        Optional<EvalReport> previous = latestReport();
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        EvalReport report = new EvalReport(stamp, model, metrics, failures);
        String markdown = ReportWriter.toMarkdown(report, previous);
        Files.writeString(REPORT_DIR.resolve(stamp + ".md"), markdown);
        MAPPER.writeValue(REPORT_DIR.resolve(stamp + ".json").toFile(), report);
        log.info("evaluation report written to {}\n{}", REPORT_DIR.resolve(stamp + ".md"), markdown);
    }

    private static Optional<EvalReport> latestReport() throws IOException {
        try (Stream<Path> files = Files.list(REPORT_DIR)) {
            Optional<Path> latest = files.filter(p -> p.toString().endsWith(".json")).sorted().reduce((a, b) -> b);
            return latest.isPresent() ? Optional.of(MAPPER.readValue(latest.get().toFile(), EvalReport.class)) : Optional.empty();
        }
    }
}
