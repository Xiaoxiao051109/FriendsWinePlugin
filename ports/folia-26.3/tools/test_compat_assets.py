"""兼容资源静态回归；只读取数据，不执行BetterModel或游戏代码。"""
import importlib.util
import io
import itertools
import json
import math
from pathlib import Path
import unittest
import zipfile

ROOT=Path(__file__).resolve().parent.parent
spec=importlib.util.spec_from_file_location('compat',Path(__file__).with_name('prepare_compat_assets.py'))
C=importlib.util.module_from_spec(spec);spec.loader.exec_module(C)


def translated(origin):
    m=C.M.identity()
    for i in range(3):m[i][3]=origin[i]
    return m


def apply(matrix,point):
    p=[*point,1]
    return [sum(matrix[i][j]*p[j] for j in range(4)) for i in range(3)]


def placements(model):
    cubes={e['uuid']:e for e in model['elements']};result={}
    def walk(children,parent):
        for node in children:
            if isinstance(node,str):result[node]=(cubes[node],parent);continue
            origin=node.get('origin',[0,0,0]);rotation=C.rotation(node.get('rotation',[0,0,0]))
            matrix=C.M.matrix_multiply(translated(origin),C.M.matrix_multiply(rotation,translated([-v for v in origin])))
            walk(node.get('children',[]),C.M.matrix_multiply(parent,matrix))
    walk(model['outliner'],C.M.identity());return result


class CompatAssetsTest(unittest.TestCase):
    def test_every_classic_cube_keeps_world_geometry_faces_and_png(self):
        with zipfile.ZipFile(ROOT/'content/builtin-1.1.0.zip') as base:
            with zipfile.ZipFile(io.BytesIO(base.read('compat/classic-models.zip'))) as old:
                count=0
                for name in old.namelist():
                    if not name.startswith('models/'):continue
                    source=json.loads(base.read(name));target=json.loads(old.read(name))
                    self.assertEqual(source['textures'],target['textures'],name)
                    before,after=placements(source),placements(target);self.assertEqual(before.keys(),after.keys())
                    for key,(cube,matrix) in before.items():
                        replacement,transform=after[key];self.assertTrue(C.legal(replacement.get('rotation',[0,0,0])),name)
                        self.assertEqual(cube['faces'],replacement['faces'],name)
                        origin=cube.get('origin',[0,0,0]);next_origin=replacement.get('origin',[0,0,0])
                        for bits in itertools.product((0,1),repeat=3):
                            p=[cube['to' if bit else 'from'][i] for i,bit in enumerate(bits)]
                            q=[replacement['to' if bit else 'from'][i] for i,bit in enumerate(bits)]
                            p=C.point([p[i]-origin[i] for i in range(3)],C.rotation(cube.get('rotation',[0,0,0])))
                            q=C.point([q[i]-next_origin[i] for i in range(3)],C.rotation(replacement.get('rotation',[0,0,0])))
                            p=apply(matrix,[p[i]+origin[i] for i in range(3)]);q=apply(transform,[q[i]+next_origin[i] for i in range(3)])
                            for a,b in zip(p,q):self.assertAlmostEqual(a,b,places=5,msg=name)
                        count+=1
                self.assertGreater(count,8605)

    def test_classic_hand_is_actual_3d_and_all_rotations_are_legal(self):
        with zipfile.ZipFile(ROOT/'content/builtin-1.1.0.zip') as z:
            with zipfile.ZipFile(io.BytesIO(z.read('compat/classic-items.zip'))) as classic:
                model=json.loads(classic.read('ce/resourcepack/assets/tongtou/models/item/doll_legacy.json'))
        self.assertEqual(51,len(model['elements']));self.assertNotEqual('minecraft:item/generated',model.get('parent'))
        for cube in model['elements']:
            self.assertTrue(cube['faces'])
            if 'rotation' in cube:self.assertIn(cube['rotation']['angle'],(-45,-22.5,0,22.5,45))
            for axis in range(3):self.assertLessEqual(cube['from'][axis],cube['to'][axis])
            self.assertTrue(all(-16<=v<=32 for key in ('from','to') for v in cube[key]))

    def test_modern_item_display_transforms_match_source_matrices(self):
        with zipfile.ZipFile(ROOT/'content/builtin-1.1.0.zip') as z:
            source=json.loads(z.read('models/tt_doll.bbmodel'))
            with zipfile.ZipFile(io.BytesIO(z.read('compat/modern-items.zip'))) as variant:
                item=json.loads(variant.read('ce/resourcepack/assets/tongtou/items/doll.json'))
                self.assertEqual(51,len(item['model']['models']))
                for index,cube in enumerate(source['elements']):
                    model=json.loads(variant.read(f'ce/resourcepack/assets/tongtou/models/doll/cube_{index}.json'))
                    self.assertNotIn('transformation',item['model']['models'][index])
                    display=model['display']['fixed'];x,y,z=display['rotation']
                    matrix=C.M.matrix_multiply(C.M.matrix_multiply(C.rotation([x,0,0]),C.rotation([0,y,0])),C.rotation([0,0,z]))
                    expected=C.rotation(cube.get('rotation',[0,0,0]))
                    for i in range(3):
                        for j in range(3):self.assertAlmostEqual(expected[i][j],matrix[i][j],places=6)
                    origin=[cube.get('origin',[0,0,0])[i]/16-(0.5 if i==1 else 0) for i in range(3)]
                    for i in range(3):self.assertAlmostEqual(origin[i]-C.point(origin,expected)[i],display['translation'][i]/16,places=6)

    def test_all_variants_have_exact_hashes_and_unified_source(self):
        import hashlib
        with zipfile.ZipFile(ROOT/'content/builtin-1.1.0.zip') as z:
            for name in ('classic-models','classic-items','modern-items'):
                with zipfile.ZipFile(io.BytesIO(z.read('compat/'+name+'.zip'))) as variant:
                    index=json.loads(variant.read('index.json'));self.assertEqual('26.3',index['source_minecraft'])
                    for f in index['files']:
                        data=variant.read(f['path']);self.assertEqual(f['size'],len(data));self.assertEqual(f['sha256'],hashlib.sha256(data).hexdigest())

if __name__=='__main__':unittest.main()
