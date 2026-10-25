from typing import List, Optional
from fastapi import APIRouter, Depends, UploadFile, File, Form, HTTPException
from pydantic import BaseModel, Field
from services.import_service import import_service
from services.auto_updater import auto_updater
from services.storage_service import storage_service
from services.auth_token import current_user

# Tieu thuyet .txt/.epub thuc te hiem khi vuot muc nay
MAX_IMPORT_BYTES = 25 * 1024 * 1024
MAX_TITLE_CHARS = 300
MAX_AUTHOR_CHARS = 200

router = APIRouter(tags=["Novel Import & Automation"])

@router.post("/novels/import")
async def import_novel_file(
    file: UploadFile = File(...),
    title: Optional[str] = Form(None),
    author: Optional[str] = Form(None),
    auth: dict = Depends(current_user),
):
    """Nạp tệp tiểu thuyết cá nhân (.txt hoặc .epub), tự động nhận diện chương và lưu vào thư viện."""
    if not file.filename:
        raise HTTPException(status_code=400, detail="Không nhận diện được tên tệp.")

    ext = file.filename.rsplit(".", 1)[-1].lower()
    if ext not in ["txt", "epub"]:
        raise HTTPException(status_code=400, detail="Hệ thống chỉ hỗ trợ định dạng .txt hoặc .epub")
    if title and len(title.strip()) > MAX_TITLE_CHARS:
        raise HTTPException(status_code=422, detail=f"Tên tác phẩm không được dài quá {MAX_TITLE_CHARS} ký tự.")
    if author and len(author.strip()) > MAX_AUTHOR_CHARS:
        raise HTTPException(status_code=422, detail=f"Tên tác giả không được dài quá {MAX_AUTHOR_CHARS} ký tự.")

    try:
        # Doc theo khoi va chan o tran. `await file.read()` truoc day doc toan bo
        # vao RAM khong gioi han -> mot tep vai GB la du lam can kiet bo nho.
        chunks = []
        total = 0
        while True:
            chunk = await file.read(1024 * 256)
            if not chunk:
                break
            total += len(chunk)
            if total > MAX_IMPORT_BYTES:
                raise HTTPException(
                    status_code=413,
                    detail=f"Tệp vượt quá giới hạn {MAX_IMPORT_BYTES // (1024 * 1024)} MB."
                )
            chunks.append(chunk)
        content = b"".join(chunks)

        result = await import_service.import_novel(
            file_bytes=content,
            filename=file.filename,
            custom_title=title,
            custom_author=author,
        )
        return result
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Lỗi nạp tệp tiểu thuyết: {str(e)}")

@router.post("/novels/{novel_id}/check-updates")
async def check_novel_updates(novel_id: str, auth: dict = Depends(current_user)):
    """Kiểm tra chương mới tức thời từ trang web nguồn và tự động dịch trước chương mới nhất."""
    res = await auto_updater.check_novel_for_updates(novel_id, auto_pretranslate=True)
    return res

@router.get("/novels/auto-updates/status")
async def get_auto_updater_status():
    """Xem trạng thái của tiến trình tự động quét chương mới trên máy chủ."""
    return auto_updater.get_status()


# ---------------------------------------------------------------------------
# Bao chuong moi
#
# Ung dung truoc day khong co cach nao biet truyen dang doc co chuong moi tru khi
# nguoi dung TU mo tung truyen ra va bam kiem tra. Endpoint duoi day tra loi cho
# CA TU SACH trong mot lan goi, doc tu du lieu may chu da co san (auto_updater
# quet ngam va cap nhat), nen no nhanh va khong cao lai trang nguon.
# ---------------------------------------------------------------------------

class ShelfNovelState(BaseModel):
    novel_id: str
    # So chuong ung dung dang biet. Khac voi tren may chu nghia la co chuong moi.
    known_chapters: int = 0


class CheckUpdatesBatchRequest(BaseModel):
    novels: List[ShelfNovelState] = Field(default_factory=list)


class NovelUpdateInfo(BaseModel):
    novel_id: str
    title: str = ""
    title_vi: str = ""
    total_chapters: int = 0
    new_chapters: int = 0


class CheckUpdatesBatchResponse(BaseModel):
    updates: List[NovelUpdateInfo] = Field(default_factory=list)
    checked: int = 0


# Tu sach lon den may cung khong nen bat may chu doc hang nghin ban ghi mot lan goi.
MAX_NOVELS_PER_CHECK = 100


@router.post("/novels/updates/check-batch", response_model=CheckUpdatesBatchResponse)
async def check_updates_batch(
    req: CheckUpdatesBatchRequest,
    auth: dict = Depends(current_user),
):
    """Kiểm tra chương mới cho cả tủ sách trong một lần gọi.

    Chỉ so với dữ liệu máy chủ đã có sẵn — KHÔNG cào lại trang nguồn. Việc cào do
    `auto_updater` làm ngầm theo chu kỳ; nếu endpoint này tự cào thì mỗi lần ứng
    dụng thức dậy sẽ kéo theo hàng chục lượt tải về phía trang nguồn.
    """
    if len(req.novels) > MAX_NOVELS_PER_CHECK:
        raise HTTPException(
            status_code=400,
            detail=f"Mỗi lần chỉ kiểm tra tối đa {MAX_NOVELS_PER_CHECK} truyện.",
        )

    updates: List[NovelUpdateInfo] = []
    for item in req.novels:
        if not item.novel_id:
            continue
        try:
            info = storage_service.get_novel_info(item.novel_id)
        except Exception:
            info = None
        if not info:
            continue

        total = int(info.get("total_chapters") or 0)
        if total <= 0:
            # Khong co so chuong dang tin thi im lang con hon bao nham.
            continue

        new_count = total - max(0, item.known_chapters)
        if new_count > 0:
            updates.append(
                NovelUpdateInfo(
                    novel_id=item.novel_id,
                    title=info.get("title") or "",
                    title_vi=info.get("title_vi") or "",
                    total_chapters=total,
                    new_chapters=new_count,
                )
            )

    return CheckUpdatesBatchResponse(updates=updates, checked=len(req.novels))
