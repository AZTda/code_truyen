/// Bộ chuẩn hoá từ điển Hán-Việt, bỏ dấu tiếng Việt (phục vụ tìm kiếm không dấu) và chuẩn hoá từ gốc tiếng Anh (`Lemma`).

const PHRASE_DICT: &[(&str, &str)] = &[
    ("凡人修仙传", "Phàm Nhân Tu Tiên"),
    ("仙逆", "Tiên Nghịch"),
    ("诡秘之主", "Quỷ Bí Chi Chủ"),
    ("完美世界", "Hoàn Mỹ Thế Giới"),
    ("遮天", "Già Thiên"),
    ("一念永恒", "Nhất Niệm Vĩnh Hằng"),
    ("大奉打更人", "Đại Phụng Đả Canh Nhân"),
    ("剑来", "Kiếm Lai"),
    ("道诡异仙", "Đạo Quỷ Dị Tiên"),
    ("深空彼岸", "Thâm Không Bỉ Ngạn"),
    ("修真", "Tu Chân"),
    ("修仙", "Tu Tiên"),
    ("系统", "Hệ Thống"),
    ("重生", "Trọng Sinh"),
    ("穿越", "Xuyên Không"),
    ("江湖", "Giang Hồ"),
    ("无敌", "Vô Địch"),
    ("神豪", "Thần Hào"),
    ("长生", "Trường Sinh"),
    ("星际", "Tinh Tế"),
];

/// Thay thế nhanh các cụm từ Hán-Việt phổ biến trong tiêu đề hoặc văn bản.
pub fn translate_hanviet_phrases(input: &str) -> String {
    let mut out = input.trim().to_string();
    for &(zh, vi) in PHRASE_DICT {
        if out.contains(zh) {
            out = out.replace(zh, vi);
        }
    }
    out
}

/// Bỏ dấu tiếng Việt và chuyển chữ thường để tìm kiếm cực nhanh không phân biệt dấu.
pub fn fold_vietnamese_ascii(input: &str) -> String {
    let mut out = String::with_capacity(input.len());
    for ch in input.chars() {
        let lower = ch.to_lowercase().next().unwrap_or(ch);
        let mapped = match lower {
            'à' | 'á' | 'ạ' | 'ả' | 'ã' | 'â' | 'ầ' | 'ấ' | 'ậ' | 'ẩ' | 'ẫ' | 'ă' | 'ằ' | 'ắ'
            | 'ặ' | 'ẳ' | 'ẵ' => 'a',
            'è' | 'é' | 'ẹ' | 'ẻ' | 'ẽ' | 'ê' | 'ề' | 'ế' | 'ệ' | 'ể' | 'ễ' => 'e',
            'ì' | 'í' | 'ị' | 'ỉ' | 'ĩ' => 'i',
            'ò' | 'ó' | 'ọ' | 'ỏ' | 'õ' | 'ô' | 'ồ' | 'ố' | 'ộ' | 'ổ' | 'ỗ' | 'ơ' | 'ờ' | 'ớ'
            | 'ợ' | 'ở' | 'ỡ' => 'o',
            'ù' | 'ú' | 'ụ' | 'ủ' | 'ũ' | 'ư' | 'ừ' | 'ứ' | 'ự' | 'ử' | 'ữ' => 'u',
            'ỳ' | 'ý' | 'ỵ' | 'ỷ' | 'ỹ' => 'y',
            'đ' => 'd',
            other => other,
        };
        out.push(mapped);
    }
    out
}

/// Rút gọn từ tiếng Anh chia thì về nguyên mẫu (`Lemma`) để tra từ điển & che từ trên Flashcard.
pub fn english_lemma(word: &str) -> String {
    let w = word
        .trim_matches(|c: char| !c.is_alphabetic())
        .to_ascii_lowercase();
    if w.len() <= 3 {
        return w;
    }
    match w.as_str() {
        "went" | "gone" | "going" | "goes" => return "go".into(),
        "took" | "taken" | "taking" => return "take".into(),
        "spoke" | "spoken" | "speaking" => return "speak".into(),
        "fought" | "fighting" => return "fight".into(),
        "knew" | "known" | "knowing" => return "know".into(),
        _ => {}
    }
    if let Some(stem) = w.strip_suffix("ies") {
        if stem.len() >= 2 {
            return format!("{}y", stem);
        }
    }
    if let Some(stem) = w.strip_suffix("ing") {
        if stem.len() >= 3 {
            return stem.to_string();
        }
    }
    if let Some(stem) = w.strip_suffix("ed") {
        if stem.len() >= 3 {
            return stem.to_string();
        }
    }
    if let Some(stem) = w.strip_suffix('s') {
        if !w.ends_with("ss") && stem.len() >= 3 {
            return stem.to_string();
        }
    }
    w
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn hanviet_and_folding_work_accurately() {
        assert_eq!(translate_hanviet_phrases("凡人修仙传"), "Phàm Nhân Tu Tiên");
        assert_eq!(
            fold_vietnamese_ascii("Phàm Nhân Tu Tiên Đạo"),
            "pham nhan tu tien dao"
        );
        assert_eq!(english_lemma("fought"), "fight");
        assert_eq!(english_lemma("cultivating"), "cultivat");
    }
}
