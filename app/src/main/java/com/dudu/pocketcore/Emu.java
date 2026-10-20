package com.dudu.pocketcore;

import java.nio.ByteBuffer;

/** Thin JNI surface. Everything real happens in native.c. */
public final class Emu {
    static { System.loadLibrary("pocketcore"); }

    // libretro joypad bit positions
    public static final int B = 0, Y = 1, SELECT = 2, START = 3,
            UP = 4, DOWN = 5, LEFT = 6, RIGHT = 7, A = 8, X = 9, L = 10, R = 11;

    public static native int  nativeLoad(String corePath, String romPath,
                                         String sysDir, String saveDir, String optionsFile);
    public static native void nativeUnload();
    public static native void nativeSurfaceCreated();
    public static native void nativeResize(int w, int h);
    public static native void nativeFrame();
    public static native void nativeSetInput(int mask);
    public static native void nativeReset();
    public static native void nativeSetOption(String key, String value);
    public static native void nativeSetIntegerScale(boolean on);
    public static native void nativeSetTurbo(boolean on);
    /** 화면 표시 — up: 0 끔 · 1 샤프 · 2 Scale2x · 3 xBR · 4 OmniScale (Settings.UPSCALERS 순서), 나머지 세기 0..100.
     *  GL 스레드에서 부를 것(queueEvent). */
    public static native void nativeSetDisplay(int up, int mix, int grid, int scan, int ghost, int color, int soft, boolean integer);
    public static native int  nativeSaveState(String path);
    public static native int  nativeLoadState(String path);
    public static native int  nativeFrameWidth();
    public static native int  nativeFrameHeight();
    public static native ByteBuffer nativeFrameBuffer();
    public static native void nativeSaveSram();
    public static native void nativeAudioPause();   /* 백그라운드 — 오디오 장치를 놓는다 */
    public static native void nativeAudioResume();  /* 복귀 — 새 스트림으로 다시 연다 */
    /** 176 음색 다듬기(저음 보강·고음 눅이기·좌우 넓힘·방 울림) 켬/끔 — 소리 카드 콜백이 다음 묶음부터 */
    public static native void nativeSetSoundFx(boolean on, int bass, int treble, int width, int reverb, int room, boolean bgm);   /* 178: 세부 0..100(50 = 기본) · 179: bgm = 배경음만 */
    public static native void nativeRunFrames(int n); /* GL 없이 n프레임 — 썸네일 캡처용 */
    public static native void nativeSetHitFx(int mode);        /* 182 타격 이펙트 — 0 끔 · 1 켬, 사무쇼2 판에서만. 191: 코어 옵션도 넣으므로 GL 스레드에서 */
    public static native void nativeSetCallouts(boolean on);   /* 182 타격 알림(COUNTER·PUNISHER·FIRST ATTACK·REVERSAL) — 사무쇼2 판에서만 켬. 191: GL 스레드에서 */
    public static native void nativeSetHitFlash(boolean on);   /* 191 몸 번쩍(앞 강베기) — 이펙트와 따로, 사무쇼2 판에서만 켬. GL 스레드(코어 옵션) */
    public static native int  nativeCoreHits();                /* 191 지금 코어가 타격 알림·이펙트·몸 번쩍을 그리나(비트 1·2·4, 0 = 앱이 그림) */
    /* 151: 앱 보간(nativeSetFrameGen·nativeFrameGenActive) 폐기 — 120Hz 사이 칸은 코어의 프레임 패치만 */
    /** 실측 전 대용 패널 주사율(Display.getRefreshRate) — 코어의 GET_TARGET_REFRESH_RATE 답에 쓴다. */
    public static native void nativeSetPanelHz(float hz);
    /** 코어 멈춤 — 게임 안 「설정」 창이 떠 있는 동안. 그림은 마지막 것을 계속 그린다. */
    public static native void nativeSetPaused(boolean on);
    /** 실측 패널 주사율(60/90/120…) — 상태 토스트용. */
    public static native float  nativePanelHz();
    /** 코어가 지금 선언한 fps — 사무쇼2 코어가 프레임 생성을 켜면 120.5. */
    public static native double nativeCoreFps();

