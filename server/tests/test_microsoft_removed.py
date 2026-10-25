"""Microsoft (edge-tts) đã bị bỏ HẲN khỏi máy chủ: không mã, không thư viện, không đường dự phòng."""

import re
from pathlib import Path

SERVER = Path(__file__).resolve().parent.parent
FORBIDDEN = re.compile(r"edge[-_]tts|microsoft|\bcommunicate\b.*\bsave\(|speech\.platform\.bing|trustedclienttoken", re.IGNORECASE)


def _tts_sources():
    files = [p for p in (SERVER / "services").glob("tts_*.py")] + [p for p in (SERVER / "routes").glob("tts*.py")]
    files += [SERVER / "requirements.txt", SERVER / "requirements-vieneu.txt", SERVER / "Dockerfile", SERVER / "docker-compose.yml",
              SERVER / ".env.example", SERVER / "server.py"]
    return [p for p in files if p.is_file()]


def test_khong_tep_giong_doc_nao_nhac_toi_dich_vu_cu():
    offenders = []
    for path in _tts_sources():
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if FORBIDDEN.search(line):
                offenders.append(f"{path.name}:{number}: {line.strip()[:100]}")
    assert offenders == []


def test_khong_tep_python_nao_cua_may_chu_import_edge_tts():
    offenders = []
    for path in SERVER.rglob("*.py"):
        if ".venv" in path.parts or "tests" in path.parts or "__pycache__" in path.parts:
            continue
        if re.search(r"^\s*(import|from)\s+edge_tts", path.read_text(encoding="utf-8"), re.MULTILINE):
            offenders.append(str(path.relative_to(SERVER)))
    assert offenders == []


def test_requirements_khong_con_edge_tts():
    for name in ("requirements.txt", "requirements-vieneu.txt"):
        assert "edge" not in (SERVER / name).read_text(encoding="utf-8").lower()


def test_dockerfile_cai_vieneu_khong_keo_theo_gradio_va_khong_co_gpu():
    text = (SERVER / "Dockerfile").read_text(encoding="utf-8")
    assert "--no-deps -r requirements-vieneu.txt" in text
    reqs = "\n".join(line for line in (SERVER / "requirements.txt").read_text(encoding="utf-8").lower().splitlines()
                     if not line.lstrip().startswith("#"))
    assert "torch" not in reqs and "gradio" not in reqs and "cuda" not in reqs and "librosa" not in reqs
    assert "vieneu==" in (SERVER / "requirements-vieneu.txt").read_text(encoding="utf-8")


def test_may_chu_chay_khong_can_edge_tts_da_cai(monkeypatch):
    """Dù gói edge_tts còn nằm trong môi trường, import toàn bộ tuyến TTS không được chạm tới nó."""
    import importlib
    import sys

    for name in [m for m in sys.modules if m == "edge_tts" or m.startswith("edge_tts.")]:
        monkeypatch.delitem(sys.modules, name)
    monkeypatch.setitem(sys.modules, "edge_tts", None)  # import edge_tts sẽ ném ImportError
    for module in ("services.tts_voice_service", "services.tts_synthesis", "services.tts_prerender_plan", "routes.tts"):
        importlib.reload(importlib.import_module(module))
