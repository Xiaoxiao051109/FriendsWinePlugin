"""逐页验证偏移音乐、玩偶逐面纹理及复合物品变换，不运行游戏客户端。"""
import base64
import importlib.util
import itertools
import json
from pathlib import Path
import struct
import unittest
import zipfile

ROOT = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('party_assets', Path(__file__).with_name('prepare_party_assets.py'))
assets = importlib.util.module_from_spec(spec)
spec.loader.exec_module(assets)

class PartyAssetsTest(unittest.TestCase):
    def test_doll_model_keeps_all_cubes_faces_and_png_without_auto_animation(self):
        original = json.loads((ROOT.parent.parent/'assets/source/doll.bbmodel').read_text(encoding='utf-8'))
        with zipfile.ZipFile(ROOT/'content/builtin-1.1.0.zip') as archive:
            model = json.loads(archive.read('models/tt_doll.bbmodel'))
            self.assertEqual(original['elements'], model['elements'])
            self.assertEqual(original['textures'], model['textures'])
            self.assertEqual([], model['animations'])
            self.assertEqual([c['uuid'] for c in model['elements']], model['outliner'][0]['children'])
            self.assertEqual([0,0,1], model['outliner'][0]['origin'])
            self.assertEqual(base64.b64decode(original['textures'][0]['source'].split(',',1)[1]), archive.read('ce/resourcepack/assets/tongtou/textures/entity/doll.png'))
            composite = json.loads(archive.read('ce/resourcepack/assets/tongtou/items/doll.json'))['model']
            self.assertEqual('minecraft:composite',composite['type']);self.assertEqual(51,len(composite['models']))
            for index,(cube,entry) in enumerate(zip(original['elements'],composite['models'])):
                payload=json.loads(archive.read(f'ce/resourcepack/assets/tongtou/models/doll/cube_{index}.json'))
                element=payload['elements'][0];matrix=entry['transformation']
                self.assertEqual(16,len(matrix))
                for direction,face in cube['faces'].items():
                    self.assertEqual(face['uv'],[x*4 for x in element['faces'][direction]['uv']])
                    self.assertEqual(face.get('rotation',0),element['faces'][direction].get('rotation',0))
                for bits in itertools.product((0,1),repeat=3):
                    point=[cube['to' if bit else 'from'][axis] for axis,bit in enumerate(bits)]
                    origin=cube.get('origin',[0,0,0]);rotation=cube.get('rotation',[0,0,0])
                    expected=assets.MATH.rotate_point([point[i]-origin[i] for i in range(3)],rotation)
                    expected=[(expected[i]+origin[i])/16-(0.5 if i==1 else 0) for i in range(3)]
                    local=[point[i]/16-(0.5 if i==1 else 0) for i in range(3)]+[1]
                    actual=[sum(matrix[i*4+j]*local[j] for j in range(4)) for i in range(3)]
                    for a,b in zip(actual,expected):self.assertAlmostEqual(a,b,places=6)

    def test_all_audio_offsets_have_valid_crc_same_packets_and_correct_sample_clock(self):
        original=(ROOT.parent.parent/'mod/src/main/resources/assets/friendswine/sounds/doll_music.ogg').read_bytes()
        source=assets.ogg_pages(original)
        with zipfile.ZipFile(ROOT/'content/builtin-1.1.0.zip') as archive:
            manifest=json.loads(archive.read('music.json'));last=-1;max_gap=0
            sounds=json.loads(archive.read('ce/resourcepack/assets/tongtou/sounds.json'))
            for offset in manifest['offsets']:
                number=offset['id'];pages=assets.ogg_pages(archive.read(f'ce/resourcepack/assets/tongtou/sounds/party/music_{number}.ogg'))
                baseline=struct.unpack_from('<q',source[number+1],6)[0]
                self.assertEqual(offset['offset_samples'],baseline);self.assertEqual(2482560-baseline,offset['remaining_samples'])
                self.assertGreater(baseline,last)
                if last>=0:max_gap=max(max_gap,baseline-last)
                last=baseline;self.assertTrue(pages[-1][5]&4)
                for seq,(page,old) in enumerate(zip(pages,[*source[:2],*source[number+2:]])):
                    self.assertEqual(seq,struct.unpack_from('<I',page,18)[0])
                    self.assertEqual(old[27:],page[27:])
                    self.assertEqual(max(0,struct.unpack_from('<q',old,6)[0]-(baseline if seq>=2 else 0)),struct.unpack_from('<q',page,6)[0])
                for slot in range(4):self.assertEqual(f'tongtou:party/music_{number}',sounds[f'doll_music_{slot}_{number}']['sounds'][0]['name'])
            self.assertLess(max(max_gap,2482560-last)/44100,1.1)
            self.assertEqual(56,len(manifest['offsets']));self.assertEqual(224,len(sounds))

    def test_function_models_are_separate_from_vanilla_catalog(self):
        with zipfile.ZipFile(ROOT/'content/builtin-1.1.0.zip') as archive:
            self.assertEqual(['tt_doll'],json.loads(archive.read('feature-models.json'))['models'])
            catalog=json.loads(archive.read('catalog.json'))['models']
            self.assertEqual(822,len(catalog));self.assertNotIn('tt_doll',[m['model'] for m in catalog])

if __name__=='__main__':unittest.main()
