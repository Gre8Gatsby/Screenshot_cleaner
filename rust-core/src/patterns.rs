use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum TagType {
    Otp,
    Payment,
    BoardingPass,
    Receipt,
    Temporary,
    Other,
}

impl TagType {
    pub fn as_str(&self) -> &'static str {
        match self {
            TagType::Otp => "OTP",
            TagType::Payment => "PAYMENT",
            TagType::BoardingPass => "BOARDING_PASS",
            TagType::Receipt => "RECEIPT",
            TagType::Temporary => "TEMPORARY",
            TagType::Other => "OTHER",
        }
    }

    pub fn from_tag_str(s: &str) -> Self {
        match s.to_uppercase().as_str() {
            "OTP" => TagType::Otp,
            "PAYMENT" => TagType::Payment,
            "BOARDING_PASS" | "BOARDING" | "TICKET" => TagType::BoardingPass,
            "RECEIPT" => TagType::Receipt,
            "TEMPORARY" | "TEMP" => TagType::Temporary,
            _ => TagType::Other,
        }
    }
}

/// Implement std::str::FromStr so Clippy is happy and callers can use `.parse::<TagType>()`.
#[derive(Debug)]
pub struct TagParseError;

impl std::str::FromStr for TagType {
    type Err = TagParseError;

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        Ok(TagType::from_tag_str(s))
    }
}

pub struct PatternMatcher {
    // We compile regexes once or inspect strings
    otp_code_regex: regex::Regex,
    currency_regex: regex::Regex,
    pnr_regex: regex::Regex,
}

impl Default for PatternMatcher {
    fn default() -> Self {
        Self::new()
    }
}

impl PatternMatcher {
    pub fn new() -> Self {
        Self {
            otp_code_regex: regex::Regex::new(r"\b\d{4,8}\b").expect("Valid OTP regex"),
            currency_regex: regex::Regex::new(r"(?:₹|rs\.?|inr|\$|€|£)\s*\d+(?:[.,]\d{1,2})?").expect("Valid currency regex"),
            pnr_regex: regex::Regex::new(r"\b[A-Z0-9]{6}\b").expect("Valid PNR regex"),
        }
    }

