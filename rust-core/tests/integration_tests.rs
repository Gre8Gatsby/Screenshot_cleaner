use expirer::{Classifier, Database, ExpirationEngine, NewScreenshot, TagType};
use std::fs::File;
use std::io::Write;
use tempfile::tempdir;

#[test]
fn test_end_to_end_flow() {
    let dir = tempdir().expect("Failed to create temporary folder");
    let db_path = dir.path().join("test_expirer.db");
    let db = Database::open(db_path.to_str().unwrap()).expect("DB open failed");

    let classifier = Classifier::new();

    // 1. Create a dummy OTP screenshot file
    let otp_file = dir.path().join("Screenshot_20261030_OTP.png");
    {
        let mut f = File::create(&otp_file).expect("Create file");
        f.write_all(b"image binary data representing OTP screen").unwrap();
    }

    // 2. Classify screenshot text
    let ocr_text = "Your Axis Bank OTP is 839201 for INR 1,299 transaction. Valid for 10 mins. Do not share.";
    let res = classifier.classify(ocr_text, "Screenshot_20261030_OTP.png");
    assert_eq!(res.tag, TagType::Otp);
    assert!(res.should_auto_expire);
    assert_eq!(res.recommended_ttl_hours, 24);

    // 3. Tag screenshot in database with an expired timestamp (for testing expiry)
    let created_at = 1000;
    let expires_at = 1500;
    let file_size = 50000;

    let id = db.insert_or_replace_screenshot(&NewScreenshot {
        file_path: otp_file.to_str().unwrap(),
        uri_string: Some("content://media/external/images/media/442"),
        tag: &res.tag_name,
        confidence: res.confidence,
        created_at,
        expires_at,
        file_size_bytes: file_size,
    }).expect("Insert DB failed");

    assert!(id > 0);

    // 4. Check active list
    let active = db.get_active_screenshots().expect("Active query failed");
    assert_eq!(active.len(), 1);
    assert_eq!(active[0].tag, "OTP");

    // 5. Run expiration engine at timestamp = 2000 (after expires_at)
    let current_time = 2000;
    let summary = ExpirationEngine::process_expirations(&db, current_time, None)
        .expect("Expiration process failed");

    assert_eq!(summary.deleted_count, 1);
    assert_eq!(summary.freed_bytes, 50000);
    assert!(!otp_file.exists(), "OTP file must be deleted from storage");

    // 6. Verify stats
    let stats = db.get_stats().expect("Stats query failed");
    assert_eq!(stats.total_active, 0);
    assert_eq!(stats.total_deleted, 1);
    assert_eq!(stats.estimated_bytes_freed, 50000);
}

#[test]
fn test_mark_kept_preserves_file() {
    let dir = tempdir().expect("Failed to create temporary folder");
    let db_path = dir.path().join("test_expirer_kept.db");
    let db = Database::open(db_path.to_str().unwrap()).expect("DB open failed");

    let flight_file = dir.path().join("AirIndia_Ticket.png");
    {
        let mut f = File::create(&flight_file).expect("Create file");
        f.write_all(b"ticket binary data").unwrap();
    }

    let file_path = flight_file.to_str().unwrap();

    // Insert
    db.insert_or_replace_screenshot(&NewScreenshot {
        file_path,
        uri_string: None,
        tag: "BOARDING_PASS",
        confidence: 0.85,
        created_at: 1000,
        expires_at: 1200,
        file_size_bytes: 12000,
    }).unwrap();

    // User marks kept
    db.mark_kept_by_path(file_path).unwrap();

    // Process expiration at time 2000
    let summary = ExpirationEngine::process_expirations(&db, 2000, None).unwrap();
    assert_eq!(summary.deleted_count, 0, "Kept file should NOT be deleted");
    assert!(flight_file.exists(), "File should still exist on disk");

    let stats = db.get_stats().unwrap();
    assert_eq!(stats.total_kept, 1);
    assert_eq!(stats.total_active, 0);
}

