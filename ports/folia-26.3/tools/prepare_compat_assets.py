"""统一26.3素材的旧客户端表达：世界模型分骨骼、旧手持3D兼容重制、新物品去矩阵字段。"""
import copy
import importlib.util
import itertools
import json
import math
from pathlib import Path
import uuid
import zipfile
import hashlib

ROOT=Path(__file__).resolve().parent.parent
spec=importlib.util.spec_from_file_location('model_math',Path(__file__).with_name('build_models.py'))
M=importlib.util.module_from_spec(spec);spec.loader.exec_module(M)
NAMESPACE=uuid.UUID('d28a88b6-a873-4e58-b758-6cb5e26491da')


def rotation(degrees):
    return M.part_matrix(dict(zip(('xRot','yRot','zRot'),map(math.radians,degrees))))


def point(p,matrix):
    return [sum(matrix[i][j]*p[j] for j in range(3)) for i in range(3)]


def legal(degrees):
    nonzero=[angle for angle in degrees if abs(angle)>1e-6]
    return len(nonzero)<=1 and all(any(abs(angle-valid)<1e-5 for valid in (-45,-22.5,0,22.5,45)) for angle in nonzero)


def classic_model(source):
    model=copy.deepcopy(source)
    elements={e['uuid']:e for e in model['elements']}
    def convert(children):
        out=[]
        for child in children:
            if isinstance(child,dict):
                child['children']=convert(child.get('children',[]));out.append(child);continue
            cube=elements[child]
            if cube.get('type','cube')!='cube':raise ValueError('不能以平均色mesh代替旧版纹理模型')
            degrees=cube.get('rotation',[0,0,0])
            if legal(degrees):
                cube['rotation']=[min((-45,-22.5,0,22.5,45),key=lambda allowed:abs(value-allowed)) for value in degrees]
                out.append(child);continue
            origin=list(cube.get('origin',[0,0,0]));cube['rotation']=[0,0,0]
            out.append({'name':'compat_'+str(len(out))+'_'+child[:8],'origin':origin,'rotation':degrees,
                        'uuid':str(uuid.uuid5(NAMESPACE,child)), 'children':[child]})
        return out
    model['outliner']=convert(model['outliner'])
    model['friendswine_compat']={'format':'classic_textured_cube_bones','source':'26.3'}
    return model


def classic_item(source):
    output=[]
    directions={'west':[-1,0,0],'east':[1,0,0],'down':[0,-1,0],'up':[0,1,0],'north':[0,0,-1],'south':[0,0,1]}
    for cube in source['elements']:
        origin=cube.get('origin',[0,0,0]);actual=rotation(cube.get('rotation',[0,0,0]))
        corners=[]
        for bits in itertools.product((0,1),repeat=3):
            raw=[cube['to' if bit else 'from'][i]-origin[i] for i,bit in enumerate(bits)]
            corners.append(point(raw,actual))
        best=None
        for axis in range(3):
            for angle in (-45,-22.5,0,22.5,45):
                degrees=[0,0,0];degrees[axis]=angle;candidate=rotation(degrees)
                inverse=[[candidate[j][i] for j in range(4)] for i in range(4)]
                local=[point(p,inverse) for p in corners]
                low=[min(p[i] for p in local) for i in range(3)];high=[max(p[i] for p in local) for i in range(3)]
                volume=math.prod(max(1e-7,high[i]-low[i]) for i in range(3))
                if best is None or volume<best[0]-1e-8:best=(volume,axis,angle,low,high,inverse)
        _,axis,angle,low,high,inverse=best
        shift=[8,0,8]
        element={'from':[round(low[i]+origin[i]+shift[i],7) for i in range(3)],
                 'to':[round(high[i]+origin[i]+shift[i],7) for i in range(3)],'faces':{}}
        if angle:element['rotation']={'origin':[origin[i]+shift[i] for i in range(3)],'axis':'xyz'[axis],'angle':angle,'rescale':False}
        names=list(directions)
        normals={name:point(point(vector,actual),inverse) for name,vector in directions.items()}
        # 重制手持外形允许几何细节差异，但始终使用原PNG及逐面UV，不取三角形平均色。
        assignment=max(itertools.permutations(names),key=lambda perm:sum(sum(normals[a][i]*directions[b][i] for i in range(3)) for a,b in zip(names,perm)))
        for name,target in zip(names,assignment):
            face=cube['faces'].get(name)
            if face is None or face.get('texture') is None:continue
            value={'uv':[v/4 for v in face['uv']],'texture':'#skin'}
            if face.get('rotation'):value['rotation']=face['rotation']
            element['faces'][target]=value
        output.append(element)
    return {'textures':{'skin':'tongtou:entity/doll','particle':'tongtou:entity/doll'},'elements':output,
            'display':{'gui':{'rotation':[20,-30,0],'scale':[0.9,0.9,0.9]},'ground':{'scale':[0.5,0.5,0.5]},
                       'firstperson_righthand':{'scale':[0.7,0.7,0.7]},'firstperson_lefthand':{'scale':[0.7,0.7,0.7]},
                       'thirdperson_righthand':{'scale':[0.6,0.6,0.6]},'thirdperson_lefthand':{'scale':[0.6,0.6,0.6]}}}


