import re
import logging
import asyncio
from typing import List, Optional, Dict
from config import settings
from models.translate import TermItem
from services.model_catalog import model_catalog_service
from services.agy_effort import effort_for_model
from services.translation_prompts import (
    TRANSLATION_SYSTEM_PROMPT,
    get_english_prompt,
    get_bilingual_prompt,
)
from services.agy_runner import AgyRunnerMixin, TaskPriority, _reap_process
from services.task_coalescer import task_coalescer
from services.title_translation_service import TitleTranslationMixin
from services.reading_intelligence_service import ReadingIntelligenceMixin
from services.english_adaptation_service import EnglishAdaptationMixin
from services.vocabulary_ai_service import VocabularyAiMixin
from services.mnemonic_story_service import MnemonicStoryMixin
from services.known_titles import KNOWN_NOVEL_TITLES, KNOWN_AUTHORS
from services import translation_cache_store
from services.translation_validation import (
    ChapterTranslation,
    ENGINE_AI,
    ENGINE_FALLBACK,
    validate_ai_translation,
)

logger = logging.getLogger("truyencloud.translation")


class AgyTimeoutError(RuntimeError):
    """Antigravity quá thời gian chờ — thử lại ngay chỉ nhân đôi thời gian chờ của độc giả."""

class TranslationWorker(
    AgyRunnerMixin,
    TitleTranslationMixin,
    ReadingIntelligenceMixin,
    EnglishAdaptationMixin,
    VocabularyAiMixin,
    MnemonicStoryMixin
):
    def __init__(self):
        self._last_agy_check = 0.0
        self._cached_agy_ready = False
        self._title_cache: Dict[str, str] = dict(KNOWN_NOVEL_TITLES)
        self._author_cache: Dict[str, str] = dict(KNOWN_AUTHORS)

    @staticmethod
    def _effective_vi_prompt(custom_prompt: Optional[str], target_language: Optional[str]) -> Optional[str]:
        if custom_prompt and custom_prompt.strip():
            return custom_prompt.strip()
        if (target_language or "vi").lower().strip() not in ("vi", "vietnamese", "tiếng việt"):
            return custom_prompt
        try:
            from routes.settings import DEFAULT_PROFILE, load_profile
            prof = load_profile() or {}
            parts = []
            if prof.get("genre") and prof["genre"] != DEFAULT_PROFILE.get("genre"):
                parts.append(f"Thể loại: {prof['genre']}.")
            if prof.get("sino_vietnamese_ratio") and prof["sino_vietnamese_ratio"] != DEFAULT_PROFILE.get("sino_vietnamese_ratio"):
                parts.append(f"Tỷ lệ Hán-Việt: {prof['sino_vietnamese_ratio']}.")
            if prof.get("custom_prompt") and prof["custom_prompt"] != DEFAULT_PROFILE.get("custom_prompt"):
                parts.append(prof["custom_prompt"].strip())
            return " ".join(parts).strip() or None
        except Exception:
            return None

    async def _translate_via_agy_cli(
        self,
        content: str,
        glossary: Optional[List[TermItem]] = None,
        custom_prompt: Optional[str] = None,
        target_language: Optional[str] = "vi",
        english_level: Optional[str] = "c1",
        priority: TaskPriority = TaskPriority.READER,
        model_override: Optional[str] = None,
    ) -> str:
        """Sends the ENTIRE chapter into Antigravity CLI in one shot using assigned priority lane."""
        agy = self._get_agy_path()
        if not agy:
            raise RuntimeError("Không tìm thấy Antigravity CLI (agy). Vui lòng cài đặt CLI.")

        target_lang = (target_language or "vi").lower().strip()
        lvl = (english_level or "c1").lower().strip()
        if target_lang in ["en", "english", "tiếng anh"]:
            base_prompt = get_english_prompt(lvl)
            lang_label = f"[NỘI DUNG CHƯƠNG CẦN DỊCH SANG TIẾNG ANH (CEFR {lvl.upper()}) - DO NOT TRANSLATE TO VIETNAMESE, TRANSLATE 100% TO ENGLISH]:"
        elif target_lang in ["bilingual", "song ngữ", "song ngu"]:
            base_prompt = get_bilingual_prompt(lvl)
            lang_label = f"[NỘI DUNG CHƯƠNG CẦN DỊCH SONG NGỮ ANH (CEFR {lvl.upper()}) - VIỆT]:"
        else:
            base_prompt = TRANSLATION_SYSTEM_PROMPT
            lang_label = "[NỘI DUNG NGUYÊN CHƯƠNG TIẾNG TRUNG CẦN DỊCH SANG TIẾNG VIỆT]:"

        glossary_inst = ""
        if glossary:
            glossary_inst = "\n\nBẢNG THUẬT NGỮ / GLOSSARY:\n" + "\n".join(f"- {g.source} → {g.target}" for g in glossary)

        custom_prompt = self._effective_vi_prompt(custom_prompt, target_lang)
        custom_inst = ""
        if custom_prompt and custom_prompt.strip():
            custom_inst = (
                f"\n\n[YÊU CẦU ĐẶC BIỆT TỪ ĐỘC GIẢ / CUSTOM INSTRUCTIONS]:\n"
                f"{custom_prompt.strip()}\n"
                f"(Hãy tuân thủ triệt để yêu cầu này về phong cách, giọng điệu và xưng hô khi dịch).\n"
            )

        prompt = (
            f"{base_prompt}{glossary_inst}{custom_inst}\n\n"
            f"{lang_label}\n\n"
            f"{content}"
        )

        active_model = (model_override or "").strip() or model_catalog_service.get_active_model()
        active_effort = effort_for_model(active_model, model_catalog_service.get_active_effort())
        base_timeout = model_catalog_service.get_active_timeout() or settings.TRANSLATE_TIMEOUT_SECONDS
        active_timeout = max(base_timeout, 180) if model_override else base_timeout

        # 1. Thử dịch nhanh qua Warm Worker Pool (< 5s) cho độc giả đọc trực tiếp
        if priority == TaskPriority.READER:
            try:
                from services.agy_pool import agy_pool
                warm_res = await agy_pool.execute(
                    prompt=prompt,
                    timeout=min(active_timeout or settings.TRANSLATE_TIMEOUT_SECONDS, 60.0),
                    model=active_model or "gemini-3.8-flash-medium",
                    effort=active_effort or "medium"
                )
                if warm_res:
                    result_text = re.sub(
                        r"^(Dưới đây là bản dịch.*?:\s*\n*|Bản dịch của bạn.*?:\s*\n*|Here is the translation.*?:\s*\n*)",
                        "",
                        warm_res.strip(),
                        flags=re.IGNORECASE
                    )
                    logger.info(f"Dịch nguyên chương thành công qua Warm Antigravity Pool! ({len(result_text.split())} từ).")
                    return result_text
            except Exception as e:
                logger.debug(f"Warm translation pool bypassed: {e}")

        cmd = [
            agy, "-p", prompt, "--model", active_model, "--output-format", "text",
            "--effort", active_effort, "--disable-slash-commands", "--dangerously-skip-permissions",
        ]
        logger.info(
            f"Đang gửi nguyên 1 chương ({len(content)} ký tự) vào Antigravity CLI "
            f"(Target: {target_lang}, Level: {lvl.upper()}, Model: {active_model}, Effort: {active_effort}, Lane: {priority.value})..."
        )
        sem = self._get_semaphore_for_priority(priority)
        proc = None
        async with sem:
            try:
                proc = await asyncio.create_subprocess_exec(
                    *cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE
                )
                try:
                    stdout, stderr = await asyncio.wait_for(
                        proc.communicate(),
                        timeout=active_timeout or settings.TRANSLATE_TIMEOUT_SECONDS,
                    )
                except asyncio.TimeoutError:
                    if proc:
                        await _reap_process(proc)
                    raise AgyTimeoutError(
                        "Antigravity CLI quá thời gian chờ (%ss) [Lane: %s, Model: %s]."
                        % (active_timeout or settings.TRANSLATE_TIMEOUT_SECONDS, priority.value, active_model)
                    )

                if proc.returncode != 0:
                    err_msg = stderr.decode("utf-8") if stderr else "Unknown error"
                    logger.error(f"Antigravity CLI ({active_model}) thất bại (code {proc.returncode}): {err_msg}")
                    raise RuntimeError(f"Lỗi Antigravity CLI (code {proc.returncode}): {err_msg}")

                result_text = stdout.decode("utf-8").strip()
                if not result_text:
                    raise RuntimeError("Antigravity CLI trả về nội dung rỗng.")

                result_text = re.sub(
                    r"^(Dưới đây là bản dịch.*?:\s*\n*|Bản dịch của bạn.*?:\s*\n*|Here is the translation.*?:\s*\n*)",
                    "", result_text, flags=re.IGNORECASE,
                )
                logger.info(f"Dịch nguyên chương thành công qua {active_model}! ({len(result_text.split())} từ).")
                return result_text
            finally:
                if proc:
                    await _reap_process(proc)

    def _compute_translation_cache_key(
        self, content: str, target_language: Optional[str], english_level: Optional[str],
        custom_prompt: Optional[str], glossary: Optional[List[TermItem]],
    ) -> str:
        return translation_cache_store.compute_key(content, target_language, english_level, custom_prompt, glossary)

    async def _attempt_ai(
        self, source: str, processed: str, glossary, custom_prompt, target_language,
        english_level, priority: TaskPriority, max_attempts: int,
        model_override: Optional[str] = None,
    ) -> Optional[str]:
        """Gọi AI tối đa max_attempts lần; chỉ trả bản dịch đã qua kiểm tra chất lượng."""
        if not self.is_agy_ready():
            if priority == TaskPriority.BACKGROUND and (target_language or "vi") == "vi":
                try:
                    from services.local_chapter_mt import qwen_healthy, translate_chapter_offline
                    if await qwen_healthy():
                        logger.info("Antigravity CLI chưa sẵn sàng — dùng Qwen3-4B Offline dự phòng...")
                        local_res = await translate_chapter_offline(processed, glossary)
                        if local_res and validate_ai_translation(source, local_res, "vi") is None:
                            return local_res
                except Exception as e:
                    logger.warning(f"Mô hình Offline Qwen3-4B-Q4_K_M.gguf dịch lỗi: {e}")
            logger.warning("Antigravity CLI chưa sẵn sàng, bỏ qua lượt dịch AI.")
            return None
        call_kwargs = {"priority": priority}
        if model_override:
            call_kwargs["model_override"] = model_override
        saw_refusal = False
        for attempt in range(1, max_attempts + 1):
            try:
                res = await self._translate_via_agy_cli(
                    processed, glossary, custom_prompt, target_language, english_level,
                    **call_kwargs,
                )
            except AgyTimeoutError as e:
                logger.warning(f"Antigravity quá thời gian chờ, không thử lại: {e}")
                return None
            except Exception as e:
                logger.warning(f"Antigravity CLI dịch lỗi (lượt {attempt}/{max_attempts}): {e}")
                continue
            reason = validate_ai_translation(source, res, target_language or "vi")
            if reason is None:
                return res
            logger.warning(f"Bản dịch AI bị loại ({reason}) ở lượt {attempt}/{max_attempts}.")
            if reason == "refusal":
                saw_refusal = True
                if len(source) >= 600:
                    break
        if saw_refusal and len(source) >= 600:
            try:
                from services.local_chapter_mt import translate_with_refusal_split
                split_res = await translate_with_refusal_split(
                    source=source,
                    processed=processed,
                    ai_translate_fn=lambda part: self._translate_via_agy_cli(
                        part, glossary, custom_prompt, target_language, english_level,
                        **call_kwargs,
                    ),
                    target_language=target_language or "vi",
                    glossary=glossary,
                    allow_offline_leaf=(priority == TaskPriority.BACKGROUND and (target_language or "vi") == "vi"),
                )
                if split_res:
                    return split_res
            except Exception as e:
                logger.warning(f"Dịch tách đoạn sau refusal lỗi: {e}")
        return None

    async def translate_chapter_detailed(
        self,
        content: str,
        glossary: Optional[List[TermItem]] = None,
        custom_prompt: Optional[str] = None,
        target_language: Optional[str] = "vi",
        english_level: Optional[str] = "c1",
        priority: TaskPriority = TaskPriority.READER,
        force: bool = False,
        allow_fallback: bool = True,
        max_attempts: int = 2,
        model_override: Optional[str] = None,
    ) -> Optional[ChapterTranslation]:
        """Dịch nguyên chương: cache -> AI (thử lại 1 lần nếu hỏng/cụt) -> dự phòng Google.

        force=True bỏ qua (và ghi đè) translation_cache. allow_fallback=False trả None
        khi AI thất bại, để caller giữ bản đã có thay vì nhận bản Google.
        """
        if not content or not content.strip():
            return ChapterTranslation(text="")
        if not custom_prompt and (target_language or "vi").lower().strip() == "vi":
            custom_prompt = self._effective_vi_prompt(custom_prompt, target_language)

        # Chỉ giữ thuật ngữ THỰC SỰ có trong chương: prompt gọn hơn, và khoá cache không đổi
        # mỗi khi auto_glossary thêm một tên riêng của chương khác.
        glossary = [g for g in (glossary or []) if g.source and g.source in content]

        cache_key = self._compute_translation_cache_key(
            content, target_language, english_level, custom_prompt, glossary
        )
        if not force:
            cached = translation_cache_store.read(cache_key, content, target_language)
            if cached:
                logger.info(f"Khôi phục bản dịch từ translation_cache [{cache_key}]!")
                return ChapterTranslation(text=cached, from_cache=True)

        processed = self._apply_glossary_to_text(content, glossary)

        async def _do_trans() -> Optional[ChapterTranslation]:
            res = await self._attempt_ai(
                content, processed, glossary, custom_prompt, target_language,
                english_level, priority, max_attempts, model_override=model_override,
            )
            if res:
                translation_cache_store.write(cache_key, res, target_language, english_level)
                return ChapterTranslation(text=res, engine=ENGINE_AI)
            if not allow_fallback:
                return None
            from services.fast_translator import fast_translator
            fb = await fast_translator.translate_chapter_text(processed, tl=target_language or "vi")
            if not fb:
                raise RuntimeError("AI và dịch dự phòng Google đều thất bại.")
            # KHÔNG ghi vào translation_cache: lần sau AI hồi phục phải được dịch lại.
            return ChapterTranslation(text=fb, engine=ENGINE_FALLBACK, is_fallback=True)

        # Làn nền và làn đọc KHÔNG gộp chung: độc giả mở chương mà bản dịch trước của nó
        # còn xếp hàng sau việc nền thì phải chạy ở làn đọc, không chờ hết hàng nền.
        mode = "fb" if allow_fallback else "ai"
        lane = "bg" if priority == TaskPriority.BACKGROUND else "rd"
        return await task_coalescer.run_coalesced(f"{cache_key}:{mode}:{lane}", _do_trans)

    async def translate_chapter(
        self,
        content: str,
        glossary: Optional[List[TermItem]] = None,
        custom_prompt: Optional[str] = None,
        target_language: Optional[str] = "vi",
        english_level: Optional[str] = "c1",
        priority: TaskPriority = TaskPriority.READER,
        force: bool = False,
    ) -> str:
        """Tương thích ngược: chỉ trả văn bản. Caller mới dùng translate_chapter_detailed."""
        result = await self.translate_chapter_detailed(
            content, glossary, custom_prompt, target_language, english_level,
            priority=priority, force=force,
        )
        return result.text if result else ""


translation_worker = TranslationWorker()
