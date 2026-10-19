#!/usr/bin/env python3
"""将官方客户端模型层转换为BetterModel模型；只读JAR，不执行其中代码。"""
from __future__ import annotations
import argparse
import base64
import hashlib
import io
import json
import math
from pathlib import Path
import re
import struct
import uuid
import zipfile


NAMESPACE = uuid.UUID("8b93c3bd-6c01-46ed-9ec6-7c44878400bb")


def matrix_multiply(a, b):
    return [[sum(a[i][k] * b[k][j] for k in range(4)) for j in range(4)] for i in range(4)]


def identity():
    return [[float(i == j) for j in range(4)] for i in range(4)]


def part_matrix(part):
    result = identity()
    for i, key in enumerate(("x", "y", "z")):
        result[i][3] = float(part.get(key, 0))
    for axis, key in ((2, "zRot"), (1, "yRot"), (0, "xRot")):
        angle = float(part.get(key, 0)); c, s = math.cos(angle), math.sin(angle)
        r = identity()
        u, v = ((1, 2), (2, 0), (0, 1))[axis]
        r[u][u] = r[v][v] = c; r[u][v] = -s; r[v][u] = s
        result = matrix_multiply(result, r)
    scale = identity()
    for i, key in enumerate(("xScale", "yScale", "zScale")):
        scale[i][i] = float(part.get(key, 1))
    return matrix_multiply(result, scale)


def transform(point, matrix):
    vector = [*point[:3], 1]
    x, y, z = [sum(matrix[i][k] * vector[k] for k in range(4)) for i in range(3)]
    # Java实体模型坐标向下，模型原点距地面24像素；匹配原版渲染的X/Y反向。
    result = [-x, 24 - y, z]
    if not all(math.isfinite(v) for v in result):
        raise ValueError("模型出现非有限坐标")
    return [round(v, 7) for v in result]


def flatten(part, parent=None):
    matrix = matrix_multiply(parent or identity(), part_matrix(part))
    for face in part.get("polygons", []):
        yield [(transform(point, matrix), point[3:5]) for point in face]
    for child in part.get("children", {}).values():
        yield from flatten(child, matrix)


def png_size(data):
    if data[:8] != b"\x89PNG\r\n\x1a\n" or len(data) < 24:
        raise ValueError("材质不是有效PNG")
    width, height = struct.unpack(">II", data[16:24])
    if not 0 < width <= 8192 or not 0 < height <= 8192:
        raise ValueError("材质尺寸超出范围")
    return width, height


def texture_bytes(archive, layer):
    paths = layer.get("textures", [layer.get("texture")])
    if not paths or any(not p or ".." in p.split("/") for p in paths):
        raise ValueError("无效材质路径")
    contents = [archive.read("assets/minecraft/" + path) for path in paths]
    if len(contents) == 1 and "tint" not in layer:
        return contents[0]
    from PIL import Image
    image = Image.open(io.BytesIO(contents[0])).convert("RGBA")
    for data in contents[1:]:
        overlay = Image.open(io.BytesIO(data)).convert("RGBA")
        if overlay.size != image.size:
            raise ValueError("附加材质层尺寸不同")
        image.alpha_composite(overlay)
    if "tint" in layer:
        color = layer["tint"]
        channels = image.split()
        image = Image.merge("RGBA", tuple(channels[i].point(lambda p, factor=color[i]: p * factor / 255) for i in range(3)) + (channels[3],))
    output = io.BytesIO(); image.save(output, format="PNG")
    return output.getvalue()


def resolve_layer(layers, names):
    if isinstance(names, str):
        names = [names]
    for name in names:
        for candidate in (name, "minecraft:" + name, "minecraft:" + name + "#main"):
            if candidate in layers:
                return candidate
    raise KeyError("缺少模型层：" + ", ".join(names))


# BetterModel 3.5.0会将Blockbench像素坐标除以16，再交给java-mesh 0.0.1。
# 该库对Newell法线平方使用1e-6阈值；必须在同一坐标系和float精度下过滤。
JAVA_MESH_NORMAL_EPSILON = 1e-6


def float32(value):
    return struct.unpack(">f", struct.pack(">f", value))[0]


