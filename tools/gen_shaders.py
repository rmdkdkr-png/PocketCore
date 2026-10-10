#!/usr/bin/env python3
"""화면 셰이더(app/src/main/cpp/shaders/*.glsl) → app/src/main/cpp/display_shaders.h

- OmniScale 템플릿의 P(m, r) 패턴 검사(비트 연산)를 GLSL ES 1.00 용 불 대수로 펼친다.
  비트 k ↔ d{k}: 0 w0, 1 w1, 2 w2, 3 w3, 4 w5, 5 w6, 6 w7, 7 w8 (원본 pattern 비트 순서와 같다)
- 각 업스케일러 = head + up_*.glsl + tail 을 한 문자열로. 주석은 남긴다(라이선스 고지).
사용: python3 tools/gen_shaders.py   (셰이더를 고치면 다시 돌려 헤더를 커밋)
"""
import re, os, sys
HERE = os.path.dirname(os.path.abspath(__file__))
SH = os.path.join(HERE, '..', 'app', 'src', 'main', 'cpp', 'shaders')
OUT = os.path.join(HERE, '..', 'app', 'src', 'main', 'cpp', 'display_shaders.h')

def expand_p(src):
    def rep(m):
        mask, val = int(m.group(1), 16), int(m.group(2), 16)
        terms = []
        for k in range(8):
            if mask >> k & 1:
                terms.append(f'd{k}' if (val >> k & 1) else f'!d{k}')
        return '(' + ' && '.join(terms) + ')' if terms else 'true'
    return re.sub(r'P\(\s*(0x[0-9a-fA-F]+)\s*,\s*(0x[0-9a-fA-F]+)\s*\)', rep, src)

def read(n):
    with open(os.path.join(SH, n), encoding='utf-8') as f: return f.read()

def cstr(s):
    out = []
    for line in s.split('\n'):
        line = line.replace('\\', '\\\\').replace('"', '\\"')
        out.append('   "' + line + '\\n"')
    return '\n'.join(out)

UPS = [('none', 'up_none.glsl'), ('sharp', 'up_sharp.glsl'), ('scale2x', 'up_scale2x.glsl'),
       ('xbr', 'up_xbr.glsl'), ('omni', 'up_omni.tmpl.glsl')]
head, tail = read('head.glsl'), read('tail.glsl')
parts = ['/* 생성됨 — tools/gen_shaders.py (직접 고치지 말 것). 업스케일러마다 한 프로그램. */',
         '#ifndef DISPLAY_SHADERS_H', '#define DISPLAY_SHADERS_H',
         f'#define DISP_N_UP {len(UPS)}']
names = []
for key, fn in UPS:
    body = read(fn)
    if 'tmpl' in fn: body = expand_p(body)
    src = head + '\n' + body + '\n' + tail
    var = f'FS_UP_{key.upper()}'
    names.append(var)
    parts.append(f'static const char {var}[] =\n{cstr(src)};')
parts.append('static const char *const FS_UP[DISP_N_UP] = { ' + ', '.join(names) + ' };')
parts.append('#endif')
with open(OUT, 'w', encoding='utf-8') as f: f.write('\n'.join(parts) + '\n')
# 시험용으로 펼친 전체 소스도 남긴다(브라우저 WebGL 컴파일 시험)
if len(sys.argv) > 1:
    os.makedirs(sys.argv[1], exist_ok=True)
    for key, fn in UPS:
        body = read(fn)
        if 'tmpl' in fn: body = expand_p(body)
        with open(os.path.join(sys.argv[1], key + '.frag'), 'w', encoding='utf-8') as f: f.write(head + '\n' + body + '\n' + tail)
print('ok', OUT)
