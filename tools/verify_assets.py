"""Validate parsed maps against known properties of the user-supplied DOS build.
Usage: python tools/verify_assets.py /path/to/extracted/ed4
"""
import sys, hashlib
from pathlib import Path
from ed4_assets import archive, unpack_entry, render_scene
root=Path(sys.argv[1]); total=0; rejected=0
for p in sorted(root.glob('DATA*.DAT')):
    for entry in archive(p):
        try:
            data=unpack_entry(entry); total+=1
        except ValueError: rejected+=1
assert total==1966,(total,rejected)
assert rejected==68,(total,rejected)
for scene in [0,4,10,14,15,21,26,30,34,39,43,47,51,55]:
    image,missing=render_scene(root,scene)
    assert image.size==(2048,1280)
    assert missing==0
print(f'{total} compressed resources; {rejected} raw entries; 14 maps validated')
