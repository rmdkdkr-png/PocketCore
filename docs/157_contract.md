# 157-A 리그 인게임 메뉴 — 구현 계약서 (설계자 → 빌더 셋)

기준: `w157-base` (4513cf0 = 내부 156 위). 기획서 `handoff/ss2_next/157_인게임메뉴_기획/기획서_리그_인게임메뉴.md`(이하 «기획서»),
목업 렌더러 `시안/mock_final.py`(이하 «목업» — 우리 글꼴·우리 픽셀만), 조사 원문 `설계/조사_시안_심사.md`.
이 문서는 **묶는 계약**이다. 빌더가 계약을 바꿔야 하면 바꾸지 말고 보고서에 «계약 변경 요청»으로 적는다(통합 담당이 정한다).

목차: §0 결정 · §1 파일 소유와 규칙 · §2 흐름 · §3 덧판 native · §4 대본(cue) · §5 TileCanvas · §6 OvArt · §7 LeagueMenu ·
§8 MenuModel · §9 BoardCache · §10 LgmPages(장면) · §11 League 새 칸 · §12 EmuActivity 연결 · §13 글꼴 · §14 대체 길 · §15 시험 · §16 병합

---

## §0 유저 결정과 범위

유저 결정(기본값으로 씀):
1. 결판 연출 기본 **«보통»** — 평범한 판 ≈36f(0.6초). ★·승급·무쌍 달성·설욕 판은 일섬·호명 띠(·승급 문장) 추가. 설정 «보통 / 짧게 / 끄기»(끄기 = 옛 Java 결과 카드).
2. 칼집은 **늘 오른쪽**(c10–19).
3. 랭크 경기(H_MATCH) 중 **OPTION 탭 = 리그 «쉼»** (게임 PAUSE 대신). 안드로이드 뒤로·패드 바 «목록/종료»도 랭크 경기 중이면 쉼.
4. 진 판 기본 커서 = **«설욕전»**(설정에서 «다음 상대»로).
5. **«연속 대전»**(끔 / 10초) 있음, 기본 **끔**.

157-A 범위: 덧판(ovplane) · TileCanvas(RankVs 일반화, RankVs 출력 바이트 동일) · OvArt · Cue · LeagueMenu
(결판 모든 결과 변형 + 일섬·호명 띠·승급 문장 · 그만할까요? · 쉼 + 기권 두 번 묻기 · 서랍 탭 «순위»(내 주변 + 위 3명 단상, 위 100 넘기기는 싸면) · «전적»(요약) · «설정»)
· MenuModel · LgmPages · BoardCache · 자료 G2·G9·G10 · EmuActivity 연결 · galmuri7 글꼴 자산.

**157-A 아님**: 리그 타이틀 «대기» 칼집 · 라이벌/상대/최근 탭 · 프로필 패 · 경기 한 장 · 소리 · 진 쪽 거울 배치 · 게임 얼굴 그림
(얼굴은 따로 조사 중 — MenuModel.Side 에 캐릭터 id 를 늘 채우고 LgmPages.FaceProvider 자리만 둔다, 구현하지 않음) · G1·G3·G6 저장 칸.

---

## §1 파일 소유 · 공통 규칙

### 1.1 담당 셋

| 담당 | 파일(이것만 고친다) |
|---|---|
| **native** | `app/src/main/cpp/ovplane.c`(빈 껍데기를 채움) · `ovplane.h`(계약 — 공개 모양 고정, 주석·내부 static 은 자유) · `native.c`(연결만, §3.6) · `CMakeLists.txt`(이미 ovplane.c 들어 있음) · `Emu.java`(선언·상수는 이미 계약대로 — 주석만) |
| **render** | `TileCanvas.java` · `RankVs.java`(TileCanvas 위로 옮김, 출력 바이트 동일, 공개 모양 그대로) · `OvArt.java`(생성물) + `tools/gen_ov_art.py` · `Cue.java`(인코더·ease 는 계약 — 그대로) · `app/src/main/assets/fonts/galmuri7.ttf` + `assets/fonts/README.txt`(OFL 줄) · `tools/subset_galmuri7.py`(또는 .sh) |
| **menu** | `LeagueMenu.java` · `MenuModel.java` · `LgmPages.java` · `BoardCache.java` · `EmuActivity.java` · `League.java` · `LeagueActivity.java`(BoardCache 쓰기로만) |

아무도 고치지 않음: `league.c` · `practice.c` · `vsband.c` · `look.c` · `cheat.c` · `vpanel.c` · `pace.c` · `RankCore.java` · `RankUi.java` · `PadView.java` 등 그 밖 전부.
꼭 고쳐야 하면 고치지 말고 보고서에 이유와 함께 «필요» 로 적는다.

시험·하네스·작업 노트는 저장소 밖: `scratchpad/u157/impl/<담당>/`. JVM 시험 소스도 저장소에 넣지 않는다(기존 관례 — `u153/impl-java/jvm` 처럼 scratch).

### 1.2 지켜야 할 것

- `sizeof H` 628 그대로 · `LEAGUE_NHUB` 15 그대로 · 새 JNI 는 모두 따로 부르는 함수(이미 13개 선언됨).
- 153/156 흐름 그대로: 결판 프레임 즉시 정산 · 승리 대사 걸쇠(0x5825) · B/뒤로 대사 건너뛰기 · 찾기 겹창(rankQ, Java) · 4배속 자동 고르기 · VS 띠 · 연습 OPTION 메뉴 · 일반 세션 게임 PAUSE.
- 랭크 판의 저장·로드·리셋·설정 막힘 그대로(메뉴의 «설정» 탭은 메뉴 pref 만).
- 사용자에게 보이는 글은 한국어, 짧고 쉬운 말(«~해요» 체, 기존 카드 말투).
- 롬·상태 파일·롬에서 나온 그림을 저장소·산출물에 넣지 않는다. 미리보기·목업은 우리 자산(앱 글꼴·우리 픽셀)으로만. 하네스 결과 그림도 scratch 밖으로 안 냄.
- 커밋·코드에 모델 이름을 쓰지 않는다. 커밋은 이정표마다(컨테이너가 재시작되면 안 한 일은 사라짐). 끝줄은 작업 지시가 준 두 줄(Co-Authored-By · 세션 링크) 그대로.
- 푸시 안 함 · `feat/framegen-art` 안 건드림 · versionCode 안 올림(통합 담당).
- 경고 0: 호스트 `-Wall -Wextra`(u153/impl-nat/build.sh) · NDK 3갈래(ndk_build.sh) · `gradle :app:compileDebugJavaWithJavac --offline`.

### 1.3 브랜치(제안)

각 담당은 `git -C /home/user/pocketcore worktree add -b w157-<담당> <scratch>/w157/<담당> w157-base` 로 시작한다.
병합 순서: native → render → menu(§16).

---

## §2 흐름 한눈에

### 2.1 결판(경기 끝)

```
[UI] leaguePoll: nativeDone → leagueSettleNow 성공(153 그대로, 화면엔 아무것도 안 띄움)
     ├ BoardCache.warm(지금 나)              — 이 판 전 판은 경기 시작 때 이미 데움(§12.3)
     ├ fx == 끄기 · natOv 없음 · RECT_OK 아님 · 단일 경기 판 · 옛 리그 판 → 옛 길(아무것도 안 함, 걸쇠 때 rankResult)
     └ MenuModel.result(…) → [그림 스레드] LgmPages.result + quit → [GL] menu.armResult(model, result, quit)
                                                                       └ ov: 층 올림 + nativeOvCue(cue, ARM_ON_LATCH|id)
[게임] 승리 대사 9~10초(B·뒤로 건너뛰기 그대로) → 걸쇠 0x5825
[GL]  nativeFrame: 스텝 루프가 걸쇠 프레임에서 멈춤 → ov_tick(now, latch=1) → 대본 t=0, 보임 → 같은 vsync gl_draw 가 합성
[UI]  leaguePoll: postState()==2 → leagueExit → hubResult → (덧판이 VISIBLE 이면) lgmShown=true, 카드 없음
      아니면(2번 살펴도 안 보임) → nativeOvClear(ALL) + 옛 rankResult
```

### 2.2 쉼(경기 중)

```
[GL] onDrawFrame → menu.feed(in): 쉼 자격(volatile, UI 가 정함)이면 in 에서 OPTION(비트 3)을 늘 지움.
     OPTION 이 다른 버튼 없이 눌렸다 30f 안에 떨어짐(탭) → ov.hold(true)(이 vsync 부터 코어 0 프레임) → actions.lgmWantPause()
[UI] 아직 결판 전·경기 중이면 MenuModel.pause → [그림] LgmPages.pause(+confirm) → [GL] menu.openPause(…) — 아니면 [GL] menu.cancelPause()
     뒤로 · 패드 바 «목록/종료»(랭크 경기 중, 쉼 자격): UI 가 바로 사본·장면을 만들어 [GL] openPause(openPause 가 hold 를 켬)
쉼: 이어하기(B·OPTION 같음) → 덧판 지움·hold 풂·쥔 키 삼킴 / 순위·전적 → 서랍 / 나중에 → actions.lgmLater / 기권 → 두 번 묻기 → actions.lgmForfeit
기권 확정 → [UI] League.forfeit → leagueDone → MenuModel.result(기권패) → [GL] menu.openResultNow(…)(hold 는 그대로 — 멈춘 경기 화면 위)
```

### 2.3 동작 → 기존 함수(UI 스레드, Handler.post)

| 메뉴 | 부르는 것 |
|---|---|
| 다음 상대 | `rankNext(false)` (겹창 rankQ 를 먼저 띄우고 hubReturn(−4)) |
| 재대결 / 설욕전 n/3 | `rankRematch(m, rp)` — **G9: 무대 = rankStageFor(rp)**(pref.stage) |
| 새 캐릭터 | `rankNext(true)` (rankAuto == 2 일 때만 켜짐) |
| ◀▶ 무대 | `rankStageStep(±1)` → `menu.setStage(sel)` |
| 그만할까요? › 타이틀로 | `rankOppV = null; hubReturn(-1)` |
| › 리그 화면 | `leagueExitBoard()` |
| › 내 게임 | `hubToGame()` |
| › 게임 목록 | `leagueHub = false; goList()` |
| 쉼 › 나중에 [저장] | leagueLeave 의 «나중에 이어하기» 몸통(체크포인트 → resume → leagueExit(null)) |
| 쉼 › 기권 | leagueLeave 의 «기권» 몸통(League.forfeit) → 결판(기권패) |
| 쉼 › 그만두기 (무효) | leagueLeaveBeforeStart 의 몸통(League.cancel) |

`hubReturn`·나가기 류는 GL 스레드에서 **먼저 `menu.close()`(= nativeOvClear(ALL))** 하고 나서 `nativeLeagueHubReturn` 등(같은 Runnable 안 순서). native 도 저절로 지우지만(§3.5) 메뉴 상태를 맞추려고 Java 가 먼저 닫는다.

---

## §3 덧판 native (담당: native)

헤더 `ovplane.h` 가 정본. 여기는 뜻과 규칙.

### 3.1 상태

층마다(W·L):
- 타일 은행 `tiles[N][16]`(W 512 · L 2048), 맵 `map[cols][rows]`(W 32×32 · L 20×128) — 안쪽 표현은 자유(절대 타일 번호 11비트·팔레트·뒤집기·빈 칸).
- 팔레트 16×4(올린 값 = «바탕 상태»), 창유리 16, 채움 8(대본만 씀 — 바탕 폭 0), 쿼드 16, 스크롤 x·y, 자르기 줄 clip_y0..clip_y1(화면 줄, 기본 0..151), 줄 x 어긋남 표 152칸(바탕 0).

공통: 바탕 칸 4개 `{mode, y0, y1, arg, color}` · 보임(visible) · 걸어 둠(armed) · 대본(ops, 시작 시각, id) · `ov_hold` · 막기 비트 · 흔들림.

«바탕 상태»(Java 가 setter 로 정한 것) + «대본»(시각 t 의 값) = 그 프레임의 «유효 상태». native 는 프레임마다 바탕 상태에서 다시 계산한다(§4.3).

### 3.2 JNI(Emu.java) ↔ C

