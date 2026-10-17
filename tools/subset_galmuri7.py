# -*- coding: utf-8 -*-
"""157 갈무리7(Galmuri7, SIL OFL 1.1) 부분집합 — 리그 인게임 메뉴(덧판) 서랍·아래 띠 글꼴 app/src/main/assets/fonts/galmuri7.ttf 를 만든다.

  python3 -I tools/subset_galmuri7.py <Galmuri7.ttf 원본> <저장소 루트> [--check]

글자 모음 = ASCII 20–7E · KS X 1001 한글 2,350자 · 한글 호환 자모 · 메뉴에 쓰는 기호 · assets/league/pool.json 의 모든 글자(닉네임·칭호·지역…)
          · 리그 자바 문자열(League·RankCore·ProfileGen·MenuModel·LgmPages·LeagueMenu 의 "…" 안 글자) · 아래 MENU 글.
원본에 없는 글자는 빼고 목록을 찍는다(그 글자가 든 닉네임은 LgmPages 가 갈무리11 로 대신 그린다 — TileCanvas.covers).
--check: 이미 있는 assets/fonts/galmuri7.ttf 가 지금 글자 모음을 다 가졌나만 본다(종료 코드 1 = 빠짐).
결과는 결정적이다(시각 칸을 다시 쓰지 않음 — 같은 입력이면 같은 바이트). 힌팅 없음 · 레이아웃 표(GSUB/GPOS) 뺌 · 이름 표 그대로(OFL 이름).
"""
import json
import os
import re
import sys

from fontTools import subset
from fontTools.ttLib import TTFont

# 메뉴 글(기획서·계약서·목업의 칼집·띠·서랍 문구) — LgmPages 가 채워지면 그 파일의 문자열도 저절로 들어간다
MENU = """
다음 상대 재대결 설욕전 새 캐릭터 순위·전적 그만하기 무대 랜덤 상대 기본 A 결정 B 그만 B 취소 B 뒤로
새 상대와 바로 한 판 같은 상대와 한 판 더 같은 상대와 바로 다시 이번 판만 직접 골라요 순위표와 전적 보기 리그는 켠 채 쉬어요
같은 상대와 3판을 다 했어요 이 조합은 아직 못 해요 그만할까요? 타이틀로 리그 화면 내 게임 게임 목록 취소 오늘 승 패 무
리그는 켠 채 · A로 다시 쉼 라운드 이어하기 나중에 저장 기권 정말 기권할까요? 아니오 패배로 기록 · LP · 연승 끊김
배치 1패로 기록 LP 그대로 · 수련생 보호 무효 · LP 없음 그만두기 (무효) 멈춘 곳에서 바로 이어서 지금 자리 저장하고 리그 화면으로
패배로 기록돼요 ◀▶ 고르기 A 확정 A 결정 · B/OPTION 이어하기 vs 나와 이기면 지면 연 설 첫 위 라 3연승 +30 이기면 +62
순위표 시즌 AI 검객 CPU 순위 이름 티어 LP MR 1위 2위 3위 무쌍 전국 위 / 상위 % ▲▼ 넘기기 A 프로필 ◀▶ 탭 B 뒤로
전적 41전 최근 10 오늘 4승 1패 · +152 LP · 첫 승 받음 LP 흐름 · 최근 20판 통산 승 패 · 최고 검호★1 라이벌 · 설욕 대기
◀▶ 탭 ▲ 시즌/통산 A 경기 연승 최고 7 1무 61% 설정 결판 연출 보통 짧게 끄기 진 판 기본 커서 연속 대전 끔 10초 마지막 탭 기억 켬
먹 가는 중… 상대 찾는 중 … 배치 완료 배치 3/5 승급! 강등 강등 위기 ★ 올라감! 무쌍 달성! 수련생 낭인 무사 검객 검호 달인 검성
오늘 첫 승 연승 끊음 설욕 성공 라이벌 격파 → 수라 나찰 기권승 기권패 무승부 이긴 판 진 판 보류 오늘 2승 1패 2라운드 1:0
"""
SYMBOLS = "·…★☆▲▼◀▶△▽○●◎◆◇■□※×÷±→←↑↓↔「」『』《》〈〉“”‘’~‥。、！？：；（）［］｛｝＋－＝％"


