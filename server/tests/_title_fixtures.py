"""Từ điển convert MẪU (vài chục mục, viết tay) cho test — không dùng dữ liệu cộng đồng thật, không ra mạng."""

import pytest

HANVIET = """﻿剑=kiếm
检=kiểm
来=lai/lại
莱=lai
斗=đấu
破=phá
苍=thương
穹=khung
万=vạn
古=cổ
第=đệ
一=nhất
神=thần
林=lâm
动=động
出=xuất
关=quan
国=quốc
师=sư
的=đích
建=kiến
议=nghị
波=ba
士=sĩ
顿=đốn
爱=ái
情=tình
故=cố
事=sự
斩=trảm
杀=sát
妖=yêu
魔=ma
风=phong
青=thanh
阳=dương
我=ngã
在=tại
大=đại
学=học
当=đương
校=hiệu/giáo
长=trưởng/trường
圣=thánh
兽=thú
战=chiến
魂=hồn
求=cầu
月=nguyệt
票=phiếu
上=thượng
潜=tiềm
入=nhập
兰=lan
利=lợi
化=hóa
进=tiến
阶=giai
凡=phàm
人=nhân
修=tu
仙=tiên
传=truyện/truyền
"""
PHIENAM = "﻿鸽=cáp\n哄=hống\n难=nan\n"
NAMES = "﻿林动=Lâm Động\n剑来=Kiếm Lai\n斗破苍穹=Đấu Phá Thương Khung\n国师=Quốc Sư\n第1章=Chương 1\n"
NAMES_TD = "﻿凡人=Phàm Nhân\n万古第一神=Vạn Cổ Đệ Nhất Thần\n"
NAMES_TD2 = "﻿兰利=Langley\n"
VIETPHRASE = """的=đích
建议=đề nghị
出关=xuất quan
斩杀=chém giết
妖魔=yêu ma
爱情=tình yêu/ái tình
故事=câu chuyện/cố sự
化神=Hóa Thần
进阶=tiến giai
求月票=cầu nguyệt phiếu
大学=đại học
校长=hiệu trưởng
潜入=lẻn vào
圣兽=thánh thú
战魂=chiến hồn
万古=vạn cổ
修仙=tu tiên
"""
FILES = {
    "ht_HanViet.txt": HANVIET, "td_PhienAm.txt": PHIENAM, "ht_Names.txt": NAMES,
    "td_Names.txt": NAMES_TD, "td_Names2.txt": NAMES_TD2, "ht_VietPhrase.txt": VIETPHRASE,
}


def write_fixture_sources(directory) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    for name, text in FILES.items():
        (directory / name).write_text(text, encoding="utf-8")


def build_fixture_dict(tmp_path):
    from services.title_dict import TitleDict
    from services.title_dict_build import build_index
    src = tmp_path / "fixture_dict_src"
    write_fixture_sources(src)
    out = tmp_path / "fixture_title_dict.sqlite"
    build_index(str(src), str(out), force=True)
    return TitleDict(str(out))


@pytest.fixture
def fixture_dict(tmp_path):
    """Dựng chỉ mục từ điển mẫu và gắn vào title_dict (fingerprint nguồn thật không áp dụng cho bộ mẫu)."""
    from services.title_dict import title_dict
    d = build_fixture_dict(tmp_path)
    title_dict.install(d)
    yield d
    title_dict.install(None)
    d.close()
