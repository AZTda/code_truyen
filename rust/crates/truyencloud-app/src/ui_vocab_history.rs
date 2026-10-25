use crate::app_state::TruyenCloudRustApp;
use egui::{Color32, RichText, Ui};
use truyencloud_core::fsrs::FsrsRating;

pub fn render_vocab_tab(app: &mut TruyenCloudRustApp, ui: &mut Ui) {
    ui.heading("Ôn Từ Vựng Theo Truyện (FSRS v4.5 Rust Engine)");
    ui.add_space(6.0);

    if app.vocab_deck.is_empty() {
        ui.label("Chưa có thẻ từ vựng nào trong bộ ôn tập.");
        return;
    }

    let idx = app.current_card_idx % app.vocab_deck.len();
    let card = app.vocab_deck[idx].clone();

    egui::Frame::group(ui.style()).show(ui, |ui| {
        ui.vertical_centered(|ui| {
            ui.label(
                RichText::new(format!(
                    "Thẻ {} / {} · Độ bền S = {:.2} ngày · Độ khó D = {:.2}",
                    idx + 1,
                    app.vocab_deck.len(),
                    card.stability,
                    card.difficulty
                ))
                .color(Color32::GRAY),
            );
            ui.add_space(10.0);
            ui.label(RichText::new(&card.word).size(26.0).strong());
            ui.label(RichText::new(&card.phonetic).color(Color32::from_rgb(56, 189, 248)));
            ui.add_space(8.0);
            ui.label(RichText::new(&card.example_sentence).italics());

            ui.add_space(12.0);
            if !app.card_flipped {
                if ui.button("Lật thẻ xem nghĩa & câu dịch").clicked() {
                    app.card_flipped = true;
                }
            } else {
                ui.separator();
                ui.label(
                    RichText::new(&card.meaning_vi)
                        .size(20.0)
                        .color(Color32::from_rgb(16, 185, 129))
                        .strong(),
                );
                ui.label(&card.example_translated);
                ui.add_space(10.0);
                ui.horizontal(|ui| {
                    if ui.button(FsrsRating::Again.label_vi()).clicked() {
                        app.grade_current_card(FsrsRating::Again);
                    }
                    if ui.button(FsrsRating::Hard.label_vi()).clicked() {
                        app.grade_current_card(FsrsRating::Hard);
                    }
                    if ui.button(FsrsRating::Good.label_vi()).clicked() {
                        app.grade_current_card(FsrsRating::Good);
                    }
                    if ui.button(FsrsRating::Easy.label_vi()).clicked() {
                        app.grade_current_card(FsrsRating::Easy);
                    }
                });
            }
        });
    });
}

pub fn render_history_tab(app: &mut TruyenCloudRustApp, ui: &mut Ui) {
    ui.horizontal(|ui| {
        ui.heading("Nhật Ký Lịch Sử Đọc & Nghe Audiobook");
        if !app.history.is_empty() && ui.button("Xoá lịch sử").clicked() {
            app.history.clear();
        }
    });
    ui.separator();

    if app.history.is_empty() {
        ui.label("Chưa có mục nào trong nhật ký.");
        return;
    }

    let entries = app.history.clone();
    for item in entries {
        egui::Frame::group(ui.style()).show(ui, |ui| {
            ui.horizontal(|ui| {
                let mode_badge = if item.is_audio { "AUDIO" } else { "ĐỌC" };
                ui.label(
                    RichText::new(mode_badge)
                        .color(if item.is_audio {
                            Color32::from_rgb(245, 158, 11)
                        } else {
                            Color32::from_rgb(56, 189, 248)
                        })
                        .strong(),
                );
                ui.label(RichText::new(&item.novel_title).strong());
                ui.label(format!("· {}", item.chapter_title));
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if ui.button("Mở lại chương").clicked() {
                        if let Some(novel) =
                            app.novels.iter().find(|n| n.id == item.novel_id).cloned()
                        {
                            app.open_novel(novel, item.chapter_number, item.is_audio);
                        }
                    }
                });
            });
        });
        ui.add_space(4.0);
    }
}