| Java(Emu) | C | 뜻 |
|---|---|---|
| `boolean nativeOvLayer(int layer, byte[] tiles, int tileBase, short[] map, int cols, int rows, int col0, int row0, short[] pals, int palBase)` | `ov_layer` | tiles(n×16, n ≤ 511)를 은행 tileBase.. 에 · map(cols×rows, 줄 먼저)을 칸 (col0,row0).. 에 · pals(np×4)를 팔레트 palBase.. 에. 셋 다 null 가능. 맵 칸 타일 = tileBase + (e & 0x1FF) — **은행 범위 안이기만 하면** 이번 묶음 밖 번호도 됨. 범위 밖이면 false, 아무것도 안 씀 |
| `boolean nativeOvCells(int layer, short[] cells, int col0, int row0, int cols, int rows, int tileBase)` | `ov_cells` | 맵 칸만(같은 칸 형식) |
| `boolean nativeOvRecolor(int layer, int col0, int row0, int cols, int rows, int pal)` | `ov_recolor` | 사각형 안 빈 칸 아닌 칸의 팔레트만 — **커서 이동**(다시 안 그림) |
| `boolean nativeOvPal(int layer, int pal, int c1, int c2, int c3)` | `ov_pal` | −1 = 그 색 그대로 |
| `boolean nativeOvQuad(int layer, int idx, int x, int y, int tile, int pal, int flags)` | `ov_quad` | 층 px · 절대 타일 · OV_Q_*(SHOW 없으면 숨김) · 16×16 = t,t+1,t+2,t+3(왼위·오른위·왼아래·오른아래) |
| `boolean nativeOvPane(int layer, int idx, int c0, int r0, int c1, int r1, int dx, int dy, boolean visible)` | `ov_pane` | 창유리(칸 사각형 포함) — 그 칸들을 dx,dy px 밀어 그림 · 숨김 · c1 < c0 = 지움 |
| `boolean nativeOvScroll(int layer, int x, int y, int clipY0, int clipY1)` | `ov_scroll` | 층 스크롤 · 자르기 줄(화면 줄, 포함) |
| `boolean nativeOvBase(int slot, int mode, int y0, int y1, int arg, int color)` | `ov_base` | 바탕 칸(§3.4) |
| `boolean nativeOvCue(int[] ops, int flags)` | `ov_cue` | 대본(§4) — flags = OV_ARM_ON_LATCH \| OV_START_NOW \| OV_SKIP_TO_END \| id<<16 |
| `void nativeOvHold(boolean on)` | `ov_hold_set` | 메뉴 멈춤(§3.5) |
| `void nativeOvBlock(int bits)` | `ov_block_set` | 입력 막기 비트 |
| `void nativeOvClear(int mask)` | `ov_clear` | OV_CLR_*(ALL = 전부 + 숨김 + hold·막기 풂) |
| `int[] nativeOvStatus()` | `ov_status` | 상태 20칸(§3.7) — 아무 스레드 |

JNI 껍데기는 이미 `ovplane.c` 에 있다(배열 길이 검사 + GetXxxArrayRegion → C 함수). C 함수는 하네스가 ctypes 로 바로 부른다(nh_stubs 의 가짜 JNIEnv 는 배열을 모름).

**스레드**: `nativeOvStatus` 말고는 GL 스레드에서만(LeagueMenu). native 는 그래도 ov 자물쇠(`pthread_mutex`) 하나로 감싼다(합성·tick·상태와 겹침 방지). `ov_hold()`·`ov_in_block()` 은 잠금 없이 volatile int.

**setter 와 대본**: setter(ov_layer·cells·recolor·pal·quad·pane·scroll·base)는 먼저 «끝난 유한 op» 를 바탕에 굽고 대본에서 뺀 뒤 바탕 상태를 바꾼다. 아직 도는 op 가 같은 대상을 쥐고 있으면 그 op 가 이긴다(끝날 때까지). → LeagueMenu 는 도는 op 의 대상을 setter 로 건드리지 않는다.

### 3.3 형식

- 타일: 16바이트, 줄 y 마다 `v = Σ (ci(x) & 3) << (14 − 2x)`, 바이트 `[2y] = v & 0xFF`, `[2y+1] = v >> 8` (RankVs.encode 그대로).
- 맵 칸 u16: `bit0-8` 상대 타일 · `bit9-12` 팔레트 · `bit13` 0 · `bit14` V · `bit15` H. `0xFFFF` = 빈 칸. 한 묶음 ≤ 511 타일이라 0xFFFF 와 안 겹침.
- 뒤집기: H = 타일 픽셀 x → 7−x, V = y → 7−y.
- 팔레트: `short[np×4]`, 칸 0 무시, 색 12비트 `0x0BGR`(R 하위 니블). 색 0 = 투명.
- 색 펼침(코어와 픽셀 동일): `r8=(c&15)*17, g8=((c>>4)&15)*17, b8=((c>>8)&15)*17` → `r5=r8>>3, g6=g8>>2, b5=b8>>3` → `R=(r5<<3)|(r5>>2), G=(g6<<2)|(g6>>4), B=(b5<<3)|(b5>>2)`, A=255. `ov_rgb()`·`Cue.argb()` 가 이미 그대로(두 쪽 값 일치 확인함).

### 3.4 합성

**자리**: `gl_draw` 에서 **잔상 뒤**. 그린 그림(잔상 켬 = ghost_apply 결과, 끔 = g_fb)을 src 로 `ov_compose(src, w, h, src_changed, &changed)` → 돌려준 버퍼를 덧판 전용 텍스처 `g_tex_ov` 로 올려 그린다(changed 일 때만 올림). `g_fb` 와 `nativeFrameBuffer` 는 날것 그대로(스크린샷 «오버레이 없음»·런처 썸네일에 메뉴가 안 들어감). 업스케일러·격자·스캔라인·색 셰이더는 합성 결과에 그대로 걸린다(게임 글자처럼). 잔상은 게임 층에만.

**게임 사각형** `ov_find_rect(fbw, fbh, &x0, &y0, &sub)` — gl_draw 와 같은 판정:
```
sub = (w ≥ 320 && w%160==0 && h%(w/160)==0 && 152 ≤ h/(w/160) ≤ 184) ? w/160 : 1
lw = w/sub, lh = h/sub
찾음 ⇔ 160 ≤ lw ≤ 320 && 152 ≤ lh ≤ 184
x0 = ((lw − 160) / 2) · sub      (288 기둥 → 64·sub)
y0 = (lh − 152) · sub            (띠는 위에 붙는다고 본다 — 앱 설정에 남은 띠는 «기술명 띠»뿐이고 늘 위, ss2comm_band_top)
```
5종 시험: 160×152 · 640×608(sub 4) · 288×152 · 160×184(위 띠 32) · 640×736(4배 + 띠). 못 찾으면 `ov_compose` = NULL(src 그대로) · 상태 RECT_OK 0 → Java 가 옛 카드로(§14).

**순서**(게임 좌표 160×152, 출력은 sub×sub 덩어리):
1. 바탕 줄 밀기: ROWSHIFT(층 2) · 일섬 베기 · 바탕 칸 SHIFT — 줄마다 dx(합이 아니라 «나중 것이 이김» — 일섬이 늘 마지막) → 빈 곳 검정.
2. 바탕 칸 0→3 차례로: DIM(채널마다 `n=(c+8)/17`(≤15) → `n'=(n·k)>>2` → 위 565 규칙으로 다시 펼침) · FILL/FILLUP(color 를 565 규칙으로) · FLASH(`c + ((255−c)·arg)/16`) · SHIFT(1번에서).
3. L 층 → 4. W 층(층마다: 맵 → 채움(GAUGE) → 쿼드). 5. 일섬 칼선. 6. 흔들림(STAMP — 게임 사각형 안 내용을 dy 만큼, 빈 줄 검정).

**층 그리기**(앞으로 그리기 권장 — 칸마다 8×8 을 화면에 뿌림):
- 칸 (c,r) 의 창유리 = 그 칸을 담은 **번호가 가장 작은** 창유리(없으면 오프셋 0·보임). 숨긴 창유리의 칸은 안 그림.
- 칸 픽셀 (px,py) → 화면 `sy = 8r + py − scrollY + pane.dy`, `sx = 8c + px − scrollX + pane.dx + rowx[sy]`.
- `clip_y0 ≤ sy ≤ clip_y1` 이고 화면 안일 때만. 색 0 은 안 그림(아래가 보임).
- 채움·쿼드는 왼위 픽셀 `(x>>3, y>>3)` 칸의 창유리를 따른다(칼집과 같이 움직이게). 자르기·줄 어긋남도 같음.
- W 맵에서 화면 밖(스크롤 0 이면 c ≥ 20 또는 r ≥ 19) 칸은 «창고» — COUNT 프레임·무대 이름 바꿔 끼울 칸을 둔다.
- 사선 드러냄(WIPE)은 그 층 사각형 안 칸의 픽셀에만(§4.4).

**언제 다시 합성하나**: 보이는 동안 `src_changed`(새 g_fb · 잔상 켬) 이거나 덧판 상태가 바뀌었거나(setter·대본 t 가 바뀜 — 돌거나 늘 도는 op 가 있을 때) 일 때만. 걸쇠·멈춤 중 연출이 끝나면 다시 올리지 않는다(4배 640×608 은 한 장 1.5MB). 더 느리면(실기) 열린 동안 1배 합성 — 157-A 에선 하지 않음, 보고서에 잰 값만.

### 3.5 멈춤 · 입력 · 저절로 지움

- `nativeFrame`: `pace_steps(..., g_paused || !g_loaded || league_post_hold() || ov_hold())`. **g_paused 와 따로**(Java 가 바·창을 닫을 때 `nativeSetPaused(pad.isBarOpen())` 로 덮어쓰기 때문).
- 입력 콜백: `blk = league_in_block | practice_in_block | look_in_block | ov_in_block()`. `ov_in_block()` = 막기 비트 | `(visible || hold ? 0xFFF : 0)`(A·B·OPTION·방향·SELECT·SP 접기 1·9·10·11 — 153 교훈). 걸어 둠(armed)만으로는 막지 않는다.
- 저절로 `ov_clear(OV_CLR_ALL)`: `load_locked` · `unload_locked` · `nativeReset` · `nativeLeagueHubReturn` · `nativeLoadState`(성공) — 코어 상태가 통째로 바뀌는 곳. (덧판은 코어 상태를 안 쓰므로 저장 전 복원·겹침 검사는 없다.)
- `gl_setup`(표면 새로) 에서 `g_tex_ov` 를 새로 만들고 덧판은 다시 합성(상태는 native static 이라 남음).

### 3.6 native.c 에 넣을 자리(이것뿐)

1. `#include "ovplane.h"`.
2. `cb_input_state`: blk 에 `| ov_in_block()`.
3. `nativeFrame`: pace 조건 `|| ov_hold()` · 스텝 루프 **뒤**(스텝 0 이어도), `gl_draw` 앞에 `ov_game_ram(ram, ramsz)`(로드된 동안 시스템 램 포인터, 아니면 NULL) · `ov_tick(t, league_post_hold())`.
4. `gl_draw`: g_fb_dirty 를 지우기 전 값을 `fresh` 로 · 잔상 뒤 `if (ov_active())` 합성·올림(`src_changed = fresh || ghost`) · 뷰포트를 정한 뒤 매번 `ov_viewport(vx, vy, dw, dh, g_vh, g_fb_w, g_fb_h)`.
5. `gl_setup`: `g_tex_ov` 만들기.
6. 저절로 지움 다섯 곳(§3.5).
153 의 걸쇠 break(`if (stop) break;`)·합성 호출(synth) 규칙은 그대로.

### 3.7 상태 `nativeOvStatus()` (int[20])

| 칸 | 이름 | 뜻 |
|---|---|---|
| 0 | OVS_VER | 1(빈 껍데기 0 → Java 는 덧판 없음으로) |
| 1 | OVS_FLAGS | 1 VISIBLE · 2 ARMED · 4 HOLD · 8 RUNNING(유한 op 남음) · 16 RECT_OK(마지막 gl_draw 그림 크기) · 32 SHOWN(보이고 나서 한 번 이상 합성) |
| 2 | OVS_T | 대본 프레임 t(없음 −1) |
| 3 | OVS_END | 유한 op 의 max(t0+dur)(없음 −1) |
| 4 | OVS_CUE_ID | 돌거나 걸어 둔 대본 id(flags>>16), 없으면 0 |
| 5 | OVS_STARTS | 대본이 시작된 횟수(코어 올림부터 — 걸쇠 시작 포함) |
| 6–10 | RX RY SUB FBW FBH | 게임 사각형(g_fb 픽셀) · sub · 그림 크기 |
| 11–14 | VX VY VW VH | 게임 160×152 자리 — **GL 뷰 픽셀, 왼위 원점** (EmuActivity 의 터치 바꿈) · 모르면 0 |
| 15 | OVS_GPAUSE | 게임 자체 일시정지(사무쇼2 램 0x61D4 == 1) 1/0 · 모르면 −1 |
| 16 | OVS_LATCH | league_post_hold() |
| 17 | OVS_COMPOSITES | 합성 횟수 |
| 18–19 | OVS_W1 OVS_W2 | 지금 라운드 승수(램 0x5821 · 0x5822) · 모르면 −1 |

램 칸은 `ov_game_ram` 포인터로 `ram[주소 − 0x4000]`(n ≥ 0x2200 일 때만, 아니면 −1). 사무쇼2 주소라 Java 는 리그 판에서만 쓴다.

뷰 자리 셈(gl_draw 뷰포트 `(vx, vy↓아래 원점, dw, dh)` 는 그림 전체 lw×lh 에 대한 것): `VX = vx + (x0/sub)·dw/lw`, `VY = (view_h − (vy + dh)) + (y0/sub)·dh/lh`, `VW = 160·dw/lw`, `VH = 152·dh/lh`.

