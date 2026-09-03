# -*- coding: utf-8 -*-
"""Before/after animated WebP for InputPatch README (replaces the long filmstrips).
   Two runs side by side (A=original, B=patched), pixel-exact 2x, frame counter, optional HIT marker.
   usage: anim.py out.webp dirA dirB labelA labelB x0,y0,x1,y1 [--hit a,b] [--delay ms] [--hold ms] [--stride n] [--scale n]
"""
import sys, glob, os
from PIL import Image, ImageDraw, ImageFont
a=sys.argv[1:]; out,dA,dB,lA,lB,crop=a[:6]; opt=dict(zip(a[6::2],a[7::2]))
x0,y0,x1,y1=map(int,crop.split(','))
S=int(opt.get('--scale',2)); DELAY=int(opt.get('--delay',66)); HOLD=int(opt.get('--hold',1400)); STRIDE=int(opt.get('--stride',1))
hit=[int(v) for v in opt['--hit'].split(',')] if '--hit' in opt else [None,None]
FONT=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',13)
FONT2=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf',13)
FONTBIG=ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf',26)
def frames(d):
    fs=sorted(p for p in glob.glob(os.path.join(d,'*.ppm')) if not p.endswith('_end.ppm'))
    return [Image.open(p).convert('RGB').crop((x0,y0,x1,y1)) for p in fs]
A=frames(dA); B=frames(dB); n=max(len(A),len(B))
A+= [A[-1]]*(n-len(A)); B+= [B[-1]]*(n-len(B))
w,h=A[0].size; W,H=w*S,h*S; BAR=20; GAP=6
BG=(24,26,30); FG=(235,235,235); RED=(255,80,60); GRN=(120,220,120)
def panel(img,label,i,hitf):
    p=Image.new('RGB',(W,H+BAR),BG); p.paste(img.resize((W,H),Image.NEAREST),(0,BAR))
    d=ImageDraw.Draw(p); d.text((4,3),label,fill=FG,font=FONT)
    t='f%02d'%i; tw=d.textlength(t,font=FONT2); d.text((W-tw-4,3),t,fill=FG,font=FONT2)
    if hitf is not None and i>=hitf:
        d.text((W-tw-4-44,3),'HIT',fill=RED,font=FONT)
        if i<hitf+4:   # flash: thick red frame around the panel for 4 frames after contact
            for k in range(4): d.rectangle((k,BAR+k,W-1-k,H+BAR-1-k),outline=RED)
            d.text((6,BAR+6),'HIT f%02d'%hitf,fill=RED,font=FONTBIG)
    return p
seq=[]; dur=[]
for i in range(0,n,STRIDE):
    f=Image.new('RGB',(W*2+GAP,H+BAR),BG)
    f.paste(panel(A[i],lA,i,hit[0]),(0,0)); f.paste(panel(B[i],lB,i,hit[1]),(W+GAP,0))
    seq.append(f); dur.append(DELAY)
dur[-1]=HOLD
seq[0].save(out,save_all=True,append_images=seq[1:],duration=dur,loop=0,lossless=True,method=6,minimize_size=True)
print('%s: %d frames %dx%d %.0f KB'%(out,len(seq),seq[0].size[0],seq[0].size[1],os.path.getsize(out)/1024))
