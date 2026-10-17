/* 연습 대전 — 사무쇼2 1인 대전 첫 경기를 끝나지 않는 연습 경기로 (practice.c). */
#pragma once
#include <stddef.h>
#include <stdint.h>

/* 코어 문자 램·스크롤 램·팔레트 램·스프라이트 램(연습 메뉴 창·대미지 숫자·«2P» 그림) */
void practice_vram(void *chr, void *scr, void *pal, void *spr);
/* 진짜 프레임 한 번(retro_run) 뒤, 코어 잠금 안에서 league_tick_hub 바로 다음에 부른다. input = 이번 프레임 사용자 입력(RETRO_DEVICE_ID 비트) */
void practice_tick(uint8_t *ram, size_t n, int input);
/* 입력 콜백: (사용자 입력 & ~(league_in_block | practice_in_block)) | league_in_or | practice_in_or  (다음 프레임) */
extern volatile int practice_in_block, practice_in_or;
/* 입력 콜백: 사용자 입력에 Option 이 있으면 이 비트도 지운다(Option 과 같은 프레임에 누른 방향·A·B — 경기 중 메뉴가 닫혀 있을 때만 0 아님) */
extern volatile int practice_opt_mask;
/* 메뉴에서 나가기를 골랐나 — 1 «타이틀로» · 2 «다시 고르기» (한 번 읽으면 지운다). native.c 가 같은 잠금 안에서 리셋 → league_practice_return */
int  practice_take_exit(void);
/* 리셋·상태 불러오기 뒤 — 램·화면이 통째로 바뀌었으니 되돌리지 않고 잊는다(설정은 남김) */
void practice_forget(void);
/* 상태 저장 직전·직후(코어 잠금 안) — 우리가 바꿔 그린 창·숫자·«2P» 를 원래대로 했다가 다음 프레임에 다시 그린다 */
void practice_before_save(void);

#define PRACTICE_NSTAT 26
/* [경기 중, 메뉴(0 닫힘·1 여는 중·2 열림·3 닫는 중), 메뉴 줄, 더미(0 서기·1 CPU), 가드(0 안 함·1 전부·2 첫 타 뒤·3 랜덤),
    내 분노(0 보통·1 MAX), 상대 분노, 리셋 위치(0 가운데·1 왼쪽 벽·2 오른쪽 벽·3 지난 위치), 리셋 좌우(0 그대로·1 바꿈), 숫자(0 대미지·1 끔),
    보이는 대미지(−1 없음), 리셋 한 횟수, 리셋 기다리는 중, 마지막 리셋 프레임, 지금 가드 켬, 연습 프레임, 빌린 타일 수, 빈 타일 후보 중 쓰이던 것,
    나가기 요청, 라운드 시작 물체 수, 메뉴 연 횟수, 버린 리셋 요청, 더미 캐릭터, 더미 검질, 프레임 미터(0 끔 · 1 켬), 미터 순서 번호] */
void practice_status(int v[PRACTICE_NSTAT]);
/* 설정 바꾸기(시험·앱) — key: 0 더미 · 1 가드 · 2 내 분노 · 3 상대 분노 · 4 리셋 위치 · 5 리셋 좌우 · 6 숫자 · 7 리셋 요청(value = 위치, −1 = 지금 설정)
   · 8 더미 체력(시험용 — 대미지·회복 셈에 안 들어감) · 9 프레임 미터(0 끔 · 1 켬) */
void practice_set(int key, int value);
/* 연습 설정만 담기·되돌리기(상태 저장 옆 .prac — native.c). 담은 크기(모자라면 0) */
size_t practice_settings_get(void *buf, size_t cap);
void practice_settings_set(const void *buf, size_t n);
/* 시험용 — 연습 경기 상태 통째로 보관·되돌리기(코어 상태와 함께 결정성 시험에) */
size_t practice_state_size(void);
void practice_state_get(void *buf);
void practice_state_set(const void *buf);

/* 프레임 데이터 — SF6 식 미터(두 줄 × 80칸)와 요약. 칸 값: 0 움직일 수 있음 · 1 움직임(점프·대시) · 2 발동 · 3 지속 · 4 경직 · 5 판정 없는 동작
   · 6 맞음 · 7 막음 · 8 다운 · 9 줄임 | 0x40 튕겨내기 판정 | 0x80 장풍이 닿을 수 있음 · 0xFF 빈칸.
   sum: [0] 켬 [1] 순서 번호 [2] 쌓인 칸 수(80 넘으면 감김) [3] 순서 진행 중 [4] 얼린 프레임(히트스톱) [5] 줄인 프레임 [6] 판 번호 [7] 숫자 줄이 볼 사람(0 나 · 1 상대 · −1 없음)
        [8..21] P1 마지막 동작 · [22..35] P2 마지막 동작 = { 번호, 발동, 지속(−1 없음 · −2 장풍), 경직, 전체, 닿음(0 헛침 1 히트 2 가드 3 잡기 4 상쇄 5 다운),
                    튕겨내기 시작, 튕겨내기 길이, 기억(헛침) 지속, 기억 경직, 기억 유불리 히트·다운·가드·잡기(0x7FFF 모름) }
        [36] 유불리(P1 기준, + = 내가 먼저 · 0x7FFF 없음) [37] 그 닿음 종류 [38] 공격한 쪽(0·1·−1) [39] 유불리 번호 */
#define PRACTICE_FD_VIEW 80
#define PRACTICE_FD_NSUM 40
uint32_t practice_fd_get(uint8_t *cells, int *sum);
