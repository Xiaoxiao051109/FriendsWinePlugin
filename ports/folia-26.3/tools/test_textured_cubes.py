"""校验纹理立方体与原始模型面的位置、UV和PNG一致，不以可解析代替保真。"""
import base64
import collections
import importlib.util
import io
import itertools
import json
import math
from pathlib import Path
import unittest
import zipfile
from PIL import Image

ROOT = Path(__file__).parent.parent
spec = importlib.util.spec_from_file_location("models", Path(__file__).with_name("build_models.py"))
models = importlib.util.module_from_spec(spec)
spec.loader.exec_module(models)


def cube_layers():
    return {"polygons": [], "children": {}, "cubes": [{"faces": [{
        "normal": [0, 0, -1],
        "vertices": [[0, 24, 0, 0, 1], [16, 24, 0, 1, 1], [16, 8, 0, 1, 0], [0, 8, 0, 0, 0]],
    }]}]}


def texture_archive():
    image = Image.new("RGBA", (16, 16), (225, 145, 140, 255))
    stream = io.BytesIO(); image.save(stream, format="PNG")
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w") as z: z.writestr("assets/minecraft/test.png", stream.getvalue())
    return archive


def native_faces(model):
    for cube in model["elements"]:
        if cube["type"] != "cube": raise AssertionError("新资源不允许继续使用单色网格")
        for face, material in cube["faces"].items():
            yield models.cube_world_points(cube, face), models.face_uv_corners(material), material["texture"]


def cell(points):
    return tuple(math.floor(sum(point[i] for point in points) / len(points)) for i in range(3))


class TexturedCubeTest(unittest.TestCase):
    def test_synthetic_model_keeps_png_texture_and_uv(self):
        recipe = {"model": "tt_test", "layers": [{"layer": "test", "texture": "test.png"}]}
        with zipfile.ZipFile(texture_archive()) as archive:
            model = models.make_model(recipe, {"minecraft:test#main": cube_layers()}, archive)
        self.assertEqual(model["elements"][0]["type"], "cube")
        self.assertEqual(model["elements"][0]["faces"]["north"]["uv"], [0, 0, 16, 16])
        self.assertEqual(model["tongtou_export"]["verified_faces"], 1)
        self.assertEqual(Image.open(io.BytesIO(base64.b64decode(model["textures"][0]["source"].split(",", 1)[1]))).getpixel((8, 8)), (225, 145, 140, 255))

    def test_mirrors_and_quarter_turns_round_trip(self):
        for rotation in (0, 90, 180, 270):
            for uv in ([0, 2, 8, 14], [8, 2, 0, 14], [0, 14, 8, 2], [8, 14, 0, 2]):
                expected = models.face_uv_corners({"uv": uv, "rotation": rotation})
                actual = models.face_uv_corners(models.fit_face_uv(expected))
                self.assertEqual(expected, actual)

    def test_rotation_scale_decomposition_matches_original(self):
        for angles in ((0.2, -0.4, 0.7), (math.pi / 2, 0, 0), (0, math.pi / 2, 0.3), (0, -math.pi / 2, -0.4)):
            part = dict(zip(("xRot", "yRot", "zRot"), angles), xScale=0.5, yScale=1.2, zScale=0.8, x=2, y=12, z=-3)
            matrix = models.part_matrix(part)
            rotation, scale = models.rotation_and_scale(matrix)
            origin = models.transform([0, 0, 0], matrix)
            original = [2, -5, 4]
            point = [-original[0] * scale[0], -original[1] * scale[1], original[2] * scale[2]]
            actual = [a + b for a, b in zip(origin, models.rotate_point(point, rotation))]
            expected = models.transform(original, matrix)
            for a, b in zip(actual, expected): self.assertAlmostEqual(a, b, places=5)

    def test_all_builtin_faces_keep_original_png_and_vertex_uv(self):
        original = ROOT / "src/test/resources/regressions/mesh-models-0.1.3.zip"
        checked = 0
        with zipfile.ZipFile(original) as old, zipfile.ZipFile(ROOT / "content/builtin-1.1.0.zip") as new:
            for name in old.namelist():
                if not name.startswith("models/") or not name.endswith(".bbmodel"): continue
                source, target = json.loads(old.read(name)), json.loads(new.read(name))
                self.assertEqual([t["source"] for t in source["textures"]], [t["source"] for t in target["textures"]], name)
                index = collections.defaultdict(list)
                for positions, uv, texture in native_faces(target): index[cell(positions)].append((positions, uv, texture))
                for mesh in source["elements"]:
                    for face_id, face in mesh["faces"].items():
                        expected_pos = [mesh["vertices"][vertex] for vertex in face["vertices"]]
                        expected_uv = [face["uv"][vertex] for vertex in face["vertices"]]
                        centre = cell(expected_pos)
                        matched = False
                        for dx, dy, dz in itertools.product((-1, 0, 1), repeat=3):
                            for positions, uv, texture in index.get((centre[0] + dx, centre[1] + dy, centre[2] + dz), []):
                                if texture != face["texture"]: continue
                                valid = True
                                for p, tex in zip(expected_pos, expected_uv):
                                    nearest = min(range(4), key=lambda i: sum((positions[i][j] - p[j]) ** 2 for j in range(3)))
                                    if max(abs(positions[nearest][j] - p[j]) for j in range(3)) > 2e-4 or max(abs(uv[nearest][j] - tex[j]) for j in range(2)) > 1e-4:
                                        valid = False; break
                                if valid: matched = True; break
                            if matched: break
                        self.assertTrue(matched, f"几何或纹理映射变化：{name}/{face_id}")
                        checked += 1
        self.assertGreater(checked, 50000)
        print(f"已对照旧版原始顶点UV验证 {checked} 个面，PNG逐字节一致")

    def test_pig_skin_samples_are_not_large_black_blocks(self):
        samples = []
        with zipfile.ZipFile(ROOT / "content/builtin-1.1.0.zip") as archive:
            model = json.loads(archive.read("models/tt_pig_temperate.bbmodel"))
        images = [Image.open(io.BytesIO(base64.b64decode(t["source"].split(",", 1)[1]))).convert("RGBA") for t in model["textures"]]
        for positions, uv, texture in native_faces(model):
            image = images[texture]
            for s, t in itertools.product((0.25, 0.5, 0.75), repeat=2):
                weights = ((1-s)*(1-t), (1-s)*t, s*t, s*(1-t))
                u, v = [sum(uv[i][axis] * weights[i] for i in range(4)) for axis in range(2)]
                samples.append(image.getpixel((min(image.width-1, max(0, int(u))), min(image.height-1, max(0, int(v))))))
        self.assertGreater(sum(r > 100 and a == 255 for r, g, b, a in samples), len(samples) * 0.9)
        self.assertLess(sum(r == g == b == 0 for r, g, b, a in samples), len(samples) * 0.1)
        print(f"猪纹理内部采样 {len(samples)} 点，黑色 {sum(r == g == b == 0 for r,g,b,a in samples)} 点")


if __name__ == "__main__":
    unittest.main()
