package com.mall.agent.agent;

import java.util.List;
import java.util.Set;

/** 一个专家 = 系统提示词 + 它能用的工具对象 + 它能加载的 Skill。权限隔离就体现在这三样的取舍上。 */
public record Expert(String name, String systemPrompt, List<Object> toolBeans, Set<String> skills) {
}
