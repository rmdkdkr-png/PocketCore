# -*- coding: utf-8 -*-
"""InputPatch 레포(github.com/rmdkdkr-png/InputPatch) 배포 — 인풋(조작·게임플레이) 패치의 집.
   ① 레포 트리: patches/<file>.ips, catalog.json(=mods_catalog), docs/img/*.png, README.md (contents API 로 커밋)
   ② 고정 릴리즈 태그 `mods`: 모든 IPS + mods.json (앱이 보는 색인)
   ③ 앱 동봉 스냅샷 assets/mods.json 갱신, news.json 소식.
   실행: python3 tools/pub_inputpatch.py [이미지 폴더]   (DRY=1 이면 README/색인만 출력)"""
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
        'default':m.get('default','disabled'),'help':m['help']})
text=json.dumps(idx,ensure_ascii=False,indent=1)

# ── README ────────────────────────────────────────────────────
imgs=sorted(os.path.basename(p) for p in glob.glob(os.path.join(IMGDIR,'*.png')))
def img(name, alt, width=None):
    if name not in imgs: return ''
    w=(' width="%d"'%width) if width else ''
    return '<img src="%s/docs/img/%s" alt="%s"%s>'%(RAW,name,alt,w)
rows='\n'.join('| %s | %s | %s | `%s` | [받기](%s) |'%(x['gameKo'],x['ko'],x['ver'],x['md5'][:12],x['url']) for x in idx['mods'])
readme = """<div align="center">

# 🎮 InputPatch

**네오지오 포켓 컬러 격투게임의 「손맛」 패치 모음** — 발동 프레임·후경직 같은 **입력 체감**을 고치는 IPS.

한글패치([KrPatch](https://github.com/%(KR)s))가 *글*을 고친다면, 여기는 *손*을 고칩니다.
[**PocketCore 앱**](https://github.com/%(PC)s/releases/tag/app)이면 「업데이트 확인」 한 번 → 설정 토글 한 번으로 켜집니다.

%(hero)s

</div>

## 왜 필요한가 — 이 기종의 입력 손해

네오지오 포켓은 버튼이 A·B 둘뿐이라 **강공격을 「길게 누르면 강」**으로 가릅니다. 그래서 강 기본기는 버튼을 누른 뒤
**게임이 "길게 누르나 보자"며 기다리는 6~8프레임**을 통째로 더 먹습니다. 아케이드 원작은 강이 별도 버튼이라 안 내는
손해입니다. 여기 패치들은 **그 손해만큼만** 돌려주고, 누르는 길이로 약/강을 가르는 방식·대미지·판정은 그대로 둡니다.

| 게임 | 패치 | 판 | IPS md5 | |
|---|---|---|---|---|
%(rows)s

## 빠른 기본기 — FastCD (SvC · KOF R-2)

서서 강펀·강킥 중 **유독 늘어진 기술**의 발동을 애니메이션 대본(WAIT 틱)에서 당깁니다.
규칙은 유저가 직접 정했습니다 — **강은 같은 캐릭터의 약보다 반드시 느리게(묵직)**, **캐릭터별 차이는 보존**, **원본이 이미
빠르면 손대지 않음**. SvC 는 약+4, KOF R-2 는 원본 중앙값을 따라 펀치 +4 · 킥 +6.

%(svc)s

*SvC 쿄 서서 강펀 — 위: 원본(누름→판정 14프레임), 아래: FastCD(8프레임). 2프레임 간격 필름.*

%(kof)s

*KOF R-2 쿄 서서 강펀 — 위: 원본(모션 16프레임), 아래: FastCD v1.1(10프레임). 2프레임 간격 필름 (이식소 제작).*

- SvC v1.3: 16기술 단축(하오마루 강펀 26→18, 레오나 강킥 18→10, 쿄 강펀 14→8·강킥 14→12 …), 22바이트.
- KOF R-2 v1.1: 8기술 단축(쿄 강펀 16→10, 레오나 강킥 20→14, 셰르미 강펀 16→12 …), 11바이트 — 이식소 제작.
- 검증: 전 기술 에뮬레이터 실측(패치 후 재측정) — 발동 프레임·피해·동작 흐름·**강<약 역전 없음** 확인.

## 평타 콤보 — SS2 (검수용 실증판)

사무라이 스피리츠! 2 는 **카운터가 아니면 평타 콤보가 안 들어갑니다** — 강베기 후경직(34f)이 상대 경직(36f)과 거의 같아서.
아수라 서서 강베기의 **회복 셀 틱을 7→3(−8f)** 으로 줄이면 「강베기 → 살짝 전진 → 강베기」가 상대가 경직에서 못 나온 채
2타로 들어갑니다(실측: 2타 명중 + 상대 중립 복귀 없음). 아직 **캐릭터 1명·기술 1개**짜리 실증이라 `[검수용]` 입니다.

%(ss2)s

*아수라 강베기→강베기 — 위: 원본(2타 전에 상대가 풀림), 아래: t3(2타가 경직 중 명중). 4프레임 간격 필름.*

## PocketCore 에서 쓰기

%(shots)s

1. 롬 목록 화면 **「업데이트 확인」** — 이 저장소의 색인(`mods.json`)과 IPS 를 받습니다.
2. **설정** — 게임 안에서 열면 그 게임 것만, 런처에서 열면 게임별로 묶여 보입니다. 토글을 켭니다.
3. 게임을 다시 열면 **원본은 그대로 두고 사본**(`PocketCore/.patched/`)에 한글패치 위로 얹혀 실행됩니다.

직접 입히려면 IPS 를 순정 롬(또는 한글패치 롬 — 겹치는 바이트가 없어 어느 쪽이든)에 Lunar IPS 등으로 적용하세요.

## 어떻게 찾았나

- 프레임은 램이 아니라 **롬의 애니메이션 대본**이 정합니다. SVC·KOF 는 `00 03 NN`(WAIT NN틱, 1틱=2프레임) 문법,
  SS2 는 다른 엔진 — 4바이트 레코드 `[틱][플래그][셀][00]` 에 램 틱 카운트다운.
- 문법 추측·커서 역산은 자주 샙니다. 최종 판정은 늘 **패치 → 재측정**: 후보 바이트를 하나씩 −1 해 보고 「정확히
  2프레임 줄고 피해·동작이 그대로」인 것만 다이얼로 채택합니다.
- 상세 실측표·방법론은 [thinkbox](https://github.com/rmdkdkr-png/thinkbox) 의 `knowledge/` 에 있습니다.

## 저작권 · 책임

게임과 그 그림·음악·이름의 권리는 **SNK 등 원 권리자**의 것입니다. 비영리 팬 패치이며 **롬을 배포하지 않습니다**(차분만).
있는 그대로 제공되며 사용에 따른 문제의 책임은 사용자에게 있습니다. 권리자 요청 시 즉시 내립니다.
""" % dict(KR=KR, PC=PC, rows=rows,
           hero=img('svc_kyo_hp_orig_vs_fastcd.png','SvC 쿄 강펀 원본 vs FastCD',900),
           svc=img('svc_kyo_hp_orig_vs_fastcd.png','SvC 쿄 강펀 원본 vs FastCD',900),
           ss2=img('ss2_asura_combo_orig_vs_t3.png','SS2 아수라 콤보 원본 vs t3',900),
           kof=img('kof_kyo_hp_orig_vs_fastcd.png','KOF R-2 쿄 강펀 원본 vs FastCD v1.1',900),
           shots=' '.join(img(n,n,300) for n in imgs if n.startswith('app_')))
