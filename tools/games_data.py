# -*- coding: utf-8 -*-
"""게임별 단일출처(SSOT) 로더 — 배포 스크립트 4벌의 유일한 데이터 API.

   games.identity.json  ← Games.java 에서 ExportGames 로 생성 (id/tag/ko/core/patchable/baseLang/voice/features)
   games_catalog.json   ← 수기: 배포 산문(fullname/sub/patchRe/extra/코어 intro/news 별칭/displayOrder/featureLabels)
   load() 가 둘을 id 로 합친다. legacy() 는 옛 스크립트가 쓰던 자료구조를 **바이트 동일**하게 재현한다
   (check_golden.py 가 그걸 보증). 새 코드는 load() 를 쓰고, legacy() 는 이관 다리다."""
import json, os

HERE = os.path.dirname(os.path.abspath(__file__))

def _read(name):
    with open(os.path.join(HERE, name), encoding='utf-8') as f:
        return json.load(f)

def load():
    ident = _read('games.identity.json')
    cat = _read('games_catalog.json')
    games = {}
    for g in ident['games']:
        d = dict(g)
        d.update(cat['games'].get(g['id'], {}))
        games[g['id']] = d
    return {
        'games': games,                                  # id → 통합 레코드
        'order': list(ident.get('displayOrder') or cat['displayOrder']),  # 표시 순서 — Games.DISPLAY_ORDER 가 정본, 카탈로그는 예비
        'identOrder': [g['id'] for g in ident['games']], # identify 순서 (구체 tag 먼저)
        'featureLabels': cat['featureLabels'],
        'cores': cat['cores'],
    }

def legacy():
    """옛 하드코딩과 같은 모양: (GAMES, FULLNAME, EXTRA, ORDER, SUBS, CORE_META)
       GAMES=[(id, IPS 정규식, 축약명)] · FULLNAME={id:정식명} · EXTRA={id:부가기능}(있는 것만)
       ORDER=[id] · SUBS={id:정보줄}(mkdesign) · CORE_META={코어id:(이름, intro)}"""
    D = load(); G = D['games']; O = D['order']
    GAMES = [(i, G[i]['patchRe'], G[i]['short']) for i in O]
    FULLNAME = {i: G[i]['fullname'] for i in O}
    EXTRA = {i: G[i]['extra'] for i in O if G[i].get('extra')}
    SUBS = {i: G[i]['sub'] for i in O}
    CORE_META = {c: (v['ko'], v['intro']) for c, v in D['cores'].items()}
    return GAMES, FULLNAME, EXTRA, list(O), SUBS, CORE_META

def feature_labels(gid):
    """게임의 features 를 사람 문구로. README 기능표·문서 생성용."""
    D = load(); g = D['games'][gid]; L = D['featureLabels']
    out = []
    for f in g.get('features', []):
        lab = L.get(f, f)
        if lab not in out: out.append(lab)
    return out