    /* 가상 리그 랭크전(league.c) — 진짜 프레임마다 대진 덮어쓰기·결판 감지.
       arm: 상대 캐릭터 id(0~14)·유파(0/1)·난도(0~2, 음수=그대로)·포인트·시간(0=그대로)·LEAGUE_* 플래그 */
    public static final int LEAGUE_CARDS_OFF = 1, LEAGUE_ALLCARDS = 2, LEAGUE_AUTONAV = 4, LEAGUE_FROM_CKPT = 8;
    public static native void  nativeLeagueArm(int opp, int style, int diff, int points, int time, int flags);
    public static native void  nativeLeagueDisarm();
    /** [무장, 프레임, bit7 0 봄, 경기 시작 봄, 결과 잡음, 결과 비트, 승수1, 승수2, KO, 실효 포인트,
     *   P1 블록, P2 블록(결판), P2 블록(경기 시작), 경기 시작 프레임, 결판 프레임, 사용자 카드 1, 2, 실효 시간,
     *   CPU 단계(0x5814), CPU 공격 배율, CPU 받는 피해 배율, 결판 때 CPU 단계 상한,
     *   (149) 결판 뒤 0 없음·1 승리 대사 도는 중·2 멈춤 걸쇠, 걸쇠 사유(1 대사 끝·2 B·3 장면·4 시간·5 뒤로), 결판 뒤 프레임, 대사가 뜬 프레임(−1)]
     *  옛 native 는 22칸 — 길이를 보고 쓴다 */
    public static native int[] nativeLeagueStatus();
    /** 153: 승리 대사 건너뛰기(뒤로 가기) — 다음 진짜 프레임에 멈춤 걸쇠(결과 카드 차례). 게임이 멈춰 있으면 풀린 뒤에 선다 */
    public static native void nativeLeaguePostSkip();
    /* 게임 속 리그(허브): 타이틀 «2인 대전» 자리 = «리그 대전». sty/dif = 캐릭터 id(0~14)별 리그 상대의 검질(없으면 −1)·난도 */
    public static native void  nativeLeagueHub(boolean on, byte[] sty, byte[] dif);
    public static native void  nativeLeagueHubCancel();
    /** 게임 속 리그 배경·컬러 — home[캐릭터] = 그 상대 기본 배경(0~7·−1), stageSel −1 상대 기본·−2 무작위·0~7, color 0~3 */
    public static native void nativeLeagueHubLook(byte[] home, int stageSel, int color);
    /** 다음 nativeLeagueArm 의 배경(0~7, −1 게임 그대로)·내 컬러(0~3) */
    public static native void nativeLeagueLook(int stage, int color);
    /** [상태 0 꺼짐·1 타이틀·2 상대 고르기·3 내 캐릭터·4 경기·5 타이틀로, 커서 캐릭터(−1 무작위), 고른 상대 캐릭터, 프레임, 메뉴 글자, 머리말 글자, 재대결, 대신 누르기] */
    public static native int[] nativeLeagueHubStatus();
    /* 랭크 매치(끝없는 매칭, league.c) — 켬/끔 · 찾기 켬(타이틀)/취소 · 정해진 상대(캐릭터·검질·옵션 난도·배경·CPU 손잡이 5개, go = 찾는 중이면 바로 내 캐릭터 고르기로)
       · 찾는 동안 모은 누름(1 A · 2 B · 4 OPTION · 8 ← · 16 → · 32 ↑ · 64 ↓, 가져가며 비움) · 다음 무장 한 번의 손잡이(체크포인트 이어 하기) */
    public static native void nativeHubRanked(boolean on);
    public static native void nativeHubSearch(boolean on);
    public static native boolean nativeHubMatch(int ch, int sty, int diff, int stage, int lv, int cap, int atk, int dfn, int floor, boolean go);
    public static native int nativeHubTakeEv();
    public static native void nativeLeagueCpu(int lv, int cap, int atk, int dfn, int floor);
    /** 결판 뒤: 리셋하고 타이틀 «리그 대전»까지(rematch = 바로 다시 붙을 상대 캐릭터, −1 없음) */
    public static native void  nativeLeagueHubReturn(int rematch);
    /* 153 랭크 «내 캐릭터 한 번만»·VS 띠(league.c · vsband.c) — 옛 native 엔 없다: 부르는 쪽이 UnsatisfiedLinkError 를 받아 152 흐름(매 판 직접 고르기·띠 없음)으로.
       league_hub_status(15칸)는 늘리지 않는다 — 고정 크기로 받는 곳이 있어 상태는 따로 */
    public static final int AUTO_TAIL = 2, AUTO_FF = 4;
    /** 내 캐릭터 대신 고르기 — cell 0~13 · 15 가운데 무작위 칸, sty 0 수라 · 1 나찰, b2p 안 씀(165 — 옛 2P색 자리, 늘 false), k −1..3 카드 칸.
     *  flags AUTO_TAIL = 직접 고르는 판(이야기부터 자동 · 고른 것은 nativeHubMyPick) · AUTO_FF = 자동 단계 1~5 동안 4배속·소리 끔.
     *  다음 go(hub_go_fixed)부터. on false 이고 TAIL 아니면 예전처럼 손으로 */
    public static native boolean nativeHubAutoPick(boolean on, int cell, int sty, boolean b2p, int k1, int k2, int flags);
    /** [있음, 칸, 캐릭터, 검질, 늘 0(옛 2P색), 카드 1, 2, 컬러 0·2~17] — 직접 고른 판의 이야기가 시작되면 있음(무작위 칸은 1라운드에 뽑힌 캐릭터로 다시) */
    public static native int[] nativeHubMyPick();
    /** [단계 0 꺼짐·1 부팅·2 캐릭터·3 검질·4 카드·5 이야기·6 무대 인트로(겹창을 내릴 때)·7 라운드·8 막힘, go 뒤 프레임, go→인트로, go→라운드, 막힌 단계].
     *  돌려보내기(nativeLeagueHubReturn)가 코어에 닿기 전(허브 상태 v[8] 가 늘기 전)엔 지난 판 값 */
    public static native int[] nativeHubAutoStatus();
    /** 경기 앞 무대 인트로 위 검은 띠에 얹을 VS 띠(RankVs) — tiles ≤ 80 × 16바이트 NGPC 2bpp, map 80칸, hold 프레임, flags. tiles null = 끄기 */
    public static native boolean nativeHubVs(byte[] tiles, short[] map, int hold, int flags);
    /** [단계 0 없음·1 걸어 둠·2 序章 넘김·3 띠 보임·4 배너·5 끝·6 실패 (|0x10 얹힘 · |0x20 타일 겹침 포기), 대사 단계 프레임, hold] */
    public static native int[] nativeHubVsStatus();
    /** 168 랭크 «내 캐릭터 먼저» — 상대 없이(무장 안 함) 1인 대전 고르기(허브 상태 9). 타이틀·찾기에서만 받는다(true). 고른 것은 이야기가 시작될 때 nativeHubMyPick 에.
     *  옛 native 면 UnsatisfiedLinkError — 부르는 쪽이 받아 예전 tail 길(상대를 정한 채 직접 고르기) */
    public static native boolean nativeHubPickOnly();
    /** [요청 대기, 고르는 판(허브 9), 고른 것을 남김, 그 판 프레임(−1)] */
    public static native int[] nativeHubPickOnlyStatus();

