# -*- coding: utf-8 -*-
"""InputPatch 레포(github.com/rmdkdkr-png/InputPatch) 배포 — 인풋(조작·게임플레이) 패치의 집.
   ① 레포 트리: patches/<file>.ips, catalog.json(=mods_catalog), docs/img/*.webp, README.md (contents API 로 커밋)
      — 이미지 폴더에 없는 원격 docs/img 파일은 지운다(낡은 필름 정리).
   ② 고정 릴리즈 태그 `mods`: 모든 IPS + mods.json (앱이 보는 색인)
   ③ 앱 동봉 스냅샷 assets/mods.json 갱신, news.json 소식.
   실행: python3 tools/pub_inputpatch.py [이미지 폴더]   (DRY=1 이면 README/색인만 출력)
   이미지: 전후 비교는 anim.py 가 만든 작은 애니메이션 WebP(원본|패치 나란히, 프레임 번호) — 긴 필름 대신."""
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
imgs=sorted(os.path.basename(p) for p in glob.glob(os.path.join(IMGDIR,'*.webp'))+glob.glob(os.path.join(IMGDIR,'*.png')))
def img(name, alt, width=None):
    if name not in imgs: return ''
    w=(' width="%d"'%width) if width else ''
    return '<img src="%s/docs/img/%s" alt="%s"%s>'%(RAW,name,alt,w)
def fig(name, alt, caption, width=None):
    """그림 + 설명 한 줄. 파일이 없으면 통째로 생략(설명만 남는 일 없음)."""
    t=img(name,alt,width)
    return (t+'\n\n*'+caption+'*\n') if t else ''
rows='\n'.join('| %s | %s | %s | [받기](%s) |'%(x['gameKo'],x['ko'],x['ver'],x['url']) for x in idx['mods'])
readme = """<div align="center">

# 🎮 InputPatch

**네오지오 포켓 컬러 격투게임의 「손맛」 패치 모음** — 발동 프레임·후경직 같은 **입력 체감**을 고치는 IPS.

한글패치([KrPatch](https://github.com/%(KR)s))가 *글*을 고친다면, 여기는 *손*을 고칩니다.
[**PocketCore 앱**](https://github.com/%(PC)s/releases/tag/app)이면 「업데이트 확인」 한 번 → 설정 토글 한 번.

%(hero)s
</div>

## 목록

| 게임 | 패치 | 판 | |
|---|---|---|---|
%(rows)s

md5·크기는 [릴리즈 `mods`](https://github.com/%(IP)s/releases/tag/mods) 의 `mods.json` 에 있습니다.

## 왜 필요한가

네오지오 포켓은 버튼이 A·B 둘뿐이라 **길게 누르면 강**입니다. 그래서 강 기본기는 게임이 「길게 누르나 보자」며
기다리는 **6~8프레임**을 통째로 더 먹습니다 — 아케이드 원작엔 없는 손해. 여기 패치들은 **그 손해만큼만** 돌려주고,
누르는 길이로 약/강을 가르는 방식·대미지·판정은 그대로 둡니다.

## 빠른 기본기 — FastCD (SvC · KOF R-2)

서서 강펀·강킥 중 **유독 늘어진 기술**의 발동을 애니메이션 대본에서 당깁니다. 규칙: **강은 약보다 반드시 느리게(묵직)**,
캐릭터별 차이 보존, 원본이 이미 빠르면 손대지 않음. 여백은 두 게임 다 **펀치 +4 · 킥 +6**(원본 약→강 차이의 중앙값).

%(kof)s
- SvC v1.4 — 11기술(하오마루 강펀 26→18, 레오나 강킥 18→12, 쿄 강펀 14→8 …), 16바이트.
- KOF R-2 v1.1 — 8기술(쿄 강펀 16→10, 레오나 강킥 20→14 …), 11바이트. 이식소 제작.
- 전 기술 에뮬레이터 실측(패치 후 재측정): 발동·피해·동작 흐름·강<약 역전 없음 확인.

## 평타 콤보 — SS2 (검수용)

사무라이 스피리츠! 2 는 **카운터가 아니면 평타 콤보가 안 들어갑니다** — 약베기(B 탭) 후경직이 상대 경직과 거의 같아서.
아수라 서서 약베기의 회복 틱을 **7→3(−8프레임)** 으로 줄이면 「약베기 → 살짝 전진 → 약베기」가 2타로 들어갑니다.
아직 **캐릭터 1명·기술 1개** 실증이라 `[검수용]` 입니다.

%(ss2)s
## PocketCore 에서 쓰기

%(shots)s

1. 롬 목록 화면 **「업데이트 확인」** — 이 저장소의 색인과 IPS 를 받습니다.
2. **설정** — 게임 안에서 열면 그 게임 것만 보입니다. 토글을 켭니다.
3. 게임을 다시 열면 원본은 그대로 두고 **사본**에 한글패치 위로 얹혀 실행됩니다.

직접 입히려면 IPS 를 순정 롬(또는 한글패치 롬 — 겹치는 바이트가 없어 어느 쪽이든)에 Lunar IPS 등으로 적용하세요.

## 더 보기

프레임은 램이 아니라 **롬의 애니메이션 대본**이 정합니다. 찾는 법·실측표는 [thinkbox](https://github.com/rmdkdkr-png/thinkbox) 의 `knowledge/` 에.

## 저작권 · 책임

게임과 그 그림·음악·이름의 권리는 **SNK 등 원 권리자**의 것입니다. 비영리 팬 패치이며 **롬을 배포하지 않습니다**(차분만).
있는 그대로 제공되며 사용에 따른 문제의 책임은 사용자에게 있습니다. 권리자 요청 시 즉시 내립니다.
""" % dict(KR=KR, PC=PC, IP=IP, rows=rows,
           hero=fig('svc_kyo_hp_orig_vs_fastcd.webp','SvC 쿄 강펀 원본 vs FastCD',
                    'SvC 쿄 서서 강펀 — 왼쪽 원본(명중 f14) · 오른쪽 FastCD(f08). 1/5 속도, HIT 는 명중 프레임.'),
           kof=fig('kof_kyo_hp_orig_vs_fastcd.webp','KOF R-2 쿄 강펀 원본 vs FastCD v1.1',
                   'KOF R-2 쿄 서서 강펀 — 왼쪽 원본 · 오른쪽 FastCD v1.1. 준비 동작은 같고 명중이 6프레임 빠르다(HIT 표시).'),
           ss2=fig('ss2_asura_combo_orig_vs_t3.webp','SS2 아수라 콤보 원본 vs t3',
                   '아수라 약베기→약베기 — 왼쪽 원본(2타 전에 상대가 풀림) · 오른쪽 t3(2타가 경직 중 명중). 1/3 속도.'),
           shots=' '.join(img(n,n,240) for n in imgs if n.startswith('app_')))
