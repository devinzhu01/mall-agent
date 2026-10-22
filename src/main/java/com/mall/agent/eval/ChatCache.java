package com.mall.agent.eval;

import com.mall.agent.config.AgentProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

/**
 * 评测专用的模型响应缓存(只在 eval 配置下存在,线上不会缓存任何模型调用)。
 * key = hash(模型 + 提示词),命中则直接返回:重复评测不重复花钱,改写/路由/裁判的结果也保持稳定,
 * 这样两次评测的指标差异来自"我改的东西",而不是模型的随机波动。
 */
@Component
@Profile("eval")
public class ChatCache {

    private final Path dir;
    private final boolean enabled;

    public ChatCache(AgentProperties props) {
        this.dir = Path.of(props.getEval().getCacheDir());
        this.enabled = props.getEval().isUseCache();
    }

    public static String key(String model, String system, String user) {
        try {
            byte[] bytes = (model + "\u0000" + system + "\u0000" + user).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<String> get(String key) {
        Path file = dir.resolve(key + ".txt");
        try {
            return enabled && Files.exists(file) ? Optional.of(Files.readString(file)) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public void put(String key, String value) {
        if (!enabled) {
            return;
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(key + ".txt"), value);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
