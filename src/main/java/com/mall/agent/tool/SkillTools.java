package com.mall.agent.tool;

import com.mall.agent.skill.SkillRegistry;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Set;

/** 按需加载 Skill 正文。每个专家一个实例,allowed 限定它能加载哪些。 */
public class SkillTools {

    private final SkillRegistry registry;
    private final Set<String> allowed;

    public SkillTools(SkillRegistry registry, Set<String> allowed) {
        this.registry = registry;
        this.allowed = allowed;
    }

    @Tool(description = "加载一个 Skill 的完整办理流程和话术。当用户的问题匹配系统提示里 Skill 目录中的某一项时,先调用它再回答。")
    public String loadSkill(@ToolParam(description = "Skill 名称,必须是目录里列出的名称") String name, ToolContext ctx) {
        return registry.load(name == null ? "" : name.trim(), allowed)
                .map(s -> ToolResult.ok(s.body()).toJson())
                .orElseThrow(() -> new ToolException("NOT_FOUND", "没有这个 Skill: " + name, true,
                        "可用 Skill: " + String.join(", ", allowed.stream().sorted().toList())));
    }
}