if os.environ.get('DRY'): print(text); print(readme); sys.exit(0)

# ── 레포 트리 커밋 ─────────────────────────────────────────────
for m in cat['mods']:
    put_file('patches/'+m['file'], open(os.path.join(REPO,'mods',m['file']),'rb').read(), '패치: %s %s'%(m['id'],m['ver']))
put_file('catalog.json', json.dumps(cat,ensure_ascii=False,indent=1).encode('utf-8'), '카탈로그 갱신')
put_file('mods.json', text.encode('utf-8'), '색인 갱신')
for n in imgs: put_file('docs/img/'+n, open(os.path.join(IMGDIR,n),'rb').read(), '이미지: '+n)
put_file('README.md', readme.encode('utf-8'), '소개글 갱신 (자동 — pub_inputpatch.py)')
print('트리 커밋: patches %d · 이미지 %d · README'%(len(cat['mods']),len(imgs)))
# ── 릴리즈 ────────────────────────────────────────────────────
body="## 조작 패치 색인 (PocketCore 앱이 보는 자리)\n\n앱은 이 릴리즈의 `mods.json` 과 IPS 를 받습니다. 사람이 읽을 소개·스샷은 [README](https://github.com/%s) 에.\n\n| 게임 | 패치 | 판 | md5 |\n|---|---|---|---|\n%s\n"%(IP,'\n'.join('| %s | %s | %s | `%s` |'%(x['gameKo'],x['ko'],x['ver'],x['md5']) for x in idx['mods']))
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
