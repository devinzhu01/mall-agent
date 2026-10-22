package com.mall.agent.rag;

import com.mall.agent.rag.ParsedDoc.Section;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DocChunkerTest {

    private final DocChunker chunker = new DocChunker(100);

    private static ParsedDoc doc(Section... sections) {
        return new ParsedDoc("return-policy", 2, "退换货政策", "售后", List.of(sections));
    }

    @Test
    void faqPairsBecomeSingleChunks() {
        var faq = new Section(List.of("FAQ", "能退吗"), "Q: 能退吗?\nA: 7天内可退。", false, true);
        List<Chunk> chunks = chunker.chunk(doc(faq), "h", true);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).parentText()).isEqualTo(chunks.get(0).text());
    }

    @Test
    void tableStaysOneChunkEvenWhenLong() {
        String table = "| 地区 | 运费 |\n|---|---|\n" + "| 华东 | 6元 |\n".repeat(30);
        List<Chunk> chunks = chunker.chunk(doc(new Section(List.of("运费", "运费表"), table, true, false)), "h", true);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).contains("|");
    }

    @Test
    void longSectionSplitsIntoChildrenSharingParent() {
        String text = "自签收之日起七天内可申请退货。".repeat(20);
        List<Chunk> chunks = chunker.chunk(doc(new Section(List.of("政策", "条款"), text, false, false)), "h", true);
        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allMatch(c -> c.text().length() <= 100);
        assertThat(chunks).extracting(Chunk::parentText).containsOnly(text);
    }

    @Test
    void shortSectionIsOneChunk() {
        List<Chunk> chunks = chunker.chunk(doc(new Section(List.of("政策"), "简短条款。", false, false)), "h", true);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).titlePath()).isEqualTo("政策");
    }

    @Test
    void chunkIdsAreDeterministicAndUnique() {
        var s = new Section(List.of("政策"), "自签收之日起七天内可申请退货。".repeat(20), false, false);
        List<String> a = chunker.chunk(doc(s), "h", true).stream().map(Chunk::id).toList();
        List<String> b = chunker.chunk(doc(s), "h", true).stream().map(Chunk::id).toList();
        assertThat(a).isEqualTo(b).doesNotHaveDuplicates();
    }

    @Test
    void chunksCarryMetadata() {
        Chunk c = chunker.chunk(doc(new Section(List.of("政策"), "条款内容。", false, false)), "hash1", false).get(0);
        assertThat(c.docId()).isEqualTo("return-policy");
        assertThat(c.version()).isEqualTo(2);
        assertThat(c.category()).isEqualTo("售后");
        assertThat(c.effective()).isFalse();
        assertThat(c.contentHash()).isEqualTo("hash1");
    }
}