def ks_hangul():
    return [bytes([a, b]).decode('euc-kr') for a in range(0xB0, 0xC9) for b in range(0xA1, 0xFF)]


def java_strings(path):
    if not os.path.exists(path):
        return ''
    src = open(path, encoding='utf-8').read()
    src = re.sub(r'/\*.*?\*/', ' ', src, flags=re.S)          # 주석은 뺀다(한자·설명 글)
    src = re.sub(r'//[^\n]*', ' ', src)
    out = []
    for m in re.finditer(r'"((?:[^"\\\n]|\\.)*)"', src):
        s = m.group(1)
        s = re.sub(r'\\u([0-9a-fA-F]{4})', lambda k: chr(int(k.group(1), 16)), s)
        out.append(s)
    for m in re.finditer(r"'((?:[^'\\\n]|\\.))'", src):
        out.append(m.group(1))
    return ''.join(out)


def json_strings(o):
    if isinstance(o, str):
        return o
    if isinstance(o, list):
        return ''.join(json_strings(v) for v in o)
    if isinstance(o, dict):
        return ''.join(json_strings(k) + json_strings(v) for k, v in o.items())
    return ''


def wanted(repo):
    s = set(chr(c) for c in range(0x20, 0x7F))
    s.update(ks_hangul())
    s.update(chr(c) for c in range(0x3131, 0x318F))          # 한글 호환 자모(닉네임 «ㅋㅋ» 등)
    s.update(SYMBOLS)
    s.update(MENU)
    pool = os.path.join(repo, 'app/src/main/assets/league/pool.json')
    if os.path.exists(pool):
        s.update(json_strings(json.load(open(pool, encoding='utf-8'))))
    jd = os.path.join(repo, 'app/src/main/java/com/dudu/pocketcore')
    for f in ('League.java', 'RankCore.java', 'ProfileGen.java', 'MenuModel.java', 'LgmPages.java', 'LeagueMenu.java'):
        s.update(java_strings(os.path.join(jd, f)))
    return sorted(c for c in s if c >= ' ' and c not in ' ﻿')


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(2)
    src, repo = sys.argv[1], sys.argv[2]
    out = os.path.join(repo, 'app/src/main/assets/fonts/galmuri7.ttf')
    want = wanted(repo)
    if '--check' in sys.argv:
        cm = TTFont(out).getBestCmap()
        srccm = TTFont(src).getBestCmap()
        miss = [c for c in want if ord(c) not in cm and ord(c) in srccm]
        print('glyphs %d, missing %d %s' % (len(cm), len(miss), ''.join(miss[:80])))
        sys.exit(1 if miss else 0)
    font = TTFont(src, recalcTimestamp=False)
    cm = font.getBestCmap()
    have = [c for c in want if ord(c) in cm]
    lack = [c for c in want if ord(c) not in cm]
    opt = subset.Options()
    opt.hinting = False
    opt.desubroutinize = True
    opt.layout_features = []
    opt.drop_tables += ['GSUB', 'GPOS', 'GDEF']
    opt.name_IDs = ['*']
    opt.name_languages = ['*']
    opt.notdef_outline = True
    opt.recalc_timestamp = False
    sub = subset.Subsetter(opt)
    sub.populate(unicodes=[ord(c) for c in have])
    sub.subset(font)
    font.save(out)
    print('galmuri7.ttf %d bytes · %d glyphs (wanted %d, not in source %d: %s)'
          % (os.path.getsize(out), len(have), len(want), len(lack), ''.join(lack)))


if __name__ == '__main__':
    main()