---

## §4 연출 대본(cue)

### 4.1 시계·시작

- 시계 = 60Hz, **시각 기반**: `t = floor((now − start)·60 + 1e-9)`, now = `ov_tick` 에 들어온 단조 시각(nativeFrame 의 t). 120Hz 화면에서도 같은 박자(같은 t 가 두 vsync).
- `OV_START_NOW`: 다음 `ov_tick` 에 t=0 으로 시작하고 보임(GL 스레드에서 nativeFrame 앞에 부르면 바로 그 vsync).
- `OV_ARM_ON_LATCH`: 걸어 둠(보이지 않음 — 이미 보이던 것도 숨김). `league_post_hold()==1` 인 첫 `ov_tick`(걸쇠 프레임 바로 그 vsync, 스텝 루프 뒤)에 t=0 시작·보임·STARTS++. **걸 때 이미 1 이면 다음 ov_tick 에 시작**(레벨 판정 — 대사를 B 로 일찍 건너뛰어 장면이 늦게 걸려도 열림).
- 둘 중 꼭 하나(둘 다·둘 다 없음 = 0). `+OV_SKIP_TO_END` = 시작할 때 이미 끝 상태(돌아오기 등 «연출 없이»).
- `ops == NULL && SKIP_TO_END` = 돌던 대본을 끝 상태로(유한 op 만 — t 를 end 로 옮김; 늘 도는 op 는 계속). 걸어 둔 것(armed)엔 아무 일 없음 → 0.
- ops 는 **t0 오름차순**(Cue.build 가 정렬) — 아니면 0. op ≤ 64. op 번호·층·범위가 틀리면 0(아무것도 안 바꿈).
- 빈 대본(ops 길이 0) + START_NOW = 연출 없이 보이기만.

### 4.2 감속 `ease(e, t, t0, dur, from, to)` (계약 — C `ov_ease` = Java `Cue.ease`, 시험 벡터 일치 확인함)

```
dur ≤ 0 또는 t ≥ t0+dur → to ;  t ≤ t0 → from
u = ((t−t0) << 16) / dur                     (0..65535, 64비트)
e = LINEAR: u · OUT: 65536 − ((65536−u)²>>16) · IN: (u²)>>16 · STEP: 0
값 = from + floorDiv((to−from)·e, 65536)
```
물결표 `SIN16[k]`(k = 0..15) = `0, 98, 181, 236, 256, 236, 181, 98, 0, −98, −181, −236, −256, −236, −181, −98`.

### 4.3 평가 규칙(프레임마다, 상태 없이)

1. 유효 상태 = 바탕 상태 복사.
2. **앞 단계(아직 시작 안 함)**: op 를 **뒤에서부터** 훑어 `t < t0` 인 «오래 남는 op» 의 시작 전 값을 적용(가장 먼저 시작할 op 의 값이 마지막에 남음).
3. **진행·끝**: 앞에서부터 `t ≥ t0` 인 오래 남는 op 의 값(끝났으면 끝 값)을 적용(나중에 시작한 것이 이김).
4. **잠깐 op**(STAMP·PALCYCLE·FLASH·BOB): 진행 중일 때만 위에 덧씌움(끝나면 사라짐). dur < 0 이면 t ≥ t0 동안 계속.
5. 새 대본이 오면: 옛 대본의 유한 op 를 **모두 끝 상태로 바탕에 굽고**(건너뛴 것처럼) 늘 도는 op 는 버린 뒤 새것을 단다. 늘 도는 op(단도 까딱·고른 줄 일렁임)는 새 대본마다 다시 넣는다.
6. RUNNING = 아직 안 끝난 유한 op 가 있음. END = 유한 op 의 max(t0+dur).

오래 남는 op 의 «시작 전 값»: DIM·BASE = 그 칸을 mode·arg=from 으로 · SLIDE = from · CLIP = from · COUNT = 프레임 0 · GAUGE = 폭 from · WIPE = 다 숨김 · SCROLLY = from · ROWSHIFT = 폭 from · SHOW = b 의 반대 · PAL = 없음(바탕 팔레트).

### 4.4 op 표 (int[7] = {op, t0, dur, a, b, c, d}; a 의 공통 칸: bit0-3 층 · bit4-7 번호 · bit8-9 감속)

| op | 번호 | 대상 | a | b | c | d | 뜻 |
|---|---|---|---|---|---|---|---|
| DIM | 1 | 바탕 칸 | 칸<<4 | y0\|y1<<8 | k from | k to | 그 칸 = DIM, k(0..4) 보간 |
| SLIDE | 2 | 창유리 오프셋 | 층·창유리·감속·축<<10 | from px | to px | — | 축 0 x · 1 y |
| CLIP | 3 | 층 자르기 | 층·가장자리<<4·감속 | from | to | — | 가장자리 0 = clip_y0 · 1 = clip_y1 (족자 펼침) |
| STAMP | 4 | 잠깐 | 층·팔레트<<4 | 번쩍 프레임 | 흔들 px | 번쩍 색 | [t0, t0+b) 그 팔레트 세 색 = d · [t0, t0+dur) 세로 흔들 `dy = ((t−t0)&1) ? −c : c` |
| COUNT | 5 | dst 칸 | 층·감속·프레임 수<<16 | dst c\|r<<8\|w<<16\|h<<24 | src c\|r<<8 | dc\|dr<<8(부호 8비트) | 프레임 `k = min(n−1, floor(e(u)·n / 65536))`(u 진행) → dst 칸 = src + k·(dc,dr) 의 칸. 시작 전 0 · 끝 n−1 |
| GAUGE | 6 | 채움 칸 | 층·칸<<4·감속·색<<10·팔레트<<12 | x\|y<<12\|h<<24 | 폭 from | 폭 to | 층 px 사각형을 (팔레트, 색 1..3) 으로 — 폭 ≤ 0 이면 없음 |
| PALCYCLE | 7 | 잠깐 | 층·팔레트<<4·색칸<<10·방식<<12 | 색 A | 색 B | 주기 | 부드럽게: `p=(t−t0)%P, tri = p<P/2 ? p : P−p, w = tri·2·65536/P`, 니블마다 `A + floorDiv((B−A)·w, 65536)` · 깜빡: `((t−t0)/max(1,P/2)) % 2 == 0 ? A : B` |
| WIPE | 8 | 층 드러냄 | 층·방향<<4·감속·테색<<10·테팔레트<<12 | c0\|r0<<8\|c1<<16\|r1<<24 | 기울기 s(0..4) | — | 사각형 폭 W·높이 H(px). 칸 안 상대 px (rx, ry)(방향 1 이면 rx = W−1−rx) · `key = rx + ry·s` · `front = ease(−1 → W + H·s + 2)` · key < front−2 보임 · front−2 ≤ key < front = 테(투명 자리도 테 색으로) · key ≥ front 숨김. 끝 = 가림 없음 · 시작 전 = 다 숨김. 층마다 마지막에 시작한 WIPE 하나 |
| SCROLLY | 9 | 층 스크롤 y | 층·감속 | from | to | — | (목록 부드럽게 넘김 2px/f) |
| ROWSHIFT | 10 | 줄 어긋남 | 층(0·1·2=바탕)·감속·모양<<10 | y0\|y1<<8 | 폭 from | 폭 to | 화면 줄 y0..y1 의 x 어긋남 v: 0 그대로 v · 1 셔터 `((i−y0)>>1)&1 ? −v : v` · 2 물결 `floorDiv(v·SIN16[(i−y0+t)&15], 256)` |
| FLASH | 11 | 잠깐(일섬) | 0 | 줄 y | 가운데 색 | 테 색 | dur 12 고정 안무: f=t−t0 · f0–3 칼선(줄 y 가운데, y−1·y+1 테) x 0 → min(160, 40(f+1)) · f4–5 칼선 다 + 바탕 흰 섞기 9/16(≈55%) · f6–9 바탕 줄 y 위 −4px · 아래 +4px 밀기, 칼선은 f6–7 만 · f10–11 아무것도 |
| SHOW | 12 | 창유리 보이기 | 층·창유리<<4 | 1/0 | — | — | t0 부터 b(시작 전 반대) |
| PAL | 13 | 팔레트(오래 남음) | 층·팔레트<<4 | 색 1 | 색 2 | 색 3 | t0 부터, −1 = 그대로(진 쪽 글자 회색 등) |
| BASE | 14 | 바탕 칸 | 칸<<4·감속·모드<<10·색<<16 | y0\|y1<<8 | arg from | arg to | 모드 OV_BASE_*(FILL 은 arg = 위에서부터 칠할 줄 수 — 띠 닫힘) |
| BOB | 15 | 잠깐 | 층·쿼드<<4·축<<10 | 폭 px | 주기 | — | `((t−t0)/max(1,c/2)) % 2 ? b : 0` 만큼 쿼드를 밈(단도 16f 까딱 = c 32, b 1) |

인코더는 `Cue.java` 에 이미 있다(계약). 박자는 Java 데이터만으로 다듬는다(native 다시 빌드 없음).

---

## §5 TileCanvas (담당: render) — `TileCanvas.java` 공개 모양이 계약

### 5.1 잉크

- `interface Ink { boolean[][] mask(String s); default int bodyTop(); default boolean has(int cp); }` — mask 줄 0 = 글꼴 줄 맨 위(ascent, RankVs 그대로). `RankVs.Ink extends TileCanvas.Ink`(이미 바꿈 — RankVsTest 의 람다 그대로 됨).
- `Inks { g11, g11b, g7 }` = 갈무리11 12px(`fonts/pixel.ttf`) · 갈무리11 Bold 12px(`pixel_b.ttf`) · 갈무리7 8px(`galmuri7.ttf`). `androidInks(AssetManager)`(못 읽으면 null) · `androidInk(Typeface, sizePx)`(Paint, 안티에일리어스 끔, `has` = `Paint.hasGlyph`). JVM 시험은 AWT 잉크(RankVsTest.awtInk 와 같은 어댑터, 크기만 다르게).
- **글자 y = 한글 몸통 맨 위**(목업 기준 — LgmPages 는 목업 좌표를 그대로 옮김). `text()` 가 `y − ink.bodyTop()` 줄에 mask 를 놓는다. 갈무리11 bodyTop = 1(RankVs 의 «한글은 1줄부터»). 갈무리7 은 render 담당이 재서 보고.
- 그림자: `shadowCi ≥ 0` 이면 잉크 픽셀마다 (+1,+1) 자리가 0 또는 3 일 때만 그림자, 그 뒤 잉크(목업·RankVs 와 결과 같음).
- `covers(ink, s)`: 갈무리7 부분집합에 없는 글자(닉네임 등)면 LgmPages 가 그 줄을 g11 로.

### 5.2 그리기

`px get fill box text textW fit mini3 mini3W mini4 mini4W art` — 캔버스 밖은 자름, 칠 0..3.
- `mini3`: 목업 MINI3(3×5: 0-9 / + - ,), 글자 사이 1px, 모르는 글자 2px 띄움(목업과 같은 폭 규칙). `mini4`: RankVs 4×7 + '*' 별 5×5(한 줄 아래) — RankVs 그림·폭 규칙 그대로.
- `art(id, x, y, ci1, ci2, ci3, hflip)`: OvArt 칠 1·2·3 → ci(0 = 그 칠은 안 그림).
- 팔레트: `pal(c1,c2,c3)` 같은 세 색 = 같은 칸(≤ 16 아니면 Overflow) · `setPal` · `cellPal(c0,r0,c1,r1,slot)` 덮어씀(목업 setpal) · `claimPal(x,y,w,h,slot)` 먼저 매긴 것이 이김(RankVs mark) · `cellPalAt`.

### 5.3 인코딩

- `encode(tileBase, maxTiles, flipDedupe, strict)` · `encodeRect(c0,r0,c1,r1, …)`: 줄 먼저 훑음, 처음 나온 순서로 번호. 팔레트 없는 칸 = 0xFFFF. flipDedupe = H·V·HV 로 같은 타일은 하나 + 칸에 뒤집기 비트(판정 순서: 그대로 → H → V → HV). strict = 팔레트 없는 칸에 바탕 칠과 다른 픽셀이 있으면 Overflow(목업 «ink without palette»). maxTiles ≤ 511. 넘으면 Overflow(메시지에 무엇이 넘쳤나).
- `encodeBands(tileBase0, maxPerBand, maxTotal)`: L 층(20×128) 용 — 위에서부터 줄을 묶어 묶음마다 고유 타일 ≤ maxPerBand(묶음 안에서만 합침), 다음 묶음 tileBase = 앞 tileBase + 앞 ntiles. 합 > maxTotal 이면 Overflow. 각 Encoded 를 `nativeOvLayer(OV_L, tiles, tileBase, map, 20, rows, 0, row0, pals, 0)` 로 올림.
- Encoded.pals = 16×4(칸 0 = 0), npals = 쓴 칸 최댓값 + 1.

