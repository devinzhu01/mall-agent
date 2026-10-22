package com.mall.agent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 评测集的行格式与加载(jsonl,每行一条)。 */
final class EvalCases {

    /** tag: normal / exception / version / table / colloquial;mustContain/mustNotContain 用于检查命中的内容是不是对的版本。 */
    record RetrievalCase(String query, List<String> expectedDocIds, boolean answerable, String tag,
                         String mustContain, String mustNotContain) {
    }

    record RoutingCase(String query, String expectedIntent) {
    }

    /** expectedArgs 的值要求出现在该工具调用的参数里(订单号等会按 Trace 的脱敏规则比较)。 */
    record ToolCase(String query, String userId, String expectedTool, Map<String, String> expectedArgs) {
    }

    record E2eCase(String query, String userId, String reference) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EvalCases() {
    }

    static <T> List<T> load(Path file, Class<T> type) {
        try {
            return Files.readAllLines(file).stream().filter(l -> !l.isBlank()).map(l -> {
                try {
                    return MAPPER.readValue(l, type);
                } catch (IOException e) {
                    throw new UncheckedIOException("bad line in " + file + ": " + l, e);
                }
            }).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