def modern_item(source,files):
    entries=[]
    contexts=('gui','ground','fixed','head','firstperson_righthand','firstperson_lefthand','thirdperson_righthand','thirdperson_lefthand')
    for index,cube in enumerate(source['elements']):
        matrix=rotation(cube.get('rotation',[0,0,0]));origin=[cube.get('origin',[0,0,0])[i]/16-(0.5 if i==1 else 0) for i in range(3)]
        translated=[16*(origin[i]-point(origin,matrix)[i]) for i in range(3)]
        y=math.asin(max(-1,min(1,matrix[0][2])))
        if abs(math.cos(y))>1e-7:x=math.atan2(-matrix[1][2],matrix[2][2]);z=math.atan2(-matrix[0][1],matrix[0][0])
        else:x=math.atan2(matrix[2][1],matrix[1][1]);z=0
        display={'rotation':[math.degrees(x),math.degrees(y),math.degrees(z)],'translation':translated}
        shift=[8,0,8];element={'from':[cube['from'][i]+shift[i] for i in range(3)],'to':[cube['to'][i]+shift[i] for i in range(3)],'faces':{}}
        for name,face in cube['faces'].items():
            if face.get('texture') is None:continue
            value={'uv':[v/4 for v in face['uv']],'texture':'#skin'}
            if face.get('rotation'):value['rotation']=face['rotation']
            element['faces'][name]=value
        displays={key:copy.deepcopy(display) for key in contexts}
        for key in ('firstperson_lefthand','thirdperson_lefthand'):
            displays[key]['rotation'][1]*=-1;displays[key]['rotation'][2]*=-1;displays[key]['translation'][0]*=-1
        payload={'textures':{'skin':'tongtou:entity/doll','particle':'tongtou:entity/doll'},'elements':[element],'display':displays}
        path=f'ce/resourcepack/assets/tongtou/models/doll/cube_{index}.json';files[path]=encode(payload)
        entries.append({'type':'minecraft:model','model':f'tongtou:doll/cube_{index}'})
    files['ce/resourcepack/assets/tongtou/items/doll.json']=encode({'model':{'type':'minecraft:composite','models':entries}})


def encode(value):return json.dumps(value,ensure_ascii=False,separators=(',',':')).encode('utf-8')


def archive_bytes(files):
    import io
    manifest={'schema':1,'source_minecraft':'26.3','files':[{'path':n,'size':len(b),'sha256':hashlib.sha256(b).hexdigest()} for n,b in sorted(files.items())]}
    stream=io.BytesIO()
    with zipfile.ZipFile(stream,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for n,b in sorted({**files,'index.json':encode(manifest)}.items()):
            info=zipfile.ZipInfo(n,(2026,10,19,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,b)
    return stream.getvalue()


def main():
    import argparse
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input',type=Path,default=ROOT/'content/builtin-26.3.zip')
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    path=args.input
    output=args.output
    if output.exists() or output.is_symlink():raise FileExistsError('保留已有资源归档，请选择全新输出路径：'+str(output))
    with zipfile.ZipFile(path) as z:files={n:z.read(n) for n in z.namelist() if n!='index.json' and not n.startswith('compat/')}
    classics={n:encode(classic_model(json.loads(b))) for n,b in files.items() if n.startswith('models/')}
    doll=json.loads(files['models/tt_doll.bbmodel']);items={'ce/resourcepack/assets/tongtou/models/item/doll_legacy.json':encode(classic_item(doll))}
    modern={};modern_item(doll,modern)
    files['compat/classic-models.zip']=archive_bytes(classics)
    files['compat/classic-items.zip']=archive_bytes(items)
    files['compat/modern-items.zip']=archive_bytes(modern)
    index={'schema':1,'minecraft':'26.3','models':sum(n.startswith('models/') for n in files),'vanilla_models':len(json.loads(files['catalog.json'])['models']),
           'files':[{'path':n,'size':len(b),'sha256':hashlib.sha256(b).hexdigest()} for n,b in sorted(files.items())]}
    files['index.json']=encode(index)
    output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(output,'x',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for n,b in sorted(files.items()):
            info=zipfile.ZipInfo(n,(2026,10,19,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,b)
    print(json.dumps({'classic_models':len(classics),'classic_item_cubes':len(doll['elements']),'output':str(output),'bytes':output.stat().st_size}))

if __name__=='__main__':main()
