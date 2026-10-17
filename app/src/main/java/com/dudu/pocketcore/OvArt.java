package com.dudu.pocketcore;

/**
 * 157 덧판 그림(우리가 찍은 픽셀) — 설계 단계 껍데기. 실제 파일은 tools/gen_ov_art.py 가 만든다(손으로 고치지 않음).
 * 계약서 docs/157_contract.md §6. 담당: 그림 담당(render builder).
 *
 * 그림마다 칠 0..3(0 = 투명). 쓰는 쪽은 TileCanvas.art(id, x, y, ci1, ci2, ci3, hflip) 로 칠 1·2·3 을 원하는 칠로 옮겨 그린다.
 * 출처: handoff/ss2_next/157_인게임메뉴_기획/시안/mock_final.py 의 그림 상수·그리기 함수(우리 그림) · 낙관 붓글자는 앱 assets/fonts/ink_b.ttf
 * (고운바탕 Bold, OFL)를 빌드 때 1비트로 구운 것. 롬 그림·게임 화면은 쓰지 않는다.
 * 아래 이름(상수)은 계약 — 번호 값은 생성기가 매겨도 되지만 이름·크기·칠의 뜻은 계약서 표대로.
 */
final class OvArt {
    private OvArt() { }

    static final int KATANA = 0;         // 8×7  단도 커서: 1 칼날 · 2 손잡이
    static final int CHAIN = 1;          // 8×6  라이벌 사슬: 1
    static final int CROWN = 2;          // 7×5  1위 왕관: 1 · 2
    static final int PIP_WIN = 3;        // 6×6  최근 판 찬 알: 1
    static final int PIP_LOSS = 4;       // 6×6  빈 알: 2
    static final int TRI_UP = 5;         // 5×3  ▲: 1
    static final int TRI_DN = 6;         // 5×3  ▼: 1
    static final int ARROW_L = 7;        // 4×7  ◀: 1
    static final int ARROW_R = 8;        // 4×7  ▶: 1
    static final int CREST = 9;          // 8×8  티어 문장 작은 것: 1 · 2 · 3
    static final int STAR5 = 10;         // 5×5  별: 1
    static final int SEAL24_SEUNG = 11;  // 24×24 낙관 «승»: 1 글자 · 2 인주 · 3 테(빠진 곳 0)
    static final int SEAL24_PAE = 12;    // 24×24 «패»
    static final int SEAL24_MU = 13;     // 24×24 «무»(무승부 · 무효 — 팔레트로 가름)
    static final int SEAL16_FRAME = 14;  // 16×16 서랍 제목 낙관 틀: 2 인주 · 3 테 — 글자는 그릴 때 g11b 칠 1
    static final int SEAL8_FRAME = 15;   // 8×8  꼬리표 도장 틀: 2 인주(네 귀 0) — 글자는 그릴 때 g7 칠 1
    static final int CREST32 = 16;       // 32×32 승급 문장: 1 · 2 · 3
    static final int BRUSH152 = 17;      // 152×3 붓 한 획(머리 굵고 꼬리 마름): 2 먹 · 1 윗빛
    static final int BRUSH72 = 18;       // 72×3
    static final int BANNER_CLOTH = 19;  // 32×40 노보리 천(꼬리 잘림 0): 3 바탕 · 2 윗띠
    static final int BANNER_POLE = 20;   // 8×40 깃대: 1 빛 · 2 나무
    static final int SHEATH_CAP_TOP = 21;// 7×3  칼집 위 금 마개: 1 · 2
    static final int SHEATH_CAP_BOT = 22;// 7×3  아래 마개: 2
    static final int SHEATH_EDGE = 23;   // 2×16 칼집 왼쪽 금 테(16줄마다 되풀이): 1 · 2
    static final int COUNT = 24;

    static int w(int id) { throw todo(); }
    static int h(int id) { throw todo(); }
    /** 칠 0..3 — 범위 밖 0 */
    static int px(int id, int x, int y) { throw todo(); }
    static String name(int id) { throw todo(); }

    private static UnsupportedOperationException todo() { return new UnsupportedOperationException("157-A OvArt: tools/gen_ov_art.py 가 만든다"); }
}