def mesh_normal_squared(polygon):
    points = [[float32(float32(p[i]) / 16) for i in range(3)] for p, _ in polygon]
    normal = [0.0, 0.0, 0.0]
    for current, following in zip(points, points[1:] + points[:1]):
        for axis, a, b in ((0, 1, 2), (1, 2, 0), (2, 0, 1)):
            delta = float32(current[a] - following[a])
            total = float32(current[b] + following[b])
            normal[axis] = float32(normal[axis] + float32(delta * total))
    return sum(value * value for value in normal)


def renderable(polygon):
    if len(polygon) < 3 or not all(math.isfinite(c) for point, _ in polygon for c in point):
        return False
    # 极薄立方体的侧面会在网格库中退化；保留其有效正反面，不膨胀模型。
    return mesh_normal_squared(polygon) >= JAVA_MESH_NORMAL_EPSILON * 1.01


def repair_model(model):
    removed = 0
    for mesh in model.get("elements", []):
        if mesh.get("type") != "mesh":
            continue
        keep = {}
        for name, face in mesh["faces"].items():
            polygon = [(mesh["vertices"][vertex], face["uv"][vertex]) for vertex in face["vertices"]]
            if renderable(polygon):
                keep[name] = face
            else:
                removed += 1
        if not keep:
            raise ValueError("修复后网格没有可渲染面：" + mesh.get("name", ""))
        mesh["faces"] = keep
        referenced = {vertex for face in keep.values() for vertex in face["vertices"]}
        mesh["vertices"] = {key: value for key, value in mesh["vertices"].items() if key in referenced}
    return removed


def make_legacy_mesh_model(recipe, layers, archive):
    model_name = recipe["model"]
    elements, textures = [], []
    for texture_index, layer in enumerate(recipe["layers"]):
        layer_id = resolve_layer(layers, layer["layer"])
        image = texture_bytes(archive, layer); width, height = png_size(image)
        vertices, faces = {}, {}
        for face_index, polygon in enumerate(flatten(layers[layer_id])):
            if not renderable(polygon):
                continue
            names, uv = [], {}
            for corner, (position, coords) in enumerate(polygon):
                key = f"v{face_index}_{corner}"; names.append(key)
                vertices[key] = position; uv[key] = [coords[0] * width, coords[1] * height]
            faces[f"f{face_index}"] = {"vertices": names, "uv": uv, "texture": texture_index}
        if not faces:
            raise ValueError("模型层没有可渲染多边形：" + layer_id)
        element_id = str(uuid.uuid5(NAMESPACE, model_name + "/mesh/" + str(texture_index)))
        elements.append({"name": layer.get("name", "base"), "type": "mesh", "uuid": element_id,
                         "origin": [0, 0, 0], "rotation": [0, 0, 0], "vertices": vertices, "faces": faces})
        textures.append({"name": f"{model_name}_{texture_index}.png", "id": str(texture_index),
                         "uuid": str(uuid.uuid5(NAMESPACE, model_name + "/texture/" + str(texture_index))),
                         "width": width, "height": height, "uv_width": width, "uv_height": height,
                         "source": "data:image/png;base64," + base64.b64encode(image).decode("ascii")})
    return {"meta": {"format_version": "4.10", "model_format": "free", "box_uv": False},
            "name": model_name, "resolution": {"width": textures[0]["width"], "height": textures[0]["height"]},
            "elements": elements, "textures": textures, "animations": [],
            "outliner": [{"name": "tongtou_root", "origin": [0, 0, 0],
                          "uuid": str(uuid.uuid5(NAMESPACE, model_name + "/root")),
                          "children": [e["uuid"] for e in elements]}]}


FACE_INFO = json.loads(Path(__file__).with_name("vanilla-face-info.json").read_text(encoding="utf-8"))


def cube_face_corners(start, end, face):
    return [[end[axis] if maximum else start[axis] for axis, maximum in enumerate(corner)]
            for corner in FACE_INFO[face]]


def face_uv_corners(face):
    u0, v0, u1, v1 = face["uv"]
    return [[u0 + point[0] * (u1 - u0), v0 + point[1] * (v1 - v0)]
            for point in FACE_INFO["uv_rotations"][str(face.get("rotation", 0))]]


