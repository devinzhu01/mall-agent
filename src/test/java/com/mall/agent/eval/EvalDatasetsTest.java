package com.mall.agent.eval;

import com.mall.agent.eval.EvalCases.E2eCase;
import com.mall.agent.eval.EvalCases.RetrievalCase;
import com.mall.agent.eval.EvalCases.RoutingCase;
import com.mall.agent.eval.EvalCases.ToolCase;
import com.mall.agent.rag.DocParser;
import com.mall.agent.rag.ParsedDoc;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 评测集本身的质量检查:数量、难点覆盖,以及 mustContain 确实出现在期望文档里(否则评测会冤枉系统)。 */
class EvalDatasetsTest {

    private static final Path DIR = Path.of("src/test/resources/eval");
    private static final Path KB = Path.of("src/main/resources/kb");

    private final List<RetrievalCase> retrieval = EvalCases.load(DIR.resolve("retrieval.jsonl"), RetrievalCase.class);

    @Test
    void everyDatasetHasAtLeastTwentyCases() {
        assertThat(retrieval).hasSizeGreaterThanOrEqualTo(20);
        assertThat(EvalCases.load(DIR.resolve("routing.jsonl"), RoutingCase.class)).hasSizeGreaterThanOrEqualTo(20);
        assertThat(EvalCases.load(DIR.resolve("tools.jsonl"), ToolCase.class)).hasSizeGreaterThanOrEqualTo(20);
        assertThat(EvalCases.load(DIR.resolve("e2e.jsonl"), E2eCase.class)).hasSizeGreaterThanOrEqualTo(20);
    }

    @Test
    void retrievalCoversEveryHardCategoryAndUnanswerable() {
        Map<String, Long> byTag = retrieval.stream().collect(Collectors.groupingBy(RetrievalCase::tag, Collectors.counting()));
        for (String tag : List.of("exception", "version", "table", "colloquial")) {
            assertThat(byTag.getOrDefault(tag, 0L)).as(tag).isGreaterThanOrEqualTo(3);
        }
        assertThat(retrieval.stream().filter(c -> !c.answerable()).count()).isGreaterThanOrEqualTo(5);
    }

    @Test
    void routingCoversAllFourIntents() {
        Set<String> intents = EvalCases.load(DIR.resolve("routing.jsonl"), RoutingCase.class).stream()
                .map(RoutingCase::expectedIntent).collect(Collectors.toSet());
        assertThat(intents).containsExactlyInAnyOrder("RECOMMEND", "ORDER", "KNOWLEDGE", "CHITCHAT");
    }

    @Test
    void expectedDocIdsAndMustContainExistInKnowledgeBase() throws IOException {
        Map<String, String> textByDoc = new HashMap<>();
        DocParser parser = new DocParser();
        try (Stream<Path> files = Files.walk(KB, 2)) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                ParsedDoc doc = parser.parse(p, p.getParent().getFileName().toString());
                String text = doc.sections().stream().map(ParsedDoc.Section::text).collect(Collectors.joining("\n"));
                textByDoc.merge(doc.docId(), text, (a, b) -> a + "\n" + b);
            }
        }
        for (RetrievalCase c : retrieval) {
            if (!c.answerable()) continue;
            assertThat(textByDoc.keySet()).as(c.query()).containsAll(c.expectedDocIds());
            if (c.mustContain() != null) {
                boolean found = c.expectedDocIds().stream().anyMatch(id -> textByDoc.get(id).contains(c.mustContain()));
                assertThat(found).as("mustContain '%s' for: %s", c.mustContain(), c.query()).isTrue();
            }
            if (c.mustNotContain() != null) {
                // 必须是旧版本里真实存在的句子,否则"过期泄漏"检查没有意义
                assertThat(textByDoc.get("return-policy")).as(c.query()).contains(c.mustNotContain());
            }
        }
    }

    @Test
    void toolCasesUseOnlyRealToolNames() {
        Set<String> tools = Set.of("getOrder", "listMyOrders", "getLogistics", "searchProducts", "getProductDetail",
                "searchKnowledge", "loadSkill");
        assertThat(EvalCases.load(DIR.resolve("tools.jsonl"), ToolCase.class))
                .allSatisfy(c -> assertThat(tools).contains(c.expectedTool()));
    }
}
