"""使用已有的已接受EULA测试服务端，在全新目录验证单JAR首次安装与资源构建。"""
import argparse
import json
from pathlib import Path
import queue
import shutil
import socket
import struct
import subprocess
import tempfile
import threading
import time
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source-server', type=Path, required=True)
    parser.add_argument('--plugin-jar', type=Path, required=True)
    parser.add_argument('--bettermodel', type=Path, required=True)
    parser.add_argument('--craftengine', type=Path, required=True)
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--output-root', type=Path, required=True)
    args = parser.parse_args()
    if (args.source_server / 'eula.txt').read_text().strip() != 'eula=true':
        raise SystemExit('已有测试服务端尚未接受EULA，本脚本不会代替操作者接受')
    args.output_root.mkdir(parents=True, exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix='single-jar-', dir=args.output_root)).resolve()
    print('隔离测试目录：', run, flush=True)
    for name in ('server.jar', 'eula.txt'):
        shutil.copyfile(args.source_server / name, run / name)
    for name in ('libraries', 'versions', 'cache'):
        if (args.source_server / name).is_dir(): shutil.copytree(args.source_server / name, run / name)
    plugins = run / 'plugins'; plugins.mkdir()
    for jar in (args.plugin_jar, args.bettermodel, args.craftengine): shutil.copyfile(jar, plugins / jar.name)
    for plugin in ('BetterModel', 'CraftEngine'):
        source = args.source_server / 'plugins' / plugin
        for file in source.rglob('*.jar'):
            dest = plugins / plugin / file.relative_to(source)
            dest.parent.mkdir(parents=True, exist_ok=True); shutil.copyfile(file, dest)
    # 仅复制依赖库，不复制模型、资源目录、世界或服务器原有配置。
    for jar, name in ((args.bettermodel, 'BetterModel'), (args.craftengine, 'CraftEngine')):
        with zipfile.ZipFile(jar) as archive: text = archive.read('config.yml').decode('utf-8')
        text = text.replace('metrics: true', 'metrics: false').replace('version-check: true', 'version-check: false')
        text = text.replace('update-checker: true', 'update-checker: false').replace('player-animation: true', 'player-animation: false')
        text = text.replace('ip: "auto"', 'ip: "127.0.0.1"')
        dest = plugins / name / 'config.yml'; dest.parent.mkdir(parents=True, exist_ok=True); dest.write_text(text, encoding='utf-8')
    with socket.socket() as sock: sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
    (run/'server.properties').write_text('\n'.join([
        'server-ip=127.0.0.1', f'server-port={port}', 'online-mode=false', 'enable-status=true',
        'enable-rcon=false', 'enable-query=false', 'view-distance=2', 'simulation-distance=2',
        'max-players=2', 'spawn-protection=0', 'level-name=smoke-world', 'level-type=minecraft:flat',
        'generate-structures=false', 'sync-chunk-writes=true', 'pause-when-empty-seconds=-1',
    ])+'\n', encoding='utf-8')
    process = subprocess.Popen([str(args.java), '-Xms512M', '-Xmx3G', '-jar', 'server.jar', '--nogui'],
                               cwd=run, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               text=True, encoding='utf-8', errors='replace')
    events = queue.Queue(); lines=[]
    def reader():
        with (run/'console.log').open('w',encoding='utf-8') as log:
            for line in process.stdout:
                lines.append(line); log.write(line); log.flush(); events.put(line)
            events.put(None)
    threading.Thread(target=reader, daemon=True).start()
    deadline=time.monotonic()+240; commands=False; ready=False; merged=False; status=False
    try:
        while time.monotonic()<deadline:
            try: line=events.get(timeout=1)
            except queue.Empty:
                if process.poll() is not None: break
                continue
            if line is None: break
            if any(word in line for word in ('FriendsWinePlugin', 'Tongtou', 'ERROR', 'Exception', 'Done (', '内置', '资源状态', '原版外观')):
                print(line.rstrip(),flush=True)
            if 'Done (' in line:
                ready=True
            if '内置模型已自动合入CraftEngine' in line:
                merged=True
            if ready and not commands:
                process.stdin.write('tongtou status\n');process.stdin.flush();commands=True
            if '资源状态：' in line: status=True
            if ready and merged and status: break
        report={'ready':ready,'ce_auto_merge':merged,'command_responded':status,'return_code':None}
    finally:
        if process.poll() is None:
            process.stdin.write('stop\n'); process.stdin.flush()
            try: process.wait(timeout=45)
            except subprocess.TimeoutExpired: process.terminate();process.wait(timeout=15)
    report['return_code']=process.returncode
    models=list((plugins/'BetterModel/models').glob('tt_*.bbmodel'))
    report['installed_models']=len(models)
    report['catalog_installed']=(plugins/'FriendsWinePlugin/catalog.json').is_file()
    report['ce_item_installed']=(plugins/'CraftEngine/resources/tongtou/configuration/items.yml').is_file()
    ce_pack=plugins/'CraftEngine/generated/resource_pack.zip'
    report['ce_pack_exists']=ce_pack.is_file()
    if ce_pack.is_file():
        with zipfile.ZipFile(ce_pack) as z:
            report['ce_pack_entries']=len(z.namelist())
            report['bettermodel_assets']=sum('/bettermodel/' in n for n in z.namelist())
            report['music_offsets']=sum(n.startswith('assets/tongtou/sounds/party/music_') and n.endswith('.ogg') for n in z.namelist())
            report['doll_item_model']='assets/tongtou/items/doll.json' in z.namelist()
            report['remote_item_model']='assets/tongtou/items/remote.json' in z.namelist()
    (run/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2),flush=True)
    if not all([ready,merged,status,len(models)==823,report['catalog_installed'],report['ce_item_installed'],report['ce_pack_exists'],report.get('music_offsets')==56,report.get('doll_item_model'),report.get('remote_item_model')]):
        raise SystemExit('单JAR启动验收未全部通过，详见隔离目录的console.log和report.json')

if __name__=='__main__': main()
