음색 다듬기 호스트 시험(179) — native.c 를 그대로 넣어(#include) 돌린다. 안드로이드 헤더는 inc/ 의 빈 껍데기(GL·로그)와 가짜 AAudio.
  J=/usr/lib/jvm/java-21-openjdk-amd64/include; C=app/src/main/cpp
  SRC="$C/pace.c $C/sndfx.c $C/league.c $C/practice.c $C/look.c $C/slash.c $C/vpanel.c $C/vsband.c $C/cheat.c $C/ovplane.c $C/uisfx.c $C/callout.c tools/sndfx_host/stubs.c"
  e2e      — 진짜 코어(.so)를 load_locked 로 올려 retro_run → cb_audio_batch → fx_apply → 고리(배속 길, 늘림 없음)를 파일로.
             gcc -std=gnu17 -O2 -w -I tools/sndfx_host/inc -I $C -I $J -I $J/linux tools/sndfx_host/e2e.c $SRC -lm -ldl -lpthread -o e2e
             ./e2e core.so rom opts.txt 프레임수 off|all|bgm out.raw 화면Hz [입력.raw 잡음.raw]
             all = tools/sndfx_test 를 입력에 / bgm = clip(sndfx(clip(입력 − 잡음)) + 잡음) 과 바이트 같아야 한다(10-20 데모 5400·2배 10800 확인).
  harness2 — UI 스레드(nativeSetSoundFx 마구)·GL 스레드(cb_audio_batch)·소리 카드 스레드(가짜 AAudio) — -fsanitize=thread / address,undefined.
             TSan 보고는 옛 고리(g_ring·g_rd·g_wr·g_cb_frames — 단일 생산·단일 소비, volatile)만이어야 한다.