    /// Evaluates text and file name, returning the best tag, confidence score (0.0 - 1.0), and matched cues.
    pub fn evaluate(&self, text: &str, file_name: &str) -> (TagType, f32, Vec<String>) {
        let lower_text = text.to_lowercase();
        let lower_filename = file_name.to_lowercase();
        let mut cues = Vec::new();

        // 1. Check filename hints first
        let mut filename_tag = None;
        if lower_filename.contains("otp") || lower_filename.contains("code") {
            cues.push("filename:otp".to_string());
            filename_tag = Some(TagType::Otp);
        } else if lower_filename.contains("paytm") || lower_filename.contains("gpay") || lower_filename.contains("payment") || lower_filename.contains("upi") {
            cues.push("filename:payment".to_string());
            filename_tag = Some(TagType::Payment);
        } else if lower_filename.contains("boarding") || lower_filename.contains("ticket") || lower_filename.contains("flight") {
            cues.push("filename:boarding".to_string());
            filename_tag = Some(TagType::BoardingPass);
        }

        // 2. Score OTP indicators
        let mut otp_score = 0.0f32;
        let otp_keywords = [
            "one time password", "one-time password", "verification code",
            "security code", "auth code", "secret code", "login code",
            "otp is", "your otp", "do not share", "valid for", "expires in",
            "expires after", "authenticate", "2-step verification", "two-factor",
        ];
        for kw in otp_keywords {
            if lower_text.contains(kw) {
                otp_score += 0.25;
                cues.push(format!("otp_kw:{}", kw));
            }
        }
        if lower_text.contains("otp") {
            otp_score += 0.3;
            cues.push("otp_kw:otp".to_string());
        }
        if self.otp_code_regex.is_match(&lower_text) && otp_score > 0.0 {
            otp_score += 0.25;
            cues.push("otp:code_digits_found".to_string());
        }

        // 3. Score Payment indicators
        let mut payment_score = 0.0f32;
        let payment_keywords = [
            "paid successfully", "payment successful", "transaction successful",
            "debited from", "credited to", "upi transaction id", "upi ref no",
            "txn id", "transaction id", "payment receipt", "money sent",
            "transfer successful", "order confirmed", "bill paid",
            "google pay", "phonepe", "paytm", "bhim upi", "amazon pay",
        ];
        for kw in payment_keywords {
            if lower_text.contains(kw) {
                payment_score += 0.3;
                cues.push(format!("pay_kw:{}", kw));
            }
        }
        if self.currency_regex.is_match(&lower_text) {
            payment_score += 0.2;
            cues.push("pay:currency_amount".to_string());
        }

        // 4. Score Boarding Pass / Travel indicators
        let mut boarding_score = 0.0f32;
        let travel_keywords = [
            "boarding pass", "e-ticket", "flight ticket", "gate closes",
            "boarding time", "terminal", "seat no", "departure time",
            "pnr status", "irctc", "coach", "berth", "airline", "indigo",
            "air india", "vistara", "emirates", "passenger name",
        ];
        for kw in travel_keywords {
            if lower_text.contains(kw) {
                boarding_score += 0.3;
                cues.push(format!("travel_kw:{}", kw));
            }
        }
        if lower_text.contains("pnr") && self.pnr_regex.is_match(text) {
            boarding_score += 0.25;
            cues.push("travel:pnr_matched".to_string());
        }

        // 5. Score Receipt indicators
        let mut receipt_score = 0.0f32;
        let receipt_keywords = ["invoice #", "receipt #", "tax invoice", "subtotal", "total amount", "grand total", "item count"];
        for kw in receipt_keywords {
            if lower_text.contains(kw) {
                receipt_score += 0.25;
                cues.push(format!("receipt_kw:{}", kw));
            }
        }

        // Factor in filename boost (+0.35)
        if let Some(ft) = filename_tag {
            match ft {
                TagType::Otp => otp_score += 0.35,
                TagType::Payment => payment_score += 0.35,
                TagType::BoardingPass => boarding_score += 0.35,
                _ => {}
            }
        }

        // Determine maximum category
        let scores = [
            (TagType::Otp, otp_score),
            (TagType::Payment, payment_score),
            (TagType::BoardingPass, boarding_score),
            (TagType::Receipt, receipt_score),
        ];

        let mut best_tag = TagType::Other;
        let mut best_score = 0.0f32;

        for (tag, score) in scores {
            let clamped = score.min(1.0);
            if clamped > best_score {
                best_score = clamped;
                best_tag = tag;
            }
        }

        if best_score < 0.35 {
            // Check for generic temporary passwords / codes
            if lower_text.contains("temporary password") || lower_text.contains("wifi password") || lower_text.contains("passcode") {
                cues.push("temp:passcode".to_string());
                return (TagType::Temporary, 0.65, cues);
            }
            (TagType::Other, best_score, cues)
        } else {
            (best_tag, best_score, cues)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_otp_detection() {
        let matcher = PatternMatcher::new();
        let text = "Your Uber verification code is 492018. Never share this OTP with anyone. Valid for 10 minutes.";
        let (tag, confidence, cues) = matcher.evaluate(text, "Screenshot_20261030_123456.png");

        assert_eq!(tag, TagType::Otp);
        assert!(confidence >= 0.7, "Confidence was {}", confidence);
        assert!(!cues.is_empty());
    }

    #[test]
    fn test_payment_detection() {
        let matcher = PatternMatcher::new();
        let text = "Paid successfully ₹450 to Starbucks Coffee. UPI Transaction ID: 394829104928. Debited from HDFC Bank.";
        let (tag, confidence, _) = matcher.evaluate(text, "Screenshot_20261030_142010.png");

        assert_eq!(tag, TagType::Payment);
        assert!(confidence >= 0.7);
    }

    #[test]
    fn test_boarding_pass_detection() {
        let matcher = PatternMatcher::new();
        let text = "IndiGo Boarding Pass. Flight 6E 204. Gate 14B. Seat 22A. Terminal 2. PNR: W8XZ9Q. Boarding time: 17:30.";
        let (tag, confidence, _) = matcher.evaluate(text, "Screenshot_ticket.png");

        assert_eq!(tag, TagType::BoardingPass);
        assert!(confidence >= 0.7);
    }

    #[test]
    fn test_normal_meme_or_chat_ignored() {
        let matcher = PatternMatcher::new();
        let text = "Hey what time are we meeting for dinner tomorrow? Let me know if 7pm works for everyone!";
        let (tag, confidence, _) = matcher.evaluate(text, "Screenshot_chat.png");

        assert_eq!(tag, TagType::Other);
        assert!(confidence < 0.35);
    }
}

