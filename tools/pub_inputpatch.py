# -*- coding: utf-8 -*-
"""InputPatch 레포(github.com/rmdkdkr-png/InputPatch) 배포 — 인풋(조작·게임플레이) 패치의 집.
   ① 레포 트리: patches/<file>.ips, catalog.json(=mods_catalog), docs/img/*.webp, README.md (contents API 로 커밋)
      — 이미지 폴더에 없는 원격 docs/img 파일은 지운다(낡은 필름 정리).
   ② 고정 릴리즈 태그 `mods`: 모든 IPS + mods.json (앱이 보는 색인)
   ③ 앱 동봉 스냅샷 assets/mods.json 갱신, news.json 소식.
   실행: python3 tools/pub_inputpatch.py [이미지 폴더]   (DRY=1 이면 README/색인만 출력)
   이미지: 전후 비교는 static_cmp.py 가 만든 **정지컷 한 장**(같은 프레임의 원본|패치, 한글 라벨·캡션 내장).
   유저 판정: 긴 필름도, 나란히 도는 영상도 「기괴」 — 잘 고른 스샷 한 장이 답."""
import base64, json, os, re, subprocess, sys, time, urllib.request, hashlib, glob
HERE=os.path.dirname(os.path.abspath(__file__)); REPO=os.path.dirname(HERE)
IP='rmdkdkr-png/InputPatch'; PC='rmdkdkr-png/PocketCore'; KR='rmdkdkr-png/KrPatch'; TAG='mods'
DL='https://github.com/%s/releases/download/%s'%(IP,TAG)
RAW='https://raw.githubusercontent.com/%s/main'%IP
IMGDIR=sys.argv[1] if len(sys.argv)>1 and not sys.argv[1].startswith('-') else os.path.expanduser('~/ss2/work_inputpatch/img')
cat=json.load(open(os.path.join(HERE,'mods_catalog.json'),encoding='utf-8'))
sys.path.insert(0,HERE); from games_data import load as gload
G=gload()['games']

url=subprocess.check_output(['git','-C',os.path.expanduser('~/ss2/repo/app'),'remote','get-url','origin'],text=True).strip()
TOKEN=re.search(r'://[^:]*:?([A-Za-z0-9_]+)@github',url).group(1)
def api(path,data=None,method=None,ctype='application/json'):
    u=path if path.startswith('http') else 'https://api.github.com'+path
    req=urllib.request.Request(u,data=data,method=method)
    req.add_header('Authorization','token '+TOKEN); req.add_header('Accept','application/vnd.github+json')
    if data is not None: req.add_header('Content-Type',ctype)
    with urllib.request.urlopen(req,timeout=180) as r: b=r.read()
    return json.loads(b) if b else {}
def put_file(path, content_bytes, msg):
    sha=None
    try: sha=api('/repos/%s/contents/%s'%(IP,path))['sha']
    except Exception: pass
    payload={'message':msg,'content':base64.b64encode(content_bytes).decode()}
    if sha: payload['sha']=sha
    api('/repos/%s/contents/%s'%(IP,path), json.dumps(payload).encode(),'PUT')

# ── 색인 ──────────────────────────────────────────────────────
idx={'ver':1,'mods':[]}
for m in cat['mods']:
    b=open(os.path.join(REPO,'mods',m['file']),'rb').read(); assert b[:5]==b'PATCH' and b.endswith(b'EOF'), m['file']
    idx['mods'].append({'id':m['id'],'game':m['game'],'gameKo':G[m['game']]['ko'],'ko':m['ko'],'ver':m['ver'],
        'url':DL+'/'+m['file'],'file':m['file'],'md5':hashlib.md5(b).hexdigest(),'size':len(b),
        'default':m.get('default','disabled'),'help':m['help'],'tag':m.get('tag',''),
        # 배타 묶음 — 앱이 한 줄짜리 다이얼로 그린다(옵션 키 pocketcore_<group>). 없으면 개별 토글.
        'group':m.get('group',''),'group_ko':m.get('group_ko',''),'pick':m.get('pick','')})
