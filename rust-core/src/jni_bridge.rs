use crate::classifier::Classifier;
use crate::db::Database;
use crate::expirer::ExpirationEngine;
use chrono::Utc;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jfloat, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use std::sync::{Mutex, OnceLock};

static DB: Mutex<Option<Database>> = Mutex::new(None);
static CLASSIFIER: OnceLock<Classifier> = OnceLock::new();

fn get_classifier() -> &'static Classifier {
    CLASSIFIER.get_or_init(Classifier::new)
}

/// Acquires the DB mutex, recovering from poisoning. Returns None if DB is uninitialized.
/// This prevents any JNI panic from permanently locking the mutex on subsequent calls.
macro_rules! lock_db {
    () => {
        DB.lock().unwrap_or_else(|poisoned| {
            log::warn!("DB Mutex was poisoned; recovering guard.");
            poisoned.into_inner()
        })
    };
}

#[no_mangle]
pub extern "system" fn Java_com_expirer_core_ExpirerJni_initNative(
    mut env: JNIEnv,
    _class: JClass,
    db_path: JString,
) -> jboolean {
    #[cfg(target_os = "android")]
    {
        let _ = android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Debug)
                .with_tag("ScreenshotExpirerRust"),
        );
    }

    let path_str: String = match env.get_string(&db_path) {
        Ok(s) => s.into(),
        Err(e) => {
            log::error!("Failed to read db_path string: {:?}", e);
            return JNI_FALSE;
        }
    };

    match Database::open(&path_str) {
        Ok(database) => {
            let mut lock = lock_db!();
            *lock = Some(database);
            log::info!("Rust database successfully initialized at: {}", path_str);
            JNI_TRUE
        }
        Err(e) => {
            log::error!("Failed to open database at {}: {:?}", path_str, e);
            JNI_FALSE
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_expirer_core_ExpirerJni_classifyText(
    mut env: JNIEnv,
    _class: JClass,
    ocr_text: JString,
    file_name: JString,
) -> jstring {
    let ocr: String = env.get_string(&ocr_text).map(|s| s.into()).unwrap_or_default();
    let name: String = env.get_string(&file_name).map(|s| s.into()).unwrap_or_default();

    let classifier = get_classifier();
    let result = classifier.classify(&ocr, &name);

    let json_str = serde_json::to_string(&result).unwrap_or_else(|_| "{}".to_string());
    env.new_string(json_str)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_expirer_core_ExpirerJni_tagScreenshot(
    mut env: JNIEnv,
    _class: JClass,
    file_path: JString,
    uri_string: JString,
    tag: JString,
    confidence: jfloat,
    ttl_hours: jlong,
    file_size_bytes: jlong,
) -> jlong {
    let path: String = match env.get_string(&file_path) {
        Ok(s) => s.into(),
        Err(_) => return -1,
    };
    let uri: Option<String> = if !uri_string.is_null() {
        env.get_string(&uri_string).ok().map(|s| s.into())
    } else {
        None
    };
    let tag_str: String = env.get_string(&tag).map(|s| s.into()).unwrap_or_else(|_| "OTHER".to_string());

    let lock = lock_db!();
    if let Some(db) = lock.as_ref() {
        let now = Utc::now().timestamp();
        let ttl_secs = if ttl_hours > 0 { ttl_hours * 3600 } else { 24 * 3600 };
        let expires_at = now + ttl_secs;

        match db.insert_or_replace_screenshot(&crate::db::NewScreenshot {
            file_path: &path,
            uri_string: uri.as_deref(),
            tag: &tag_str,
            confidence,
            created_at: now,
            expires_at,
            file_size_bytes,
        }) {
            Ok(id) => id,
            Err(e) => {
                log::error!("Error tagging screenshot in DB: {:?}", e);
                -1
            }
        }
    } else {
        log::error!("Database not initialized");
        -1
    }
}

#[no_mangle]
pub extern "system" fn Java_com_expirer_core_ExpirerJni_getActiveScreenshots(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let lock = lock_db!();
    let records = if let Some(db) = lock.as_ref() {
        db.get_active_screenshots().unwrap_or_default()
    } else {
        Vec::new()
    };

    let json_str = serde_json::to_string(&records).unwrap_or_else(|_| "[]".to_string());
    env.new_string(json_str)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_expirer_core_ExpirerJni_markKept(
    mut env: JNIEnv,
    _class: JClass,
    file_path: JString,
) -> jboolean {
    let path: String = match env.get_string(&file_path) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };

    let lock = lock_db!();
    if let Some(db) = lock.as_ref() {
        match db.mark_kept_by_path(&path) {
            Ok(_) => JNI_TRUE,
            Err(e) => {
                log::error!("Error marking kept for {}: {:?}", path, e);
                JNI_FALSE
            }
        }
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_expirer_core_ExpirerJni_deleteNow(
    mut env: JNIEnv,
    _class: JClass,
    file_path: JString,
) -> jboolean {
    let path: String = match env.get_string(&file_path) {
        Ok(s) => s.into(),
        Err(_) => return JNI_FALSE,
    };

    let lock = lock_db!();
    if let Some(db) = lock.as_ref() {
        let now = Utc::now().timestamp();
        match ExpirationEngine::delete_immediate(db, &path, now) {
            Ok(_) => JNI_TRUE,
            Err(e) => {
                log::error!("Error deleting {}: {:?}", path, e);
                JNI_FALSE
            }
        }
    } else {
        JNI_FALSE
    }
}

#[no_mangle]
pub extern "system" fn Java_com_expirer_core_ExpirerJni_runExpiration(
    mut env: JNIEnv,
    _class: JClass,
    trash_dir: JString,
) -> jstring {
    let trash_path: Option<String> = if !trash_dir.is_null() {
        env.get_string(&trash_dir)
            .ok()
            .map(String::from)
            .filter(|s: &String| !s.is_empty())
    } else {
        None
    };

    let lock = lock_db!();
    let summary = if let Some(db) = lock.as_ref() {
        let now = Utc::now().timestamp();
        ExpirationEngine::process_expirations(db, now, trash_path.as_deref()).unwrap_or_else(|e| {
            crate::expirer::ExpirationSummary {
                deleted_count: 0,
                freed_bytes: 0,
                deleted_paths: Vec::new(),
                deleted_uris: Vec::new(),
                errors: vec![format!("DB error: {:?}", e)],
            }
        })
    } else {
        crate::expirer::ExpirationSummary {
            deleted_count: 0,
            freed_bytes: 0,
            deleted_paths: Vec::new(),
            deleted_uris: Vec::new(),
            errors: vec!["Database not initialized".to_string()],
        }
    };

    let json_str = serde_json::to_string(&summary).unwrap_or_else(|_| "{}".to_string());
    env.new_string(json_str)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_expirer_core_ExpirerJni_getStats(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let lock = lock_db!();
    let stats = if let Some(db) = lock.as_ref() {
        db.get_stats().unwrap_or(crate::db::DBStats {
            total_active: 0,
            total_kept: 0,
            total_deleted: 0,
            estimated_bytes_freed: 0,
        })
    } else {
        crate::db::DBStats {
            total_active: 0,
            total_kept: 0,
            total_deleted: 0,
            estimated_bytes_freed: 0,
        }
    };

    let json_str = serde_json::to_string(&stats).unwrap_or_else(|_| "{}".to_string());
    env.new_string(json_str)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}