### 5.4 RankVs 옮기기(출력 바이트 동일)

RankVs 의 공개 모양(`render(Ink, Side, Side) → Out{tiles, map, cells}`, `side`, `placement`, `androidInk`, `fit`, `snap`, `TIER_STYLE`, `HOLD`, `FLAGS`, `MAXT`, `PAL_*`, `NAME_W`)은 그대로(EmuActivity 가 씀). 안은 `new TileCanvas(20, 4, 3)` + `claimPal`(게임 팔레트 번호 그대로) + `encode(0, 80, false, false)`(80 넘으면 null — 지금처럼). 시험: 옛 RankVs 사본(scratch)과 **1290 조합 이상**(티어 0..7 × 별 × 무쌍 MR × 배치 n/5 × 닉 10종 × 검질 3) 바이트 비교 0 차이 + 기존 RankVsTest 통과.

### 5.5 한도

W 층 한 장면 ≤ 512 타일(권장 배분 §10.2) · 팔레트 ≤ 16(층마다) · L ≤ 2048. 넘으면 Overflow → LgmPages 가 받아 `actions.lgmFallback(FB_RENDER)`.

---

## §6 OvArt (담당: render) — `tools/gen_ov_art.py` → `OvArt.java`

- 생성기는 `python3 -I tools/gen_ov_art.py <저장소 루트>` 로 돌고 `app/src/main/java/com/dudu/pocketcore/OvArt.java` 를 **결정적으로** 쓴다(두 번 돌려도 같은 파일). PIL 만 씀. 붓글자는 `app/src/main/assets/fonts/ink_b.ttf`(고운바탕 Bold, OFL)를 목업 `brush_glyph` 방식으로 1비트로 구움(난수는 생성기 안에서 고정 씨앗 — Java 는 구운 결과만 가짐). 파일 머리에 출처·OFL 한 줄.
- Java 모양: `static int w(id), h(id), px(id, x, y)`(0..3, 밖 0), `name(id)`, `COUNT`. 그림 자료는 문자열 줄 또는 byte[] — 자유.
- 이름·크기·칠의 뜻은 계약(번호 값은 생성기가 매겨도 됨, 껍데기의 순서 권장):

| 이름 | 크기 | 칠 | 출처(목업) |
|---|---|---|---|
| KATANA | 8×7 | 1 칼날 · 2 손잡이 | KATANA |
| CHAIN | 8×6 | 1 | CHAINB |
| CROWN | 7×5 | 1·2 | CROWN |
| PIP_WIN / PIP_LOSS | 6×6 | 1 / 2 | PIPW / PIPL |
| TRI_UP / TRI_DN | 5×3 | 1 | TRI_UP / TRI_DN |
| ARROW_L / ARROW_R | 4×7 | 1 | ARROW_L / ARROW_R |
| CREST | 8×8 | 1·2·3 | CREST |
| STAR5 | 5×5 | 1 | STAR5 |
| SEAL24_SEUNG / SEAL24_PAE / SEAL24_MU | 24×24 | 1 글자 · 2 인주 · 3 테 · 0 빠진 곳 | `seal(fr, …, 24, '승'/'패'/'무')` (grow 승 5 · 나머지 3) |
| SEAL16_FRAME | 16×16 | 2 · 3 · 0 | seal16 의 틀(글자 없이) |
| SEAL8_FRAME | 8×8 | 2 · 0 | seal8 의 틀 |
| CREST32 | 32×32 | 1·2·3 | CREST 를 4배 키우고 1px 테(3) — 우리 그림 |
| BRUSH152 / BRUSH72 | 152×3 / 72×3 | 2 먹 · 1 윗빛 | `brush_stroke(head=3, seed=11)` 길이별 |
| BANNER_CLOTH | 32×40 | 3 바탕 · 2 윗띠 · 0 꼬리 잘림 | banner() 천 |
| BANNER_POLE | 8×40 | 1 · 2 | banner() 깃대 |
| SHEATH_CAP_TOP / SHEATH_CAP_BOT | 7×3 | 1·2 / 2 | sheath() 마개 |
| SHEATH_EDGE | 2×16 | 1·2 | sheath() 왼쪽 테(16줄 되풀이) |

시험: 생성 결정성 · 크기 · 칠 범위 · 목업 그림과 픽셀 같음(목업 함수로 그린 비트맵과 비교).

---

## §7 LeagueMenu (담당: menu) — 껍데기 `LeagueMenu.java` 의 공개 모양이 계약

### 7.1 스레드

- LeagueMenu 는 **GL 스레드 전용**. EmuActivity 는 `gl.queueEvent(() -> menu.xxx(…))` 로만 부름(`feed` 는 onDrawFrame 안). 예외: `setPauseEligible`(volatile)·`hitTest`(아래, 불변 스냅숏 읽기)는 UI 스레드에서.
- 동작은 `Actions` 를 `Handler(Looper.getMainLooper()).post` 로 부름.
- native 는 `OvPort` 로만(`OvPort.emu()` 기본; 옛 native = UnsatisfiedLinkError → null → EmuActivity 가 natOv=false). JVM 시험은 가짜 OvPort.
- native 가 저절로 지웠으면(§3.5 — 상태에 VISIBLE·ARMED 둘 다 없음) 다음 feed 에서 닫힘으로 맞춘다(hold·쥔 키 처리 포함). 열려 있는 동안 feed 는 상태를 프레임마다 읽어도 된다(int[20]).
- 장면(LgmPages.Scene)은 그림 스레드에서 다 만든 불변 객체로 받는다. GL 스레드에서 TileCanvas 로 큰 그림을 그리지 않는다(작은 조각 patch 는 미리 만든 Encoded 로).

### 7.2 공개 메서드

`armResult(model, result, quit)` · `openResultNow(model, result, quit)` · `openPause(model, pause, confirm)` · `cancelPause()` · `openQuit()` · `showDrawer(tab, page)` · `setStage(sel)` · `patch(Encoded)` · `int feed(int in, long frameNanos)` · `onTouch(x, y, action)`(게임 px) · `back()` · `isOpen()` · `state()` · `close()` · `setPauseEligible(bool)` · UI 스레드용 `boolean hitTest(int x, int y)`(메뉴가 받을 자리인가 — 아래 7.5).

### 7.3 입력 규칙(feed)

- `in` = padMask|keyMask|axisMask(비트: A=0, B=8, OPTION=3, ↑4 ↓5 ←6 →7). 돌려주는 값 = 게임에 줄 입력.
- 닫힘: 쉼 자격이면 비트 3 을 **늘** 지움(게임 PAUSE 안 뜸). OPTION 누름 시작에 `status()[OVS_GPAUSE] == 1`(게임이 이미 자기 PAUSE)이면 그 누름은 게임에 그대로. 누른 동안 다른 게임 비트(0xFFF & ~8)가 섞이면 «조합» — 취소. 떼었을 때 조합 아님·30f(OPT_TAP_MAX) 안 → **탭** → `ov.hold(true)` → `actions.lgmWantPause()` → ST_PAUSE_PENDING(게임 입력 0).
- 열림: 게임에 0. 연 순간 쥐고 있던 비트는 뗄 때까지 무시(대사를 넘기던 A·B 연타).
- 닫음: 닫는 순간 쥔 비트는 뗄 때까지 게임에서 뺌(qHeld 방식).
- 박자는 60Hz 시계(frameNanos 기준 — vsync 수 아님): ▲▼ 첫 누름에 한 칸, 18f 쥐면 그 뒤 5f 마다. 끝에서 돌아감. 흐린 줄은 건너뜀.
- 장면 readyT 전(대본 t < readyT 이고 RUNNING): A·B·방향·OPTION = `ov.cue(null, SKIP_TO_END)` 만(고르지 않음).

| 화면 | ▲▼ | ◀▶ | A | B / 뒤로 | OPTION |
|---|---|---|---|---|---|
| 결판 | 줄 | 무대(커서와 상관없이) | 결정 | 처음: 커서만 «그만하기» · 그 위에서 또: 그만할까요? | 서랍(순위) |
| 그만할까요? | 줄 | — | 결정(취소 = 결판·커서 «그만하기») | 결판으로 | — |
| 쉼 | 줄 | — | 결정 · 기권은 확인 펼침 | 이어하기 | 이어하기 |
| 기권 확인 | — | 아니오/기권(기본 아니오) | 확정 | 접음(쉼, 커서 기권) | 접음 |
| 서랍 | 넘기기 | 탭 | (157-A 없음) | 부른 화면으로 | 부른 화면으로 |

- 연속 대전(prefs.autoNextSec == 10): 결판이 readyT 에 닿은 뒤 600f 동안 아무 입력이 없으면 `lgmNext(false)`. 입력이 한 번이라도 오면 이 결판에선 끔(도화선 표시도 숨김).
- 보류·무효 결판: 줄 «다음 상대 · 그만하기» 만.
- 열린 동안 상태 RECT_OK 가 0 이 되면(프레임 크기가 바뀌어 게임 사각형을 잃음 — 메뉴가 안 보이는데 입력만 막힘) 바로 `close()` + `actions.lgmFallback(FB_RECT)` → 결판이면 옛 rankResult, 쉼이면 옛 leagueLeave 카드.

### 7.4 커서·화면 바꾸기

- 커서 = `ov.recolor(W, 줄 칸, palOn/palOff)` + 단도 쿼드 자리 옮김(`ov.quad`). 줄 픽셀은 고른·안 고른 줄이 같게 그린다(LgmPages §10.3). 고른 줄 일렁임(PALCYCLE)·단도 까딱(BOB)은 장면 대본의 늘 도는 op.
- 결판 ↔ 그만할까요?: 다른 장면을 통째로 올리고(`layer`) 칼집 사각형에 WIPE 6f(칼 휘두름). 띠·아래 띠는 두 장면이 같은 칸이라 안 움직여 보임.
- 서랍 열기: W 페이지 + L 목록 올리고 바탕 FILL 012(전체) + CLIP 0→151 10f(족자 펼침) · 탭 = WIPE 10f · 닫기 = CLIP 151→0 8f 뒤 부른 장면을 `START_NOW|SKIP_TO_END` 로 다시.
- 무대 ◀▶: `actions.lgmStage(d)` → UI 가 pref 를 바꾸고 `setStage(sel)` → 장면 창고의 그 이름 칸을 무대 줄에 `ov.cells`.
- 순위 «12,483위 ▲212» 처럼 늦게 오는 조각은 `patch(Encoded)`(예약 타일 0x1F0–0x1FF).
- 모든 동작 전, 다음 상대·재대결은 칼집 닫힘 6f(SLIDE 0→80 + 위아래 BASE FILL 띠 닫힘) 뒤 동작을 부른다(겹창 rankQ 가 바로 덮음).

### 7.5 터치(게임 px)

- EmuActivity 가 gameBox 위(패드 위)에 투명 터치 뷰를 둔다 — gl 뷰와 같은 자리·크기라 뷰 좌표 = GL 뷰 좌표. DOWN 에서 `menu.hitTest(gx, gy)` 가 false 면 그 터치를 안 받음(패드로 감). 뷰 좌표 → 게임 px: `gx = (x − VX)·160/VW`, `gy = (y − VY)·152/VH`(OVS_VX.. — 상태를 UI 에서 읽음).
- 줄 탭 = 커서 · 같은 줄 다시 탭 = 결정 · 무대 줄 왼쪽/오른쪽 반 = ◀/▶ · 서랍 탭 줄 = 탭 바꿈 · 기권은 늘 두 단계.
- 쉼에서 칼집 밖 탭 = 이어하기 — **세로 화면만**(가로는 패드 단추가 게임 상자에 겹칠 수 있어 hitTest 를 칼집·판 정보 패로만).

---

## §8 MenuModel (담당: menu) — 칸 목록(껍데기)이 계약

UI 스레드에서 `MenuModel.result(ctx, m, Ctx)` / `pause(...)` 로 한 번 만들고 고치지 않음. 없거나 깨진 값이면 null(옛 카드로).

### 8.1 결과 변형 표

