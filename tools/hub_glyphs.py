# 게임 속 리그 글자 타일 만들기 — NGPC 2bpp 타일(행마다 16비트 LE, 왼쪽 픽셀이 높은 비트)
# 사용: python3 hub_glyphs.py <앱>/app/src/main/cpp/hub_glyphs.h glyphs.json [<앱>/app/src/main/assets/fonts/pixel.ttf]
#       (셋째 인자 = 갈무리11 — 주면 캐릭터 고르기 오른쪽 세로 칸 글자 HUB_VLAB(배경)·LOOK_VLAB(컬러) 도 만든다. Pillow 필요)
# 컬러판(모든 모드 컬러 look.c): 세로 칸의 컬러 이름 넷(«기본·2P색·컬러3·컬러4»)을 HUB_VLAB 에서 빼고(HUB_VLAB_N = 9 — 배경 8 + 무작위),
#       컬러 18개(프리셋 16 · 내 색 1·2 — look_tab.h LOOK_NAME · Java Look.NAMES 와 같은 순서)를 LOOK_VLAB 로 따로 낸다(look.c 가 #define HUB_WANT_LOOK 로 받음).
# 165 베기 색: SLASH_VLAB(«베기 + 이름» 7개)·SLASH_ICON(아이콘 4타일)을 같은 HUB_WANT_LOOK 안에(기존 배열은 바이트까지 그대로).
import sys, json
RI_GEU = [   # 메뉴 «리그» 16x8 (원래 «2 인» 자리, 7px 글꼴 · 잉크 1 · 바탕 3)
 "####..#..######.",
 "...#..#.......#.",
 "####..#.......#.",
 "#.....#.........",
 "####..#.#######.",
 "......#.........",
 "......#.........",
 "................",
]
SANG_DAE = [  # 머리말 «상대» 잉크 마스크(11줄) — 바깥테 자동, 48x16 자리(원래 «캐릭터»)
 "...#.....#.....###.#.#..",
 "..#.#....#.....#...#.#..",
 ".#...#...##....#...#.#..",
 "#.....#..#.....#...###..",
 ".........#.....#...#.#..",
 "...............###.#.#..",
 "...#####...........#.#..",
 "..#.....#..........#.#..",
 "..#.....#..........#.#..",
 "..#.....#..........#.#..",
 "...#####................",
]
SEON_TAEK = [  # 머리말 «선택» 잉크 마스크(11줄) — 순정판 «選択» 32x16 자리(한글판은 원래 «선택» 이 있어 안 씀)
 "...#....#...######.#.#",
 "..#.#...#...#......#.#",
 ".#...#.###..######.###",
 "#.....#.#...#......#.#",
 "........#...######.#.#",
 "...................#.#",
 "..#.........########..",
 "..#..............#....",
 "..#..............#....",
 "..#..............#....",
 "..########.......#....",
]
def enc_tiles(pix, w, h):   # pix[y][x] in 0..3 -> list of tiles (row-major tiles), each 16 bytes
    out=[]
    for ty in range(h//8):
        for tx in range(w//8):
            b=[]
            for y in range(8):
                v=0
                for x in range(8): v |= (pix[ty*8+y][tx*8+x] & 3) << (14-2*x)
                b += [v & 0xFF, v >> 8]
            out.append(b)
    return out
menu=[[ (1 if c=='#' else 3) for c in row] for row in RI_GEU]
def outlined(mask, W, H, ox, oy):   # 잉크 1 · 바깥테 3 · 바탕 0 (원래 머리말 글꼴과 같은 칠)
    ink=[[0]*W for _ in range(H)]
    for y,row in enumerate(mask):
        for x,c in enumerate(row):
            if c=='#': ink[oy+y][ox+x]=1
    out=[[0]*W for _ in range(H)]
    for y in range(H):
        for x in range(W):
            if ink[y][x]: out[y][x]=1
            elif any(0<=y+dy<H and 0<=x+dx<W and ink[y+dy][x+dx] for dy in (-1,0,1) for dx in (-1,0,1)): out[y][x]=3
    return out
hdr=outlined(SANG_DAE,48,16,8,2); sel=outlined(SEON_TAEK,32,16,4,2)
mt=enc_tiles(menu,16,8); ht=enc_tiles(hdr,48,16); st=enc_tiles(sel,32,16)   # ht: 위 6타일 뒤 아래 6타일 · st: 위 4 뒤 아래 4
def carr(name, tiles):
    flat=[x for t in tiles for x in t]
    s='static const uint8_t %s[%d] = {\n' % (name, len(flat))
    for i in range(0,len(flat),16): s+='   '+', '.join('0x%02X'%b for b in flat[i:i+16])+',\n'
    return s+'};\n'
# 캐릭터 고르기 오른쪽 세로 이름 칸(둘째 판 3~11줄 × 17·18칸 = 16 × 72px)에 쓰는 세로 글자 — 게임 이름 칸처럼 1px 획(갈무리11, OFL),
# 바탕 3 · 글자 1(원래 이름 칸 칠). 배경 8개 · 무작위(HUB_VLAB) · 컬러 18개(LOOK_VLAB). 글자마다 16 × 13 칸, 세로 가운데.
VLABELS = [['후','지','산','마','을'], ['숲','속','호','숫','가'], ['산','위','신','사'], ['화','염','사','원'], ['폐','허','사','원'],
           ['불','꽃','동','굴'], ['마','계','제','단'], ['수','정','신','전'], ['무','작','위']]
# 컬러 이름 18개 — 0 기본 · 1 2P색 · 2~15 프리셋 · 16·17 내 색 1·2 (기록·기억에 남는 번호)
LOOK_VLABELS = [['기','본'], ['2P','색'], ['쪽','빛'], ['비','취'], ['보','라'], ['다','홍'], ['분','홍'], ['먹','빛'], ['흰','옷'],
                ['황','금'], ['은','빛'], ['청','동'], ['흑','백'], ['그','림','자'], ['핏','빛'], ['유','령'], ['내','색','1'], ['내','색','2']]
# 165 베기 색(look.c — HUB_WANT_LOOK): A+B 직후 세로 칸에 «베기» + 6px 틈 + 이름 두 글자(1.2초) — slash.h SLASH_NAME · Java Look.FX_NAMES 와 같은 순서
SLASH_VLABELS = [['베','기','원','래'], ['베','기','청','염'], ['베','기','핏','빛'], ['베','기','비','취'], ['베','기','벚','꽃'], ['베','기','흑','염'], ['베','기','서','리']]
# 베기 색 아이콘 16x16 — 우리 픽셀 그림(롬 그림 아님): 칼끝이 지나간 오른쪽 위가 두껍고 밝은 초승달 궤적. 1 core · 2 mid · 3 edge · . 투명(색 0)
#   고르기 화면 오른쪽 문양 칸(둘째 판 0~1줄 × 17·18칸)에 타일 0x142~0x145(위왼·위오른·아래왼·아래오른)로
SLASH_ICON_ART = [
 "................",
 "...........1112.",
 ".........111223.",
 ".......1112233..",
 ".....11122333...",
 "....1122333.....",
 "...112233.......",
 "...12233........",
 "..112233........",
 "..12233.........",
 "..1223..........",
 ".1123...........",
 ".123............",
 ".23.............",
 ".3..............",
 "................",
]
icon_tiles = enc_tiles([[0 if c == '.' else int(c) for c in row] for row in SLASH_ICON_ART], 16, 16)
stiles = []
vtiles=[]; ltiles=[]
def vglyph(lab):
    col = [[3]*16 for _ in range(72)]
    top = (72 - 13*len(lab)) // 2
    for k, ch in enumerate(lab):
        im = Image.new('L', (24, 16), 0); ImageDraw.Draw(im).text((0, 0), ch, font=fnt, fill=255)
        bb = im.getbbox(); w = bb[2] - bb[0]; ox = (16 - w) // 2 - bb[0]
        for y in range(16):
            for x in range(24):
                if im.getpixel((x, y)) > 127:
                    X, Y = x + ox, top + k*13 + y
                    if 0 <= X < 16 and 0 <= Y < 72: col[Y][X] = 1
    return enc_tiles(col, 16, 72)   # 9줄 × 2칸, 줄 먼저
def vglyph_gap(lab, gap):   # «베기» 두 글자 + gap px + 이름 두 글자 — 같은 글꼴·같은 칠, 세로 가운데
    col = [[3]*16 for _ in range(72)]
    top = (72 - 13*len(lab) - gap) // 2
    for k, ch in enumerate(lab):
        im = Image.new('L', (24, 16), 0); ImageDraw.Draw(im).text((0, 0), ch, font=fnt, fill=255)
        bb = im.getbbox(); w = bb[2] - bb[0]; ox = (16 - w) // 2 - bb[0]
        y0 = top + k*13 + (gap if k >= 2 else 0)
        for y in range(16):
            for x in range(24):
                if im.getpixel((x, y)) > 127:
                    X, Y = x + ox, y0 + y
                    if 0 <= X < 16 and 0 <= Y < 72: col[Y][X] = 1
    return enc_tiles(col, 16, 72)
if len(sys.argv) > 3:
    from PIL import Image, ImageDraw, ImageFont
    fnt = ImageFont.truetype(sys.argv[3], 12)
    vtiles = [vglyph(lab) for lab in VLABELS]
    ltiles = [vglyph(lab) for lab in LOOK_VLABELS]
    stiles = [vglyph_gap(lab, 6) for lab in SLASH_VLABELS]
open(sys.argv[1],'w').write('/* hub_glyphs.py 로 만든 글자 타일 — 「리그」(타이틀 메뉴 2번째 줄 앞 두 타일) · 「상대」(캐릭터 고르기 머리말 앞 6칸 × 2줄) · 「선택」(순정판 머리말 뒤 4칸 × 2줄) */\n'
   + carr('HUB_MENU_RIGEU', mt) + carr('HUB_HDR_SANGDAE', ht) + carr('HUB_HDR_SEONTAEK', st)
   + ('#define HUB_VLAB_N %d   /* 세로 글자(캐릭터 고르기 오른쪽 이름 칸): 배경 0~7 · 8 무작위 — 하나에 9줄 × 2칸 = 18타일.\n'
      '                    갈무리11(Galmuri11, SIL OFL 1.1 — 앱 assets/fonts/pixel.ttf 와 같은 글꼴) 12px 로 그림 */\n' % len(vtiles)
      + carr('HUB_VLAB', [t for v in vtiles for t in v]) if vtiles else '')
   + ('#ifdef HUB_WANT_LOOK\n#define LOOK_VLAB_N %d   /* 세로 글자 — 컬러 이름(look.c): 0 기본 · 1 2P색 · 2~15 프리셋 · 16·17 내 색 1·2. 같은 글꼴·같은 칠 */\n' % len(ltiles)
      + carr('LOOK_VLAB', [t for v in ltiles for t in v])
      + '#define SLASH_VLAB_N %d   /* 세로 글자 — 베기 색 이름(look.c, A+B 직후 1.2초): «베기» + 6px 틈 + 0 원래 · 1 청염 · 2 핏빛 · 3 비취 · 4 벚꽃 · 5 흑염 · 6 서리 */\n' % len(stiles)
      + carr('SLASH_VLAB', [t for v in stiles for t in v])
      + '/* 베기 색 아이콘 16x16(우리 픽셀 그림) — 2x2 타일(위왼·위오른·아래왼·아래오른), 색 1 core · 2 mid · 3 edge · 0 투명 */\n'
      + carr('SLASH_ICON', icon_tiles) + '#endif\n' if ltiles else ''))
json.dump({'menu':mt,'hdr':ht,'sel':st}, open(sys.argv[2],'w'))
for row in menu: print(''.join('.#+@'[v] for v in row))
for row in hdr: print(''.join('.#+@'[v] for v in row))
for row in sel: print(''.join('.#+@'[v] for v in row))
