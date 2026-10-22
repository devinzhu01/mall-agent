package com.mall.agent.api;

import com.mall.agent.data.Preference;
import com.mall.agent.data.PreferenceRepo;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 用户可以查看并清除系统记住的自己的偏好(长期记忆的可见性与可控性)。 */
@RestController
@RequestMapping("/memory/preferences")
public class PreferenceController {

    private final PreferenceRepo repo;

    public PreferenceController(PreferenceRepo repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<Preference> list(@RequestHeader("X-User-Id") String userId) {
        return repo.list(userId);
    }

    @DeleteMapping
    public Map<String, Boolean> clear(@RequestHeader("X-User-Id") String userId) {
        repo.deleteAll(userId);
        return Map.of("cleared", true);
    }
}