def rotation_and_scale(matrix):
    # 原版PartPose采用Rz*Ry*Rx；反转X/Y后在同一坐标系中分解。
    signs = [-1, -1, 1]
    linear = [[signs[i] * matrix[i][j] * signs[j] for j in range(3)] for i in range(3)]
    scale = [math.sqrt(sum(linear[i][j] ** 2 for i in range(3))) for j in range(3)]
    if min(scale) <= 0:
        raise ValueError("模型有零缩放")
    rotation = [[linear[i][j] / scale[j] for j in range(3)] for i in range(3)]
    for j in range(3):
        for k in range(j):
            if abs(sum(rotation[i][j] * rotation[i][k] for i in range(3))) > 1e-5:
                raise ValueError("模型包含无法无损表达为立方体的剪切")
    y = math.asin(max(-1.0, min(1.0, -rotation[2][0])))
    if abs(math.cos(y)) > 1e-6:
        x, z = math.atan2(rotation[2][1], rotation[2][2]), math.atan2(rotation[1][0], rotation[0][0])
    else:
        x, z = 0.0, math.atan2(-rotation[0][1], rotation[1][1])
    return [round(math.degrees(a), 7) for a in (x, y, z)], scale


def rotate_point(point, degrees):
    matrix = part_matrix({key: math.radians(value) for key, value in zip(("xRot", "yRot", "zRot"), degrees)})
    return [sum(matrix[i][j] * point[j] for j in range(3)) for i in range(3)]


def cube_world_points(cube, face):
    origin = cube["origin"]
    result = []
    for point in cube_face_corners(cube["from"], cube["to"], face):
        offset = rotate_point([point[i] - origin[i] for i in range(3)], cube["rotation"])
        result.append([origin[i] + offset[i] for i in range(3)])
    return result


def fit_face_uv(expected):
    us = sorted({point[0] for point in expected})
    vs = sorted({point[1] for point in expected})
    if len(us) > 2 or len(vs) > 2:
        raise ValueError("面不是矩形UV")
    for u0, u1 in ((us[0], us[-1]), (us[-1], us[0])):
        for v0, v1 in ((vs[0], vs[-1]), (vs[-1], vs[0])):
            for rotation in (0, 90, 180, 270):
                face = {"uv": [u0, v0, u1, v1], "rotation": rotation}
                actual = face_uv_corners(face)
                if all(abs(actual[i][j] - expected[i][j]) < 1e-4 for i in range(4) for j in range(2)):
                    if not rotation:
                        del face["rotation"]
                    return face
    raise ValueError("无法保留原版逐面UV")


