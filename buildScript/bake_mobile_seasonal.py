"""Pack approved transparent APNG masters into small lossless Android sprite pages.

Requires Pillow only when refreshing artwork; APK builds use the committed WebP pages.
Usage: python buildScript/bake_mobile_seasonal.py ../mobile153-artwork
"""
from pathlib import Path
import hashlib
import json
import sys
from PIL import Image, ImageChops

ROOT = Path(__file__).resolve().parent.parent
DEST = ROOT / 'app/src/main/assets/seasonal'
THEMES = ('halloween', 'newyear', 'easter', 'kupala')
LABELS = ('Осень', 'Зима', 'Весна', 'Лето')
COLUMNS, PAGE_FRAMES, INTERVAL = 4, 16, 50


def bake(source):
    approved = json.loads((source / 'current-manifest.json').read_text(encoding='utf-8'))
    assert set(approved) == set(THEMES), 'Review changed desktop selection first'
    manifest = {}
    previous = json.loads((DEST / 'manifest.json').read_text(encoding='utf-8'))
    for theme, label in zip(THEMES, LABELS):
        master = source / 'animations' / approved[theme]['wide']['transparent_master']
        files, frames, elapsed = [], 0, 0
        with Image.open(master) as animation:
            assert animation.size == (544, 184) and animation.info.get('loop') == 0
            atlas = None
            for index in range(animation.n_frames):
                animation.seek(index)
                duration = round(animation.info['duration'])
                assert duration > 0 and duration % INTERVAL == 0
                frame = animation.convert('RGBA')
                bounds = frame.getchannel('A').getbbox()
                assert bounds and bounds[0]>0 and bounds[1]>0 and bounds[2]<544 and bounds[3]<184
                for _ in range(duration // INTERVAL):
                    slot = frames % PAGE_FRAMES
                    if slot == 0:
                        atlas = Image.new('RGBA', (544 * COLUMNS, 184 * (PAGE_FRAMES // COLUMNS)))
                    position = ((slot % COLUMNS) * 544, (slot // COLUMNS) * 184)
                    atlas.paste(frame, position)
                    assert ImageChops.difference(atlas.crop((position[0], position[1], position[0]+544, position[1]+184)), frame).getbbox() is None
                    frames += 1
                    if frames % PAGE_FRAMES == 0:
                        name = f'{theme}-wide-{len(files):03d}.webp'
                        atlas.save(DEST / name, lossless=True, method=4, exact=True)
                        files.append(name)
                        atlas = None
                elapsed += duration
            if atlas is not None:
                name = f'{theme}-wide-{len(files):03d}.webp'
                atlas.save(DEST / name, lossless=True, method=4, exact=True)
                files.append(name)
        assert elapsed == approved[theme]['wide']['duration_ms'] == frames * INTERVAL
        manifest[theme] = dict(files=files, width=544, height=184, columns=COLUMNS,
                               page_frames=PAGE_FRAMES, frames=frames, interval=INTERVAL,
                               label_ru=label, source_sha256=hashlib.sha256(master.read_bytes()).hexdigest())
        print(f'{theme}: {frames} frames, {len(files)} pages, {elapsed} ms', flush=True)
    (DEST / 'manifest.json').write_text(json.dumps(manifest, indent=2, ensure_ascii=False)+'\n', encoding='utf-8')
    # Remove only obsolete files explicitly referenced by the previous manifest.
    keep = {name for entry in manifest.values() for name in entry['files']}
    for entry in previous.values():
        for name in entry.get('files', [entry.get('file')])+[entry.get('base')]:
            if name and name not in keep:
                assert Path(name).name == name
                (DEST / name).unlink(missing_ok=True)


if __name__ == '__main__':
    bake(Path(sys.argv[1]).resolve())