text=json.dumps(idx,ensure_ascii=False,indent=1)

# ── README ────────────────────────────────────────────────────
imgs=sorted(os.path.basename(p) for p in glob.glob(os.path.join(IMGDIR,'*.webp'))+glob.glob(os.path.join(IMGDIR,'*.png')))
def img(name, alt, width=None):
    if name not in imgs: return ''
    w=(' width="%d"'%width) if width else ''
    return '<img src="%s/docs/img/%s" alt="%s"%s>'%(RAW,name,alt,w)
def fig(name, alt, caption, width=None):
    """그림 + 설명 한 줄. 파일이 없으면 통째로 생략(설명만 남는 일 없음)."""
    t=img(name,alt,width)
    return (t+'\n\n*'+caption+'*\n') if t else ''
TAG_OF={m['id']:m.get('tag',m['id']) for m in cat['mods']}
rows='\n'.join('| %s | %s | %s | [받기](https://github.com/%s/releases/tag/%s) |'%(x['gameKo'],x['ko'],x['ver'],IP,TAG_OF[x['id']]) for x in idx['mods'])
readme = """<div align="center">

# 🎮 InputPatch

**네오지오 포켓 컬러 격투게임의 「손맛」 패치 모음** — 발동 프레임·후경직 같은 **입력 체감**을 고치는 IPS.

한글패치([KrPatch](https://github.com/%(KR)s))가 *글*을 고친다면, 여기는 *손*을 고칩니다. IPS 차분만 배포하며 롬은 없습니다.

%(hero)s

</div>

## 목록

| 게임 | 패치 | 판 | |
|---|---|---|---|
%(rows)s

md5·크기는 [릴리즈 `mods`](https://github.com/%(IP)s/releases/tag/mods) 의 `mods.json` 에 있습니다.

## 적용법

1. 위 표에서 IPS 를 받습니다. 대상 롬은 **순정 롬**이든 **이미 한글패치한 롬**이든 됩니다 — 한글패치와 겹치는 바이트가 없어 순서도 무관합니다.
2. 패치 도구로 IPS 를 롬에 적용합니다.
   - PC: [Floating IPS](https://www.romhacking.net/utilities/1040/) 또는 [Lunar IPS](https://www.romhacking.net/utilities/240/)
   - 웹(설치 없음): [ROM Patcher JS](https://www.marcrobledo.com/RomPatcher.js/) — 롬과 IPS 를 고르면 결과 파일을 내려줍니다
   - 안드로이드: UniPatcher (Play 스토어)
3. 결과 롬을 에뮬레이터나 카트에 넣습니다. 확장자(.ngc)는 그대로.
4. 되돌리기 = 원본 롬을 다시 쓰면 됩니다. 한글패치와 인풋패치를 둘 다 원하면 **한글패치 IPS → 인풋패치 IPS** 순으로 두 번 적용하세요.

주의: 같은 게임의 패치 두 개가 **같은 바이트**를 고치는 경우(사무라이 쇼다운! 2 「보통」·「넉넉」)는 하나만 고르세요.
받은 IPS 가 최신인지는 릴리즈의 md5 로 확인합니다(IPS 파일 자체의 md5 — 결과 롬 해시는 원본 덤프 종류마다 달라서 적지 않습니다).

## 왜 필요한가

네오지오 포켓은 버튼이 A·B 둘뿐이라 **길게 누르면 강**입니다. 게임은 버튼을 8프레임 눌러 본 뒤에야 강 모션을 시작하고
(쿄: 누른 뒤 8프레임째), 거기에 기술 자체의 발동이 붙습니다. 그래서 쿄 서서 강펀은 격겜 관례의 스타트업으로 **17프레임**
(아케이드 KOF'98 쿄 원거리 강펀 12), 버튼을 누른 순간부터 재면 **24프레임**, 하오마루는 35프레임입니다. 여기 패치들은 모션
쪽에서 홀드 판정이 먹는 몫(6~8프레임)만 돌려주고, 누르는 길이로 약/강을 가르는 방식·판정 상자·대미지·약공격은 그대로 둡니다.
홀드 문턱 자체를 낮추는 입력 쪽 롬 수정도 시험했지만 약/강 구분이 무너져 접었습니다.

## 빠른 기본기 — FastCD (SvC · KOF R-2)

서서 강펀·강킥 중 **유독 늘어진 기술**의 발동을 애니메이션 대본에서 당깁니다. 규칙: **강은 약보다 반드시 느리게(묵직)**,
캐릭터별 차이 보존, 원본이 이미 빠르면 손대지 않음. 여백은 두 게임 다 **펀치 +4 · 킥 +6**(원본 약→강 차이의 중앙값).

%(kof)s

- SvC v1.4 — 11기술, 16바이트. **누름→명중(순정 2버튼 홀드, 버튼이 눌린 첫 프레임 → 맞는 프레임, 에뮬 실측, 위상에 따라 ±1)**: 강펀 쿄 24→18 · 하오마루 35→27 · 나코루루 24→20 · 장기에프 22→18 · 모리간 22→18 · 이오리 22→18 / 강킥 레오나 28→22 · 하오마루 27→21 · 나코루루 30→26 · 셰르미 24→22 · 야시로 22→20. 나머지는 원본 그대로, 약공격은 손대지 않음. 격겜 관례의 **스타트업(모션 첫 프레임을 1로 세어 첫 판정까지)**으로는 쿄 강펀 **17 → 11**, 약펀 7.
- KOF R-2 v1.1 — 8기술, 11바이트. 같은 잣대로 쿄 강펀 누름→명중 **23 → 17**, 스타트업 17 → 11. 나머지 7기술도 각 6프레임 단축.
- 전 기술 에뮬레이터 실측(패치 후 재측정): 발동·피해·동작 흐름·강<약 역전 없음 확인.

## 평타 콤보 — SS2 (검수용)

사무라이 쇼다운! 2 는 **카운터가 아니면 평타 콤보가 안 들어갑니다** — 약베기(B 탭) 후경직이 상대 경직과 거의 같아서.
아수라 서서 약베기의 회복 틱을 **7→3(−8프레임)** 으로 줄이면 「약베기 → 살짝 전진 → 약베기」가 2타로 들어갑니다.
아직 **캐릭터 1명·기술 1개** 실증이라 `[검수용]` 입니다.

%(ss2)s

## 더 보기

프레임은 램이 아니라 **롬의 애니메이션 대본**이 정합니다. 찾는 법·실측표는 [thinkbox](https://github.com/rmdkdkr-png/thinkbox) 의 `knowledge/` 에.

## 저작권 · 책임

게임과 그 그림·음악·이름의 권리는 **SNK 등 원 권리자**의 것입니다. 비영리 팬 패치이며 **롬을 배포하지 않습니다**(차분만).
있는 그대로 제공되며 사용에 따른 문제의 책임은 사용자에게 있습니다. 권리자 요청 시 즉시 내립니다.
""" % dict(KR=KR, PC=PC, IP=IP, rows=rows,
           hero=img('svc_kyo_hp_orig_vs_fastcd.png','SvC 쿄 강펀 — 같은 프레임의 원본 vs FastCD',900),
           kof=img('kof_kyo_hp_orig_vs_fastcd.png','KOF R-2 쿄 강펀 — 같은 프레임의 원본 vs FastCD v1.1',900),
           ss2=img('ss2_asura_combo_orig_vs_t3.png','SS2 아수라 약베기 콤보 — 2타 시점의 원본 vs t3',900),
           shots=' '.join(img(n,n,240) for n in imgs if n.startswith('app_')))
