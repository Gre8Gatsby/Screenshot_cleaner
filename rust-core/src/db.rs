use rusqlite::{params, Connection, Result};
use serde::{Deserialize, Serialize};

/// Bundles all fields needed to insert/update a screenshot record,
/// avoiding Clippy's too-many-arguments lint on insert_or_replace_screenshot.
#[derive(Debug, Clone)]
pub struct NewScreenshot<'a> {
    pub file_path: &'a str,
    pub uri_string: Option<&'a str>,
    pub tag: &'a str,
    pub confidence: f32,
    pub created_at: i64,
    pub expires_at: i64,
    pub file_size_bytes: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScreenshotRecord {
    pub id: i64,
    pub file_path: String,
    pub uri_string: Option<String>,
    pub tag: String,
    pub confidence: f32,
    pub created_at: i64,
    pub expires_at: i64,
    pub status: String, // 'ACTIVE', 'KEPT', 'DELETED', 'TRASHED'
    pub file_size_bytes: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DBStats {
    pub total_active: i64,
    pub total_kept: i64,
    pub total_deleted: i64,
    pub estimated_bytes_freed: i64,
}

pub struct Database {
    conn: Connection,
}

impl Database {
    pub fn open(path: &str) -> Result<Self> {
        let conn = Connection::open(path)?;
        let db = Self { conn };
        db.init_schema()?;
        Ok(db)
    }

    #[cfg(test)]
    pub fn open_in_memory() -> Result<Self> {
        let conn = Connection::open_in_memory()?;
        let db = Self { conn };
        db.init_schema()?;
        Ok(db)
    }

    pub fn init_schema(&self) -> Result<()> {
        self.conn.execute_batch(
            "
            CREATE TABLE IF NOT EXISTS tagged_screenshots (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                file_path TEXT NOT NULL UNIQUE,
                uri_string TEXT,
                tag TEXT NOT NULL,
                confidence REAL NOT NULL,
                created_at INTEGER NOT NULL,
                expires_at INTEGER NOT NULL,
                status TEXT NOT NULL DEFAULT 'ACTIVE',
                file_size_bytes INTEGER NOT NULL DEFAULT 0,
                deleted_at INTEGER
            );

            CREATE INDEX IF NOT EXISTS idx_expires_status ON tagged_screenshots(expires_at, status);
            CREATE INDEX IF NOT EXISTS idx_file_path ON tagged_screenshots(file_path);
            ",
        )?;
        Ok(())
    }

    pub fn insert_or_replace_screenshot(&self, s: &NewScreenshot<'_>) -> Result<i64> {
        self.conn.execute(
            "INSERT INTO tagged_screenshots (file_path, uri_string, tag, confidence, created_at, expires_at, status, file_size_bytes)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, 'ACTIVE', ?7)
             ON CONFLICT(file_path) DO UPDATE SET
                tag = excluded.tag,
                confidence = excluded.confidence,
                expires_at = excluded.expires_at,
                status = 'ACTIVE',
                file_size_bytes = excluded.file_size_bytes",
            params![
                s.file_path,
                s.uri_string,
                s.tag,
                s.confidence,
                s.created_at,
                s.expires_at,
                s.file_size_bytes,
            ],
        )?;

        Ok(self.conn.last_insert_rowid())
    }

    pub fn get_active_screenshots(&self) -> Result<Vec<ScreenshotRecord>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, file_path, uri_string, tag, confidence, created_at, expires_at, status, file_size_bytes
             FROM tagged_screenshots
             WHERE status = 'ACTIVE'
             ORDER BY expires_at ASC",
        )?;

        let rows = stmt.query_map([], |row| {
            Ok(ScreenshotRecord {
                id: row.get(0)?,
                file_path: row.get(1)?,
                uri_string: row.get(2)?,
                tag: row.get(3)?,
                confidence: row.get(4)?,
                created_at: row.get(5)?,
                expires_at: row.get(6)?,
                status: row.get(7)?,
                file_size_bytes: row.get(8)?,
            })
        })?;

        let mut list = Vec::new();
        for r in rows {
            list.push(r?);
        }
        Ok(list)
    }

    pub fn get_expired_screenshots(&self, now_ts: i64) -> Result<Vec<ScreenshotRecord>> {
        let mut stmt = self.conn.prepare(
            "SELECT id, file_path, uri_string, tag, confidence, created_at, expires_at, status, file_size_bytes
             FROM tagged_screenshots
             WHERE status = 'ACTIVE' AND expires_at <= ?1
             ORDER BY expires_at ASC",
        )?;

        let rows = stmt.query_map(params![now_ts], |row| {
            Ok(ScreenshotRecord {
                id: row.get(0)?,
                file_path: row.get(1)?,
                uri_string: row.get(2)?,
                tag: row.get(3)?,
                confidence: row.get(4)?,
                created_at: row.get(5)?,
                expires_at: row.get(6)?,
                status: row.get(7)?,
                file_size_bytes: row.get(8)?,
            })
        })?;

        let mut list = Vec::new();
        for r in rows {
            list.push(r?);
        }
        Ok(list)
    }

    pub fn mark_status(&self, id: i64, new_status: &str, deleted_at: Option<i64>) -> Result<usize> {
        self.conn.execute(
            "UPDATE tagged_screenshots SET status = ?1, deleted_at = ?2 WHERE id = ?3",
            params![new_status, deleted_at, id],
        )
    }

    pub fn mark_kept_by_path(&self, file_path: &str) -> Result<usize> {
        self.conn.execute(
            "UPDATE tagged_screenshots SET status = 'KEPT' WHERE file_path = ?1",
            params![file_path],
        )
    }

    pub fn mark_deleted_by_path(&self, file_path: &str, now_ts: i64) -> Result<usize> {
        self.conn.execute(
            "UPDATE tagged_screenshots SET status = 'DELETED', deleted_at = ?1 WHERE file_path = ?2",
            params![now_ts, file_path],
        )
    }

    pub fn delete_record(&self, id: i64) -> Result<usize> {
        self.conn.execute(
            "DELETE FROM tagged_screenshots WHERE id = ?1",
            params![id],
        )
    }

    pub fn get_stats(&self) -> Result<DBStats> {
        let total_active: i64 = self.conn.query_row(
            "SELECT count(*) FROM tagged_screenshots WHERE status = 'ACTIVE'",
            [],
            |r| r.get(0),
        ).unwrap_or(0);

        let total_kept: i64 = self.conn.query_row(
            "SELECT count(*) FROM tagged_screenshots WHERE status = 'KEPT'",
            [],
            |r| r.get(0),
        ).unwrap_or(0);

        let total_deleted: i64 = self.conn.query_row(
            "SELECT count(*) FROM tagged_screenshots WHERE status = 'DELETED'",
            [],
            |r| r.get(0),
        ).unwrap_or(0);

        let estimated_bytes_freed: i64 = self.conn.query_row(
            "SELECT coalesce(sum(file_size_bytes), 0) FROM tagged_screenshots WHERE status = 'DELETED'",
            [],
            |r| r.get(0),
        ).unwrap_or(0);

        Ok(DBStats {
            total_active,
            total_kept,
            total_deleted,
            estimated_bytes_freed,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_db_lifecycle() {
        let db = Database::open_in_memory().expect("In-memory DB creation failed");
        let now = 1700000000;
        let expires = now + 86400;

        let id = db.insert_or_replace_screenshot(&NewScreenshot {
            file_path: "/storage/emulated/0/DCIM/Screenshots/otp_1.png",
            uri_string: Some("content://media/external/images/media/101"),
            tag: "OTP",
            confidence: 0.92,
            created_at: now,
            expires_at: expires,
            file_size_bytes: 245100,
        }).expect("Insert failed");

        assert!(id > 0);

        let active = db.get_active_screenshots().expect("Failed to fetch active");
        assert_eq!(active.len(), 1);
        assert_eq!(active[0].tag, "OTP");

        // Expired query before expire time -> empty
        let expired_early = db.get_expired_screenshots(now + 1000).expect("Query failed");
        assert_eq!(expired_early.len(), 0);

        // Expired query after expire time -> 1 item
        let expired_later = db.get_expired_screenshots(expires + 10).expect("Query failed");
        assert_eq!(expired_later.len(), 1);

        // Mark kept
        db.mark_kept_by_path("/storage/emulated/0/DCIM/Screenshots/otp_1.png").expect("Mark kept");
        let active_after = db.get_active_screenshots().expect("Failed to fetch active");
        assert_eq!(active_after.len(), 0);

        let stats = db.get_stats().expect("Stats failed");
        assert_eq!(stats.total_kept, 1);
        assert_eq!(stats.total_active, 0);
    }
}