def emit_textured_cubes(part, model, texture_index, size, elements, parent=None, path="root"):
    matrix = matrix_multiply(parent or identity(), part_matrix(part))
    degrees, scale = rotation_and_scale(matrix)
    origin = transform([0, 0, 0], matrix)
    count = 0
    if not part.get("skipDraw", False) and part.get("visible", True):
        if "cubes" not in part:
            raise ValueError("缺少原始立方体数据，请重新执行exportVanilla")
        for index, source in enumerate(part["cubes"]):
            faces = source["faces"]
            points = [[-p[0] * scale[0], -p[1] * scale[1], p[2] * scale[2]]
                      for face in faces for p in face["vertices"]]
            if not points:
                continue
            start = [min(p[i] for p in points) for i in range(3)]
            end = [max(p[i] for p in points) for i in range(3)]
            cube = {"name": "part_" + path.replace("/", "_") + "_" + str(index), "type": "cube",
                    "uuid": str(uuid.uuid5(NAMESPACE, model + "/cube/" + str(texture_index) + "/" + path + "/" + str(index))),
                    "origin": origin, "rotation": degrees,
                    "from": [round(origin[i] + start[i], 7) for i in range(3)],
                    "to": [round(origin[i] + end[i], 7) for i in range(3)], "faces": {}}
            for source_face in faces:
                vertices = source_face["vertices"]
                local = [[-p[0] * scale[0], -p[1] * scale[1], p[2] * scale[2]] for p in vertices]
                normal = source_face["normal"]
                axis = max(range(3), key=lambda i: abs(normal[i]))
                direction = normal[axis] * (-1 if axis < 2 else 1)
                if end[axis] - start[axis] > 1e-6:
                    direction = -1 if abs(local[0][axis] - start[axis]) < 1e-4 else 1
                name = (("west", "east"), ("down", "up"), ("north", "south"))[axis][direction > 0]
                corners = cube_face_corners(start, end, name)
                expected_uv = []
                for corner in corners:
                    nearest = min(range(len(local)), key=lambda i: sum((local[i][j] - corner[j]) ** 2 for j in range(3)))
                    if max(abs(local[nearest][i] - corner[i]) for i in range(3)) > 1e-4:
                        raise ValueError("立方体面几何不匹配")
                    expected_uv.append([round(vertices[nearest][3] * size[0], 7), round(vertices[nearest][4] * size[1], 7)])
                if name in cube["faces"]:
                    raise ValueError("原始立方体出现重复方向面")
                converted = fit_face_uv(expected_uv)
                converted["texture"] = texture_index
                cube["faces"][name] = converted
                # 四个顶点同时检查几何与UV；防止模型编译成功但纹理已错位。
                for position, uv in zip(cube_world_points(cube, name), face_uv_corners(converted)):
                    nearest = min(vertices, key=lambda p: sum((transform(p, matrix)[i] - position[i]) ** 2 for i in range(3)))
                    expected_position = transform(nearest, matrix)
                    if max(abs(position[i] - expected_position[i]) for i in range(3)) > 2e-4:
                        raise ValueError("立方体变换改变原几何")
                    if max(abs(uv[i] - nearest[i + 3] * size[i]) for i in range(2)) > 1e-4:
                        raise ValueError("立方体UV改变原材质映射")
                count += 1
            elements.append(cube)
    for name, child in part.get("children", {}).items():
        count += emit_textured_cubes(child, model, texture_index, size, elements, matrix, path + "/" + name)
    return count


def transform_elements(elements, scale=1.0, translate=(0, 0, 0)):
    factor = float(scale)
    shift = tuple(float(value) for value in translate)
    if not math.isfinite(factor) or factor <= 0 or len(shift) != 3 or not all(math.isfinite(v) for v in shift):
        raise ValueError("模型尺寸变换无效")
    for element in elements:
        for key in ("from", "to", "origin"):
            element[key] = [round(value * factor + shift[i], 7) for i, value in enumerate(element[key])]


def make_model(recipe, layers, archive):
    name = recipe["model"]
    elements, textures = [], []
    verified = 0
    for texture_index, layer in enumerate(recipe["layers"]):
        image = texture_bytes(archive, layer)
        width, height = png_size(image)
        layer_id = resolve_layer(layers, layer["layer"])
        first = len(elements)
        verified += emit_textured_cubes(layers[layer_id], name, texture_index, (width, height), elements)
        if "scale" in layer or "translate" in layer:
            transform_elements(elements[first:], layer.get("scale", 1), layer.get("translate", (0, 0, 0)))
        textures.append({"name": f"{name}_{texture_index}.png", "id": str(texture_index),
                         "uuid": str(uuid.uuid5(NAMESPACE, name + "/texture/" + str(texture_index))),
                         "width": width, "height": height, "uv_width": width, "uv_height": height,
                         "source": "data:image/png;base64," + base64.b64encode(image).decode("ascii")})
    if not elements:
        raise ValueError("模型没有原版立方体")
    return {"meta": {"format_version": "4.10", "model_format": "free", "box_uv": False},
            "name": name, "resolution": {"width": textures[0]["width"], "height": textures[0]["height"]},
            "elements": elements, "textures": textures, "animations": [],
            "tongtou_export": {"renderer": "textured_cubes", "verified_faces": verified},
            "outliner": [{"name": "tongtou_root", "origin": [0, 0, 0],
                          "uuid": str(uuid.uuid5(NAMESPACE, name + "/root")),
                          "children": [element["uuid"] for element in elements]}]}


