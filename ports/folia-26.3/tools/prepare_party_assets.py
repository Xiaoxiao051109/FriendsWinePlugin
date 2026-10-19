"""生成玩偶静态复合物品、原纹理BetterModel和无损OGG页边界偏移资源，无外部编码器。"""
import base64
import importlib.util
import json
import math
from pathlib import Path
import shutil
import struct
import zipfile

ROOT = Path(__file__).resolve().parent.parent
REPO = ROOT.parent.parent
spec = importlib.util.spec_from_file_location("model_math", Path(__file__).with_name("build_models.py"))
MATH = importlib.util.module_from_spec(spec)
spec.loader.exec_module(MATH)


def translation(point):
    matrix = MATH.identity()
    for i in range(3): matrix[i][3] = point[i]
    return matrix


def cube_transform(cube):
    origin = [cube.get("origin", [0, 0, 0])[i] / 16 for i in range(3)]
    origin[1] -= 0.5
    rotation = MATH.part_matrix({key: math.radians(value) for key, value in zip(("xRot", "yRot", "zRot"), cube.get("rotation", [0, 0, 0]))})
    return MATH.matrix_multiply(translation(origin), MATH.matrix_multiply(rotation, translation([-v for v in origin])))


def crc(page):
    value = 0
    for byte in page:
        value ^= byte << 24
        for _ in range(8): value = ((value << 1) ^ (0x04C11DB7 if value & 0x80000000 else 0)) & 0xFFFFFFFF
    return value


def ogg_pages(data):
    pages = []; offset = 0
    while offset < len(data):
        if data[offset:offset+4] != b"OggS": raise ValueError("OGG页签名无效")
        count = data[offset+26]; end = offset + 27 + count + sum(data[offset+27:offset+27+count])
        page = data[offset:end]
        if len(page) < 27 or end > len(data): raise ValueError("OGG页截断")
        saved = struct.unpack_from("<I", page, 22)[0]
        blank = bytearray(page); blank[22:26] = b"\0" * 4
        if crc(blank) != saved: raise ValueError("原音频CRC错误")
        pages.append(page); offset = end
    return pages


def offset_ogg(pages, start):
    if start < 2 or start >= len(pages) or pages[start][5] & 1: raise ValueError("偏移必须落在独立音频页边界")
    baseline = struct.unpack_from("<q", pages[start-1], 6)[0]
    output = []
    for index, source in enumerate([*pages[:2], *pages[start:]]):
        page = bytearray(source)
        struct.pack_into("<I", page, 18, index)
        if index >= 2:
            granule = struct.unpack_from("<q", page, 6)[0]
            if granule >= 0: struct.pack_into("<q", page, 6, max(0, granule-baseline))
        page[22:26] = b"\0" * 4; struct.pack_into("<I", page, 22, crc(page))
        output.append(bytes(page))
    return b"".join(output), baseline


def generate():
    output = ROOT / "build/party-assets"
    output.mkdir(parents=True, exist_ok=True)
    assets = output / "ce/resourcepack/assets/tongtou"
    model = json.loads((REPO / "assets/source/doll.bbmodel").read_text(encoding="utf-8"))
    model["name"] = "tt_doll"
    # 动态控制由插件统一时钟接管，不能让原动画在停止状态自动运行。
    model["animations"] = []
    # 原素材的子组没有静态旋转，展平后几何/逐面UV不变，避免创建空的展示骨骼。
    def check_groups(nodes):
        for node in nodes:
            if isinstance(node, dict):
                if any(node.get("rotation", [])): raise ValueError("玩偶子组新增旋转，需要先烘焙变换")
                check_groups(node.get("children", []))
    check_groups(model["outliner"])
    root = dict(model["outliner"][0]); root["children"] = [cube["uuid"] for cube in model["elements"]]
    model["outliner"] = [root]
    (output / "models").mkdir(exist_ok=True)
    (output / "models/tt_doll.bbmodel").write_text(json.dumps(model, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    cube_dir = assets / "models/doll"; cube_dir.mkdir(parents=True, exist_ok=True)
    components = []
    for index, cube in enumerate(model["elements"]):
        shift = [8, 0, 8]
        element = {"from": [cube["from"][i]+shift[i] for i in range(3)],
                   "to": [cube["to"][i]+shift[i] for i in range(3)], "faces": {}}
        for direction, face in cube["faces"].items():
            if face.get("texture") is None: continue
            value = {"uv": [v/4 for v in face["uv"]], "texture": "#skin"}
            if face.get("rotation"): value["rotation"] = face["rotation"]
            element["faces"][direction] = value
        payload = {"textures": {"skin": "tongtou:entity/doll", "particle": "tongtou:entity/doll"}, "elements": [element]}
        (cube_dir / f"cube_{index}.json").write_text(json.dumps(payload, separators=(",", ":")), encoding="utf-8")
        matrix = cube_transform(cube)
        components.append({"type": "minecraft:model", "model": f"tongtou:doll/cube_{index}",
                           "transformation": [matrix[i][j] for i in range(4) for j in range(4)]})
    item_model = {"model": {"type": "minecraft:composite", "models": components}}
    (assets / "items").mkdir(parents=True, exist_ok=True)
    (assets / "items/doll.json").write_text(json.dumps(item_model, separators=(",", ":")), encoding="utf-8")
    (assets / "textures/entity").mkdir(parents=True, exist_ok=True)
    image = base64.b64decode(model["textures"][0]["source"].split(",", 1)[1])
    (assets / "textures/entity/doll.png").write_bytes(image)
    (assets / "textures/item").mkdir(exist_ok=True)
    shutil.copyfile(REPO / "mod/src/main/resources/assets/friendswine/textures/item/remote.png", assets / "textures/item/remote.png")
    (assets / "items/remote.json").write_text(json.dumps({"model": {"type": "minecraft:model", "model": "tongtou:item/remote"}}), encoding="utf-8")
    (assets / "models/item").mkdir(parents=True, exist_ok=True)
    (assets / "models/item/remote.json").write_text(json.dumps({"parent": "minecraft:item/generated", "textures": {"layer0": "tongtou:item/remote"}}), encoding="utf-8")
    sound_dir = assets / "sounds/party"; sound_dir.mkdir(parents=True, exist_ok=True)
    original = (REPO / "mod/src/main/resources/assets/friendswine/sounds/doll_music.ogg").read_bytes()
    pages = ogg_pages(original)
    if len(pages) != 58 or any(page[5] & 1 for page in pages): raise ValueError("原音乐页布局变化，需要复核偏移生成器")
    entries = []; definitions = {}
    for start in range(2, len(pages)):
        number = start-2
        data, offset = offset_ogg(pages, start)
        (sound_dir / f"music_{number}.ogg").write_bytes(data)
        entries.append({"id": number, "offset_samples": offset, "remaining_samples": 2482560-offset})
        for slot in range(4):
            definitions[f"doll_music_{slot}_{number}"] = {"sounds": [{"name": f"tongtou:party/music_{number}", "stream": True, "attenuation_distance": 20}]}
    (assets / "sounds.json").write_text(json.dumps(definitions, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    (output / "music.json").write_text(json.dumps({"schema": 1, "sample_rate": 44100, "frames": 2482560, "slots": 4,
            "offsets": entries, "method": "lossless_ogg_page_boundary"}, indent=2), encoding="utf-8")
    (output / "feature-models.json").write_text(json.dumps({"schema": 1, "models": ["tt_doll"]}, indent=2), encoding="utf-8")
    print(json.dumps({"doll_cubes": len(components), "music_offsets": len(entries), "output": str(output)}, ensure_ascii=False))


if __name__ == "__main__": generate()
