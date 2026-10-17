package com.dudu.pocketcore;

/**
 * 랭크 대기창 팁 한 줄(«팁 · …») — 찾기 한 번에 새 팁 하나, ▲▼·누르기로 넘긴다. 순수 자바(JVM 시험 RankTipsTest).
 * 모두 사무쇼2 에서 잰 것(handoff/ss2_next/설계/framedata.md)이고 특정 캐릭터 이야기는 없다 — 이미 고른 상대를 미리 알려 주지 않게.
 * 강베기 문턱(손 7·8 프레임)은 원버튼·두버튼 코어가 달라서 안 쓴다. 글자는 모두 픽셀 글꼴(pixel.ttf)에 있는 것만 — 빼기는 ASCII '-'.
 */
final class RankTips {
    private RankTips() { }

    static final String[] TIPS = {
        "약베기는 버튼을 뗄 때 나가요 — 꾹 쥐면 강베기",
        "B 혼자는 공격이 아니에요 — ↓B·→B 는 튕겨내기",
        "튕겨내기는 누르고 5프레임부터 16프레임 동안 받아요",
        "←B 는 B 로 치는 공격이에요",
        "붙어서 → 나 ← + A 길게 = 잡기, 3프레임 만에 나가요",
        "다운됐을 때 ↓ 나 ↑ 를 쥐면 빨리 일어나요",
        "다운됐을 때 뒤를 쥐면 굴러서 일어나요",
        "필살기는 한 틱 일찍 넣어도 나가요 — 보통 기술은 선입력이 없어요",
        "연습 대전에선 프레임 미터로 발동·경직을 볼 수 있어요",
        "오늘 첫 승엔 +" + RankCore.BONUS_DAILY + " LP 보너스",
        "지고 바로 재대결에서 이기면 설욕 보너스 +" + RankCore.BONUS_REVENGE,
        "상대 카드에서 나가도 그 상대는 하루 동안 기다려요",
    };

    private static int idx = -1;            // 프로세스 안에서 이어 간다 — 처음엔 오늘(한국 시각 날짜)로 정한 자리
    /** 다음 팁 번호(TIPS 칸) — 부를 때마다 하나씩 */
    static synchronized int next() {
        idx = idx < 0 ? Math.floorMod(RankCore.today(System.currentTimeMillis()), TIPS.length) : (idx + 1) % TIPS.length;
        return idx;
    }
}
