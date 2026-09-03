# -*- coding: utf-8 -*-
"""조작(게임플레이) 패치 채널 배포 — PocketCore 레포 고정 태그 `mods`.
   tools/mods_catalog.json(SSOT) → mods.json 색인 생성 → 태그 `mods` 에 IPS 전부 + mods.json 업로드(같은 이름은 지우고
   다시) → 앱 동봉 스냅샷 app/src/main/assets/mods.json 갱신 → ~/ss2/release/news.json 에 바뀐 판만 소식 적립.
   IPS 원본은 레포 mods/<file>. DRY=1 이면 색인만 출력."""
import base64, json, os, re, subprocess, sys, time, urllib.request, hashlib
HERE=os.path.dirname(os.path.abspath(__file__)); REPO=os.path.dirname(HERE)
PC='rmdkdkr-png/PocketCore'; DL='https://github.com/%s/releases/download/%s'
cat=json.load(open(os.path.join(HERE,'mods_catalog.json'),encoding='utf-8')); TAG=cat['tag']
sys.path.insert(0,HERE); from games_data import load as gload
G=gload()['games']

idx={'ver':1,'mods':[]}
for m in cat['mods']:
    p=os.path.join(REPO,'mods',m['file']); b=open(p,'rb').read()
    assert b[:5]==b'PATCH' and b.endswith(b'EOF'), m['file']
    idx['mods'].append({'id':m['id'],'game':m['game'],'gameKo':G[m['game']]['ko'],'ko':m['ko'],'ver':m['ver'],
                        'url':DL%(PC,TAG)+'/'+m['file'],'file':m['file'],'md5':hashlib.md5(b).hexdigest(),
                        'size':len(b),'default':m.get('default','disabled'),'help':m['help']})
text=json.dumps(idx,ensure_ascii=False,indent=1)
if os.environ.get('DRY'): print(text); sys.exit(0)

url=subprocess.check_output(['git','-C',os.path.expanduser('~/ss2/repo/app'),'remote','get-url','origin'],text=True).strip()
TOKEN=re.search(r'://[^:]*:?([A-Za-z0-9_]+)@github',url).group(1)
def api(path,data=None,method=None,ctype='application/json'):
    u=path if path.startswith('http') else 'https://api.github.com'+path
    req=urllib.request.Request(u,data=data,method=method)
    req.add_header('Authorization','token '+TOKEN); req.add_header('Accept','application/vnd.github+json')
    if data is not None: req.add_header('Content-Type',ctype)
    with urllib.request.urlopen(req,timeout=180) as r: b=r.read()
    return json.loads(b) if b else {}
rows='\n'.join('| %s | %s | %s | `%s` |'%(x['gameKo'],x['ko'],x['ver'],x['md5']) for x in idx['mods'])
body="""## PocketCore 조작 패치 채널 (게임플레이 패치 모음)

한글패치가 **아닌** 게임플레이·조작 개조(IPS)를 한 곳에 모읍니다. **PocketCore 앱**은 「업데이트 확인」 한 번으로
이 색인(`mods.json`)을 받아 설정에 게임별 토글로 보여 주고, 켠 것만 실행 사본에 얹습니다(원본 롬 불변, 한글패치와
겹치는 바이트 없음). 직접 입히려면 아래 IPS 를 순정 또는 한글패치 롬에 Lunar IPS 등으로 적용하세요.

| 게임 | 패치 | 판 | IPS md5 |
|---|---|---|---|
%s

`[검수용]` 표시는 아직 검증 중인 실험판입니다 — 켜 보고 이상하면 끄면 됩니다. 각 패치의 설명은 앱 설정 문구와 같습니다.
권리는 원 권리자(SNK 등)에게 있으며 롬은 배포하지 않습니다(차분만). 권리자 요청 시 즉시 내립니다.
""" % rows
try:
    rel=api('/repos/%s/releases/tags/%s'%(PC,TAG)); api('/repos/%s/releases/%d'%(PC,rel['id']),json.dumps({'name':'조작 패치 채널','body':body}).encode(),'PATCH'); print('릴리즈 갱신',TAG)
except Exception:
    rel=api('/repos/%s/releases'%PC,json.dumps({'tag_name':TAG,'name':'조작 패치 채널','body':body,'make_latest':'false'}).encode(),'POST'); print('릴리즈 생성',TAG)
names={x['file'] for x in idx['mods']}|{'mods.json'}
for a in api('/repos/%s/releases/%d/assets'%(PC,rel['id'])):
    if a['name'] in names: api('/repos/%s/releases/assets/%d'%(PC,a['id']),method='DELETE')
for x in idx['mods']:
    api('https://uploads.github.com/repos/%s/releases/%d/assets?name=%s'%(PC,rel['id'],x['file']),open(os.path.join(REPO,'mods',x['file']),'rb').read(),'POST','application/octet-stream'); print('업로드',x['file'])
api('https://uploads.github.com/repos/%s/releases/%d/assets?name=mods.json'%(PC,rel['id']),text.encode('utf-8'),'POST','application/json'); print('업로드 mods.json')
# 앱 동봉 스냅샷
snap=os.path.join(REPO,'app','src','main','assets','mods.json')
open(snap,'w',encoding='utf-8',newline='\n').write(text); print('스냅샷',snap)
# 소식
NEWS=os.path.expanduser('~/ss2/release/news.json')
try: items=json.load(open(NEWS,encoding='utf-8'))['items']
except Exception: items=[]
try:
    old={m['id']:m['ver'] for m in json.load(urllib.request.urlopen(DL%(PC,TAG)+'/mods.json',timeout=30))['mods']}
except Exception: old={}
today=time.strftime('%Y-%m-%d')
for x in idx['mods']:
    if old.get(x['id'])!=x['ver']:
        t='조작 패치: %s — %s %s'%(x['gameKo'],x['ko'],x['ver'])
        if not any(e.get('text')==t for e in items[:20]): items.insert(0,{'date':today,'text':t})
json.dump({'items':items[:40]},open(NEWS,'w',encoding='utf-8'),ensure_ascii=False,indent=1)
print('완료: https://github.com/%s/releases/tag/%s'%(PC,TAG))