| 변형 | 판정(경기 기록 m) | outcome / mode | 낙관 · 띠 아래 | 칼집 머리 | 기본 커서 | 일섬 조건(eventful) |
|---|---|---|---|---|---|---|
| 이긴 판 | outcome W | O_WIN / M_LP | 승(SEAL) · «2 : 1» | 막대 + «지금/다음★» + «+95» | 다음 상대 | starUp·promoted·revenge |
| 진 판 | outcome L, forfeit 아님 | O_LOSS | 패(SEALK, 내 쪽 글자 회색) | 잃은 몫 붉은 꼬리 20f · «−38» | **설욕전**(prefs.lossCursor=LOSS_NEXT 면 다음 상대 · 재대결 못 하면 다음 상대) | 없음 |
| 무승부 | outcome D | O_DRAW | 무(금 팔레트) | 막대 그대로 | 다음 상대 | 없음 |
| 기권 | status forfeit | O_FORFEIT | 패(SEALK) · 점수 자리 «기권» | 진 판과 같음 | 다음 상대 | 없음 |
| 배치 중 | tier_before «배치», placement_left > 0 | M_PLACEMENT | 승/패 | ●●●○○ «배치 3/5» (막대 없음) | 위 규칙 | 없음 |
| 배치 완료 | tier_before «배치», placement_left 0 | M_PLACEMENT, placementDone | 승/패 | «배치 완료» + 새 티어 | 위 규칙 | 호명 띠 E_PLACED |
| 무쌍 | start.master | M_MASTER | 승/패 | «MR 1532 +12»(막대 없음) | 위 규칙 | 없음 |
| 무쌍 달성 | event «무쌍 달성!» | enteredMaster | 승 | 무쌍 | 다음 상대 | 예(E_MASTER) |
| 승급 | event «승급!…» | promoted | 승 | 막대 → 승급 문장(CREST32) +36f | 다음 상대 | 예 |
| ★ 올라감 | event «★ 올라감…» | starUp | 승 | 막대 금↔흰 3번 | 다음 상대 | 예 |
| 강등 위기 | event «강등 위기…» / shieldUsed | shieldUsed | 패 | 막대 테 주홍 깜빡 · «위» 도장 | 설욕전 | 없음 |
| 강등 | event «…로 강등» | demoted | 패 | «검객 → 무사» 잿빛(호명 띠 E_DEMOTE) | 설욕전 | 없음 |
| 수련생 보호 | L · tier_before 수련생 · lp_delta 0 | noviceProtect | 패 | «LP 그대로 · 수련생 보호» | 설욕전 | 없음 |
| 보류·무효 | status hold · cancelled | O_VOID | 무(SEALK) · 점수 자리 «무효» | 연출 없음(낙관만) | 다음 상대 | 없음 — 줄 «다음 상대 · 그만하기» 만 |

- `ribbons`: RIBBON_ORDER 순으로 해당 사건 최대 2개(설정 «짧게»면 0). 나머지 사건과 늘 붙는 것은 `tags`:
  연(연승 ≥ 2, «3연승») · 설(설욕 보너스 «+20» / 진 판엔 «이기면 +62» = 재대결 이기면 stakes 승 + BONUS_REVENGE) · 첫(오늘 첫 승 «+30») · 위(강등 위기) · 라(라이벌 — RankCore 꼬리표 «라이벌»).
- 줄(rows): 이긴·무·기권 = NEXT · REMATCH · NEWCHAR · DRAWER · QUIT / 진 = REMATCH(«설욕전») · NEXT · NEWCHAR · DRAWER · QUIT / 보류·무효 = NEXT · QUIT.
  REMATCH 켬 = `League.rankPlayer(opp) != null && rematchCount(opp) < 3 && oppSupported(ch, sty)` · 오른쪽 «n/3»(n = rematchCount = 이어 붙은 판 수, 이번 판 포함 — 목업 «2/3»·«1/3»). 끈 줄 rowDesc = 이유(«같은 상대와 3판을 다 했어요» · «이 조합은 아직 못 해요»). NEWCHAR 켬 = rankAuto == 2.
- 쉼 줄: started → RESUME · DRAWER · LATER(오른쪽 «저장») · FORFEIT(오른쪽 «−18» = stakes 진 값, 배치면 «배치», 수련생 «±0») / 첫 라운드 전 → RESUME · DRAWER · VOIDQUIT.
- rowDesc(r18 짧은 설명) 예: 다음 상대 «새 상대와 바로 한 판» · 재대결 «같은 상대와 한 판 더» · 설욕전 «같은 상대와 바로 다시» · 새 캐릭터 «이번 판만 직접 골라요» · 순위·전적 «순위표와 전적 보기» · 그만하기 «리그는 켠 채 쉬어요» · 이어하기 «멈춘 곳에서 바로 이어서» · 나중에 «지금 자리 저장하고 리그 화면으로» · 기권 «패배로 기록돼요».
- 쉼 머리: «쉼 · n라운드 a:b»(OVS_W1/W2 — 모르면 «쉼»만) · 판 정보 패: vs 닉 · 티어★ LP · 꼬리표 «[설] 나와 4-6 · 설욕전» · «이기면 +31 · 지면 −18»(stakes, 없으면 배치·무쌍 줄).
- 아래 띠 순위: rankNow(BoardCache 지금) · ▲▼ = rankBefore − rankNow(rankBefore = 이 판 전 판, 없으면 세션 rank0 — G1 은 157-B). 둘 다 모르면 칸을 비워 두고 나중에 patch.

---

## §9 BoardCache (담당: menu)

- `key(me, around, k)` = `씨앗:한국 날짜:myKey(me):around:k:me.games` (LeagueActivity 옛 boardKey 와 같은 요소 + k).
- `get` 아무 스레드(최근 4판 LRU) · `warm(ctx, me, around, k, avatars, onReady)` 백그라운드 «league-board»(같은 열쇠 하나만) → onReady 를 메인 스레드에 · `busy` · `invalidate`(LeagueActivity 의 새 시즌·처음부터 뒤 — 지금 `board = null` 하던 곳).
- Board: rank(rankOf) · total(RankCore.total) · rows(around ±k = `RankCore.around(..., k)` / top k = `RankCore.top(..., k)`) · top3(늘 `top(…, 3)`) · at.
- LeagueActivity: 지금의 `Board`·`boardLoad`·`boardFor` 를 BoardCache 로(내 주변 ±5 · 위 100 · avatars true — 동작 그대로). 화면 글·순서 안 바뀜.
- 게임 안: 내 주변 ±4(목록 9줄, 내 줄 r11) · avatars false(157-A 는 아바타 안 그림). 위 100 넘기기는 `top(…, 100)` 판을 따로 데워(싸면) L 층 묶음으로.

---

## §10 LgmPages · 장면 (담당: menu, 권장 — 메뉴 담당 안의 일이라 바꿔도 됨)

### 10.1 배치는 목업 그대로

160×152 = 20칸×19줄. 결판: 띠 r0–3(배경 투명 → 게임 검정) · 칼집 c10–19 × r4–16(c10 금 테 · c11–19 옻칠) · 명령 5줄 r6–15(줄 높이 16px, Galmuri11) · 무대 줄 r16 · 아래 띠 r17–18(Galmuri7). 쉼: HUD r0–3 안 가림 · 바탕 DIM 줄 32..151 k 2/4 · 판 정보 패 c0–9 × r4–9 · 아래 먹 띠 r16–18(불투명). 서랍: 바탕 FILL 012 · 머리 r0–1 · 탭 r2 · 단상 r3–5 · 열 머리 r6 · 목록 r7–15(L 층, clip 56..127) · 붓 r16 · 요약 r17 · 안내 r18.
팔레트 값은 목업 PAL·TIER 표 그대로(먹·금·주홍 · 결판 띠 = 게임 HUD 값 · 티어 = RankVs.TIER_STYLE 빛깔). 서랍 탭은 157-A 에 있는 «순위 · 전적 · 설정» 셋만 보인다(없는 탭을 보이지 않음).

### 10.2 W 타일 배분(권장)

0x000–0x1BF 장면 · 0x1C0–0x1EF 장면 덤(무대 이름 10개 창고 칸, COUNT 프레임, 단도·쿼드 타일) · 0x1F0–0x1FF 늦은 조각(patch). 장면은 32×32 캔버스 한 장(화면 20×19 + 창고)을 `encode` 해 한 번에 올림.

### 10.3 커서 그리기 규칙

고른 줄과 안 고른 줄의 픽셀은 같게: 바탕 칠 3(칼집 몸통) · 글자 칠 1 · 오른쪽 작은 글 칠 2. SEL 팔레트 `{FFF, 7DF, 46E}`(칠 3 = 주홍 막대) ↔ INK `{CEF, 789, 012}` ↔ WARN `{46E, 789, 012}` 로 recolor. 흐린 줄 = 글자를 칠 2 로. 단도는 쿼드(KATANA).

### 10.4 결판 대본(권장 박자 — 기획서 §5 표)

| 프레임 | «보통» 평범한 판 | op |
|---|---|---|
| 0–3 | 먹 번짐 무대 4/4 → 3/4 | DIM 칸0 32..135 4→3 |
| 2–7 | 띠 두 이름이 양옆에서 | SLIDE 창유리(왼 이름 c0–7 r0–3) x −48→0 · (오른 c12–19) +48→0 |
| 4–5 | 낙관 쾅 · 진 쪽 회색 | SHOW 낙관 창유리 · STAMP 낙관 팔레트 흰 2f + 흔들 2px 4f · PAL 진 쪽 팔레트 → B_DIM |
| 6–16 | 칼집 들어옴(감속) | SLIDE 칼집 창유리 c10–19 r4–16 x 72→0 OUT 10f → readyT 16 |
| 10–30 | LP 오름 · 막대 2px/f · ★ 문턱 번쩍 | COUNT(숫자 프레임 창고) · GAUGE 폭 · PALCYCLE 막대 팔레트 깜빡(주기 4, 12f) |
| 18–26 | 아래 띠 도장 4f 간격 | SHOW 도장 창유리들 |
| 늘 | 고른 줄 일렁임 · 단도 까딱 | PALCYCLE SEL 칠 2 (7DF↔5BE 주기 12, dur −1) · BOB 단도 (b 1, c 32, dur −1) |
| ≈36 | 끝(0.6초) | — |

사건 판(«보통» + eventful): 맨 앞에 FLASH(일섬, 줄 84, 0xFFF/0x7DF) 12f 를 두고 위 표 전체를 +12. 호명 띠는 칼집과 같이 r4–5 c0–9 에 오른쪽에서 4f 만에 들어와 24f 머물고 둘째로 바뀜(마지막 띠가 끝 장면에 남음). 승급 문장은 띠 뒤 +36f(CREST32 + «승급! 검객» STAMP). 끝 ≈ 60f(★) · 96f(승급).
«짧게»: 일섬·호명 띠·이름 미끄럼 없음, 낙관 SHOW+STAMP t0=0, 칼집 SLIDE t0=2 dur 8(readyT 10), COUNT t0=6 dur 10, 도장 10 에 한꺼번에 → 끝 ≈18f.
쉼: DIM 32..151 4→2 4f · 칼집 SLIDE 72→0 10f · 판 정보 패 SLIDE −80→0 8f · readyT 10. 닫기 6f(칼집 0→80) 뒤 close.

### 10.5 그만할까요? 장면

결판 장면과 띠·아래 띠 r17 이 같고, 칼집 안: 머리 «그만할까요?»(g11b) · 줄 «타이틀로(기본) / 리그 화면 / 내 게임 / 게임 목록 / 취소(흐린 글)» · r16 «오늘 4승 1패 +152»(세션 G2, 세션 없으면 rankedToday) · r18 설명 «리그는 켠 채 · A로 다시».

---

## §11 League 새 칸 (담당: menu) — league.json 선택 칸(옛 판이 읽고 다시 써도 남음, H 와 무관)

| 칸·함수 | 뜻 |
|---|---|
| **G2** `ranked.session {start, w, l, d, lp, rank0}` · `sessionStart()` · `sessionNote(JSONObject m)` · `sessionRank0(long)` · `session()` | hubStart 때 새로(rank0 = −1). 정산·기권 뒤 W/L/D·lp_delta(배치 판 LP 는 뺌) 더함. rank0 는 BoardCache 가 처음 순위를 알면 한 번만. 없으면 null |
| **G9** (EmuActivity) | `rankRematch` 의 무대 = `rankStageFor(rp)`(pref.stage: −1 상대 기본 · −2 무작위 · 0..7). 156 은 지난 경기 look.stage |
| **G10** `forfeitPreview(JSONObject m) → League.ForfeitPreview {kind, lpDelta, brokeStreak}` | askForfeit 의 셈을 옮김(배치 → PLACEMENT · stakes 진 값 0 → NOVICE · 아니면 LP) · brokeStreak = me.streak ≥ 2 면 그 값. askForfeit 도 이것을 씀(글 그대로) |
| 메뉴 설정 `pref.lgm {fx, loss, auto, remember, tab}` · `menuPrefs() → MenuModel.Prefs` · `setMenuPrefs(Prefs)` | 기본 fx 0(보통) · loss 0(설욕전) · auto 0 · remember true · tab 0. 바뀔 때만 save() |

시험: 옛 판(147·148·152·156) League 가 새 칸이 든 파일을 읽고 다시 써도 남고, 새 판이 옛 파일(칸 없음)을 읽으면 기본값.

---

## §12 EmuActivity 연결 (담당: menu)

### 12.1 켜는 조건

덧판 메뉴 = `leagueHub && hubRanked && natOv && prefs.fx != FX_OFF`(결판) · 쉼은 fx 와 상관없이 `leagueHub && hubRanked && natOv`. `natOv` = `Emu.nativeOvStatus()` 가 UnsatisfiedLinkError 없이 `[OVS_VER] ≥ 1`. 단일 경기 판(leagueId 이고 !leagueHub)·옛 12명 리그 판(!hubRanked)은 옛 그대로.