def default_recipes(assets, palette=None):
    result = []
    def add(entity, name, layer, texture=None, when=None, extra=None):
        state = {"baby": "false", "equipment": "empty", "pose": "standing", "special_name": ""}
        state.update(when or {})
        result.append({"entity": "minecraft:" + entity, "model": "tt_" + name,
                       "when": state, "layers": [{"layer": layer, "texture": texture}] + (extra or [])})
    for animal in ("pig", "cow", "chicken"):
        for climate in ("temperate", "cold", "warm"):
            for baby in (False, True):
                name = f"{animal}_{climate}" + ("_baby" if baby else "")
                adult = ("cold_" + animal if climate == "cold" else
                         "warm_cow" if animal == "cow" and climate == "warm" else animal)
                geometry = (adult if animal == "cow" else animal) + "_baby" if baby else adult
                when = {"baby": str(baby).lower(), "variant": "minecraft:" + climate}
                if animal == "pig": when["saddled"] = "false"
                add(animal, name, geometry, f"textures/entity/{animal}/{name}.png", when)
    simple = ("allay", "armadillo", "bat", "blaze", "cod", "creeper", "dolphin", "donkey", "elder_guardian",
              "enderman", "endermite", "evoker", "ghast", "glow_squid", "goat", "guardian", "hoglin", "husk",
              "illusioner", "iron_golem", "mule", "ocelot", "parched", "piglin", "piglin_brute", "pillager",
              "polar_bear", "ravager", "silverfish", "skeleton", "skeleton_horse", "sniffer", "snow_golem",
              "spider", "squid", "strider", "tadpole", "turtle", "vex", "vindicator", "warden", "witch",
              "wither", "wither_skeleton", "zoglin", "zombie", "zombie_horse", "zombified_piglin")
    aliases = {"elder_guardian": "guardian_elder", "polar_bear": "polarbear",
               "skeleton_horse": "horse_skeleton", "zombie_horse": "horse_zombie"}
    babies = {"armadillo", "dolphin", "donkey", "glow_squid", "goat", "hoglin", "husk", "mule", "ocelot",
              "piglin", "polar_bear", "skeleton_horse", "sniffer", "squid", "strider", "turtle", "zoglin",
              "zombie", "zombie_horse", "zombified_piglin"}
    for entity in simple:
        for baby in ((False, True) if entity in babies else (False,)):
            suffix = "_baby" if baby else ""
            leaf = aliases.get(entity, entity) + suffix + ".png"
            matches = sorted(path for path in assets if path.startswith("textures/entity/") and path.rsplit("/", 1)[-1] == leaf)
            texture = matches[0] if len(matches) == 1 else f"textures/entity/{entity}/{entity}{suffix}.png"
            if entity == "sniffer" and baby: texture = "textures/entity/sniffer/sniffer.png"
            extra_when = {"baby": str(baby).lower()}
            if entity in {"donkey", "mule"}: extra_when["carrying_chest"] = "false"
            if entity in {"donkey", "mule", "strider", "skeleton_horse", "zombie_horse"}: extra_when["saddled"] = "false"
            if entity == "goat": extra_when.update({"left_horn": "true", "right_horn": "true"})
            if entity == "strider": extra_when["shivering"] = "false"
            if entity == "snow_golem": extra_when["derp"] = "true"
            if entity == "creeper": extra_when["charged"] = "false"
            if entity == "enderman": extra_when["carried_block"] = "empty"
            add(entity, entity + suffix, [entity + suffix], texture, extra_when)
    for path in sorted(assets):
        match = re.fullmatch(r"textures/entity/cat/cat_(.+?)(_baby)?\.png", path)
        if match and match[1] != "collar":
            variant, baby = match[1], bool(match[2])
            add("cat", "cat_" + variant + ("_baby" if baby else ""), "cat_baby" if baby else "cat", path,
                {"baby": str(baby).lower(), "variant": "minecraft:" + variant, "tamed": "false", "sitting": "false"})
        match = re.fullmatch(r"textures/entity/fox/fox(_snow)?(_sleep)?(_baby)?\.png", path)
        if match and not match[2]:
            baby, snow = bool(match[3]), bool(match[1])
            add("fox", "fox_" + ("snow" if snow else "red") + ("_baby" if baby else ""),
                "fox_baby" if baby else "fox", path,
                {"baby": str(baby).lower(), "fox_type": "snow" if snow else "red", "sleeping": "false", "sitting": "false"})
        match = re.fullmatch(r"textures/entity/boat/([a-z_]+)\.png", path)
        if match:
            wood = match[1]; entity = "bamboo_raft" if wood == "bamboo" else wood + "_boat"
            add(entity, entity, [entity, "boat/" + wood, "boat/" + wood + "_boat"], path)
    colors = {"white": (255, 255, 255), "orange": (216, 127, 51), "magenta": (178, 76, 216),
              "light_blue": (102, 153, 216), "yellow": (229, 229, 51), "lime": (127, 204, 25),
              "pink": (242, 127, 165), "gray": (76, 76, 76), "light_gray": (153, 153, 153),
              "cyan": (76, 127, 153), "purple": (127, 63, 178), "blue": (51, 76, 178),
              "brown": (102, 76, 51), "green": (102, 127, 51), "red": (153, 51, 51), "black": (25, 25, 25)}
    if palette is not None:
        colors = palette
    for baby in (False, True):
        suffix = "_baby" if baby else ""
        for sheared in (False, True):
            for color, rgb in colors.items():
                layers = [] if sheared else [{"name": "wool", "layer": ["sheep" + suffix + "#wool", "sheep_wool" + suffix, "sheep_baby_wool" if baby else "sheep_wool"],
                           "texture": f"textures/entity/sheep/sheep_wool{suffix}.png", "tint": rgb}]
                add("sheep", f"sheep_{color}_{'sheared' if sheared else 'wool'}{suffix}", "sheep" + suffix,
                    f"textures/entity/sheep/sheep{suffix}.png", {"baby": str(baby).lower(), "sheared": str(sheared).lower(), "color": color}, layers)
    for path in sorted(assets):
        match = re.fullmatch(r"textures/entity/rabbit/rabbit_(.+?)(_baby)?\.png", path)
        if match and match[1] != "toast":
            kind, baby = match[1], bool(match[2]); types = {"caerbannog": "the_killer_bunny", "salt": "salt_and_pepper", "white_splotched": "black_and_white"}
            add("rabbit", f"rabbit_{kind}" + ("_baby" if baby else ""), "rabbit_baby" if baby else "rabbit", path,
                {"baby": str(baby).lower(), "rabbit_type": types.get(kind, kind)})
    result.extend(missing_mob_recipes())
    result.extend(additional_recipes(assets))
    return result


