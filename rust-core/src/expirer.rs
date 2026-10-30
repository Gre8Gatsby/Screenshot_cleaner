use crate::db::{Database, ScreenshotRecord};
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::Path;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExpirationSummary {
    pub deleted_count: usize,
    pub freed_bytes: i64,
    pub deleted_paths: Vec<String>,
    pub deleted_uris: Vec<String>,
    pub errors: Vec<String>,
}

pub struct ExpirationEngine;

impl ExpirationEngine {
    /// Scans the database for expired screenshots (expires_at <= now_ts) and processes deletion.
    /// If trash_dir is provided, moves the file there; otherwise performs permanent fs::remove_file.
    pub fn process_expirations(
        db: &Database,
        now_ts: i64,
        trash_dir: Option<&str>,
    ) -> Result<ExpirationSummary, rusqlite::Error> {
        let expired_list: Vec<ScreenshotRecord> = db.get_expired_screenshots(now_ts)?;

        let mut deleted_count = 0;
        let mut freed_bytes = 0i64;
        let mut deleted_paths = Vec::new();
        let mut deleted_uris = Vec::new();
        let mut errors = Vec::new();

        for record in expired_list {
            let path = Path::new(&record.file_path);
            let mut file_removed = false;

            if path.exists() {
                if let Some(trash_path) = trash_dir {
                    // Attempt moving to trash folder
                    let trash_folder = Path::new(trash_path);
                    let _ = fs::create_dir_all(trash_folder);
                    if let Some(file_name) = path.file_name() {
                        let dest = trash_folder.join(file_name);
                        match fs::rename(path, &dest) {
                            Ok(_) => {
                                file_removed = true;
                                log::info!("Moved expired screenshot to trash: {:?}", dest);
                            }
                            Err(e) => {
                                // Fallback to remove directly if rename fails across mount points
                                match fs::remove_file(path) {
                                    Ok(_) => file_removed = true,
                                    Err(err) => {
                                        errors.push(format!("Trash & delete failed for {}: {}", record.file_path, err));
                                    }
                                }
                                log::warn!("Trash rename failed ({}). Fallback delete attempted.", e);
                            }
                        }
                    }
                } else {
                    // Direct permanent delete
                    match fs::remove_file(path) {
                        Ok(_) => {
                            file_removed = true;
                            log::info!("Permanently deleted expired screenshot: {}", record.file_path);
                        }
                        Err(e) => {
                            errors.push(format!("Failed to delete {}: {}", record.file_path, e));
                        }
                    }
                }
            } else {
                // File was already removed by user or another app
                file_removed = true;
                log::debug!("File already gone from storage: {}", record.file_path);
            }

            if file_removed {
                deleted_count += 1;
                freed_bytes += record.file_size_bytes;
                deleted_paths.push(record.file_path.clone());
                if let Some(uri) = record.uri_string {
                    deleted_uris.push(uri);
                }
                let _ = db.mark_status(record.id, "DELETED", Some(now_ts));
            }
        }

        Ok(ExpirationSummary {
            deleted_count,
            freed_bytes,
            deleted_paths,
            deleted_uris,
            errors,
        })
    }

    /// Immediately deletes a single screenshot by file path or record ID
    pub fn delete_immediate(
        db: &Database,
        file_path: &str,
        now_ts: i64,
    ) -> Result<bool, rusqlite::Error> {
        let path = Path::new(file_path);
        if path.exists() {
            let _ = fs::remove_file(path);
        }
        db.mark_deleted_by_path(file_path, now_ts)?;
        Ok(true)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;
    use tempfile::tempdir;

    #[test]
    fn test_process_expirations() {
        let dir = tempdir().expect("Failed to create tempdir");
        let file_path = dir.path().join("otp_sample.png");
        {
            let mut file = fs::File::create(&file_path).expect("Failed to create file");
            file.write_all(b"fake image data 1234567890").expect("Write failed");
        }

        let file_path_str = file_path.to_str().unwrap();
        let db = Database::open_in_memory().expect("In-memory DB creation failed");
        let now = 1000;
        let expired_time = 900; // already expired

        db.insert_or_replace_screenshot(&crate::db::NewScreenshot {
            file_path: file_path_str,
            uri_string: Some("content://test/1"),
            tag: "OTP",
            confidence: 0.9,
            created_at: 800,
            expires_at: expired_time,
            file_size_bytes: 26,
        }).expect("Insert failed");

        let summary = ExpirationEngine::process_expirations(&db, now, None)
            .expect("Expiration process failed");

        assert_eq!(summary.deleted_count, 1);
        assert_eq!(summary.freed_bytes, 26);
        assert!(!file_path.exists(), "File should have been deleted from disk");

        // DB check
        let active = db.get_active_screenshots().expect("Failed fetch");
        assert_eq!(active.len(), 0);
    }
}

