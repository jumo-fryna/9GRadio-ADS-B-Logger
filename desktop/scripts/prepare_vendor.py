"""Download pinned official driver and public-domain map with SHA-256 checks."""
import hashlib
import io
import json
from pathlib import Path
import shutil
import urllib.request
import zipfile

ROOT=Path(__file__).resolve().parents[1]
VENDORS=[
    ('https://github.com/rtlsdrblog/rtl-sdr-blog/releases/download/V1.4.0/Release.zip',
     '7ef33f1304647f65e5e0fde43637a73d54f076e91e651a3cecc4f55a17fd9815','driver'),
    ('https://github.com/rtlsdrblog/rtl-sdr-blog/archive/refs/tags/V1.4.0.zip',
     'efaac50d53ea2f12d9853167ac9470bf4f04de944f17c5d52d2fd73a9ec24c18','source'),
    ('https://raw.githubusercontent.com/nvkelso/natural-earth-vector/master/geojson/ne_110m_admin_0_countries.geojson',
     '6866c877d39cba9c357620878839b336d569f8c662d3cfab4cb1dbe2d39c977f','map')]


def download(url,digest):
    with urllib.request.urlopen(url,timeout=90) as r:data=r.read()
    actual=hashlib.sha256(data).hexdigest()
    if actual!=digest:raise RuntimeError(f'Checksum mismatch for {url}: {actual}')
    return data


def main():
    for folder in ('drivers','assets','vendor-sources','licenses'):(ROOT/folder).mkdir(exist_ok=True)
    manifest=[]
    for url,digest,kind in VENDORS:
        data=download(url,digest);manifest.append(dict(url=url,sha256=digest))
        if kind=='driver':
            with zipfile.ZipFile(io.BytesIO(data)) as z:
                for name in ('rtlsdr.dll','msvcr100.dll','pthreadVC2.dll'):
                    (ROOT/'drivers'/name).write_bytes(z.read('x64/'+name))
        elif kind=='source':
            (ROOT/'vendor-sources'/'rtl-sdr-blog-V1.4.0.zip').write_bytes(data)
            with zipfile.ZipFile(io.BytesIO(data)) as z:
                name=next(n for n in z.namelist() if n.endswith('/COPYING'))
                (ROOT/'licenses'/'librtlsdr-GPL-2.0.txt').write_bytes(z.read(name))
        else:(ROOT/'assets'/'world.geojson').write_bytes(data)
    (ROOT/'vendor-manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf8')
    # Some wheels include only the commercial alternative notice. Include the
    # applicable open-source license texts from the Qt source tree as well.
    for license_name in ('LGPL-3.0-only','GPL-3.0-only','GPL-2.0-only'):
        url=f'https://raw.githubusercontent.com/qt/qtbase/v6.12.0/LICENSES/{license_name}.txt'
        with urllib.request.urlopen(url,timeout=30) as response:
            (ROOT/'licenses'/f'Qt-{license_name}.txt').write_bytes(response.read())
    # Include the licensing metadata provided by all installed distributions.
    import importlib.metadata as md
    for dist in md.distributions():
        name=dist.metadata['Name']
        if name.lower() in ('pip',):continue
        for f in dist.files or []:
            if any(s in str(f).lower() for s in ('license','copying','notice')) and '.dist-info/' in str(f):
                source=Path(dist.locate_file(f))
                if source.is_file():
                    dest=ROOT/'licenses'/name/Path(str(f).split('.dist-info/',1)[-1])
                    dest.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,dest)
    # Self-contained original icon, drawn from geometry (no remote asset).
    from PIL import Image,ImageDraw
    image=Image.new('RGBA',(256,256),'#0b1522');d=ImageDraw.Draw(image)
    for r in (40,72,104):d.ellipse((128-r,128-r,128+r,128+r),outline='#29475a',width=3)
    d.line((128,24,128,232),fill='#29475a',width=2);d.line((24,128,232,128),fill='#29475a',width=2)
    d.polygon([(128,42),(140,112),(198,146),(198,161),(140,142),(138,187),(154,202),(128,192),(102,202),(118,187),(116,142),(58,161),(58,146),(116,112)],fill='#4fe0c3')
    image.save(ROOT/'assets'/'skylog.ico',sizes=[(16,16),(32,32),(48,48),(64,64),(128,128),(256,256)])
    print('Official V4L x64 driver, verified map, licenses and source archive prepared.')


if __name__=='__main__':main()
