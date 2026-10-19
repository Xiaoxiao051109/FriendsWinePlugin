"""模型转换器回归测试；使用自建数据，不启动Minecraft。"""
import importlib.util
import io
import json
import math
from pathlib import Path
import tempfile
import unittest
import zipfile
from PIL import Image

spec = importlib.util.spec_from_file_location("tongtou_models", Path(__file__).with_name("build_models.py"))
models = importlib.util.module_from_spec(spec)
spec.loader.exec_module(models)


def png():
    image = Image.new("RGBA", (16, 16), (255, 255, 255, 255))
    stream = io.BytesIO(); image.save(stream, format="PNG")
    return stream.getvalue()


def layer():
    return {"polygons": [[[0, 24, 0, 0, 1], [16, 24, 0, 1, 1], [16, 8, 0, 1, 0], [0, 8, 0, 0, 0]]], "children": {}}


class ConversionTest(unittest.TestCase):
    def test_ground_anchor_and_scale(self):
        self.assertEqual(models.transform([0, 24, 0], models.identity()), [0, 0, 0])
        result = list(models.flatten({"x": 2, "yScale": 0.5, **layer()}))[0]
        self.assertEqual(result[0][0], [-2, 12, 0])

    def test_hierarchy_and_rotation(self):
        parent = {"x": 3, "children": {"child": {"x": 2, **layer()}}}
        self.assertEqual(list(models.flatten(parent))[0][0][0], [-5, 0, 0])
        m = models.part_matrix({"yRot": math.pi / 2})
        self.assertEqual(models.transform([1, 24, 0], m), [-0.0, 0, -1])

    def test_nonfinite_rejected(self):
        with self.assertRaises(ValueError): models.transform([float("nan"), 0, 0], models.identity())

    def test_near_zero_thickness_face_uses_mesh_library_coordinate_system(self):
        polygon = [([-4.001, 10.501, -4.999], [0, 0]), ([4.001, 10.501, -4.999], [1, 0]),
                   ([4.001, 10.501, -5.001], [1, 1]), ([-4.001, 10.501, -5.001], [0, 1])]
        self.assertGreater(models.mesh_normal_squared(polygon), 0, "该面不是数学上的零面积")
        self.assertLess(models.mesh_normal_squared(polygon), 1e-6, "但低于真实网格库的有效阈值")
        self.assertFalse(models.renderable(polygon))
        self.assertTrue(models.renderable(list(models.flatten(layer()))[0]))

    def test_old_fixture_repair_is_idempotent_and_keeps_visible_faces_and_texture(self):
        fixture = Path(__file__).parent.parent / "src/test/resources/regressions/axolotl-0.1.1.bbmodel"
        original = json.loads(fixture.read_text(encoding="utf-8"))
        before = len(original["elements"][0]["faces"])
        textures = json.dumps(original["textures"], sort_keys=True)
        self.assertEqual(models.repair_model(original), 28)
        self.assertEqual(len(original["elements"][0]["faces"]), before - 28)
        self.assertGreater(len(original["elements"][0]["faces"]), 0)
        self.assertEqual(json.dumps(original["textures"], sort_keys=True), textures)
        self.assertEqual(models.repair_model(original), 0)

    def test_png_validation(self):
        self.assertEqual(models.png_size(png()), (16, 16))
        with self.assertRaises(ValueError): models.png_size(b"invalid")

    def test_layer_resolution(self):
        self.assertEqual(models.resolve_layer({"minecraft:pig#main": {}}, "pig"), "minecraft:pig#main")
        with self.assertRaises(KeyError): models.resolve_layer({}, "missing")

    def test_deterministic_mesh_and_uv(self):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as z: z.writestr("assets/minecraft/textures/entity/test.png", png())
        recipe = {"entity": "minecraft:pig", "model": "tt_pig", "when": {},
                  "layers": [{"layer": "pig", "texture": "textures/entity/test.png"}]}
        with zipfile.ZipFile(stream) as z:
            first = models.make_legacy_mesh_model(recipe, {"minecraft:pig#main": layer()}, z)
            second = models.make_legacy_mesh_model(recipe, {"minecraft:pig#main": layer()}, z)
        self.assertEqual(first, second)
        face = first["elements"][0]["faces"]["f0"]
        self.assertEqual(face["uv"]["v0_1"], [16, 16])
        self.assertEqual(first["outliner"][0]["name"], "tongtou_root")

    def test_tint_does_not_change_alpha(self):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w") as z: z.writestr("assets/minecraft/a.png", png())
        with zipfile.ZipFile(stream) as z:
            raw = models.texture_bytes(z, {"texture": "a.png", "tint": [255, 0, 0]})
        self.assertEqual(Image.open(io.BytesIO(raw)).getpixel((0, 0)), (255, 0, 0, 255))

    def test_player_export_refused_and_reported(self):
        with tempfile.TemporaryDirectory() as folder:
            stream = io.BytesIO()
            with zipfile.ZipFile(stream, "w"): pass
            with zipfile.ZipFile(stream) as z:
                report = models.build({}, z, [{"entity": "minecraft:player", "model": "tt_player", "when": {}, "layers": []}], Path(folder))
            self.assertEqual(report["exported"], 0)
            self.assertEqual(len(report["not_exported"]), 1)

    def test_overwrite_protection(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / "model.json"
            models.write_owned(target, b"first")
            models.write_owned(target, b"first")
            with self.assertRaises(FileExistsError): models.write_owned(target, b"other")
            self.assertEqual(target.read_bytes(), b"first")

    def test_minecraft_variant_recipe_uses_actual_layer(self):
        recipes = models.default_recipes(set())
        pig = next(x for x in recipes if x["model"] == "tt_pig_temperate")
        self.assertEqual(pig["layers"][0]["layer"], "pig")
        baby = next(x for x in recipes if x["model"] == "tt_pig_cold_baby")
        self.assertEqual(baby["layers"][0]["layer"], "pig_baby")
        self.assertFalse(any(x["entity"] == "minecraft:player" for x in recipes))

    def test_missing_mobs_have_real_layers_and_actual_sizes(self):
        recipes = models.missing_mob_recipes()
        self.assertEqual(13, len(recipes))
        for entity in ("slime", "magma_cube"):
            group = [r for r in recipes if r["entity"] == "minecraft:" + entity]
            self.assertEqual({"1", "2", "4"}, {r["when"]["size"] for r in group})
            for recipe in group:
                self.assertEqual("empty", recipe["when"]["equipment"])
                self.assertEqual(2 if entity == "slime" else 1, len(recipe["layers"]))
                size = int(recipe["when"]["size"])
                self.assertAlmostEqual(size * (0.999 if entity == "slime" else 1), recipe["layers"][0]["scale"])
        adult = next(r for r in recipes if r["model"] == "tt_drowned")
        baby = next(r for r in recipes if r["model"] == "tt_drowned_baby")
        self.assertEqual("false", adult["when"]["swimming"])
        self.assertEqual("drowned#outer", adult["layers"][1]["layer"])
        self.assertEqual("drowned_baby#outer", baby["layers"][1]["layer"])
        self.assertTrue(baby["layers"][1]["texture"].endswith("drowned_outer_layer_baby.png"))

    def test_scaled_cubes_keep_uv_rotations_and_ground_anchor(self):
        cube = {"from": [-4, 0, -4], "to": [4, 8, 4], "origin": [1, 2, 3], "rotation": [22.5, 0, 0],
                "faces": {"north": {"uv": [1, 2, 3, 4], "texture": 0}}}
        faces = json.dumps(cube["faces"])
        models.transform_elements([cube], 4)
        self.assertEqual([-16, 0, -16], cube["from"])
        self.assertEqual([16, 32, 16], cube["to"])
        self.assertEqual([4, 8, 12], cube["origin"])
        self.assertEqual([22.5, 0, 0], cube["rotation"])
        self.assertEqual(faces, json.dumps(cube["faces"]))
        with self.assertRaises(ValueError): models.transform_elements([], float("nan"))


if __name__ == "__main__":
    unittest.main()
