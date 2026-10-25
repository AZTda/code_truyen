use crate::models::{Chapter, Novel};
use crate::text_paging::clean_paragraphs;
use std::io::{Cursor, Write};
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, ZipWriter};

/// Đóng gói một bộ truyện và danh sách chương đã dịch thành tệp `.EPUB` hoàn chỉnh ngay trong bộ nhớ RAM.
pub fn build_epub_bytes(novel: &Novel, chapters: &[Chapter]) -> Result<Vec<u8>, String> {
    let buf = Cursor::new(Vec::new());
    let mut zip = ZipWriter::new(buf);

    let stored = SimpleFileOptions::default().compression_method(CompressionMethod::Stored);
    let deflated = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);

    // 1. Tệp mimetype bắt buộc đứng đầu tiên và không nén theo chuẩn EPUB
    zip.start_file("mimetype", stored).map_err(|e| e.to_string())?;
    zip.write_all(b"application/epub+zip")
        .map_err(|e| e.to_string())?;

    // 2. META-INF/container.xml
    zip.start_file("META-INF/container.xml", deflated)
        .map_err(|e| e.to_string())?;
    zip.write_all(
        br#"<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"#,
    )
    .map_err(|e| e.to_string())?;

    // 3. OEBPS/style.css
    zip.start_file("OEBPS/style.css", deflated)
        .map_err(|e| e.to_string())?;
    zip.write_all(
        br#"body { font-family: Georgia, "Noto Serif", serif; line-height: 1.75; margin: 1.2em; color: #1c1917; }
h1, h2 { font-weight: 700; color: #0f172a; margin-bottom: 0.8em; }
p { text-indent: 1.4em; margin: 0.55em 0; text-align: justify; }"#,
    )
    .map_err(|e| e.to_string())?;

    // 4. Các tệp chương XHTML
    let mut manifest_items = String::new();
    let mut spine_items = String::new();
    let mut nav_points = String::new();

    for (idx, ch) in chapters.iter().enumerate() {
        let item_id = format!("chap_{}", idx + 1);
        let file_name = format!("chapter_{}.xhtml", idx + 1);
        let title_escaped = xml_escape(&ch.display_title());
        let paras = clean_paragraphs(ch.best_body());

        let mut body_html = String::new();
        for p in paras {
            body_html.push_str("<p>");
            body_html.push_str(&xml_escape(&p));
            body_html.push_str("</p>\n");
        }

        let xhtml = format!(
            r#"<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="vi">
<head>
  <meta charset="UTF-8"/>
  <title>{}</title>
  <link rel="stylesheet" type="text/css" href="style.css"/>
</head>
<body>
  <h2>{}</h2>
  {}
</body>
</html>"#,
            title_escaped, title_escaped, body_html
        );

        zip.start_file(format!("OEBPS/{}", file_name), deflated)
            .map_err(|e| e.to_string())?;
        zip.write_all(xhtml.as_bytes()).map_err(|e| e.to_string())?;

        manifest_items.push_str(&format!(
            r#"    <item id="{}" href="{}" media-type="application/xhtml+xml"/>"#,
            item_id, file_name
        ));
        manifest_items.push('\n');

        spine_items.push_str(&format!(r#"    <itemref idref="{}"/>"#, item_id));
        spine_items.push('\n');

        nav_points.push_str(&format!(
            r#"    <navPoint id="nav_{}" playOrder="{}">
      <navLabel><text>{}</text></navLabel>
      <content src="{}"/>
    </navPoint>"#,
            idx + 1,
            idx + 1,
            title_escaped,
            file_name
        ));
        nav_points.push('\n');
    }

    // 5. OEBPS/toc.ncx
    let book_title = xml_escape(novel.display_title());
    let book_author = xml_escape(novel.display_author());
    let ncx = format!(
        r#"<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head><meta name="dtb:uid" content="truyencloud-{}"/></head>
  <docTitle><text>{}</text></docTitle>
  <navMap>
{}  </navMap>
</ncx>"#,
        xml_escape(&novel.id),
        book_title,
        nav_points
    );
    zip.start_file("OEBPS/toc.ncx", deflated)
        .map_err(|e| e.to_string())?;
    zip.write_all(ncx.as_bytes()).map_err(|e| e.to_string())?;

    // 6. OEBPS/content.opf
    let opf = format!(
        r#"<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" unique-identifier="BookId" version="2.0">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:title>{}</dc:title>
    <dc:creator>{}</dc:creator>
    <dc:language>vi</dc:language>
    <dc:identifier id="BookId">truyencloud-{}</dc:identifier>
  </metadata>
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="css" href="style.css" media-type="text/css"/>
{}  </manifest>
  <spine toc="ncx">
{}  </spine>
</package>"#,
        book_title,
        book_author,
        xml_escape(&novel.id),
        manifest_items,
        spine_items
    );
    zip.start_file("OEBPS/content.opf", deflated)
        .map_err(|e| e.to_string())?;
    zip.write_all(opf.as_bytes()).map_err(|e| e.to_string())?;

    let cursor = zip.finish().map_err(|e| e.to_string())?;
    Ok(cursor.into_inner())
}

fn xml_escape(input: &str) -> String {
    input
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&apos;")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generates_valid_epub_archive_with_mimetype_header() {
        let novel = Novel {
            id: "n1".into(),
            title: "仙逆".into(),
            title_vi: Some("Tiên Nghịch".into()),
            author: "Nhĩ Căn".into(),
            ..Default::default()
        };
        let chapters = vec![Chapter {
            novel_id: "n1".into(),
            chapter_number: 1,
            title_vi: Some("Chương 1: Ly biệt".into()),
            content_translated: Some("Vương Lâm bước đi trong sương sớm.\nThiết Trụ ngoái nhìn.".into()),
            is_translated: true,
            ..Default::default()
        }];

        let bytes = build_epub_bytes(&novel, &chapters).expect("epub bytes");
        assert!(bytes.len() > 200);
        // Kiểm tra chữ ký ZIP PK\x03\x04 và tệp mimetype đứng đầu
        assert_eq!(&bytes[0..4], b"PK\x03\x04");
        assert!(String::from_utf8_lossy(&bytes[..80]).contains("mimetypeapplication/epub+zip"));
    }
}
