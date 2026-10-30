use crate::patterns::{PatternMatcher, TagType};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ClassificationResult {
    pub tag: TagType,
    pub tag_name: String,
    pub confidence: f32,
    pub should_auto_expire: bool,
    pub recommended_ttl_hours: u32,
    pub matched_cues: Vec<String>,
    pub explanation: String,
}

pub struct Classifier {
    matcher: PatternMatcher,
}

impl Default for Classifier {
    fn default() -> Self {
        Self::new()
    }
}

impl Classifier {
    pub fn new() -> Self {
        Self {
            matcher: PatternMatcher::new(),
        }
    }

    pub fn classify(&self, ocr_text: &str, file_name: &str) -> ClassificationResult {
        let (tag, confidence, cues) = self.matcher.evaluate(ocr_text, file_name);

        let (should_auto_expire, ttl_hours, explanation) = match tag {
            TagType::Otp => (
                confidence >= 0.45,
                24,
                "Detected One-Time Password / 2FA authentication code.".to_string(),
            ),
            TagType::Payment => (
                confidence >= 0.50,
                24,
                "Detected payment or UPI transaction confirmation.".to_string(),
            ),
            TagType::BoardingPass => (
                confidence >= 0.50,
                48, // Boarding passes / tickets typically expire after travel day (48h)
                "Detected boarding pass or travel ticket confirmation.".to_string(),
            ),
            TagType::Receipt => (
                confidence >= 0.55,
                72, // Receipts might be kept for 3 days before expiry
                "Detected purchase receipt / tax invoice.".to_string(),
            ),
            TagType::Temporary => (
                confidence >= 0.45,
                24,
                "Detected temporary information or passcode.".to_string(),
            ),
            TagType::Other => (
                false,
                0,
                "No sensitive or temporary pattern detected.".to_string(),
            ),
        };

        ClassificationResult {
            tag_name: tag.as_str().to_string(),
            tag,
            confidence,
            should_auto_expire,
            recommended_ttl_hours: ttl_hours,
            matched_cues: cues,
            explanation,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_otp_auto_expire() {
        let classifier = Classifier::new();
        let res = classifier.classify("Your OTP is 738291. Do not share.", "Screenshot_20261030.jpg");
        assert!(res.should_auto_expire);
        assert_eq!(res.tag, TagType::Otp);
        assert_eq!(res.recommended_ttl_hours, 24);
    }

    #[test]
    fn test_chat_not_auto_expire() {
        let classifier = Classifier::new();
        let res = classifier.classify("Hello world from team chat!", "Screenshot_chat.jpg");
        assert!(!res.should_auto_expire);
        assert_eq!(res.tag, TagType::Other);
    }
}

