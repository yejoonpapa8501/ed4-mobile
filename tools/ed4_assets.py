"""Read the user's ED4 DOS resources; no game bytes are distributed.

Format evidence: DATA11 tile renderer 0x8621, composition 0x7d3a,
mask conversion 0x7f68; DATA10/0 palette writer 0xdee (B,R,G order).
Falcom BZ bitstream reference: https://github.com/eArmada8/Ys8_IT3/blob/main/lib_falcompress.py
This implementation adds strict bounds and supports chained blocks.
"""
import struct
from pathlib import Path

def u16(b,o):
    if o<0 or o+2>len(b):raise ValueError('Truncated word')
    return struct.unpack_from('<H',b,o)[0]

def unpack_block(b):
    if len(b)<2 or b[0]!=0:raise ValueError('Unsupported compression')
    pos=2; flags=b[1]; remaining=8; out=bytearray()
    def byte():
        nonlocal pos
        if pos>=len(b):raise ValueError('Truncated stream')
        v=b[pos];pos+=1;return v
    def bit():
        nonlocal flags,remaining
        if remaining==0:flags=byte()|(byte()<<8);remaining=16
        v=flags&1;flags>>=1;remaining-=1;return v
    def bits(n):
        v=0
        for _ in range(n):v=(v<<1)|bit()
        return v
    while len(out)<1048576:
        if bit()==0:out.append(byte());continue
        if bit()==0:distance=byte()
        else:
            distance=(bits(5)<<8)|byte()
            if distance==0:return bytes(out)
            if distance<=2:
                long=bit();length=bits(4)
                if long:length=(length<<8)|byte()
                out.extend(bytes([byte()])*(length+14));continue
        length=2
        while length<6 and bit()==0:length+=1
        if length==6:length=bits(3)+6 if bit() else byte()+14
        if distance==0 or distance>len(out):raise ValueError('Invalid back-reference')
        for _ in range(length):out.append(out[-distance])
    raise ValueError('Decompressed size limit')

def unpack_entry(b):
    result=bytearray();pos=0
    while True:
        size=u16(b,pos)
        if size<4 or pos+size>=len(b):raise ValueError('Invalid block size')
        result.extend(unpack_block(b[pos+2:pos+size]));pos+=size
        marker=b[pos];pos+=1
        if marker==0:return bytes(result)
        if marker!=1:raise ValueError('Invalid continuation marker')

def archive(path):
    b=Path(path).read_bytes()
    if b[:9]!=b'AFLB DAT\x1a':raise ValueError('Invalid archive')
    n=u16(b,10);offsets=[min(len(b),u16(b,16+i*2)*32) for i in range(n+1)]
    if offsets[0]<18+2*n or any(a>z for a,z in zip(offsets,offsets[1:])) or offsets[-1]>len(b):raise ValueError('Invalid resource table')
    return [b[a:z] for a,z in zip(offsets,offsets[1:])]

def palette(engine,gfx):
    base=u16(engine,0xffa4)*16
    raw=bytearray(engine[base+0x1ef6:base+0x1ef6+48])
    if len(raw)!=48 or max(raw)>15:raise ValueError('Unsupported palette layout')
    if len(gfx)>=32774:raw[36:42]=gfx[32768:32774]
    def dac(v):return round((v*4+3)*255/63) if v else 0
    return [tuple(dac(raw[i+j]) for j in (1,2,0)) for i in range(0,48,3)]

def tiles(gfx):
    return [bytes(sum(((gfx[n*128+p*32+y*2+x//8]>>(7-x%8))&1)<<p for p in range(4)) for y in range(16) for x in range(16)) for n in range(len(gfx)//128)]

def compose(gfx,defs):
    source=tiles(gfx); result=[]
    for i in range(len(defs)//16):
        dest=bytearray(256)
        for index in defs[i*16:i*16+14]:
            if index:
                for j,v in enumerate(source[index]):
                    if v!=15:dest[j]=v
        result.append(bytes(dest))
    return source,result

def render_scene(root,scene=0):
    from PIL import Image
    a=archive(Path(root)/'DATA_A.DAT');meta=unpack_entry(a[scene]);mi,gi,ci=(u16(meta,o)&4095 for o in [0,4,6])
    grid=unpack_entry(a[mi]);gfx=unpack_entry(a[gi]);defs=unpack_entry(a[ci]);engine=unpack_entry(archive(Path(root)/'DATA11.DAT')[0]);pal=palette(engine,gfx)
    source,combined=compose(gfx,defs)
    ims=[]
    for tile in source+combined:
        im=Image.new('RGB',(16,16));im.putdata([pal[v] for v in tile]);ims.append(im)
    out=Image.new('RGB',(2048,len(grid)//256*16))
    missing=0
    for i in range(len(grid)//2):
        v=u16(grid,i*2);index=v>>6
        if index>=512:im=ims[len(source)+index-512]
        elif index<256:im=ims[index]
        else:missing+=1;continue
        out.paste(im,(i%128*16,i//128*16))
    return out,missing

if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser();p.add_argument('game_directory');p.add_argument('output');p.add_argument('--scene',type=int,default=0);args=p.parse_args()
    image,missing=render_scene(args.game_directory,args.scene);image.save(args.output);print('Rendered scene; unsupported dynamic cells:',missing)
