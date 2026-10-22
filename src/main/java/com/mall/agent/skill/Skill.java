package com.mall.agent.skill;

/** 一个 Skill:name+description 常驻提示词(目录),body 只在模型主动加载时才进入上下文。 */
public record Skill(String name, String description, String body) {
}