def missing_mob_recipes():
    recipes = []
    common = {"baby": "false", "equipment": "empty", "pose": "standing", "special_name": ""}
    for entity in ("slime", "magma_cube"):
        for size in (1, 2, 4):
            slime = entity == "slime"
            texture = "textures/entity/slime/" + ("slime.png" if slime else "magmacube.png")
            factor = size * (0.999 if slime else 1)
            shift = [0, 0.001 * 0.999 * 16 if slime else 0, 0]
            layers = [{"layer": entity + "#main", "texture": texture, "scale": factor, "translate": shift}]
            if slime:
                layers.append({"layer": "slime#outer", "texture": texture, "scale": factor, "translate": shift})
            recipes.append({"entity": "minecraft:" + entity, "model": f"tt_{entity}_size_{size}",
                            "when": {**common, "size": str(size)}, "layers": layers})
    for baby in (False, True):
        suffix = "_baby" if baby else ""
        recipes.append({"entity": "minecraft:drowned", "model": "tt_drowned" + suffix,
                        "when": {**common, "baby": str(baby).lower(), "swimming": "false"},
                        "layers": [{"layer": "drowned" + suffix + "#main", "texture": "textures/entity/zombie/drowned" + suffix + ".png"},
                                   {"layer": "drowned" + suffix + "#outer", "texture": "textures/entity/zombie/drowned_outer_layer" + suffix + ".png"}]})
    for entity, texture in (("cave_spider", "textures/entity/spider/cave_spider.png"),
                            ("wandering_trader", "textures/entity/wandering_trader/wandering_trader.png")):
        recipes.append({"entity": "minecraft:" + entity, "model": "tt_" + entity, "when": dict(common),
                        "layers": [{"layer": entity + "#main", "texture": texture}]})
    for state, geometry in enumerate(("small", "medium", "big")):
        recipes.append({"entity": "minecraft:pufferfish", "model": "tt_pufferfish_" + geometry,
                        "when": {**common, "puff_state": str(state)},
                        "layers": [{"layer": "pufferfish_" + geometry + "#main", "texture": "textures/entity/fish/pufferfish.png"}]})
    return recipes


