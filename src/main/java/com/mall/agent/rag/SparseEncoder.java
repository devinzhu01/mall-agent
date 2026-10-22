package com.mall.agent.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 稀疏向量编码(BM25 思路的关键词信号,纯 Java、离线可用)。
 * 中文取相邻字二元组(不用分词库:"无理由退货" 和 "七日无理由退货" 能共享 "无理/理由/由退/退货");
 * 英文数字按整词小写。token 哈希到 2^20 个维度,权重 1+ln(tf)。
 * 作用:补足稠密向量对型号、专有名词、条款关键词不敏感的短板。
 */
@Component
public class SparseEncoder {

    private static final int DIMENSIONS = 1 << 20;

    public SparseVec encode(String text) {
        Map<Integer, Integer> tf = new TreeMap<>();
        for (String token : tokens(text)) {
            tf.merge((token.hashCode() & 0x7fffffff) % DIMENSIONS, 1, Integer::sum);
        }
        int[] indices = new int[tf.size()];
        float[] values = new float[tf.size()];
        int i = 0;
        for (var e : tf.entrySet()) {
            indices[i] = e.getKey();
            values[i] = (float) (1 + Math.log(e.getValue()));
            i++;
        }
        return new SparseVec(indices, values);
    }

    private static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder han = new StringBuilder();
        StringBuilder word = new StringBuilder();
        for (char c : text.toCharArray()) {
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                flushWord(word, out);
                han.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                flushHan(han, out);
                word.append(Character.toLowerCase(c));
            } else {
                flushHan(han, out);
                flushWord(word, out);
            }
        }
        flushHan(han, out);
        flushWord(word, out);
        return out;
    }

    private static void flushHan(StringBuilder han, List<String> out) {
        if (han.length() == 1) {
            out.add(han.toString());
        }
        for (int i = 0; i + 1 < han.length(); i++) {
            out.add(han.substring(i, i + 2));
        }
        han.setLength(0);
    }

    private static void flushWord(StringBuilder word, List<String> out) {
        if (word.length() > 0) {
            out.add(word.toString());
            word.setLength(0);
        }
    }
}