    /* 연습 대전(practice.c) — 일반 세션에서 타이틀 «2 인 대전» 자리 = «연습 대전». 판정 주소를 확인한 롬 판(League.romLabel)에서만 켤 것.
       autostart = 로고·메뉴를 넘겨 «캐릭터 선택»(내 캐릭터 먼저 → 상대)까지 대신 누름(실행 창 «연습 대전으로» 링크). 상태·허브 상태는 nativeLeagueHubStatus 와 같이 본다 */
    public static native void  nativePracticeHub(boolean on, boolean autostart);
    /* 리그·연습 «둘 다»(실행 창 «리그·연습») — 허브를 켠 뒤 켠다. 타이틀 셋째 줄 ◀▶ 로 연습·리그.
       상태 [둘 다 켜짐, 셋째 줄에 보이는 종류(0 리그 · 1 연습), 갈아타기 요청(0 없음 · 1 리그 · 2 연습), 셋째 줄에 커서, 이 판의 종류] */
    public static native void  nativeHubDual(boolean on);
    public static native int[] nativeHubDualStatus();
    /** 168 고침(K5) — 지난 진짜 프레임에 타이틀 메뉴(글자 타일)가 떠 있었나: 0 아님 · 1 한글판 · 2 순정판(GAME OPTION·COLLECTION·인트로 그림은 0). 옛 native 면 UnsatisfiedLinkError */
    public static native int   nativeHubMenu();
    public static native void  nativeHubHandoffClear();
    /* 갈아탄 리그 판 — 로고·메뉴를 넘겨 바로 상대 선택까지 */
    public static native void  nativeHubGo();
    /** [경기 중, 메뉴(0 닫힘·1 여는 중·2 열림·3 닫는 중), 메뉴 줄, 더미(0 서기·1 CPU), 가드, 내 분노, 상대 분노, 리셋 위치, 리셋 좌우, 숫자,
     *   보이는 대미지, 리셋 횟수, 리셋 기다림, 마지막 리셋 프레임, 가드 켬, 프레임, 빌린 타일, 쓰이던 빈 타일, 나가기 요청, 시작 물체 수, 메뉴 연 횟수,
     *   버린 리셋, 더미 캐릭터, 더미 검질] (practice.h) */
    public static native int[] nativePracticeStatus();
    /** 연습 대전 프레임 미터 — cells[160](나 80칸 · 상대 80칸) · sum[40](practice.h PRACTICE_FD_NSUM). 돌려줌: 바뀔 때마다 오르는 판 번호 */
    public static native int nativePracticeFrames(byte[] cells, int[] sum);
    /** 176 치트 보기(practice.c practice_view_status) — [0 보기 비트(1 프레임 · 2 대미지), 1 켜짐(전투측), 2 미터 켬, 3 숫자 그림, 4 막힌 이유, 5 보이는 대미지, 6 라운드 수, 7 프레임] */
    public static native int[] nativeViewStatus();

