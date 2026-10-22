package com.mall.agent.data;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/** 会话摘要:Redis 里的历史过期后,摘要仍可恢复早期关键信息。 */
@Repository
public class SummaryRepo {

    private final JdbcClient jdbc;

    public SummaryRepo(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> get(String sessionId) {
        return jdbc.sql("SELECT summary FROM conversation_summary WHERE session_id = ?")
                .param(sessionId).query(String.class).optional();
    }

    public void save(String sessionId, String userId, String summary, int coveredCount) {
        int updated = jdbc.sql("UPDATE conversation_summary SET summary=?, covered_msg_count=?, "
                        + "updated_at=CURRENT_TIMESTAMP WHERE session_id=?")
                .params(summary, coveredCount, sessionId).update();
        if (updated == 0) {
            jdbc.sql("INSERT INTO conversation_summary (session_id, user_id, summary, covered_msg_count) "
                            + "VALUES (?,?,?,?)")
                    .params(sessionId, userId, summary, coveredCount).update();
        }
    }
}
