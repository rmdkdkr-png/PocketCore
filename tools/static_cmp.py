# -*- coding: utf-8 -*-
"""Static before/after comparison still (user: video looked bizarre, use well-picked screenshots).
   usage: static_cmp.py out.png dirA dirB frameA frameB x0,y0,x1,y1 "labelA" "labelB" "caption" [--scale 3]
   Two panels side by side at chosen frames, Korean labels (Malgun Gothic from Windows), one caption line.
"""
import sys, glob, os
from PIL import Image, ImageDraw, ImageFont
a=sys.argv[1:]; out,dA,dB,fA,fB,crop,lA,lB,cap=a[:9]; opt=dict(zip(a[9::2],a[10::2]))
S=int(opt.get('--scale',3)); x0,y0,x1,y1=map(int,crop.split(','))
FONT_PATH='/mnt/c/Windows/Fonts/malgun.ttf'; FONTB='/mnt/c/Windows/Fonts/malgunbd.ttf'
F1=ImageFont.truetype(FONTB if os.path.exists(FONTB) else FONT_PATH, 22); F2=ImageFont.truetype(FONT_PATH, 18)
def frame(d,i):
    fs=sorted(p for p in glob.glob(os.path.join(d,'*.ppm')) if not p.endswith('_end.ppm'))
    return Image.open(fs[int(i)]).convert('RGB').crop((x0,y0,x1,y1))
A=frame(dA,fA); B=frame(dB,fB); w,h=A.size; W,H=w*S,h*S
BAR=34; GAP=12; CAP=34; PAD=10; BG=(250,250,250); FG=(30,30,30); ACC=(200,40,40)
img=Image.new('RGB',(W*2+GAP+PAD*2, BAR+H+CAP+PAD*2), BG); d=ImageDraw.Draw(img)
for k,(im,lab,col) in enumerate([(A,lA,FG),(B,lB,ACC)]):
    x=PAD+k*(W+GAP)
    d.text((x,PAD+4),lab,fill=col,font=F1)
    img.paste(im.resize((W,H),Image.NEAREST),(x,PAD+BAR))
    d.rectangle((x-1,PAD+BAR-1,x+W,PAD+BAR+H),outline=(200,200,200))
d.text((PAD,PAD+BAR+H+8),cap,fill=(70,70,70),font=F2)
img.save(out,optimize=True); print(out,img.size,os.path.getsize(out)//1024,'KB')
