package com.mall.agent.skill;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Skill 注册表:启动时扫描 classpath:skills/<name>/SKILL.md。
 * 渐进披露:提示词里只放 catalog()(每个 Skill 一行),模型判断需要时调用 loadSkill 才拿到正文,
 * 避免所有流程文档常驻上下文。每个专家只能看到/加载自己允许的 Skill。
 */
@Component
public class SkillRegistry {

    private static final Pattern FRONTMATTER = Pattern.compile("(?s)\\A---\\R(.*?)\\R---\\R?(.*)\\z");

    private final Map<String, Skill> skills = new LinkedHashMap<>();

    @Autowired
    public SkillRegistry() {
        this(scanClasspath());
    }

    /** 直接传入 SKILL.md 原文(测试用)。 */
    public SkillRegistry(List<String> rawFiles) {
        for (String raw : rawFiles) {
            Skill s = parse(raw);
            skills.put(s.name(), s);
        }
    }

    /** 给提示词用的目录:仅列出 allowed 中存在的 Skill。 */
    public String catalog(Set<String> allowed) {
        StringBuilder sb = new StringBuilder();
        skills.values().stream().filter(s -> allowed.contains(s.name()))
                .forEach(s -> sb.append("- ").append(s.name()).append(": ").append(s.description()).append('\n'));
        return sb.toString().trim();
    }

    public Optional<Skill> load(String name, Set<String> allowed) {
        return allowed.contains(name) ? Optional.ofNullable(skills.get(name)) : Optional.empty();
    }

    private static Skill parse(String raw) {
        Matcher m = FRONTMATTER.matcher(raw.strip());
        if (!m.matches()) {
            throw new IllegalStateException("SKILL.md 缺少 frontmatter(--- name/description ---)");
        }
        String name = null;
        String description = null;
        for (String line : m.group(1).split("\\R")) {
            int i = line.indexOf(':');
            if (i < 0) continue;
            String key = line.substring(0, i).trim();
            String value = line.substring(i + 1).trim();
            if (key.equals("name")) name = value;
            if (key.equals("description")) description = value;
        }
        if (name == null || name.isBlank() || description == null || description.isBlank()) {
            throw new IllegalStateException("SKILL.md frontmatter 必须包含 name 和 description");
        }
        return new Skill(name, description, m.group(2).strip());
    }

    private static List<String> scanClasspath() {
        try {
            List<String> raws = new ArrayList<>();
            for (Resource r : new PathMatchingResourcePatternResolver().getResources("classpath*:skills/*/SKILL.md")) {
                raws.add(new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            }
            return raws;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