def additional_recipes(assets):
    result = []
    def add(entity, name, layer, texture=None, when=None, textures=None):
        state = {"baby": "false", "equipment": "empty", "pose": "standing", "special_name": ""}
        state.update(when or {})
        material = {"layer": layer, "textures": textures} if textures else {"layer": layer, "texture": texture}
        result.append({"entity": "minecraft:" + entity, "model": "tt_" + name, "when": state, "layers": [material]})
    for path in sorted(assets):
        match = re.fullmatch(r"textures/entity/(axolotl|frog|parrot|llama)/\1_(.+?)(_baby)?\.png", path)
        if match and match[2] != "spit":
            entity, variant, baby = match[1], match[2], bool(match[3])
            prop = "color" if entity == "llama" else "variant"
            state_variant = {"red_blue": "red", "yellow_blue": "cyan", "grey": "gray"}.get(variant, variant) if entity == "parrot" else variant
            value = state_variant if prop == "color" else "minecraft:" + state_variant
            when = {"baby": str(baby).lower(), prop: value}
            if entity == "parrot": when["sitting"] = "false"
            if entity == "llama": when.update({"carrying_chest": "false", "decorated": "false"})
            add(entity, entity + "_" + variant + ("_baby" if baby else ""), entity + ("_baby" if baby else ""), path, when)
    for variant in ("pale", "spotted", "snowy", "black", "ashen", "rusty", "woods", "chestnut", "striped"):
        prefix = "wolf" if variant == "pale" else "wolf_" + variant
        for baby in (False, True):
            for angry in (False, True):
                suffix = ("_angry" if angry else "") + ("_baby" if baby else "")
                add("wolf", prefix + suffix, "wolf_baby" if baby else "wolf", "textures/entity/wolf/" + prefix + suffix + ".png",
                    {"baby": str(baby).lower(), "variant": "minecraft:" + variant, "tamed": "false", "angry": str(angry).lower(), "sitting": "false"})
    styles = {"none": None, "white": "white", "whitefield": "whitefield", "white_dots": "whitedots", "black_dots": "blackdots"}
    for baby in (False, True):
        suffix = "_baby" if baby else ""
        for color in ("white", "creamy", "chestnut", "brown", "black", "gray", "dark_brown"):
            for style, marking in styles.items():
                textures = [f"textures/entity/horse/horse_{color.replace('_', '')}{suffix}.png"]
                if marking: textures.append(f"textures/entity/horse/horse_markings_{marking}{suffix}.png")
                add("horse", f"horse_{color}_{style}{suffix}", "horse" + suffix, textures=textures,
                    when={"baby": str(baby).lower(), "color": color, "style": style, "saddled": "false", "horse_armor": "empty"})
    professions = sorted(path.rsplit("/", 1)[-1][:-4] for path in assets
                         if path.startswith("textures/entity/villager/profession/") and path.endswith(".png"))
    badges = ("stone", "iron", "gold", "emerald", "diamond")
    for biome in ("desert", "jungle", "plains", "savanna", "snow", "swamp", "taiga"):
        add("villager", "villager_" + biome + "_baby", "villager_baby",
            textures=["textures/entity/villager/villager_baby.png", f"textures/entity/villager/baby/{biome}.png"],
            when={"baby": "true", "villager_type": "minecraft:" + biome})
        for profession in ["none", *professions]:
            levels = (0,) if profession in {"none", "nitwit"} else range(1, 6)
            for level in levels:
                textures = ["textures/entity/villager/villager.png", f"textures/entity/villager/type/{biome}.png"]
                if profession != "none": textures.append(f"textures/entity/villager/profession/{profession}.png")
                when = {"villager_type": "minecraft:" + biome, "profession": "minecraft:" + profession}
                if level:
                    textures.append(f"textures/entity/villager/profession_level/{badges[level-1]}.png")
                    when["villager_level"] = str(level)
                add("villager", f"villager_{biome}_{profession}_{level}", "villager", textures=textures, when=when)
    return result


