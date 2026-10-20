/* 186 COUNTER 몸 번쩍 — 반격 맞음 때 맞은 몸을 2 진짜 프레임(히트스톱 첫머리) 하얗게.
 *
 * 몸 팔레트(스프라이트 팔레트 0 = P1 몸 · 5 = P2 몸, look.c 머리말 실측) 세 색 중 밝은 둘을 흰색으로 — 가장 어두운 색(테두리)은 그대로라
 * 밝은 무대에서도 몸 모양이 남는다. 팔레트 램을 직접 쓰므로 그림·프레임 생성(합성 프레임)·업스케일러가 다 같은 몸을 본다.
 *
 * 게임은 맞은 지 2 프레임 뒤 몸 팔레트를 롬에서 다시 쓴다(봇 판 실측 — 대개 같은 색, 센 맞음 몇은 물들임 «fff f9b f58» 16~24 프레임).
 * 그래서 look.c 가 번쩍 동안 코어 바꿔 쓰기 표에 «번쩍 전 세 색 → 흰색»을 얹어 그 다시 쓰기도 흰색으로 들어가게 한다 — 직접 쓴 흰색만으론
 * 런어헤드(보이는 그림이 2 프레임 앞)에서 화면에 한 번도 안 나왔다(봇 판: 런어헤드 2 에서 흰 화소 그대로 · 표를 얹으면 2 프레임).
 * 되돌리기: 2 프레임 뒤 아직 우리 흰색이면 처음 세 색으로. 게임이 다른 세 색(물들임)을 썼으면 게임 것을 그대로 두고 끝.
 * look.c 의 몸 색 고침(match_want · opp_want)은 세 색이 아는 색일 때만 쓰므로 흰색 동안엔 안 건드리고, 되돌린 뒤엔 그대로 이어진다.
 *
 * 부르는 곳(모두 코어 잠금 안): native.c 진짜 프레임 뒤 hitflash_tick(맨 앞 — 끝나면 이어지는 look_tick 이 표에서 뺀다) → … →
 * callout_tick 뒤 callout_flash_take 가 있으면 hitflash_start + look_flash_sync(표에 바로 얹기).
 * 상태 저장 직전 hitflash_end + look_flash_sync(우리 흰색이 저장된 상태에 안 들어가게 — look_before_save 보다 먼저) ·
 * 불러오기(look_after_load 보다 먼저)·리셋·코어 내림은 hitflash_forget.
 */
#ifndef HITFLASH_H
#define HITFLASH_H
#include <stdint.h>

#define HITFLASH_FRAMES 2

void hitflash_start(uint8_t *pal, int side);   /* side 0 = P1(팔레트 0) · 1 = P2(팔레트 5). 이미 번쩍이는 중이면 길이만 다시 */
void hitflash_tick(uint8_t *pal);              /* 진짜 프레임마다 한 번(retro_run 뒤) — 다 되면 되돌린다 */
void hitflash_end(uint8_t *pal);               /* 지금 바로 되돌리고 끝(상태 저장 직전) */
void hitflash_forget(void);                    /* 되돌리지 않고 잊는다(불러온 상태·리셋·코어 내림 — 팔레트가 통째로 바뀜) */
int  hitflash_active(void);                    /* 번쩍이는 쪽 비트(1 = P1 · 2 = P2) — 시험용 */
void hitflash_colors(const uint16_t in[3], uint16_t out[3]);   /* 세 색 → 번쩍 세 색(시험용) */
/* 번쩍이는 중이면 1 — from = 처음 세 색 · to = 흰 세 색(12비트). look.c 가 코어 바꿔 쓰기 표에 «from → to» 를 얹는다:
   게임은 맞은 지 2 프레임 뒤 몸 팔레트를 다시 쓰는데(봇 판 실측), 런어헤드면 보이는 그림이 그 뒤라 직접 쓴 흰색이 화면에 안 나온다 */
int  hitflash_remap(int side, uint16_t from[3], uint16_t to[3]);

#endif