if os.environ.get('DRY'): print(text); print(readme); sys.exit(0)

# ── 레포 트리 커밋 ─────────────────────────────────────────────
for m in cat['mods']:
    put_file('patches/'+m['file'], open(os.path.join(REPO,'mods',m['file']),'rb').read(), '패치: %s %s'%(m['id'],m['ver']))
for _t,_m in cat.get('tags',{}).items():
    if _m.get('intro'):
        _p=os.path.join(REPO,'mods','docs',_m['intro'])
        if os.path.exists(_p): put_file('docs/'+_m['intro'], open(_p,'rb').read(), '본문: '+_t)
put_file('catalog.json', json.dumps(cat,ensure_ascii=False,indent=1).encode('utf-8'), '카탈로그 갱신')
put_file('mods.json', text.encode('utf-8'), '색인 갱신')
for n in imgs: put_file('docs/img/'+n, open(os.path.join(IMGDIR,n),'rb').read(), '이미지: '+n)
for _t,_m in cat.get('tags',{}).items():                 # 본문이 가리키는 그림이 실제로 올라갔나
    if _m.get('shot') and _m['shot'] not in imgs:
        raise SystemExit('★ %s 의 스샷 %s 가 %s 에 없다 — 올리면 본문이 404 를 가리킨다'
                         %(_t,_m['shot'],IMGDIR))
