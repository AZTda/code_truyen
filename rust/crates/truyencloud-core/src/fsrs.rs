use crate::models::VocabularyCard;
use serde::{Deserialize, Serialize};

/// 4 mức đánh giá thẻ FSRS chuẩn (`Quên`, `Khó`, `Tốt`, `Dễ`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub enum FsrsRating {
    Again = 1,
    Hard = 2,
    Good = 3,
    Easy = 4,
}

impl FsrsRating {
    pub fn label_vi(self) -> &'static str {
        match self {
            Self::Again => "Quên (1p)",
            Self::Hard => "Khó",
            Self::Good => "Thuộc",
            Self::Easy => "Rất dễ",
        }
    }

    pub fn score_f64(self) -> f64 {
        (self as u8) as f64
    }
}

/// Bộ lập lịch lặp lại ngắt quãng FSRS v4.5 viết thuần bằng Rust.
#[derive(Debug, Clone)]
pub struct FsrsScheduler {
    pub request_retention: f64,
    pub max_interval_days: f64,
    pub weights: [f64; 17],
}

impl Default for FsrsScheduler {
    fn default() -> Self {
        Self {
            request_retention: 0.90,
            max_interval_days: 3650.0,
            weights: [
                0.4072, 1.1829, 3.1262, 15.4722, 7.2102, 0.5316, 1.0651, 0.0234, 1.616, 0.1544,
                1.0824, 1.9813, 0.0953, 0.2975, 2.2042, 0.2407, 2.9466,
            ],
        }
    }
}

impl FsrsScheduler {
    /// Tính xác suất nhớ hiện tại `R(t, S) = (1 + t / (9 * S))^-1`.
    pub fn retrievability(&self, elapsed_days: f64, stability: f64) -> f64 {
        if stability <= 0.0 {
            return 0.0;
        }
        let t = elapsed_days.max(0.0);
        (1.0 + t / (9.0 * stability)).powf(-1.0)
    }

    /// Chấm điểm một thẻ từ vựng và trả về bản sao thẻ đã cập nhật `stability`, `difficulty`, `next_review_ms`.
    pub fn review(&self, card: &VocabularyCard, rating: FsrsRating, now_ms: i64) -> VocabularyCard {
        let mut next = card.clone();
        let g = rating.score_f64();

        if card.reps == 0 || card.stability <= 0.0 {
            // Lần học đầu tiên
            let idx = (rating as usize).saturating_sub(1).min(3);
            next.stability = self.weights[idx].max(0.1);
            let d0 = self.weights[4] - self.weights[5] * (g - 3.0);
            next.difficulty = d0.clamp(1.0, 10.0);
        } else {
            let elapsed_days = if card.last_review_ms > 0 && now_ms > card.last_review_ms {
                (now_ms - card.last_review_ms) as f64 / 86_400_000.0
            } else {
                1.0
            };
            let r = self.retrievability(elapsed_days, card.stability);
            let delta_d = -self.weights[6] * (g - 3.0);
            let damped_d = card.difficulty + delta_d * ((10.0 - card.difficulty) / 9.0);
            let d0_easy = self.weights[4] - self.weights[5] * (4.0 - 3.0);
            next.difficulty =
                (self.weights[7] * d0_easy + (1.0 - self.weights[7]) * damped_d).clamp(1.0, 10.0);

            if rating == FsrsRating::Again {
                next.lapses = card.lapses.saturating_add(1);
                let s_fail = self.weights[11]
                    * next.difficulty.powf(-self.weights[12])
                    * ((card.stability + 1.0).powf(self.weights[13]) - 1.0)
                    * (self.weights[14] * (1.0 - r)).exp();
                next.stability = s_fail.clamp(0.1, card.stability);
            } else {
                let hard_penalty = if rating == FsrsRating::Hard {
                    self.weights[15]
                } else {
                    1.0
                };
                let easy_bonus = if rating == FsrsRating::Easy {
                    self.weights[16]
                } else {
                    1.0
                };
                let factor = self.weights[8].exp()
                    * (11.0 - next.difficulty)
                    * card.stability.powf(-self.weights[9])
                    * (((1.0 - r) * self.weights[10]).exp() - 1.0)
                    * hard_penalty
                    * easy_bonus;
                next.stability = (card.stability * (1.0 + factor)).clamp(0.1, self.max_interval_days);
            }
        }

        next.reps = card.reps.saturating_add(1);
        next.last_review_ms = now_ms;

        let interval_ms = if rating == FsrsRating::Again {
            60_000 // Ôn lại sau 1 phút
        } else {
            let days = (9.0 * next.stability * (1.0 / self.request_retention - 1.0))
                .clamp(0.5, self.max_interval_days);
            (days * 86_400_000.0).round() as i64
        };
        next.next_review_ms = now_ms + interval_ms;
        next
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fsrs_progression_increases_stability_on_good_and_easy() {
        let scheduler = FsrsScheduler::default();
        let card = VocabularyCard {
            id: "v1".into(),
            word: "cultivation".into(),
            meaning_vi: "tu luyện".into(),
            ..Default::default()
        };
        let now = 1_700_000_000_000;
        let first = scheduler.review(&card, FsrsRating::Good, now);
        assert!(first.stability > 2.5);
        assert_eq!(first.reps, 1);

        let second = scheduler.review(&first, FsrsRating::Easy, first.next_review_ms);
        assert!(second.stability > first.stability * 2.0);
        assert_eq!(second.reps, 2);
    }

    #[test]
    fn fsrs_lapse_resets_short_interval() {
        let scheduler = FsrsScheduler::default();
        let card = VocabularyCard {
            id: "v2".into(),
            word: "immortal".into(),
            stability: 12.0,
            difficulty: 5.0,
            reps: 3,
            last_review_ms: 1_000_000_000,
            ..Default::default()
        };
        let failed = scheduler.review(&card, FsrsRating::Again, 1_500_000_000);
        assert_eq!(failed.lapses, 1);
        assert_eq!(failed.next_review_ms - 1_500_000_000, 60_000);
    }
}
