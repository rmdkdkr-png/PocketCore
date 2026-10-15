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
    public static native void nativeRunFrames(int n); /* GL 없이 n프레임 — 썸네일 캡처용 */
    /* 프레임 생성(중간 프레임 보간) — 0=끔 1=섞기 2=움직임 보정. 120Hz 같은 빠른 화면에서만 실제로 끼운다 */
    public static native void nativeSetFrameGen(int mode);
    /** 실측 전 대용 패널 주사율(Display.getRefreshRate) — 코어의 GET_TARGET_REFRESH_RATE 답에 쓴다. */
    public static native void nativeSetPanelHz(float hz);
    /** 코어 멈춤 — 게임 안 「설정」 창이 떠 있는 동안. 그림은 마지막 것을 계속 그린다. */
    public static native void nativeSetPaused(boolean on);
    /** 지금 끼우고 있으면 화면 Hz, 화면이 느려 못 끼우면 0, 아직 모르면 -1. */
    public static native int  nativeFrameGenActive();
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
     *   CPU 단계(0x5814), CPU 공격 배율, CPU 받는 피해 배율] */
    public static native int[] nativeLeagueStatus();
    /* 게임 속 리그(허브): 타이틀 «2인 대전» 자리 = «리그 대전». sty/dif = 캐릭터 id(0~14)별 리그 상대의 검질(없으면 −1)·난도 */
    public static native void  nativeLeagueHub(boolean on, byte[] sty, byte[] dif);
    public static native void  nativeLeagueHubCancel();
    /** 게임 속 리그 배경·컬러 — home[캐릭터] = 그 상대 기본 배경(0~7·−1), stageSel −1 상대 기본·−2 무작위·0~7, color 0~3 */
    public static native void nativeLeagueHubLook(byte[] home, int stageSel, int color);
    /** 다음 nativeLeagueArm 의 배경(0~7, −1 게임 그대로)·내 컬러(0~3) */
    public static native void nativeLeagueLook(int stage, int color);
    /** [상태 0 꺼짐·1 타이틀·2 상대 고르기·3 내 캐릭터·4 경기·5 타이틀로, 커서 캐릭터(−1 무작위), 고른 상대 캐릭터, 프레임, 메뉴 글자, 머리말 글자, 재대결, 대신 누르기] */
    public static native int[] nativeLeagueHubStatus();
    /** 결판 뒤: 리셋하고 타이틀 «리그 대전»까지(rematch = 바로 다시 붙을 상대 캐릭터, −1 없음) */
    public static native void  nativeLeagueHubReturn(int rematch);

    /* 연습 대전(practice.c) — 일반 세션에서 타이틀 «2 인 대전» 자리 = «연습 대전». 판정 주소를 확인한 롬 판(League.romLabel)에서만 켤 것.
       autostart = 로고·메뉴를 넘겨 상대 선택까지 대신 누름(실행 창 «연습 대전으로» 링크). 상태·허브 상태는 nativeLeagueHubStatus 와 같이 본다 */
    public static native void  nativePracticeHub(boolean on, boolean autostart);
    /* 리그·연습 «둘 다»(실행 창 «리그·연습») — 허브를 켠 뒤 켠다. 타이틀 셋째 줄 ◀▶ 로 연습·리그.
       상태 [둘 다 켜짐, 셋째 줄에 보이는 종류(0 리그 · 1 연습), 갈아타기 요청(0 없음 · 1 리그 · 2 연습), 셋째 줄에 커서, 이 판의 종류] */
    public static native void  nativeHubDual(boolean on);
    public static native int[] nativeHubDualStatus();
    public static native void  nativeHubHandoffClear();
    /* 갈아탄 리그 판 — 로고·메뉴를 넘겨 바로 상대 선택까지 */
    public static native void  nativeHubGo();
    /** [경기 중, 메뉴(0 닫힘·1 여는 중·2 열림·3 닫는 중), 메뉴 줄, 더미(0 서기·1 CPU), 가드, 내 분노, 상대 분노, 리셋 위치, 리셋 좌우, 숫자,
     *   보이는 대미지, 리셋 횟수, 리셋 기다림, 마지막 리셋 프레임, 가드 켬, 프레임, 빌린 타일, 쓰이던 빈 타일, 나가기 요청, 시작 물체 수, 메뉴 연 횟수,
     *   버린 리셋, 더미 캐릭터, 더미 검질] (practice.h) */
    public static native int[] nativePracticeStatus();
    /** 연습 대전 프레임 미터 — cells[160](나 80칸 · 상대 80칸) · sum[40](practice.h PRACTICE_FD_NSUM). 돌려줌: 바뀔 때마다 오르는 판 번호 */
    public static native int nativePracticeFrames(byte[] cells, int[] sum);

    /* 모든 모드 컬러(look.c) — 사무쇼2 확인한 판에서 캐릭터·검질 고르기 OPTION = 다음 컬러(길게 = 만들기 요청) · B = 2P색으로 결정.
       번호 0 기본 · 1 2P색 · 2~15 프리셋 · 16·17 내 색 1·2(Look.NAMES). 기억 칸 16개 = 캐릭터 0~13 · 14 안 씀 · 15 무작위 칸 */
    public static native void   nativeLookEnable(boolean on);
    public static native void   nativeLookSetMem(byte[] mem16);
    public static native byte[] nativeLookGetMem();
    /** 내 색 k(0·1) — 캐릭터마다, 두 검질 같이. c1 < 0 = 지움. 12비트 0x0BGR */
    public static native void   nativeLookSetCustom(int ch, int k, int c1, int c2, int c3);
    /** 이 경기 컬러(리그 체크포인트 이어 하기) — −1·0 = 정하지 않음(고르기 화면에서 정함) */
    public static native void   nativeLookSetMatch(int idx);
    /** 컬러 만들기 창 — 고르기 화면 미리보기를 이 세 색으로(off = 끝). 열린 동안 게임 입력은 모두 막는다 */
    public static native void   nativeLookEdit(boolean on, int c1, int c2, int c3);
    /** [켜짐, 고르기 화면을 쥠, 기억 바뀐 횟수, 보이는 컬러, 경기 컬러(−1), 경기 캐릭터(−1), 2P색 결정 중, 만들기 요청 횟수,
     *   검질 고르기 검질(−1 = 캐릭터 고르기), 경기 컬러 바뀐 횟수, 만드는 중, 프레임, 만들 캐릭터(−1 = 안 됨), 미리보기 검질] (look.h) */
    public static native int[]  nativeLookStatus();
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
}