def write_owned(path, data, overwrite=False):
    if path.exists() and path.read_bytes() != data and not overwrite:
        raise FileExistsError(f"拒绝覆盖已有文件：{path}；确认后使用 --overwrite")
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_bytes(data); temporary.replace(path)


def build(layers, archive, recipes, output, overwrite=False, entity_types=None):
    catalog, failures = [], []
    cube_count = face_count = 0
    assets = {name.removeprefix("assets/minecraft/") for name in archive.namelist() if name.startswith("assets/minecraft/")}
    for recipe in recipes:
        try:
            if recipe["entity"] == "minecraft:player" or not re.fullmatch(r"tt_[a-z0-9_]+", recipe["model"]):
                raise ValueError("玩家或无效模型名称不允许导出")
            if entity_types is not None and recipe["entity"] not in entity_types:
                raise ValueError("官方注册表中不存在该实体类型")
            model = make_model(recipe, layers, archive)
            cube_count += len(model["elements"])
            face_count += model["tongtou_export"]["verified_faces"]
            data = json.dumps(model, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            write_owned(output / "BetterModel" / "models" / (recipe["model"] + ".bbmodel"), data, overwrite)
            catalog.append({k: recipe[k] for k in ("entity", "model", "when")})
        except (KeyError, ValueError) as error:
            failures.append({"entity": recipe.get("entity"), "model": recipe.get("model"), "reason": str(error)})
    document = {"schema": 1, "minecraft": "26.3", "models": catalog}
    write_owned(output / "TongtouMode" / "catalog.json", json.dumps(document, ensure_ascii=False, indent=2).encode("utf-8"), overwrite)
    report = {"minecraft": "26.3", "exported": len(catalog), "entity_types": sorted({r["entity"] for r in catalog}),
              "unmapped_entity_types": sorted(set(entity_types or []) - {r["entity"] for r in catalog}),
              "not_exported": failures, "available_layers": sorted(layers),
              "texture_fidelity": {"renderer": "native_textured_cubes", "verified_cubes": cube_count, "verified_faces": face_count, "constant_color_mesh": False},
              "limitations": ["玩家始终排除", "导出基础姿态，不自动复制行走和攻击动画", "装备、特殊命名和未匹配变种明确拒绝，不伪装外观", "程序化效果和特殊附加层需要额外配方"]}
    write_owned(output / "coverage.json", json.dumps(report, ensure_ascii=False, indent=2).encode("utf-8"), overwrite)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--layers", type=Path, required=True)
    parser.add_argument("--client-jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--recipes", type=Path)
    parser.add_argument("--overwrite", action="store_true")
    args = parser.parse_args()
    layers = json.loads(args.layers.read_text(encoding="utf-8"))
    with zipfile.ZipFile(args.client_jar) as archive:
        version = json.loads(archive.read("version.json"))
        if version["id"] != "26.3": raise ValueError("只接受官方26.3客户端")
        assets = {n.removeprefix("assets/minecraft/") for n in archive.namelist() if n.startswith("assets/minecraft/")}
        source = json.loads(args.layers.with_name("source.json").read_text(encoding="utf-8"))
        with args.client_jar.open("rb") as client:
            client_hash = hashlib.file_digest(client, "sha1").hexdigest()
        if source.get("minecraft") != "26.3" or source.get("client_sha1") != client_hash or source.get("cube_export") != 1:
            raise ValueError("模型层与当前客户端不匹配，请重新运行exportVanilla")
        if json.loads(args.layers.with_name("face-info.json").read_text(encoding="utf-8")) != FACE_INFO:
            raise ValueError("当前客户端面顶点/UV旋转定义已改变，需核对转换器")
        palette = json.loads(args.layers.with_name("palette.json").read_text(encoding="utf-8"))
        recipes = json.loads(args.recipes.read_text(encoding="utf-8")) if args.recipes else default_recipes(assets, palette)
        report = build(layers, archive, recipes, args.output.resolve(), args.overwrite, source.get("entities"))
    print(json.dumps({"导出模型": report["exported"], "实体类型数": len(report["entity_types"]), "未导出项": len(report["not_exported"])}, ensure_ascii=False))
    if not report["exported"]: raise SystemExit("没有成功生成模型，请检查coverage.json")


if __name__ == "__main__":
    main()
