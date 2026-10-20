#!/bin/bash
# 191 코어 타격 알림·이펙트·몸 번쩍 시험 — qbot_hx 여럿을 «같은 초에» 띄운다(롬 RTC 가 벽시계를 읽어서 띄운 초가 다르면 판이 갈린다).
#   빌드: gcc -std=gnu17 -O1 -g -w -I app/src/main/cpp -o qbot_hx tools/callout_host/qbot_hx.c app/src/main/cpp/callout.c app/src/main/cpp/hitflash.c -ldl
#   쓰기: ROM=사무쇼2.ngc CORE=코어.so HXBIN=./qbot_hx PRELUDE=고르기·대전까지_대본.txt CHAR=1 SEED=71 IDLE=5000 [PROBE=1 HZ=120 FAKECLK=1 HASHALL=1 WHITELOG=1] \
#         qbot_hx_par.sh 이름:모드:옵션[:환경K=V;K=V] …     (모드 app · core · off — qbot_hx.c 머리말)
#   결과: 이름/ev.txt(사건 줄) · hash.txt · err.txt. 앱 판정과 코어를 견줄 땐 diff 이름_app/ev.txt 이름_core/ev.txt.
: "${ROM:?ROM}" "${CORE:?CORE}"
HXBIN=${HXBIN:-./qbot_hx}
for spec in "$@"; do
  IFS=: read -r name mode opts envs <<< "$spec"
  rm -rf "$name"; mkdir -p "$name"
done
for spec in "$@"; do
  IFS=: read -r name mode opts envs <<< "$spec"
  ( if [ -n "$envs" ]; then IFS=';' read -ra kv <<< "$envs"; for e in "${kv[@]}"; do export "$e"; done; fi
    HXMODE=$mode OPTS="$opts" exec "$HXBIN" "$CORE" "$ROM" - 0 "$name" 2> "$name/err.txt" ) &
done
wait
for spec in "$@"; do
  IFS=: read -r name mode opts envs <<< "$spec"
  echo "== $name: $(tr '\n' ' ' < "$name/err.txt") | FLASH $(grep -c '^FLASH' "$name/ev.txt")"
done