    /* 모든 모드 컬러(look.c) — 사무쇼2 확인한 판에서 캐릭터·검질 고르기 OPTION = 다음 컬러(길게 = 만들기 요청) · A+B = 베기 색 다음 것 · A·B = 결정(보통 색).
       번호 0 기본 · 1 (옛 2P색 — 안 고름, 옛 기록 이름용) · 2~15 프리셋 · 16·17 내 색 1·2(Look.NAMES). 기억 칸 16개 = 캐릭터 0~13 · 14 안 씀 · 15 무작위 칸 */
    public static native void   nativeLookEnable(boolean on);
    public static native void   nativeLookSetMem(byte[] mem16);
    public static native byte[] nativeLookGetMem();
    /** 내 색 k(0·1) — 캐릭터마다, 두 검질 같이. c1 < 0 = 지움. 12비트 0x0BGR */
    public static native void   nativeLookSetCustom(int ch, int k, int c1, int c2, int c3);
    /** 베기 색 0~6(Look.FX_NAMES — 하나로 기억). 경기 베기 색은 고르기 화면에서 이 값을 따라간다(165 — 옛 native 엔 없다) */
    public static native void   nativeLookSetFx(int fx);
    /** 이 경기 컬러(리그 체크포인트 이어 하기) — 2~17 만, 그 밖(−1·0·1) = 정하지 않음(고르기 화면에서 정함) */
    public static native void   nativeLookSetMatch(int idx);
    /** 컬러 만들기 창 — 고르기 화면 미리보기를 이 세 색으로(off = 끝). 열린 동안 게임 입력은 모두 막는다 */
    public static native void   nativeLookEdit(boolean on, int c1, int c2, int c3);
    /** [켜짐, 고르기 화면을 쥠, 기억 바뀐 횟수, 보이는 컬러, 경기 컬러(−1), 경기 캐릭터(−1), 늘 0(옛 2P색), 만들기 요청 횟수,
     *   검질 고르기 검질(−1 = 캐릭터 고르기), 경기 컬러 바뀐 횟수, 만드는 중, 프레임, 만들 캐릭터(−1 = 안 됨), 미리보기 검질,
     *   베기 색 0~6, A+B 로 바뀐 횟수, 결정 횟수, 경기 베기 색,
     *   상대 컬러 상태(0 칠하지 않음 · 1 주 색 · 2 거울 겹침이라 대신 색), 대신 색 번호(−1 · 0~15 LookTab 프리셋)] (look.h — 177 부터 20칸 · 165 18칸 · 옛 native 14칸 — length 를 볼 것) */
    public static native int[]  nativeLookStatus();
    /** 177 리그 상대 컬러 — ch 0~14 · sty · 주 색 세 색(12비트 0x0BGR, c1 < 0 = 끔) · 상대 베기 색 0~6. 거울전 대신 색은 native 가 표에서 고른다.
     *  랭크 경기 무장·승리 대사·걸쇠 동안만 칠하고(연습 빼고) 코어를 올리고 내릴 때 native 가 끈다. 옛 native 엔 없다(UnsatisfiedLinkError) */
    public static native void   nativeLookOpp(int ch, int sty, int c1, int c2, int c3, int fx);
    /** 컬러 만들기 창의 시작 색 — 그 캐릭터·검질의 18색 × 세 색(없는 내 색 = 기본) */
    public static native int[]  nativeLookTriples(int ch, int sty);
    /* 치트(cheat.c) — 사무쇼2 일반 세션. key = Cheats.KEYS 순서(CH_*), value 0..n — 다음 진짜 프레임부터 먹는다(코어를 다시 올리지 않음).
       잠긴 세션(랭크전·게임 속 리그)에선 무시. 코어를 올리고 내릴 때 native 가 모두 끈다 — 일반 세션이면 로드 뒤에 다시 넣을 것 */
    public static native void  nativeCheatSet(int key, int value);
    /** 리그 판 — 켜면 값을 비우고 이후 nativeCheatSet 을 받지 않는다 */
    public static native void  nativeCheatLock(boolean on);
    /** [잠김, 켜진 수, 이번 세션 경기 치트 먹음, 올카드 창 안, 보관 합, 세이브에 이미 올카드, 유가 고름, 막힌 이유(0·1 잠김·2 리그·3 연습),
     *   마지막 P1 공격 배율, 그 기준, 마지막 한 방 KO 프레임, 무한 체력 되돌림 수, 프레임, 0x57C6, 0x5726, 세이브에 남기기로 8 을 쓰는 중] (cheat.h) */
    public static native int[] nativeCheatStatus();
    /** 168 이 판에 실제로 먹은 치트(1 << Cheats 번호) — clear 면 0 으로. 무장(nativeLeagueArm·허브 대진)이 비운다. 옛 native 면 UnsatisfiedLinkError */
    public static native int nativeCheatMatch(boolean clear);

