/// Bộ làm sạch quảng cáo trang nguồn (`69shuba`, `uukanshu`, `piaotia`) và chia trang văn bản tốc độ cao.

const NOISE_PATTERNS: &[&str] = &[
    "69书吧",
    "www.69shuba",
    "uukanshu",
    "piaotia",
    "请记住本书首发域名",
    "最新章节！",
    "天才一秒记住",
    "手机版阅读网址",
    "本章未完，点击下一页继续阅读",
];

/// Tách nội dung chương thành danh sách đoạn văn sạch (loại bỏ dòng rác & khoảng trắng thừa).
pub fn clean_paragraphs(raw_text: &str) -> Vec<String> {
    raw_text
        .replace("\r\n", "\n")
        .replace("<br>", "\n")
        .replace("<br/>", "\n")
        .replace("<br />", "\n")
        .replace("&nbsp;", " ")
        .lines()
        .map(|line| line.trim().trim_start_matches('\u{3000}').trim())
        .filter(|line| !line.is_empty())
        .filter(|line| !NOISE_PATTERNS.iter().any(|pat| line.contains(pat)))
        .map(ToOwned::to_owned)
        .collect()
}

/// Chia chương truyện thành các trang hiển thị dựa theo giới hạn số ký tự và số dòng mỗi trang.
pub fn paginate_chapter(text: &str, max_chars_per_page: usize, max_lines_per_page: usize) -> Vec<Vec<String>> {
    let paragraphs = clean_paragraphs(text);
    if paragraphs.is_empty() {
        return vec![vec!["(Chương chưa có nội dung)".to_string()]];
    }

    let char_budget = max_chars_per_page.max(20);
    let line_budget = max_lines_per_page.max(2);
    let mut pages: Vec<Vec<String>> = Vec::new();
    let mut current_page: Vec<String> = Vec::new();
    let mut current_chars = 0usize;
    let mut current_lines = 0usize;

    for para in paragraphs {
        let para_chars = para.chars().count();
        let estimated_lines = (para_chars / 42).max(1) + 1;

        if !current_page.is_empty()
            && (current_chars + para_chars > char_budget || current_lines + estimated_lines > line_budget)
        {
            pages.push(std::mem::take(&mut current_page));
            current_chars = 0;
            current_lines = 0;
        }

        current_chars += para_chars;
        current_lines += estimated_lines;
        current_page.push(para);
    }

    if !current_page.is_empty() {
        pages.push(current_page);
    }

    pages
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cleans_noise_and_paginates_accurately() {
        let raw = "　　Chương 1: Khởi đầu\n请记住本书首发域名 www.69shuba.com\nĐoạn văn thứ nhất rất hay.\n<br/>Đoạn văn thứ hai tiếp nối.";
        let paras = clean_paragraphs(raw);
        assert_eq!(paras.len(), 3);
        assert_eq!(paras[0], "Chương 1: Khởi đầu");

        let pages = paginate_chapter(raw, 40, 2);
        assert!(pages.len() >= 2);
    }
}
