# ss2 코어 프레임 생성(120Hz) — 앱이 알아야 할 계약

작성: 「커스텀 apk」방 (ss2-sp-core / emu-ex-plus-alpha / CustumApKS 담당), 2026-10-09.
코어: `rmdkdkr-png/ss2-sp-core` **main** ee87648 (framegen 가지 fast-forward 병합, svc-core 는 그대로).
바이너리: `cores/mednafen_ngp_libretro.android-arm64-v8a.so` (스탬프 a8499f6).

## 무엇을 하나
60fps 게임의 두 실제 프레임 사이에 중간 프레임을 끼워 120Hz 로 내보낸다. 픽셀을 섞지 않고
K2GE 스프라이트표·스크롤 레지스터를 보간해 같은 타일을 다시 그린다. 기본 「예측」방식은
상태를 저장한 채 다음 프레임을 미리 돌려 현재↔다음 중간을 그리고 되돌리므로 추가 지연 0.

## 코어 옵션
| 키 | 값 | 기본 |
|---|---|---|
| `ngp_framegen` | `auto` / `disabled` / `enabled` | `auto` |
| `ngp_framegen_mode` | `predict` / `interp` | `predict` |

`auto` 는 `RETRO_ENVIRONMENT_GET_TARGET_REFRESH_RATE` 가 60.25 의 짝수 배(120·240)일 때만 켠다.
PocketCore feat/framegen-art dcb08a8 이 이 질문에 실측 패널 주사율로 답하므로 그대로 맞물린다.

## 프론트엔드가 보게 되는 것
1. **fps 선언**: 켜지면 `retro_get_system_av_info().timing.fps = 120.5` (60.25×2). 목표 주사율이
   로드 때 이미 120 이면 `retro_load_game` 안에서 바로 정해져 재초기화가 없다. 런타임에 바뀌면
   30 실제 프레임 뒤 `RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO` 로 알린다 (native.c 가 g_av 를 갱신하는 것 확인).
2. **호출 교대**: `retro_run` 짝수 = 실제 에뮬 프레임, 홀수 = 합성 프레임. 비디오는 매 호출 나간다.
3. **오디오**: 실제 프레임의 샘플(약 732)을 반씩 두 호출에 나눠 보낸다(호출당 약 366). 호출당
   샘플 수를 가정하지 않으면 그대로 된다.
4. **호출 속도 감시**: 100ms 넘는 공백을 뺀 1초 창 호출 속도가 2초 연속 90/s 미만이면 코어가
   스스로 60.25 로 돌아가고 `RETRO_ENVIRONMENT_SET_MESSAGE` 로
   「화면이 120Hz 로 돌지 않습니다 — 프레임 생성을 껐습니다」를 보낸다. 패널이 60 에 묶였을 때
   뜨는 게 정상이며, 그 뒤엔 앱 보간이 대신 맡으면 된다(앱은 vsync 실측으로 판단하니 자동).
   옵션을 다시 쓰거나 게임을 다시 열면 재판정.
5. **런어헤드 가드**: `GET_AUDIO_VIDEO_ENABLE` 로 비디오 꺼짐(숨은 호출)이 오거나
   `GET_SAVESTATE_CONTEXT` 가 런어헤드면 자동 모드는 바로 꺼진다. PocketCore 는 늘 3 을 돌려주니 해당 없음.
6. **알림**: 켜질 때 「프레임 생성 켬 — 120Hz 출력 (예측)」, 꺼질 때 「프레임 생성 끔 — 60Hz 출력」.

## 역할 분담 (합의)
- 사무쇼2: 코어 방식(레지스터 보간·예측·지연 0). 코어가 120.5 를 선언하면 앱의 vsync 판정에서
  앱 보간은 저절로 꺼진다 — 이중 보간 없음.
- 나머지 게임(svc 코어): 앱의 픽셀 보간(framegen.c).

## 합성하지 않는 순간 (그때만 60Hz 와 같음)
표시 도중 스프라이트표/타일맵/스프라이트 팔레트 번호를 고쳐 쓴 프레임, 흑백 모드, 24px 넘는
스프라이트 점프·32px 넘는 스크롤 점프, 타일이 바뀐 조각(같은 체인 그룹의 다른 조각이 한 방향이면 같이 이동).

## 확인 부탁
폴드6에서 main 의 새 .so 로 구워 「프레임 생성 켬」 알림이 뜨는지, 걷기가 부드러운지, 알림 4번이
뜨는지 — 이 PR 에 댓글로 남겨 주면 커스텀 apk 방이 받는다(PR 활동 구독 중).

자세한 설명: ss2-sp-core `docs/프레임생성.md`.