try:
    for f in api('/repos/%s/contents/docs/img'%IP):
        if f['name'] not in imgs:
            api('/repos/%s/contents/docs/img/%s'%(IP,f['name']), json.dumps({'message':'낡은 이미지 정리: '+f['name'],'sha':f['sha']}).encode(),'DELETE'); print('원격 이미지 삭제:',f['name'])
except Exception as e: print('원격 이미지 목록 실패(무시):',e)
put_file('README.md', readme.encode('utf-8'), '소개글 갱신 (자동 — pub_inputpatch.py)')
print('트리 커밋: patches %d · 이미지 %d · README'%(len(cat['mods']),len(imgs)))
# ── 릴리즈 ────────────────────────────────────────────────────
body="## 조작 패치 IPS 색인\n\n소개·적용법·전후 비교는 [README](https://github.com/%s) 에. 아래 IPS 를 받아 롬에 입힙니다(롬은 배포하지 않습니다).\n\n### 적용법 (요약)\n- IPS 를 순정 롬(또는 한글패치 롬)에 Floating IPS / Lunar IPS(PC), ROM Patcher JS(웹), UniPatcher(안드로이드)로 적용. 한글패치와 겹치지 않아 순서 무관. 되돌리기는 원본 롬.\n- 같은 게임의 「보통」·「넉넉」처럼 같은 바이트를 고치는 패치는 하나만.\n\n| 게임 | 패치 | 판 | md5 |\n|---|---|---|---|\n%s\n"%(IP,'\n'.join('| %s | %s | %s | `%s` |'%(x['gameKo'],x['ko'],x['ver'],x['md5']) for x in idx['mods']))
try:
    rel=api('/repos/%s/releases/tags/%s'%(IP,TAG)); api('/repos/%s/releases/%d'%(IP,rel['id']),json.dumps({'name':'조작 패치 색인 (앱용)','body':body}).encode(),'PATCH')
except Exception:
    rel=api('/repos/%s/releases'%IP,json.dumps({'tag_name':TAG,'name':'조작 패치 색인 (앱용)','body':body,'make_latest':'true'}).encode(),'POST')
names={x['file'] for x in idx['mods']}|{'mods.json'}
for a in api('/repos/%s/releases/%d/assets'%(IP,rel['id'])):
    if a['name'] in names: api('/repos/%s/releases/assets/%d'%(IP,a['id']),method='DELETE')
for x in idx['mods']:
    api('https://uploads.github.com/repos/%s/releases/%d/assets?name=%s'%(IP,rel['id'],x['file']),open(os.path.join(REPO,'mods',x['file']),'rb').read(),'POST','application/octet-stream')