    /* 157 덧판(ovplane.c — 계약서 docs/157_contract.md §3). 게임 화면 위 가상 오버레이 판: 코어 램·VRAM 을 안 건드리고 gl_draw 가 잔상 뒤에 합성한다.
       nativeOvStatus 만 아무 스레드 — 나머지는 GL 스레드(LeagueMenu, gl.queueEvent)에서만. 옛 native 면 UnsatisfiedLinkError — 부르는 쪽이 받아 옛 결과 카드로.
       타일 = NGPC 2bpp 16바이트(줄마다 16비트 LE, 왼쪽 픽셀이 높은 비트) · 맵 칸 = (팔레트<<9)|상대 타일(0..510) | 0x4000 V | 0x8000 H, 0xFFFF 빈 칸 ·
       팔레트 = 4색 × 12비트 0x0BGR(색 0 투명) */
    public static final int OV_W = 0, OV_L = 1, OV_BASE_LAYER = 2;
    public static final int OV_ARM_ON_LATCH = 1, OV_START_NOW = 2, OV_SKIP_TO_END = 4, OV_CUE_ID_SHIFT = 16;
    public static final int OV_BASE_NONE = 0, OV_BASE_DIM = 1, OV_BASE_FILL = 2, OV_BASE_FILLUP = 3, OV_BASE_FLASH = 4, OV_BASE_SHIFT = 5;
    public static final int OV_CLR_W = 1, OV_CLR_L = 2, OV_CLR_BASE = 4, OV_CLR_CUE = 8, OV_CLR_HOLD = 16, OV_CLR_ALL = -1;
    public static final int OV_Q_SHOW = 1, OV_Q_HFLIP = 2, OV_Q_VFLIP = 4, OV_Q_16 = 8;
    /** nativeOvStatus 칸 */
    public static final int OVS_VER = 0, OVS_FLAGS = 1, OVS_T = 2, OVS_END = 3, OVS_CUE_ID = 4, OVS_STARTS = 5,
            OVS_RX = 6, OVS_RY = 7, OVS_SUB = 8, OVS_FBW = 9, OVS_FBH = 10, OVS_VX = 11, OVS_VY = 12, OVS_VW = 13, OVS_VH = 14,
            OVS_GPAUSE = 15, OVS_LATCH = 16, OVS_COMPOSITES = 17, OVS_W1 = 18, OVS_W2 = 19, OV_NSTAT = 20;
    public static final int OVF_VISIBLE = 1, OVF_ARMED = 2, OVF_HOLD = 4, OVF_RUNNING = 8, OVF_RECT_OK = 16, OVF_SHOWN = 32;
    /** 층(OV_W 타일 512 · 맵 32×32 / OV_L 타일 2048 · 맵 20×128)에 올리기 — tiles(n×16, ≤511개) → tileBase.., map(cols×rows) → 칸 (col0,row0)..,
     *  pals(npal×4) → 팔레트 palBase... 셋 다 null 가능. false = 인자 틀림(아무것도 안 씀). 보이기는 바꾸지 않는다(대본 시작이 보이게 한다) */
    public static native boolean nativeOvLayer(int layer, byte[] tiles, int tileBase, short[] map, int cols, int rows, int col0, int row0, short[] pals, int palBase);
    /** 맵 칸만 다시 쓰기(타일 번호 = tileBase + 칸의 하위 9비트) */
    public static native boolean nativeOvCells(int layer, short[] cells, int col0, int row0, int cols, int rows, int tileBase);
    /** 사각형 안 빈 칸 아닌 칸의 팔레트만 바꿈(커서 줄) */
    public static native boolean nativeOvRecolor(int layer, int col0, int row0, int cols, int rows, int pal);
    /** 팔레트 하나의 색 1·2·3(−1 = 그대로) */
    public static native boolean nativeOvPal(int layer, int pal, int c1, int c2, int c3);
    /** 쿼드 idx(0..15) — x,y 층 px · tile 층 은행 절대 번호 · flags OV_Q_* (SHOW 없으면 숨김) */
    public static native boolean nativeOvQuad(int layer, int idx, int x, int y, int tile, int pal, int flags);
    /** 창유리 idx(0..15) — 칸 사각형 (c0,r0)..(c1,r1)(포함)을 dx,dy px 밀어 그림 · visible false = 숨김 · c1 < c0 = 창유리 지움 */
    public static native boolean nativeOvPane(int layer, int idx, int c0, int r0, int c1, int r1, int dx, int dy, boolean visible);
    /** 층 스크롤(x,y px) · 자르기 화면 줄 clipY0..clipY1(포함) */
    public static native boolean nativeOvScroll(int layer, int x, int y, int clipY0, int clipY1);
    /** 바탕 칸 slot(0..3) — mode OV_BASE_* · 게임 줄 y0..y1 · arg · color(FILL 12비트) */
    public static native boolean nativeOvBase(int slot, int mode, int y0, int y1, int arg, int color);
    /** 연출 대본 — ops = op 마다 int 7개 {op, t0, dur, a, b, c, d}(Cue.build) · flags = OV_ARM_ON_LATCH|OV_START_NOW|OV_SKIP_TO_END | id<<16.
     *  ops null + OV_SKIP_TO_END = 돌던 대본 끝 상태로 */
    public static native boolean nativeOvCue(int[] ops, int flags);
    /** 메뉴 멈춤 — g_paused 와 따로(바·창 닫기가 덮어쓰지 않는다). 켜 있는 동안 코어 0 프레임 */
    public static native void nativeOvHold(boolean on);
    /** 입력 막기 비트(게임에 안 줌) — 보이는 중·멈춤 중엔 native 가 0xFFF 를 저절로 더한다 */
    public static native void nativeOvBlock(int bits);
    /** 지우기 — OV_CLR_* 묶음, OV_CLR_ALL = 전부 + 숨김(멈춤·막기도 풂) */
    public static native void nativeOvClear(int mask);
    /** [OVS_*] — 아무 스레드. [OVS_VER] 0 = 덧판 없음(빈 껍데기) */
    public static native int[] nativeOvStatus();
    /* 157 post — 경기 중 HUD 이름표(체력 막대 아래 검은 줄): 메뉴 층과 따로 · 입력 안 막음 · native 가 라운드 조작 중(램)일 때만, 게임이 그 자리에 무엇을 그리면
       비켜 줌(OV_TAG_CLEAR 20프레임 비면 다시) · 라운드 시작 2초 강조. 메뉴 닫기(nativeOvClear)는 안 지움 — 코어가 통째로 바뀌는 곳(로드·내림·리셋·허브 돌아가기·
       상태 불러오기)에서 native 가 지운다. GL 스레드(LeagueMenu.OvPort)에서만 · 옛 native 면 UnsatisfiedLinkError(부르는 쪽이 받아 이름표 없이) */
    public static final int OV_TAG_ME = 0, OV_TAG_OPP = 1, OV_TAG_MAXW = 64, OV_TAG_H = 8, OV_TAG_CLEAR = 20;
    /** nativeOvTagStatus 칸 */
    public static final int OVT_ON = 0, OVT_LIVE = 1, OVT_VIS = 2, OVT_FLASH = 3, OVT_OCC = 4, OVT_SCANS = 5, OVT_YIELDS = 6, OVT_EMPH = 7, OVT_SIG = 8, OVT_ROUNDS = 9, OV_TAG_NSTAT = 10;
    /** 이름표 한쪽 — pix w×h(칠 0..3, 0 투명) · 게임 px (x,y) · pals 6칸 = 강조 c1..c3 · 보통 c1..c3(12비트 0x0BGR). pix null = 그 쪽 지움 */
    public static native boolean nativeOvTag(int side, byte[] pix, int w, int h, int x, int y, short[] pals);
    /** 이름표 켬/끔(랭크 경기 동안 켬) */
    public static native void nativeOvTagOn(boolean on);
    /** [OVT_*] — 아무 스레드 */
    public static native int[] nativeOvTagStatus();
    /* 176 덧판 메뉴 효과음(uisfx.c) — 사무쇼2 메뉴 소리(커서 0x0E · 결정 0x02 · 뒤로 0x06)를 native 가 합성해 오디오 콜백에서 게임 소리 위에 더한다
       (코어가 멈춰 있어도 남 · 스트림이 닫히면(onPause) 안 남 · 미디어 음량을 따름). 메뉴가 보이거나 메뉴 멈춤 중일 때만 받는다(false = 안 받음).
       아무 스레드 · 옛 native 면 UnsatisfiedLinkError(부르는 쪽이 받아 소리 없이) */
    public static final int UI_SFX_STOP = 0, UI_SFX_CURSOR = 1, UI_SFX_SELECT = 2, UI_SFX_BACK = 3;
    public static native boolean nativeUiSfx(int kind);
    /** 182: 앱 화면 메뉴(확인 카드·내 이름 카드·찾기 겹창)가 떠 있나 — 메뉴 효과음 문을 열고 결정의 메아리를 끝까지 */
    public static native void nativeSetAppMenu(boolean on);
    /** 182: 지금 멈춤(nativeSetPaused)이 곡을 이어도 되는 멈춤인가 — 게임 안 «설정» 창만(소리 CPU 만 돌린다, 옛 코어면 조용) */
    public static native void nativeSetPauseBgm(boolean on);
}