### 12.2 onDrawFrame

```java
int in = padMask | keyMask | axisMask;
if (rankQBlock) { qHeld = in; in = 0; } else if (qHeld != 0) { qHeld &= in; in &= ~qHeld; }   // 156 그대로
if (menu != null) in = menu.feed(in, System.nanoTime());
Emu.nativeSetInput(in); Emu.nativeFrame(); …
```
쉼 자격(`setPauseEligible`, hubTick·leaguePoll 에서 UI 가 갱신): `leagueId != null && leagueArmed && !leagueDone && leagueStarted && postState() == 0 && hubState ∈ {4, 또는 체크포인트 이어 하기(leagueId != null && st ≤ 2)} && rankQ == null && rankQFading == null && !lgmShown && ask == null && !(pad.isBarOpen()) && !leaving`.

### 12.3 결판

- 경기 시작(rankNewMatch 성공) 때 `BoardCache.warm(지금 나, true, 4, false, null)` — 이 판 전 순위(rankBefore).
- leaguePoll 의 `leagueSettleNow()` 가 막 정산하면(한 번만): 위 조건이면 MenuModel.result → 그림 스레드 LgmPages.result + quit → `gl.queueEvent(menu.armResult(...))`. 동시에 `BoardCache.warm(새 나)` → 준비되면 순위 조각 patch. 그림 실패(Overflow 등) → 덧판 없이 옛 길.
- 걸쇠 뒤 hubResult(→ 지금은 rankResult): `lgmArmed` 이면 `nativeOvStatus` 의 VISIBLE 을 보고 lgmShown = true(카드 없음). 아직 ARMED 면 100ms 뒤 한 번 더, 그래도 아니면 `close()` + 옛 rankResult(로그 «lgm fallback …»).
- **hubCard 와 같은 뜻의 곳마다 `|| lgmShown`**: onMenu(멈춤) · postPaused · hubTick(띠 숨김·안내) · onBackPressed(lgmShown 이면 `menu.back()`) · rankTick(st 6) · hubPickBack · hubLeave/leagueLeave 의 onDismiss · placeScreen · hubResult 맨 앞 가드 · hubStartAgain · onResume 의 leagueExit 다시 부름.
- `hubReturn` 들어갈 때 `lgmShown = false` 와 GL 쪽 `menu.close()`(nativeLeagueHubReturn 앞, 같은 Runnable).
- `lgmShown` = 결판 메뉴(카드와 같은 뜻) · `lgmOpen` = 결판·쉼·서랍 무엇이든 열림(UI 쪽 표시 — openPause/armResult 시작·close 때 맞춤). lgmOpen 동안 hubBanner(가로 화면 안내 띠)·leagueTag 숨김(VS 띠 때와 같은 규칙).

### 12.4 쉼

- `lgmWantPause()`(UI): `leagueSettleNow()` 가 결판을 잡았거나 자격이 없어졌으면 `gl.queueEvent(menu::cancelPause)`. 아니면 MenuModel.pause(started, OVS_W1/W2, stakes, G10) → LgmPages.pause/confirm → `gl.queueEvent(menu.openPause(...))`.
- 뒤로(onBackPressed)·패드 바 ACT_PICK/ACT_QUIT: 랭크 경기 중·쉼 자격이면 위와 같은 길(코어는 openPause 가 hold). **결판 메뉴가 열려 있으면**(lgmShown) 뒤로 = `menu.back()`(B), 바 «목록/종료» = `menu.openQuit()`(156 은 여기서 leagueExit(null) 로 리그를 나갔다 — 메뉴 안 «그만할까요?»로 바꿈). 쉼이 열려 있으면 바 «목록/종료» 는 무시(쉼이 이미 나가기 메뉴). 그 밖은 156 그대로(leagueLeave·hubLeave·hubPickBack). 바가 열린 채 고른 거라 바를 닫은 뒤에(156 처럼 h.post).
- lgmResume: 메뉴가 이미 닫음(hold 풂). lgmLater/lgmForfeit: 156 leagueLeave 버튼 몸통 그대로 — 단 «기권» 성공 뒤 `hubResult` 가 덧판이면 `MenuModel.result` → `openResultNow`(hold 그대로). 첫 라운드 전 «그만두기 (무효)» = leagueLeaveBeforeStart 몸통.

### 12.5 터치 뷰 · 뒤로

- `lgmTouch`(투명 View)를 메뉴가 열릴 때 root 맨 위(패드 위)·gameBox 자리에 붙이고 닫힐 때 뗌. placeScreen(회전·크기 바뀜)에서 자리 다시. §7.5 규칙.
- «나중에» 체크포인트 저장이 실패하면(156: 알림 뒤 경기 계속) `menu.close()` 로 hold 를 풀고 이어서.
- onBackPressed: ask·lookEditor 다음, hubCard 줄 자리에 `if (lgmShown || menu.isOpen()) { gl.queueEvent(menu::back); return; }`.

### 12.6 그 밖

- 상태 확인(`nativeOvStatus`)은 UI 에서 불러도 됨. 나머지 `Emu.nativeOv*` 는 EmuActivity 가 직접 부르지 않는다(LeagueMenu 만).
- 순위 «먹 가는 중…»: 서랍을 열었는데 BoardCache 가 없으면 LgmPages.board(m, null) 로 먼저 열고, onReady 에 다시 그려 showDrawer.

---

## §13 글꼴 자산 (담당: render)

- `app/src/main/assets/fonts/galmuri7.ttf` = 원본 `/home/user/CustumApKS/handoff/ss2_practice/stage1/fonts/Galmuri7.ttf`(3.7MB)를 `pyftsubset` 으로 줄임(fonttools 4.67 깔림): ASCII 20–7E · KS X 1001 한글 2,350자 · 기호(· … ★ ☆ ▲ ▼ ◀ ▶ ○ ● ⛓ ← → ↑ ↓ ~ ㆍ 등 메뉴에 쓰는 것) · `assets/league/pool.json` 닉네임·칭호 글자 · League.ROSTER 닉 · 메뉴 글(LgmPages·MenuModel 문자열) 합침. `--no-hinting --desubroutinize`, 목표 ≤ 250KB.
- 줄이는 스크립트를 `tools/` 에 두고(입력 경로는 인자), README.txt 에 한 줄 «galmuri7.ttf = 갈무리7 (Galmuri7, OFL 1.1) — 같은 폴더 OFL-Galmuri.md». OFL-Galmuri.md 는 이미 있음(같은 가족).
- 검사: 위 글자 모음이 다 들어 있나(빠진 글자 목록 0 — 닉네임 중 빠지면 LgmPages 가 g11 로 대신, 보고).

---

## §14 대체 길(옛 Java 결과 카드 rankResult · 옛 leagueLeave 카드)

| 경우 | 결판 | 쉼 |
|---|---|---|
| 설정 «끄기» | 옛 카드 | 쉼은 덧판(설정과 무관) |
| native 없음(UnsatisfiedLinkError · OVS_VER 0) | 옛 카드 | 옛 길(OPTION 은 게임 PAUSE, 뒤로 = leagueLeave) |
| RECT_OK 아님(정산 때·걸쇠 때) | 옛 카드 + close | 옛 길 |
| 장면 그림 실패(Overflow·예외) | 옛 카드 | 옛 길 |
| 걸쇠 뒤 2번 살펴도 VISIBLE 아님 | close + 옛 카드 | — |
| 열린 동안 RECT_OK 잃음 | close + 옛 카드 | close + 옛 leagueLeave 카드 |
| 단일 경기 판 · 옛 12명 리그 | 옛 길 그대로 | 옛 길 그대로 |
모두 `android.util.Log.i("PocketCore", "lgm fallback <why>")`.

---

## §15 시험

### native (리눅스 하네스 — build_nh.sh 가 CMakeLists 소스를 그대로 묶음, ovplane.c 자동 포함)
1. 단위(C 또는 ctypes로 ov_*): 색 4096 = ov_rgb · DIM 니블 되읽기 표(n 0..15 × 채널 3 → n) · 뒤집기 4종 · 창유리(겹침 → 작은 번호) · 자르기 · 줄 어긋남 · 쿼드 16×16 · 채움 · WIPE 테·방향 · COUNT · 일섬 프레임 0..11 · 흔들림 · sub 4 합성 = sub 1 합성 4배 키운 것.
2. 대본 평가: §4.3 규칙(앞 단계 뒤에서부터, 나중 시작이 이김, 잠깐 op, 굽기) — Java 와 같은 벡터 파일(render 담당이 `Cue` 평가기로 만든 JSON, scratch 공유)로 대조.
3. 사각형 5종(§3.4) 자리 · 못 찾으면 NULL.
4. 걸쇠(진짜 코어·KO 롬, JUE 한 번): ARM 뒤 대사 → 걸쇠 vsync 에 STARTS+1·VISIBLE·그 vsync 가 합성 텍스처를 그림 · 보이는 동안 코어 0 프레임(0x57C8 = 15 유지, RAM·VRAM 해시 불변) · nativeFrameBuffer crc = 덧판 없는 것 · SKIP_TO_END · 60Hz/120Hz 같은 t · 대사를 B 로 일찍 건너뛴 뒤 늦게 건 ARM 도 바로 시작.
5. hold: 경기 중 hold 300 vsync 동안 0 프레임·RAM 불변 · 풀면 다음 프레임이 바로 그다음 · 보이는/hold 동안 입력 콜백이 A·B·OPTION·SP 1/9/10/11 을 안 줌(ov_in_block 내보내 확인) · 열고 닫기 100번 누수 0.
6. 저절로 지움 다섯 곳 · gl_setup 뒤 텍스처 다시.
7. 회귀: u153 t_e2e·t_sweep·t_tail·t_fail·t_nh_ff, u149 t_quote·t_practice·t_nh 그대로 통과 · 호스트 경고 0 · NDK 3갈래 경고 0.
8. (하면 좋음) 동일성: 타이틀에서 같은 쪽을 VRAM(vsband 처럼)과 덧판에 그려 픽셀 차이 0.

### render (JVM — run_tests.sh 처럼 scratch)
1. RankVs 바이트 동일(1290+ 조합) + RankVsTest(이 기준에서 9/0 통과 확인 — run_tests.sh 의 RankVs javac 줄에 `TileCanvas.java`(·`OvArt.java`)를 더해야 컴파일됨).
2. TileCanvas: 칸당 팔레트 하나 · 16 넘침 Overflow · strict · 뒤집기 합침 되풀어 같은 그림 · encodeBands(묶음 ≤ 511, tileBase 이어짐, 합 넘침) · mini3/mini4 폭 · fit «…» · 갈무리11 bodyTop 1.
3. 목업 옮김 확인: TileCanvas+AWT 잉크로 목업 p1_result_win 을 그려(LgmPages 없이 시험 코드로) 칸·팔레트 수가 tile_report.json 근처(±15%).
4. OvArt 결정성·크기·목업 비트맵과 같음.
5. Cue: 인코더 비트 배치(ovplane.h 와 대조) · build 정렬 안정·검사 · ease 벡터 = ov_ease(이미 일치) · 평가기 벡터 JSON 내보냄.
6. 글꼴: 부분집합에 글자 모음 다 있음 · 크기.

### menu (JVM + gradle)
1. MenuModel: §8.1 변형 14가지 합성 기록 → outcome·mode·ribbons(최대 2, 순서)·tags·rows·rowOn·defaultRow(prefs 둘 다)·rematchPlayed·eventful·쉼 줄(started 둘 다).
2. LeagueMenu(가짜 OvPort·가짜 Handler): 쥔 키 무시 · readyT 전 A = SKIP 만 · B 두 번 → 그만할까요? · 기권 두 번 · OPTION 탭/조합/30f 넘음/게임 PAUSE 중 통과 · 반복 18/5(60Hz 시계, 120Hz 로 불러도 같은 박자) · 흐린 줄 건너뜀 · 동작 → Actions 매핑 표 §2.3 · 연속 대전 600f · 닫은 뒤 qHeld · 터치 hitTest·줄 두 번 탭.
3. League: G2 왕복·옛 파일 호환(147/148/152/156) · G10 = 옛 askForfeit 글 · pref.lgm 기본·저장.
4. BoardCache: 열쇠가 옛 LeagueActivity boardKey 요소와 같음 · 같은 열쇠 warm 겹침 하나 · LRU 4.
5. 기존 JVM 묶음(run_tests.sh — RankCore·LeagueRanked·MainPref·RankQuick·Glyph153·compat) 그대로 통과 · gradle 컴파일. JVM 스텁 `jvm/stub/.../Emu.java`(scratch)에 OV 상수·nativeOv* 를 더해야 새 코드가 컴파일된다(저장소 Emu.java 에서 옮겨 적기).

