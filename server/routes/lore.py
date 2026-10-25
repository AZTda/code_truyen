from typing import Optional
from fastapi import APIRouter, Depends
from pydantic import BaseModel, Field
from services.translation_worker import translation_worker
from services.auth_token import current_user

router = APIRouter(prefix="/lore", tags=["Lore & Glossary"])

class LoreExplainRequest(BaseModel):
    term: str = Field(min_length=1, max_length=200)
    context: Optional[str] = Field(default=None, max_length=8_000)

@router.post("/explain")
async def explain_lore(req: LoreExplainRequest, auth: dict = Depends(current_user)):
    """Giải nghĩa từ ngữ Hán Việt, điển tích, bối cảnh tu tiên hoặc danh từ riêng bằng Antigravity AI."""
    return await translation_worker.explain_lore_term(term=req.term, context=req.context)
