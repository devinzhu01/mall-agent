package com.mall.agent.eval;

import com.mall.agent.config.AgentProperties;
import com.mall.agent.llm.Llm;
import com.mall.agent.trace.Tracer;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * LLM-as-Judge:对照参考要点给回答打分。评分标准(rubric)写在提示词里,温度为 0:
 *  correctness(正确性):5=与参考要点一致且无多余编造;4=基本正确略有遗漏;3=部分正确;2=大部分错误;1=完全错误或编造。
 *  faithfulness(忠实度):5=所有事实都有工具结果/知识库依据;3=有少量无依据的补充;1=大量编造。
 *  hasCitation:回答是否注明了规则来源(如《退换货政策》),只对规则类问答有意义。
 */
@Component
@Profile("eval")
public class LlmJudge {

    public record JudgeScore(int correctness, int faithfulness, boolean hasCitation, String reason) {
    }

    private static final String SYSTEM = """
            你是严格的电商客服质检员。根据"参考要点"评估"助手回答"。评分标准:
            correctness(1-5):5=与参考要点一致且没有多余编造;4=基本正确,略有遗漏;3=部分正确;2=大部分错误;1=完全错误或在编造。
            参考要点里写"不得编造"或"如实说明"的,助手编造了细节就最多给 2 分。
            faithfulness(1-5):5=回答中的事实都能在"本次调用的工具"对应的结果里找到依据;3=有少量无依据的补充;1=大量编造。
            hasCitation:回答是否说明了信息来源(如《退换货政策》、"根据知识库"),是为 true。
            reason:一句话说明扣分原因。
            只输出 JSON:{"correctness":5,"faithfulness":5,"hasCitation":true,"reason":"..."}""";

    private final Llm llm;
    private final AgentProperties props;

    public LlmJudge(Llm llm, AgentProperties props) {
        this.llm = llm;
        this.props = props;
    }

    public JudgeScore judge(String question, String answer, String reference, List<String> toolsCalled) {
        String user = "用户问题:" + question + "\n\n参考要点:" + reference + "\n\n本次调用的工具:"
                + (toolsCalled.isEmpty() ? "(无)" : String.join(", ", toolsCalled)) + "\n\n助手回答:" + answer;
        JudgeScore s = llm.json(Tracer.NOOP, "judge", props.getEval().getJudgeModel(), SYSTEM, user, JudgeScore.class);
        return new JudgeScore(clamp(s.correctness()), clamp(s.faithfulness()), s.hasCitation(), s.reason());
    }

    private static int clamp(int score) {
        return Math.max(1, Math.min(5, score));
    }
}