### 통합(통합 담당)
- 세 갈래 병합 뒤 NDK·gradle·JVM·하네스 전부 · 156 끝에서 끝(320/0) · VS 띠(343/0) · 연습 메뉴(1181/0).
- 실기 확인 목록(유저 기기): 걸쇠 → 결판 지연(다음 vsync) · 120Hz 박자 · 세로/가로 · 셰이더 켜고 끔 · 4배 서기 그림 켠 채 프레임 빠짐 · 터치 줄/다시 탭/칼집 밖 · OPTION 쉼 · 기권 두 번 · 연속 대전.

---

## §16 병합과 위험

- 병합 순서 native → render → menu. 겹치는 파일 없음(Emu.java·RankVs.Ink 는 이 기준에서 이미 계약대로). menu 는 TileCanvas·Cue 가 채워지기 전엔 LgmPages 를 JVM 에서 못 돌리므로 MenuModel·LeagueMenu(가짜 OvPort)·League·BoardCache 부터.
- 계약 변경 요청은 보고서에 «무엇·왜·누가 영향» 으로.
- 위험: 4배 합성 비용(실기에서 잼 — 바뀐 프레임만 합성) · 입력 샘(쥔 키 무시·readyT·qHeld·백스톱 0xFFF) · 스레드 경합(불변 사본·GL 전용·queueEvent·post) · 띠 위치 가정(위 — 앱 설정에 남은 건 기술명 띠뿐) · 갈무리7 부분집합 밖 닉(g11 대신) · 156 흐름 회귀(동작은 기존 함수만 부름, 찾기 겹창은 Java 그대로).

---

## §17 통합에서 정한 것(w157-int)

세 갈래를 합친 뒤 진짜 JVM 에서 앱 native.c 통째(+ 진짜 코어·롬)를 진짜 JNI 로 부르는 끝에서 끝 시험으로 찾은 것과 정한 것.

| 무엇 | 정함 | 왜 |
|---|---|---|
| §7.1 «저절로 지움» 판정 | VISIBLE·ARMED 둘 다 없고 **상태의 대본 id 가 마지막으로 보낸 우리 id 가 아닐 때만** 닫힘으로 맞춘다 | `nativeOvCue(START_NOW)` 는 다음 `ov_tick` 까지 VISIBLE 도 ARMED 도 아니다. queueEvent(openPause) 뒤 같은 vsync 의 feed 가 이것을 «지움»으로 보고 닫아, 쉼이 늘 안 열리고 코어만 ov_hold 로 멈췄다. native 가 지우면 대본도 버려 id 0 |
| 결판 ↔ 그만할까요? 칼 휘두름 | 옛 장면의 칼집 칸(c10–19 r4–16, 고른 줄 색 그대로)을 L 층에 깔고 W 에서 새 장면을 WIPE | 안 깔면 휘두름 6f 동안 아직 안 드러난 칼집 자리에 무대가 보인다(그림 담당 장면 규칙 3). 두 장면은 L 을 안 쓴다 |
| FLASH(일섬) 흰 섞기·베기 줄 | native 그대로 **게임 152줄 전부** | 걸쇠 때 위·아래 띠는 검정이라 f4–5 두 프레임만 회색으로 번쩍 — 일섬답다. 목업은 32..135 |
| PALCYCLE 홀수 주기 | Java `Cue.palCycleAt` 도 니블마다 0..15 로 자름(native `nib_mix` 와 같게) | 계약 셈은 tri ≤ (P+1)/2 라 w > 65536. 메뉴는 짝수 주기만 쓰지만 평가기·미리보기를 native 와 같게 |
| 시계 ε | 1e-6(native 보고 그대로) | 몇 주 켜 둔 단조 시계에서 1e-9 는 반올림에 진다 |
| 순위 «위 100 ↔ 내 주변» 사이 줄 | «…»(U+2026) | U+22EF 는 갈무리 셋에 없다(안드로이드는 다른 글꼴로 그림) |
| 첫 라운드 전 쉼 «그만두기» 줄 | 오른쪽 «무효» 뺌 | 48px 글자와 겹침 — 아래 띠 설명이 «무효로 닫아요 · LP 그대로» |

시험(scratch `u157/impl/merge/e2e/`): `run_all.sh` = libnhj(앱 native.c 통째 + 시험 받침, 진짜 JVM 이 System.load) · 장면 6
(결판 보통 app 60Hz · 짧게 진 판 plain 120Hz · 쉼 plain 120Hz · 쉼 app 60Hz · 결판 JUE · 저절로 지움) + `check_e2e.py`
(덧판 부름 로그를 참조 모형 ovref 에 다시 해 스냅마다 native 가 그린 텍스처와 픽셀 대조) · `run_shots.sh` = 미리보기(진짜 ov_compose,
자리표시 무대) · `check_glyphs.py`. 실기 확인 목록은 §15 그대로.


---

## §18 고침 1차(검토에서 찾은 것 — w157-int)

| 무엇 | 정함 | 왜 |
|---|---|---|
| §3.4 «언제 다시 합성하나» | 늘 도는 잠깐 op(일렁임·까딱·STAMP·FLASH)는 **값이 실제로 바뀌는 t 에서만** 바뀜으로 친다(물결 ROWSHIFT 는 폭이 0 이 아니면 늘). 합성은 줄마다 «바탕 처리 사본 + 층 그림 줄»을 지난 합성과 견줘 **바뀐 줄만** 다시 만들고, `*changed` = 지난번과 픽셀이 다를 때만 | 예전엔 늘 도는 op 가 있으면 t 가 바뀔 때마다 4배 그림 전체(1.5MB)를 다시 합성·올렸다 — 쉬는 결판·쉼이 60Hz 로 93MB/s. 이제 결판·쉼 ≈1MB/s(고른 줄·단도 줄만), 서랍 순위는 1위 왕관 반짝임(주기 8, 매 vsync) 단상 줄만 ≈14MB/s |
| `ov_compose_span(&y0, &y1)` (새 공개 함수) | 마지막 `ov_compose` 가 바꾼 그림 줄 범위. gl_draw 는 그 줄만 `glTexSubImage2D(…, 0, y0, iw, rows, …, ob + y0·iw·4)` | 줄 전체 폭이라 GLES2(UNPACK_ROW_LENGTH 없음)에서도 그대로 |
| 보인 뒤 첫 합성 | `g_shown` 0(보이기 시작)이면 그림 전체를 견줌 | 안 보이던 동안 게임 그림이 바뀌어도 gl_draw 는 ov_compose 를 부르지 않는다 — 다시 보일 때 옛 합성 줄이 남았다(끝에서 끝 픽셀 대조가 찾음) |
| §4.1 시계 | `ov_tick` 이 지난 tick 과 0.25초(pace_steps 의 긴 틈 문턱) 넘게 벌어지면 그 틈만큼 시작 시각을 미룬다 — t 는 멈춘 데서 이어짐(걸어 둔 대본은 걸쇠에 그대로) | 앱이 내려가 있는 동안 nativeFrame 은 안 불리지만 단조 시계는 간다. «연속 대전» 10초가 밖에서 다 타 돌아오자마자 다음 상대로 넘어갔다(153 찾기 겹창 시계도 선다) |
| §7.3 무장 | 결판·쉼이 **닫힌 데서**(그리고 기권 뒤 결판이) 열리면 ARM_FRAMES 16f(60Hz 시계) 동안, 그리고 A·B·OPTION·방향을 **모두 QUIET_FRAMES 10f 동안 뗄 때까지** 누름은 연출 건너뛰기만(건너뛸 것이 없으면 삼킴). 뒤로(B)도 같은 규칙 · 터치는 16f 만. 한 번 무장하면 그 열림 동안 다시 막지 않음 | readyT(대본 시각)만으로는 한 번 건너뛰면 다음 누름이 골랐다 — 대사를 넘기던 A 연타가 열린 지 0.2초에 «다음 상대»·«설욕전», B 연타는 «그만할까요?»를 오갔다(기획서 위험표 «대사 연타 → 결판 다음 상대» · «16f 무장») |
| §7.4 서랍 펼침 | 부른 화면(W)은 **그대로** 두고 위에서부터 잘라 내림(W clip y0 0→152) · L 에 서랍 쪽 사본(화면 20×19 칸, 커서 줄 색·설명·단도까지)을 위에서 드러냄(L clip y1 −1→151) · 먹 채움(바탕 칸 1) 0→152 — 셋이 같은 줄(10f, 선형). 부른 화면의 DIM(바탕 칸 0)·띠·칼집은 줄 아래 그대로. 끝나면 서랍 쪽을 W 로(같은 그림) · 순위면 보이는 목록 줄을 6f 칼 휘두름(`Scene.revealCue`). 펼치는 중 누름·터치 = 바로 끝으로 | 예전엔 펼침 첫 vsync 에 부른 화면·DIM 이 통째로 사라져 줄 아래가 날 무대(어둡게 안 한 멈춘 그림)였다(≈150ms). L 은 순위 목록이 쓰므로 목록은 다 편 뒤에 |
| §7.4 서랍 말림 | (순위면 먼저 목록을 4f 접음: L clip y1 → 55) → W ← 부른 화면(끝 상태 대본 + 커서·무대·순위 조각) · L ← 서랍 쪽 사본 · 거꾸로(8f, EASE_IN). 끝나면 부른 화면을 `SHOW_END` 로 다시(같은 그림) | 말림도 날 무대가 드러난 뒤 부른 화면이 «툭» 나타났다 |
| §7.4 칼 휘두름 단도 | 결판 ↔ 그만할까요? 휘두름(6f) 동안 새 단도(W 쿼드 0)를 숨기고 대본 t ≥ 6(또는 끝)에 보임 · 옛 단도는 L 밑깔개에(휘두름이 지나가면 새 칼집 몸통이 덮음) | 쿼드는 WIPE 에 안 가려져, 아직 안 드러난 옛 목록 옆에 새 단도가 먼저 서 있었다 |
| §11 G10 | `forfeitPreview` = 지금 나의 **사본**에서 `RankCore.settle(me,'L',start.opp_mmr, omr, false, today())`(applyRanked 와 같은 omr) — lpDelta · demoted · shieldUsed · tierAfter. 쉼 두 번 묻기 글 «패배로 기록 · −310 LP · 낭인으로 강등» / «… · 강등 위기» · askForfeit 도 | stakes(보너스·바닥·방패·강등 없는 날값)를 보여 «−80 LP» 라 해 놓고 실제는 −310 LP 강등이 될 수 있었다(LP 500..9000 × 방패 × MMR 차 680판에서 0 어긋남) |
| 진 판 «설 이기면 +N» | `League.rematchWinLp(p)` = 재대결 승 정산 그대로(설욕 +20 · 오늘 첫 승 +30 · 연승) | stakes + 20 만 더해 첫 승 +30 이 빠졌다 |
| §11 G2 세션 | `sessionStart` 는 **같은 한국 날짜**의 세션이 있으면 이어 간다(rank0 = 오늘 첫 판 전) | 리그 화면을 다녀올 때마다(새 EmuActivity → hubStart) 세션이 끊겼다 |
| §10.5 r16 | «오늘 n승 n패 ±LP» = `rankedToday`(`MenuModel.todayLine()`) — 쉼 아래 띠·서랍 전적과 같은 글·같은 숫자 | 같은 «오늘» 글자에 세션·오늘 두 숫자가 나왔다 |
| §9 BoardCache | `Board.avatars`(프로필·아바타를 데웠나) · `get(me, around, k, needAvatars)` · `warm(avatars=true)` 가 아바타 없이 데운 판을 만나면 **다시 계산 없이 아바타만**. 리그 화면은 데운 판만 «준비됨». 도는 계산(`Job`)에 늦게 온 avatars warm 은 합류 · invalidate 뒤 끝난 옛 계산은 **새 계산의 BUSY 칸·대기 목록을 건드리지 않음** | 게임 안 서랍이 아바타 없이 데운 위 100 판을 리그 화면이 그대로 써 UI 스레드에서 100줄 아바타를 만들었다 · 옛 계산이 새 대기 목록을 지워 onReady 가 안 오고 계산이 둘로 갈렸다 |
| §10 갈무리7 줄 | 7줄 글자는 자기 칸 줄 안에: 머리 둘째 줄 y41 · 전적 «LP 흐름» y81 · 순위 «12,483위»·서랍 요약 y136(쉼표 꼬리까지 r17) · 목록 닉 54px(c5–11) · 서랍 요약 «/ 39,899 · 상위 32%» 는 ▲▼ 금 칸 앞에서 끊고 금 칸은 ▲ 자리부터. 수련생 보호 = 막대 없이 «LP 그대로 · 수련생 보호»(기획서 §4.2) | 글자 맨 아랫줄이 아래 칸(커서 막대·차트·금 칸) 팔레트로 칠해졌다 |
| §8.1 낙관 | 기권 «기권» · 보류·무효 «무효» = 두 글자 낙관 `SEAL32_GIGWON`/`SEAL32_MUHYO`(32×24, c8–11) · 점수 자리 비움 | 기획서 §4.2·§8. 무효가 무승부와 같은 «무» 그림이었다 |
| §10.1 배치 빛 | «배치 n/5» = 수련생 빛(`TIER_C[0]`) — 결판 띠·서랍 전적 머리 | 경기 전 VS 띠(RankVs.placement = tier 0)와 같은 빛 |