if os.environ.get('DRY'): print(text); print(readme); sys.exit(0)

# ── 레포 트리 커밋 ─────────────────────────────────────────────
for m in cat['mods']:
    put_file('patches/'+m['file'], open(os.path.join(REPO,'mods',m['file']),'rb').read(), '패치: %s %s'%(m['id'],m['ver']))
put_file('catalog.json', json.dumps(cat,ensure_ascii=False,indent=1).encode('utf-8'), '카탈로그 갱신')
put_file('mods.json', text.encode('utf-8'), '색인 갱신')
for n in imgs: put_file('docs/img/'+n, open(os.path.join(IMGDIR,n),'rb').read(), '이미지: '+n)
try:
    for f in api('/repos/%s/contents/docs/img'%IP):
        if f['name'] not in imgs:
            api('/repos/%s/contents/docs/img/%s'%(IP,f['name']), json.dumps({'message':'낡은 이미지 정리: '+f['name'],'sha':f['sha']}).encode(),'DELETE'); print('원격 이미지 삭제:',f['name'])
except Exception as e: print('원격 이미지 목록 실패(무시):',e)
put_file('README.md', readme.encode('utf-8'), '소개글 갱신 (자동 — pub_inputpatch.py)')
print('트리 커밋: patches %d · 이미지 %d · README'%(len(cat['mods']),len(imgs)))
# ── 릴리즈 ────────────────────────────────────────────────────
body="## 조작 패치 색인 (PocketCore 앱이 보는 자리)\n\n앱은 이 릴리즈의 `mods.json` 과 IPS 를 받습니다. 사람이 읽을 소개·전후 비교는 [README](https://github.com/%s) 에.\n\n| 게임 | 패치 | 판 | md5 |\n|---|---|---|---|\n%s\n"%(IP,'\n'.join('| %s | %s | %s | `%s` |'%(x['gameKo'],x['ko'],x['ver'],x['md5']) for x in idx['mods']))
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
