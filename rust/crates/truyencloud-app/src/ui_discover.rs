use crate::app_state::{QuickFilter, TruyenCloudRustApp};
use egui::{Color32, RichText, Ui};

pub fn render_discover_tab(app: &mut TruyenCloudRustApp, ui: &mut Ui) {
    ui.horizontal(|ui| {
        ui.label(RichText::new("Máy chủ:").strong());
        ui.text_edit_singleline(&mut app.server_url);
        if ui.button("Đồng bộ kho 366+ truyện").clicked() {
            app.fetch_discover_from_server();
        }
    });

    ui.add_space(6.0);
    ui.horizontal(|ui| {
        ui.label("Tìm nhanh:");
        ui.text_edit_singleline(&mut app.search_query);
        if !app.search_query.is_empty() && ui.button("Xoá").clicked() {
            app.search_query.clear();
        }
    });

    ui.add_space(4.0);
    let total = app.novels.len();
    let translated_cnt = app.novels.iter().filter(|n| n.translated_chapters > 0).count();
    let overnight_cnt = app.novels.iter().filter(|n| n.overnight_enabled).count();
    let completed_cnt = app
        .novels
        .iter()
        .filter(|n| n.status.eq_ignore_ascii_case("completed"))
        .count();

    ui.horizontal_wrapped(|ui| {
        ui.selectable_value(
            &mut app.quick_filter,
            QuickFilter::All,
            format!("Tất cả ({})", total),
        );
        ui.selectable_value(
            &mut app.quick_filter,
            QuickFilter::Translated,
            format!("Đã có bản dịch ({})", translated_cnt),
        );
        ui.selectable_value(
            &mut app.quick_filter,
            QuickFilter::Overnight,
            format!("Đang dịch ngầm ({})", overnight_cnt),
        );
        ui.selectable_value(
            &mut app.quick_filter,
            QuickFilter::Completed,
            format!("Hoàn thành ({})", completed_cnt),
        );
    });

    ui.separator();

    let filtered = app.filtered_novels();
    ui.label(
        RichText::new(format!(
            "Hiển thị {} / {} bộ truyện",
            filtered.len(),
            app.novels.len()
        ))
        .italics(),
    );

    egui::ScrollArea::vertical().show(ui, |ui| {
        for novel in filtered {
            egui::Frame::group(ui.style()).show(ui, |ui| {
                ui.horizontal(|ui| {
                    ui.vertical(|ui| {
                        ui.label(RichText::new(novel.display_title()).size(16.0).strong());
                        ui.label(
                            RichText::new(format!(
                                "{} · Nguồn: {} · {} chương",
                                novel.display_author(),
                                if novel.source_id.is_empty() {
                                    "69shuba"
                                } else {
                                    &novel.source_id
                                },
                                novel.total_chapters
                            ))
                            .color(Color32::GRAY),
                        );
                        if !novel.description.is_empty() {
                            let desc: String = novel.description.chars().take(120).collect();
                            ui.label(desc);
                        }
                    });

                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        if ui.button("Nghe Audio").clicked() {
                            app.open_novel(novel.clone(), 1, true);
                        }
                        if ui.button("Đọc ngay").clicked() {
                            app.open_novel(novel.clone(), 1, false);
                        }
                        if novel.overnight_enabled || novel.translated_chapters > 0 {
                            let badge = if novel.overnight_enabled {
                                format!("Đêm · {}c dịch", novel.translated_chapters)
                            } else {
                                format!("{}c dịch", novel.translated_chapters)
                            };
                            ui.label(
                                RichText::new(badge)
                                    .color(Color32::from_rgb(16, 185, 129))
                                    .strong(),
                            );
                        }
                    });
                });
            });
            ui.add_space(4.0);
        }
    });
}
