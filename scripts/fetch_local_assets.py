"""Fetch the same public assets as upstream CI, without unsafe archive extraction."""
import io
import pathlib
import tarfile
import urllib.request
import re

assets = pathlib.Path('app/src/main/assets')
def fetch(url):
    with urllib.request.urlopen(url, timeout=120) as response:
        return response.read()

def unpack(url, destination):
    with tarfile.open(fileobj=io.BytesIO(fetch(url)), mode='r:gz') as archive:
        for member in archive:
            parts = pathlib.PurePosixPath(member.name).parts[1:]
            if not parts or any(p in ('..', '.') or p.startswith('.') for p in parts):
                continue
            if not member.isfile():
                continue
            target = destination.joinpath(*parts)
            if not target.resolve().is_relative_to(destination.resolve()):
                raise ValueError('Unsafe archive path')
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.extractfile(member).read())

routes = assets / 'routeprofiles'
routes.mkdir(parents=True, exist_ok=True)
(routes / 'srslist.h').write_bytes(fetch('https://raw.githubusercontent.com/throneproj/routeprofiles/rule-set/srslist.h'))
catalog = dict(re.findall(r'\{\s*"([^"]+)"\s*,\s*"([^"]+)"\s*\}', (routes / 'srslist.h').read_text()))
seeds = assets / 'onlineseti-rules'
seeds.mkdir(parents=True, exist_ok=True)
for tag in ['geoip-ru', 'geosite-ru', 'geosite-category-ru', 'geosite-ru-available-only-inside']:
    data = fetch(catalog[tag])
    if not data.startswith(b'SRS') or len(data) < 10:
        raise ValueError('Invalid bundled rule set: ' + tag)
    (seeds / (tag + '.srs')).write_bytes(data)
unpack('https://codeload.github.com/throneproj/routeprofiles/tar.gz/refs/heads/profile', routes / 'profile')
dashboard = assets / 'sb-dashboard'
unpack('https://codeload.github.com/SagerNet/sing-box-dashboard/tar.gz/refs/heads/gh-pages', dashboard)
assert (dashboard / 'index.html').stat().st_size > 0
assert not any(p.is_dir() and p.name.startswith('_') for p in dashboard.rglob('*'))
print('PUBLIC_ASSETS_READY')
