# -*- coding: utf-8 -*-
"""회귀 게이트: games_data.legacy() 가 옛 스크립트의 하드코딩과 **완전히 같은 자료**를 내는가.
   아래 사전들은 이관 직전 pub_pocketcore.py / mkdesign.py / pub_content.py 에서 그대로 복사한 골든본.
   같으면 그 스크립트들의 산출물(patches.json·design.json·README 표·코어 본문)도 바이트 동일하다
   (그들은 이 자료의 순수 함수 + 릴리즈 실태이므로)."""
import sys, os
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from games_data import legacy, load

GAMES_OLD = [
    ('svc',   r'^SvC_MotM_Korean_v([0-9][0-9A-Za-z.]*)\.ips$',   'SvC'),
    ('ss2',   r'^SS2_Korean_v([0-9][0-9A-Za-z.]*)\.ips$',        '사무쇼2'),
    ('ss1',   r'^SS1_Korean_v([0-9][0-9A-Za-z.]*)\.ips$',        '사무쇼1'),
    ('lb',    r'^LastBlade_Korean_v([0-9][0-9A-Za-z.]*)\.ips$',  '월화'),
    ('kofr2', r'^KOF_?R2_Korean_v([0-9][0-9A-Za-z.]*)\.ips$',    'KOF R-2'),
    ('ffc',   r'^FatalFuryFC_Korean_v([0-9][0-9A-Za-z.]*)\.ips$','아랑전설'),
    ('ms1',   r'^MetalSlug1st_Korean_v([0-9][0-9A-Za-z.]*)\.ips$','메탈슬러그 1st'),
    ('ms2',   r'^MetalSlug2nd_Korean_v([0-9][0-9A-Za-z.]*)\.ips$','메탈슬러그 2nd'),
]
FULLNAME_OLD = {'svc':'SNK vs. Capcom — 정상결전 최강 파이터즈','ss2':'사무라이 쇼다운! 2',
            'ss1':'사무라이 쇼다운!','lb':'월화의 검사 특별편',
            'kofr2':'더 킹 오브 파이터즈 R-2','ffc':'아랑전설 퍼스트 컨택트',
            'ms1':'메탈슬러그 1st 미션','ms2':'메탈슬러그 2nd 미션'}
EXTRA_OLD = {'svc':'원버튼 필살기 엔진','ss2':'캐릭터 해설 + 한국어 더빙(음성팩)'}
ORDER_OLD = ['svc','ss2','ss1','lb','kofr2','ffc','ms1','ms2']
SUBS_OLD = {
    'svc':   '1999 · 대전격투 · SNK vs 캡콤',
    'ss2':   '1999 · 대전격투 · 사무라이 쇼다운',
    'ss1':   '1998 · 대전격투 · 사무라이 쇼다운',
    'lb':    '2000 · 대전격투 · 월화의 검사',
    'kofr2': '1999 · 대전격투 · KOF',
    'ffc':   '1999 · 대전격투 · 아랑전설',
    'ms1':   '1999 · 런앤건 · 메탈슬러그',
    'ms2':   '2000 · 런앤건 · 메탈슬러그',
}
CORE_META_OLD = {
    'svc': ('SvC 원버튼 코어 (SP 코어)',
        "PocketCore 의 **SNK vs. Capcom MotM** 코어입니다. 기술키 하나로 커맨드를 대신\n"
        "넣는 **원버튼 필살기 엔진**이 들어 있습니다. 지원 표에 없는 다른 네오지오 포켓(컬러)\n"
        "롬도 이 코어로 돕니다 — 엔진은 SVC 롬이 아니면 스스로 잡니다.\n"),
    'ss2': ('사무쇼2 코어 (해설·더빙)',
        "PocketCore 의 **사무라이 쇼다운! 2** 전용 코어입니다. 캐릭터 해설 자막과\n"
        "한국어 더빙 재생, 간이입력(ABLE) 연동이 들어 있습니다. 더빙 **음성**은 별도\n"
        "음성팩(ss2-voice 릴리즈)이 있어야 납니다.\n"),
}

GAMES, FULLNAME, EXTRA, ORDER, SUBS, CORE_META = legacy()
checks = [
    ('GAMES (IPS 정규식·축약명·순서)', GAMES == GAMES_OLD),
    ('FULLNAME', FULLNAME == FULLNAME_OLD and list(FULLNAME) == list(FULLNAME_OLD)),
    ('EXTRA', EXTRA == EXTRA_OLD and list(EXTRA) == list(EXTRA_OLD)),
    ('ORDER', ORDER == ORDER_OLD),
    ('SUBS (mkdesign, 순서 포함)', SUBS == SUBS_OLD and list(SUBS) == list(SUBS_OLD)),
    ('CORE_META (순서 포함)', CORE_META == CORE_META_OLD and list(CORE_META) == list(CORE_META_OLD)),
]
D = load()
checks.append(('identity 게임 수 9 (lbj 포함)', len(D['games']) == 9))
checks.append(('features 핵심', D['games']['svc']['features'] == ['sp:svc','band','basics','actshow','fastcd:svc']
               and D['games']['kofr2']['features'] == ['sp:kof','fastcd:kof']
               and D['games']['ss2']['features'] == ['sp:ss2','comm','sides']))
bad = 0
for name, ok in checks:
    print(('  ✓ ' if ok else '  ✗✗ ') + name); bad += (not ok)
print('골든 대조: %s' % ('전부 일치 — 이관해도 산출물 바이트 동일' if not bad else '%d 불일치 — 이관 중단' % bad))
sys.exit(1 if bad else 0)
