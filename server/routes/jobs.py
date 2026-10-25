from fastapi import APIRouter, Depends, HTTPException

from services.auth_token import current_user, user_id_of
from services.job_registry import job_registry

router = APIRouter(prefix="/jobs", tags=["Background Jobs"])


@router.get("/{job_id}")
async def get_job_status(job_id: str, auth: dict = Depends(current_user)):
    result = job_registry.get_for_owner(job_id, user_id_of(auth))
    if result is None:
        raise HTTPException(
            status_code=404,
            detail="Không tìm thấy tác vụ hoặc bạn không có quyền xem tác vụ này.",
        )
    return result
