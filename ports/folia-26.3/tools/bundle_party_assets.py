"""把受管理CE配置及功能素材合入资源存档，并重新生成长度和SHA256清单。"""
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parent.parent

def main():
    archive = ROOT / 'content/builtin-26.3.zip'
    with zipfile.ZipFile(archive) as source:
        files = {n: source.read(n) for n in source.namelist() if n != 'index.json' and not n.endswith('/')}
    generated = ROOT / 'build/party-assets'
    for path in generated.rglob('*'):
        if path.is_file(): files[path.relative_to(generated).as_posix()] = path.read_bytes()
    ce = ROOT / 'content/CraftEngine/resources/tongtou'
    for path in ce.rglob('*'):
        if path.is_file(): files['ce/'+path.relative_to(ce).as_posix()] = path.read_bytes()
    models = sum(n.startswith('models/') for n in files)
    catalog = json.loads(files['catalog.json'])
    index = {'schema': 1, 'minecraft': '26.3', 'models': models, 'vanilla_models': len(catalog['models']),
             'files': [{'path': n, 'size': len(data), 'sha256': hashlib.sha256(data).hexdigest()} for n, data in sorted(files.items())]}
    files['index.json'] = json.dumps(index, ensure_ascii=False, separators=(',', ':')).encode()
    temporary = archive.with_suffix('.new.zip')
    with zipfile.ZipFile(temporary, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as target:
        for n, data in sorted(files.items()):
            info = zipfile.ZipInfo(n, (2026, 10, 19, 0, 0, 0)); info.compress_type = zipfile.ZIP_DEFLATED
            target.writestr(info, data)
    temporary.replace(archive)
    print(json.dumps({'files': len(files), 'models': models, 'bytes': archive.stat().st_size}, ensure_ascii=False))

if __name__ == '__main__': main()