---

## §19 157 post — 유저 요청 넷(w157-int)

유저: «리그 시작하고 나서 양측 에너지바 아래에 유저 이름/계급 표시» · «히트 수 표시랑 겹치겠지» → 결정 «체력바 아래 (비켜 주기)» ·
«아래 메시지 박스는 무용» · «랭매 등 들어가고 나오는 거 자유롭게, 끊김 없이, '나가나요?' 같은 것 하지 않는다» ·
«승리·패배 후 게이지로 상대와 나 점수 변화 주르르릉 — 인게임 디스플레이 느낌».

### 19.1 HUD 이름표(덧판 native 의 따로 된 칸 — 메뉴 층과 무관)

실측(scratch `u157/impl/post/h/measure` — 진짜 코어 CPU 대 CPU 5판, 레벨 8/8 · 15/2 · 2/15 · 10/10 · 12/12) · 다시 잼 `sweep_tag.py`(4판 · 하오마루/겐쥬로 · 나코루루/우쿄 · 갈포드/샬롯 · 카즈키/시키 — 줄 24..31 표지 밖 그린 덩어리 P1 x41..63(두 자리면 x34..) · P2 x98..127 · 표지 잉크 x0..12 · x146..158):
체력 막대 아래 검은 줄 = **게임 줄 24..31**. 그 줄의 게임 것 — 라운드 표지 P1 x0..12(이기면 ★ x0..6) · P2 x146..158(★ x152..158) ·
히트 수 P1 x34..63 · P2 x98..127 · 큰 타격 때 화면 전체 검정 한 프레임(표지까지 사라짐) → 흰 한 프레임 · 둘째·셋째 라운드 첫 조작 프레임은 붉은 띠.

| 무엇 | 정함 |
|---|---|
| API(ovplane.h) | `ov_tag(side, pix, w, h, x, y, pals6)`(side 0 = 나/P1 · 1 = 상대/P2 · pix NULL = 그 쪽 끔 · w ≤ 64 · h ≤ 8 · 칠 0..3 · pals6 = 강조 c1..c3 · 보통 c1..c3) · `ov_tag_on(on)` · `ov_tag_clear()` · `ov_tag_status(v[10])`(ON·LIVE·VIS 비트·FLASH·OCC 비트·SCANS·YIELDS·EMPH·SIG·ROUNDS) · `ov_tag_scan(raw, w, h)`(gl_draw 새 그림마다) · `ov_tag_sig()` · `ov_tag_band(src, w, h, &y0, &rows)`. JNI `nativeOvTag/nativeOvTagOn/nativeOvTagStatus` |
| 메뉴와 따로 | `ov_in_block`·`OVF_VISIBLE` 에 안 들어감(입력 안 막음 · «보임» 아님) · `ov_clear(ALL)`(메뉴 닫기)로 안 지워짐. 지우는 곳은 `ov_tag_clear` 뿐 — native 저절로 지움 다섯 자리(load·unload·허브 돌아가기·리셋·상태 불러오기 성공)와 같다 |
| 보이는 조건 | 켜짐 && **라운드 조작 중**(램 0x40A7=241 · 0x411A=1 · 0x5824=0 · 0x5823=0 — KO·대사·걸쇠엔 숨김) && 그 프레임이 «번쩍»이 아님 && 마지막으로 게임이 그 자리(이름표 칸 + 1px 둘레)에 그린 때부터 **20f**(60Hz 시계) && 지금 화면의 게임 그림(마지막 살핌)에도 그 자리가 비었음 — 쉼으로 멈춘 그림에 히트 수가 남아 있으면 시간이 지나도 안 덮는다 |
| 번쩍 | 이름표 줄 넓이 전체 80% 넘게 칠함(흰·붉은 띠) 또는 라운드 표지 넷(3,27)(11,27)(147,27)(155,27)이 다 검정 — 그 프레임만 숨기고 비켜 주기 시계는 안 건드림 |
| 비켜 주기 | 칠함 = r+g+b > 48(검정 바탕 아님). 새 그림마다 날 그림을 살펴 칠한 것이 있으면 **그 프레임부터** 숨김 → 20f 빈 뒤 다시 |
| 라운드 시작 | 조작 시작 프레임(또는 경기 중에 켬 — 체크포인트 이어 하기)부터: 표지 쪽에서 8f 펼침 · 강조 120f(닉 금빛 0x7DF · 티어 제 빛) → 15f 니블 섞기 → 보통(닉 0xBBB · 티어 빛 ⅓ 그림자 쪽) · 비켜 주기 잊음 |
| 그리기 | 메뉴가 안 보이면 gl_draw 가 게임 텍스처를 올린 뒤 이름표 줄 띠(8줄 × sub)만 `glTexSubImage2D` 로 덧칠(서명이 바뀔 때 · 잔상 길은 늘). 메뉴가 보이면(쉼) `ov_compose` 가 바탕 처리 **앞**에 칠함 — DIM·FILL·층이 위(서랍 먹 채움은 덮음). 쉬는 메뉴에서 이름표 색만 바뀌면 그 줄만 다시 합성 |
| 자리(LgmPages.hudTag) | 게임 줄 24 · P1 = 표지 뒤 x16 부터 왼쪽 맞춤 · P2 = 표지 앞 x142 까지 오른쪽 맞춤 · 한쪽 62px. «검객» 갈무리7 + STAR5 + mini3 별 숫자 + 3px + 닉(남는 폭, «…»). 배치 = «배치»(수련생 빛) · 무쌍 = «무쌍» |
| 켜는 곳(EmuActivity) | `lgmMatchStart`(경기 기록을 만들 때) · `leagueStart`(체크포인트 이어 하기) → `lgmTagsArm` → 그림 스레드 `hudTag` → GL `LeagueMenu.setTags` · `tagsOff` |

### 19.2 아래 안내 띠

랭크 판(`hubRanked`)은 아래 안내 띠를 **숨김**. 예외 = 게임 고르기 화면을 직접 밟을 때(`hubBannerNeeded`: 허브 상태 3 · 찾기 겹창 아님 · 화면 0x41C0 ∈ {2, 4, 6} — 첫 판 내 캐릭터·검질·컬러 고르기, 자동이 막혀 직접 고르기).

### 19.3 들어가고 나오기 — 묻지 않음

| 어디서 | 무엇이 | 바뀐 뒤 |
|---|---|---|
| 결판 «그만하기» · 결판 B | «그만할까요?» 장면 | **바로 타이틀로**(`lgmQuit(QUIT_TITLE)` — 리그는 켠 채, A 로 다시 찾기). 줄 설명 «타이틀로 · 리그는 켠 채». `armResult/openResultNow(…, quit = null)` |
| 타이틀·찾기·고르기·결판 뒤 — 메뉴 바 «목록»·«종료» | `hubLeave` 카드(계속/타이틀로/내 게임/순위표/목록) | `hubLeaveTo(dest)`: 목록 = 게임 목록 · 종료 = 앱 종료 · 대진 전 준비 기록은 `cancelReady`(무효 · `leagueId` 잊음 — 내려가며 onPause 가 닫은 경기의 체크포인트를 쓰지 않게) |
| 리그 타이틀에서 뒤로 | 같은 카드 | `hubLeaveTo(0)` = 게임에서 왔으면 그 게임 · 아니면 리그 화면 |
| 첫 라운드 전 나가기(옛 길) | «시작 전 — 계속/그만두기» 카드 | 바로 무효(토스트 «시작 전이라 무효로 닫았어요 — LP 그대로») · 게임 속 리그면 «무효» 결과 카드 없이 바로 타이틀(`hubReturn(-1)` — 고르기 화면 뒤로와 같게) · 한 경기 판이면 리그 화면 · 그사이 첫 라운드가 시작됐으면 보통 나가기 |
| 일반 판 패드 «리그 대전» 칸 | 오토세이브가 꺼져 있으면 «리그 대전으로 — 갈까요?» 카드 | 바로 리그 판(꺼져 있으면 토스트 «자동 저장이 꺼져 있어 지금 판은 이어지지 않아요» — 목록으로 나갈 때와 같은 규칙) |
| 이어 할 수 없는 경기(허브 — 체크포인트 없음·못 불러옴·앱 바뀜) | 막힘 카드 | 무효로 닫을 수 있으면(`League.voidKind`) **묻지 않고** 카드의 «무효로 닫고 계속» 길 그대로(`autoVoidId` 로 한 번만 — 저장 실패면 카드) |
| 점수가 걸린 경기 중 | 쉼(덧판) | 그대로 — 쉼이 나가기 메뉴(나중에·기권). **기권만** 묻는다: 한 번에 — 같은 칼집 안 기권 줄 맥박 · 아래 띠 «기권 글(−LP·강등)» + «A 한 번 더 = 기권 · B 취소» · 커서가 옮기면 확인 접음 · 연 지 8f 안의 A 는 무시(`CONFIRM_MIN`) |

`hubMatchLive()` = 경기 기록 있음 · 무장 · 결판 전 · 첫 라운드 시작(결판 프레임이 이미 왔으면 정산하고 아님). 기록은 늘 한 번 — 정산은 걸쇠·`leagueSettleNow`, 나중에(체크포인트)는 쉼에서만.

### 19.4 결판 띠 — 두 사람 LP 같이

| 무엇 | 정함 |
|---|---|
| 상대 LP | `League.applyRanked` 가 정산 앞·뒤 상대 `RankCore.Player.lp(day, adj, lpAdj)` 를 `opp_lp_before/opp_lp_after`(MASTER_LP 로 자름) · 무쌍이면 `opp_mr_before/after` 로 기록 — 정산이 쓴 `oppLpDelta`·`oppMmrDelta` 가 반영된 값이라 순위표와 같다. `MenuModel.result` 가 읽음(없으면 스냅 LP · 무효면 그대로) · 상대 Side = 뒤 값 |
| 띠 r2–3(양쪽) | r2 = 티어(갈무리7 «검객★3», 티어 빛) + LP 숫자(mini4 3칸 — 판 전 → 뒤 COUNT) · r3 = LP 막대(지금 ★ 칸 안 자리 · 58px · 게임 체력 막대처럼 바깥 끝에 붙음: 나 왼쪽 끝부터 · 상대 오른쪽 끝부터). ★ 칸이 바뀌면 반씩(끝까지 → 새 칸). 무쌍은 MR 숫자만(막대 없음) · 배치 중 나는 «배치 n/5» |
| 박자 | 칼집 머리 LP 숫자와 같은 때(tL, dL) 두 쪽이 같이 · 그만할까요?·쉼 끝 상태 대본도 끝 값 |
| 진 쪽 | 이름만 회색 — LP 줄(티어·숫자·막대)은 제 빛 그대로 |

### 19.5 시험(scratch `u157/impl/post/h` — `README.txt`)

JVM `run_jvm.sh`(+ `menu/t/PostTest.java` — 이름표 그림·자리·색 · 띠 막대/숫자 대본 · 상대 LP 기록·순위표 일치 · 기권 한 번에 · 그만하기 바로) ·
native `native/run_ov_tests.sh`(+ `t_ov_tag.py` ov.so · libnh.so) · 끝에서 끝 `e2e/run_all.sh`(+ `hud` 장면: 진짜 코어 CPU 대 CPU 한 경기 —
vsync 마다 그린 텍스처의 이름표 줄 = 날 그림 + 자바로 따로 짠 같은 규칙의 이름표 · 진짜 히트 수에 비켜 주기 · 20f 돌아옴 · KO·걸쇠 숨김 · 쉼 합성 · 서랍 덮음은 참조 모형 대조) ·
미리보기 `e2e/run_post_shots.sh`(자리표시 무대).

더함(마무리): `measure/sweep_tag.py`(OV_SO=ov.so — 진짜 코어 CPU 대 CPU 4판을 프레임마다 ov_game_ram·ov_tick·ov_tag_scan 에 넣고 날 그림에서 바로 봄: 이름표가 보인 프레임에 그 칸(+1px)은 그 프레임·지난 20 프레임 잉크 0 · 조작 중 아니면 숨김 · 21 프레임 빈 조작 중이면 보임 · 라운드 첫 조작 프레임 보임 · 표지는 둘레 밖) · `flow/run_flow.sh`(진짜 EmuActivity 바이트코드 + 안드로이드 받침 + Emu 받침 + 진짜 League 기록 — 들어가기·나가기 길 17가지에서 확인 카드(Ask)·옛 결과 카드 0 · 가야 할 화면 · 기록 한 번(무효·정산·resume·기권 · 체크포인트 한 번 쓰고 지움 · LP) · 랭크 판 아래 안내 띠는 첫 직접 고르기 화면에서만. 559d938 트리에선 12 FAIL — hubLeave·leagueBlocked·leagueLeaveBeforeStart·leagueFromGame 카드).