api('https://uploads.github.com/repos/%s/releases/%d/assets?name=mods.json'%(IP,rel['id']),text.encode('utf-8'),'POST','application/json')
print('릴리즈 %s: IPS %d + mods.json'%(TAG,len(idx['mods'])))
# ── PER-TAG: 패치별 릴리즈(사람이 받는 자리 — 디시 링크용). mods 태그는 앱 색인으로 유지 ──
HOWTO=("### 적용법\n1. 아래 IPS 를 받습니다. 대상 롬은 순정 롬이든 이미 한글패치한 롬이든 됩니다(한글패치와 겹치는 바이트가 없어 순서 무관).\n"
       "2. [Floating IPS](https://www.romhacking.net/utilities/1040/) / [Lunar IPS](https://www.romhacking.net/utilities/240/)(PC), "
       "[ROM Patcher JS](https://www.marcrobledo.com/RomPatcher.js/)(웹, 설치 없음), UniPatcher(안드로이드)로 IPS 를 롬에 적용합니다.\n"
       "3. 결과 롬(.ngc)을 에뮬레이터나 실기 플래시 카트에 넣습니다. 되돌리기 = 원본 롬.\n")
groups={}
for x in idx['mods']: groups.setdefault(x['tag'],[]).append(x)
for tag,xs in groups.items():
    g=xs[0]['gameKo']; title='%s — %s'%(g, ' / '.join(dict.fromkeys(x['ko'].split(' — ')[0] for x in xs)))
    lines=['## %s'%title,'']
    meta=cat.get('tags',{}).get(tag,{})
    intro=None
    if meta.get('intro'):
        ip_=os.path.join(REPO,'mods','docs',meta['intro'])
        if os.path.exists(ip_): intro=open(ip_,encoding='utf-8').read().rstrip()
        else: print('  ⚠ intro 없음:',ip_)
    if intro:
        # 사람이 쓴 글이 있으면 그걸 쓴다 — 자동 help 나열은 겹치므로 뺀다
        lines+=[intro,'']
    else:
        for x in xs: lines+=['**%s %s** — %s'%(x['ko'],x['ver'],x['help']),'']
        if len(xs)>1: lines+=['같은 바이트를 고치는 판들이라 **하나만** 입히세요.','']
    lines+=[HOWTO,'| 파일 | 판 | md5 |','|---|---|---|']+['| %s | %s | `%s` |'%(x['file'],x['ver'],x['md5']) for x in xs]
    lines+=['','롬은 배포하지 않습니다(차분만). 전체 목록·전후 비교는 [README](https://github.com/%s).'%IP]
    body_t='\n'.join(lines)
    try:
        rel_t=api('/repos/%s/releases/tags/%s'%(IP,tag)); api('/repos/%s/releases/%d'%(IP,rel_t['id']),json.dumps({'name':title,'body':body_t}).encode(),'PATCH')
    except Exception:
        rel_t=api('/repos/%s/releases'%IP,json.dumps({'tag_name':tag,'name':title,'body':body_t}).encode(),'POST')
    keep={x['file'] for x in xs}
    for a_ in api('/repos/%s/releases/%d/assets'%(IP,rel_t['id'])):
        if a_['name'] in keep or a_['name'].endswith('.ips'): api('/repos/%s/releases/assets/%d'%(IP,a_['id']),method='DELETE')
    for x in xs:
        api('https://uploads.github.com/repos/%s/releases/%d/assets?name=%s'%(IP,rel_t['id'],x['file']),open(os.path.join(REPO,'mods',x['file']),'rb').read(),'POST','application/octet-stream')
    print('릴리즈 %s: %s'%(tag,[x['file'] for x in xs]))
# ── 앱 스냅샷·소식 ────────────────────────────────────────────
open(os.path.join(REPO,'app','src','main','assets','mods.json'),'w',encoding='utf-8',newline='\n').write(text)
NEWS=os.path.expanduser('~/ss2/release/news.json')
try: items=json.load(open(NEWS,encoding='utf-8'))['items']
except Exception: items=[]
today=time.strftime('%Y-%m-%d')
for x in idx['mods']:
    t='조작 패치: %s — %s %s'%(x['gameKo'],x['ko'],x['ver'])
    if not any(e.get('text')==t for e in items[:40]): items.insert(0,{'date':today,'text':t})
json.dump({'items':items[:40]},open(NEWS,'w',encoding='utf-8'),ensure_ascii=False,indent=1)
print('완료: https://github.com/%s'%IP)
