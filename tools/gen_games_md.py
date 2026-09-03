# -*- coding: utf-8 -*-
"""게임별 문서 생성 — GAMES.md. 개발·배포용 단일 페이지.
   games_data.load()(카탈로그 진실) + ~/ss2/release 의 patches.json(한패 판)·cores.json(코어·팩 판)·
   news.json(게임별 별칭으로 거른 최근 변경)을 **조인**한다. 병합이 아니다 — 카탈로그와 릴리즈 실태는 따로 산다.
   순수 추가 산출물이라 기존 배포물엔 영향 없음."""
import json, os, sys, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from games_data import load, feature_labels

REL = os.path.expanduser('~/ss2/release')
def rj(n):
    try:
        with open(os.path.join(REL, n), encoding='utf-8') as f: return json.load(f)
    except Exception: return {}

D = load(); G = D['games']
patches = rj('patches.json').get('patches', {})
cores = rj('cores.json'); core_v = cores.get('cores', {}); packs = cores.get('packs', {})
news = rj('news.json').get('items', [])

def news_for(gid, n=6):
    """가장 긴 별칭이 맞는 게임에 귀속 — '사무라이 스피리츠!' 가 '사무라이 스피리츠! 2' 를 삼키지 않게."""
    out = []
    for it in news:
        t = it.get('text', ''); best = None; blen = 0
        for oid, og in G.items():
            for al in og.get('news', []):
                if al in t and len(al) > blen: best, blen = oid, len(al)
        if best == gid: out.append(it)
        if len(out) >= n: break
    return out

core_id = lambda g: 'ss2' if g['core'] == 'libretro_ss2.so' else 'svc'
L = ['# PocketCore 게임별 현황', '',
     '_자동 생성 — `tools/gen_games_md.py` (%s). 게임 정체성·기능은 `Games.java`, 배포 산문은'
     ' `tools/games_catalog.json`, 판번호는 릴리즈 색인(patches/cores/news.json)에서 조인._' % time.strftime('%Y-%m-%d'), '']
for gid in D['order'] + [i for i in D['identOrder'] if i not in D['order']]:
    g = G[gid]
    L.append('## %s  (`%s`)' % (g.get('fullname', g['ko']), gid))
    L.append('')
    L.append('- 롬 표식 `%s` · 코어 **%s**%s · 바탕언어 %s' % (
        g['tag'], core_id(g),
        (' v' + core_v[core_id(g)]['ver']) if core_id(g) in core_v else '', g['baseLang']))
    if g.get('sub'): L.append('- %s' % g['sub'])
    p = patches.get(gid)
    L.append('- 한글패치: %s' % (('**%s** — %s' % (p['ver'], p['url'])) if p else
                                ('없음(미지원)' if not g['patchable'] else '아직 없음')))
    fl = feature_labels(gid)
    L.append('- 부가 기능: %s' % (' · '.join(fl) if fl else '없음(순정)'))
    if g.get('voice'):
        pk = next((v for k, v in packs.items() if v.get('file') == g['voice']), None)
        L.append('- 음성팩: %s%s' % (g['voice'],
                 (' ' + (pk['ver'] if str(pk['ver']).startswith('v') else 'v' + pk['ver'])) if pk else ''))
    nf = news_for(gid)
    if nf:
        L.append('- 최근 변경:')
        for it in nf: L.append('  - %s  %s' % (it.get('date', ''), it.get('text', '')))
    L.append('')
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'GAMES.md')
with open(out, 'w', encoding='utf-8', newline='\n') as f: f.write('\n'.join(L))
print('GAMES.md 생성:', os.path.abspath(out), '(%d 게임)' % len(G))
