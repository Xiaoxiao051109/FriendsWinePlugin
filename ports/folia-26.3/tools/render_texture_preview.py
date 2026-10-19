"""离线纹理预览，不启动游戏；用于检查PNG/UV，不声称是Minecraft截图。"""
import argparse
import base64
import importlib.util
import io
import json
import math
from pathlib import Path
import zipfile
from PIL import Image, ImageDraw, ImageFont

spec = importlib.util.spec_from_file_location("model_math", Path(__file__).with_name("build_models.py"))
model_math = importlib.util.module_from_spec(spec)
spec.loader.exec_module(model_math)


def dot(a, b): return sum(x * y for x, y in zip(a, b))
def cross(a, b): return [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]]
def unit(a): return [v / math.sqrt(dot(a, a)) for v in a]


def render(model, size=280):
    image = Image.new("RGB", (size, size), (235, 237, 240))
    textures = [Image.open(io.BytesIO(base64.b64decode(t["source"].split(",", 1)[1]))).convert("RGBA") for t in model["textures"]]
    direction = unit([0.7, 0.45, -1.0]); right = unit([1.0, 0, 0.7]); up = cross(right, direction)
    faces = []
    for cube in model["elements"]:
        for name, face in cube["faces"].items():
            positions = model_math.cube_world_points(cube, name)
            uv = model_math.face_uv_corners(face)
            normal = cross([positions[1][i]-positions[0][i] for i in range(3)], [positions[2][i]-positions[0][i] for i in range(3)])
            if dot(normal, direction) <= 1e-8: continue
            projected = [[dot(p, right), -dot(p, up), dot(p, direction)] for p in positions]
            faces.append((projected, uv, textures[face["texture"]]))
    coordinates = [p for face, uv, tex in faces for p in face]
    x0, x1 = min(p[0] for p in coordinates), max(p[0] for p in coordinates)
    y0, y1 = min(p[1] for p in coordinates), max(p[1] for p in coordinates)
    scale = (size - 35) / max(x1-x0, y1-y0)
    depth = [[-math.inf] * size for _ in range(size)]
    pixels = image.load()
    for positions, uv, texture in faces:
        vertices = [[(p[0]-(x0+x1)/2)*scale+size/2, (p[1]-(y0+y1)/2)*scale+size/2, p[2]] for p in positions]
        for triangle in ((0, 1, 2), (0, 2, 3)):
            a, b, c = [vertices[i] for i in triangle]
            denominator = (b[1]-c[1])*(a[0]-c[0]) + (c[0]-b[0])*(a[1]-c[1])
            if abs(denominator) < 1e-8: continue
            for y in range(max(0, int(min(a[1], b[1], c[1]))), min(size-1, math.ceil(max(a[1], b[1], c[1])))+1):
                for x in range(max(0, int(min(a[0], b[0], c[0]))), min(size-1, math.ceil(max(a[0], b[0], c[0])))+1):
                    w0 = ((b[1]-c[1])*(x+0.5-c[0])+(c[0]-b[0])*(y+0.5-c[1]))/denominator
                    w1 = ((c[1]-a[1])*(x+0.5-c[0])+(a[0]-c[0])*(y+0.5-c[1]))/denominator
                    weights = (w0, w1, 1-w0-w1)
                    if min(weights) < -1e-7: continue
                    z = sum(weights[j]*vertices[i][2] for j, i in enumerate(triangle))
                    if z <= depth[y][x]: continue
                    u, v = [sum(weights[j]*uv[i][axis] for j, i in enumerate(triangle)) for axis in (0, 1)]
                    color = texture.getpixel((max(0, min(texture.width-1, int(u))), max(0, min(texture.height-1, int(v)))))
                    if color[3] < 128: continue
                    pixels[x, y] = color[:3]; depth[y][x] = z
    return image


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).parent.parent
    samples = [("tt_pig_temperate", "猪"), ("tt_cow_temperate", "牛"), ("tt_zombie", "僵尸"), ("tt_sheep_white_wool", "羊")]
    result = Image.new("RGB", (1120, 330), (235, 237, 240))
    font = ImageFont.truetype("C:/Windows/Fonts/msyh.ttc", 16)
    draw = ImageDraw.Draw(result)
    draw.text((12, 5), "原PNG与逐面UV离线预览（非游戏截图）", fill=(25, 25, 25), font=font)
    with zipfile.ZipFile(root / "content/builtin-26.3.zip") as archive:
        for index, (model, label) in enumerate(samples):
            result.paste(render(json.loads(archive.read("models/"+model+".bbmodel"))), (index*280, 25))
            draw.text((index*280+125, 304), label, fill=(25, 25, 25), font=font)
    args.output.parent.mkdir(parents=True, exist_ok=True); result.save(args.output)


if __name__ == "__main__": main()
