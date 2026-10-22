package com.mall.agent.data;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/** 知识库文档登记:增量更新靠 content_hash,版本管理靠 effective。 */
@Repository
public class KbDocumentRepo {

    private final JdbcClient jdbc;

    public KbDocumentRepo(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<KbDoc> find(String docId, int version) {
        return jdbc.sql("SELECT * FROM kb_document WHERE doc_id = ? AND version = ?")
                .params(docId, version).query(KbDocumentRepo::map).optional();
    }

    public List<KbDoc> listByDocId(String docId) {
        return jdbc.sql("SELECT * FROM kb_document WHERE doc_id = ? ORDER BY version")
                .param(docId).query(KbDocumentRepo::map).list();
    }

    public void save(KbDoc d) {
        int updated = jdbc.sql("UPDATE kb_document SET title=?, category=?, file_name=?, content_hash=?, "
                        + "chunk_count=?, effective=?, updated_at=CURRENT_TIMESTAMP WHERE doc_id=? AND version=?")
                .params(d.title(), d.category(), d.fileName(), d.contentHash(), d.chunkCount(),
                        d.effective(), d.docId(), d.version()).update();
        if (updated == 0) {
            jdbc.sql("INSERT INTO kb_document (doc_id, version, title, category, file_name, content_hash, "
                            + "chunk_count, effective) VALUES (?,?,?,?,?,?,?,?)")
                    .params(d.docId(), d.version(), d.title(), d.category(), d.fileName(), d.contentHash(),
                            d.chunkCount(), d.effective()).update();
        }
    }

    public void setEffective(String docId, int version, boolean effective) {
        jdbc.sql("UPDATE kb_document SET effective = ? WHERE doc_id = ? AND version = ?")
                .params(effective, docId, version).update();
    }

    public void delete(String docId) {
        jdbc.sql("DELETE FROM kb_document WHERE doc_id = ?").param(docId).update();
    }

    private static KbDoc map(java.sql.ResultSet rs, int i) throws java.sql.SQLException {
        return new KbDoc(rs.getString("doc_id"), rs.getInt("version"), rs.getString("title"),
                rs.getString("category"), rs.getString("file_name"), rs.getString("content_hash"),
                rs.getInt("chunk_count"), rs.getBoolean("effective"));
    }
}
