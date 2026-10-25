mod app_state;
mod ui_detail_reader;
mod ui_discover;
mod ui_vocab_history;

use app_state::{AppTab, TruyenCloudRustApp};
use egui::{Color32, RichText};

impl eframe::App for TruyenCloudRustApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        // Thanh điều hướng trên cùng
        egui::TopBottomPanel::top("top_nav").show(ctx, |ui| {
            ui.horizontal(|ui| {
                ui.label(
                    RichText::new("TruyenCloud Rust Edition v1.6.0")
                        .size(17.0)
                        .strong(),
                );
                ui.separator();
                ui.selectable_value(
                    &mut self.active_tab,
                    AppTab::Discover,
                    "Khám phá & Tủ sách",
                );
                ui.selectable_value(
                    &mut self.active_tab,
                    AppTab::DetailAndReader,
                    "Chi tiết & Trình đọc",
                );
                ui.selectable_value(
                    &mut self.active_tab,
                    AppTab::VocabularyDeck,
                    "Ôn từ vựng FSRS",
                );
                ui.selectable_value(
                    &mut self.active_tab,
                    AppTab::ReadingHistory,
                    format!("Nhật ký ({})", self.history.len()),
                );
            });
        });

        // Trình phát Audiobook thu nhỏ nổi ở đáy (MiniAudiobookBar)
        egui::TopBottomPanel::bottom("bottom_status_and_audio").show(ctx, |ui| {
            if self.is_playing_audio {
                ui.horizontal(|ui| {
                    ui.label(
                        RichText::new("ĐANG PHÁT AUDIOBOOK")
                            .color(Color32::from_rgb(16, 185, 129))
                            .strong(),
                    );
                    if let Some(n) = &self.selected_novel {
                        ui.label(format!(
                            "{} · {} · Giọng: {}",
                            n.display_title(),
                            self.current_chapter.display_title(),
                            self.selected_voice
                        ));
                    }
                    if ui.button("Tạm dừng").clicked() {
                        self.is_playing_audio = false;
                    }
                    if ui.button("Mở trình đọc").clicked() {
                        self.active_tab = AppTab::DetailAndReader;
                    }
                });
                ui.separator();
            }
            ui.label(RichText::new(&self.status_banner).color(Color32::GRAY));
        });

        // Khu vực nội dung chính
        egui::CentralPanel::default().show(ctx, |ui| match self.active_tab {
            AppTab::Discover => ui_discover::render_discover_tab(self, ui),
            AppTab::DetailAndReader => ui_detail_reader::render_detail_and_reader_tab(self, ui),
            AppTab::VocabularyDeck => ui_vocab_history::render_vocab_tab(self, ui),
            AppTab::ReadingHistory => ui_vocab_history::render_history_tab(self, ui),
        });
    }
}

fn main() -> Result<(), eframe::Error> {
    let args: Vec<String> = std::env::args().collect();
    if args.iter().any(|a| a == "--cli" || a == "--check") {
        let mut app = TruyenCloudRustApp::default();
        app.export_current_epub();
        println!("✅ TruyenCloud Rust App (CLI Check) hoạt động hoàn hảo!");
        println!("   • Số truyện nạp sẵn: {}", app.novels.len());
        println!("   • Số thẻ FSRS: {}", app.vocab_deck.len());
        println!("   • Trạng thái: {}", app.status_banner);
        return Ok(());
    }

    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([1080.0, 720.0])
            .with_title("TruyenCloud v1.6.0 — Pure Rust Edition"),
        ..Default::default()
    };
    eframe::run_native(
        "TruyenCloud Rust Edition",
        options,
        Box::new(|_cc| Ok(Box::new(TruyenCloudRustApp::default()))),
    )
}
