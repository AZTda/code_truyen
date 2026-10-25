use crate::app_state::TruyenCloudRustApp;
use egui::{Color32, RichText, Ui};

const VOICES: &[(&str, &str)] = &[
    ("NgocHuyen", "Ngọc Huyền (Bắc)"),
    ("MaiPhuong", "Mai Phương (Bắc)"),
    ("ThaoVy", "Thảo Vy (Nam)"),
    ("MinhQuang", "Minh Quang (Bắc)"),
    ("DucTri", "Đức Trí (Nam)"),
    ("Binh", "Thanh Bình (Bắc)"),
    ("Tuyen", "Phạm Tuyên (Trầm)"),
    ("Vinh", "Xuân Vĩnh (Nam)"),
];

pub fn render_detail_and_reader_tab(app: &mut TruyenCloudRustApp, ui: &mut Ui) {
    let Some(novel) = app.selected_novel.clone() else {
        ui.label("Hãy chọn một bộ truyện ở tab Khám phá.");
        return;
    };

    // Studio Bar: Xuất EPUB, Giọng đọc VieNeu-TTS, Thuật ngữ
    egui::Frame::group(ui.style()).show(ui, |ui| {
        ui.horizontal_wrapped(|ui| {
            ui.label(RichText::new(novel.display_title()).size(18.0).strong());
            ui.label(RichText::new(format!("by {}", novel.display_author())).color(Color32::GRAY));

            if ui.button("Xuất sách .EPUB").clicked() {
                app.export_current_epub();
            }

            if ui
                .button(if app.is_playing_audio {
                    "Tạm dừng Audiobook"
                } else {
                    "Phát Audiobook VieNeu-TTS"
                })
                .clicked()
            {
                app.is_playing_audio = !app.is_playing_audio;
            }

            egui::ComboBox::from_label("Giọng đọc")
                .selected_text(&app.selected_voice)
                .show_ui(ui, |ui| {
                    for &(id, label) in VOICES {
                        ui.selectable_value(&mut app.selected_voice, id.to_string(), label);
                    }
                });
        });

        ui.collapsing(
            format!("Bảng thuật ngữ truyện ({} mục)", app.glossary.len()),
            |ui| {
                ui.horizontal(|ui| {
                    ui.label("Chữ Hán:");
                    ui.text_edit_singleline(&mut app.new_term_zh);
                    ui.label("Nghĩa Việt:");
                    ui.text_edit_singleline(&mut app.new_term_vi);
                    if ui.button("Thêm").clicked()
                        && !app.new_term_zh.trim().is_empty()
                        && !app.new_term_vi.trim().is_empty()
                    {
                        app.glossary.insert(
                            app.new_term_zh.trim().to_string(),
                            app.new_term_vi.trim().to_string(),
                        );
                        app.new_term_zh.clear();
                        app.new_term_vi.clear();
                    }
                });
                for (zh, vi) in &app.glossary {
                    ui.label(format!("• {} → {}", zh, vi));
                }
            },
        );
    });

    ui.add_space(6.0);
    ui.horizontal(|ui| {
        ui.label(RichText::new(app.current_chapter.display_title()).size(16.0).strong());
        let total_pages = app.pages.len().max(1);
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            if ui.button("Trang sau ▶").clicked() && app.current_page_idx + 1 < total_pages {
                app.current_page_idx += 1;
            }
            ui.label(format!("Trang {} / {}", app.current_page_idx + 1, total_pages));
            if ui.button("◀ Trang trước").clicked() && app.current_page_idx > 0 {
                app.current_page_idx -= 1;
            }
        });
    });

    ui.separator();

    egui::ScrollArea::vertical().show(ui, |ui| {
        if let Some(page) = app.pages.get(app.current_page_idx) {
            for para in page {
                ui.label(RichText::new(para).size(16.0));
                ui.add_space(8.0);
            }
        }
        if let Some(en) = app.current_chapter.content_english.as_deref() {
            ui.separator();
            ui.label(
                RichText::new(format!(
                    "Bản chuyển thể Tiếng Anh (CEFR {}):",
                    app.current_chapter.english_level.as_deref().unwrap_or("B2")
                ))
                .color(Color32::from_rgb(56, 189, 248))
                .strong(),
            );
            ui.label(RichText::new(en).italics());
        }
    });
}
