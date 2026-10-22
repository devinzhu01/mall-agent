package com.mall.agent.data;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

/** 长期记忆:唯一键 (user_id, pref_key, pref_value),重复写入只更新置信度。 */
@Repository
public class PreferenceRepo {

    private final JdbcClient jdbc;

    public PreferenceRepo(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(String userId, Preference p) {
        int updated = jdbc.sql("UPDATE user_preference SET confidence = ?, updated_at = CURRENT_TIMESTAMP "
                        + "WHERE user_id = ? AND pref_key = ? AND pref_value = ?")
                .params(p.confidence(), userId, p.key(), p.value()).update();
        if (updated == 0) {
            jdbc.sql("INSERT INTO user_preference (user_id, pref_key, pref_value, confidence) VALUES (?,?,?,?)")
                    .params(userId, p.key(), p.value(), p.confidence()).update();
        }
    }

    public List<Preference> list(String userId) {
        return jdbc.sql("SELECT pref_key, pref_value, confidence FROM user_preference "
                        + "WHERE user_id = ? ORDER BY confidence DESC, updated_at DESC")
                .param(userId)
                .query((rs, i) -> new Preference(rs.getString(1), rs.getString(2), rs.getDouble(3))).list();
    }

    public void deleteAll(String userId) {
        jdbc.sql("DELETE FROM user_preference WHERE user_id = ?").param(userId).update();
    }
}
