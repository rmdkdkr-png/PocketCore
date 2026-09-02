# PocketCore

네오지오 포켓 컬러 게임을 **한글패치 자동 적용 · 해설 더빙 · 원버튼 필살기**와 함께 돌리는
안드로이드 앱. 메뉴 없는 libretro 프론트엔드 — 켜면 바로 게임이다.

**➜ [최신 APK 받기 (릴리즈 app 태그)](https://github.com/rmdkdkr-png/PocketCore/releases/tag/app)**

## 무엇을 해 주나
- **한글패치 자동 적용** — 롬을 넣으면 헤더로 게임을 알아보고, [KrPatch](https://github.com/rmdkdkr-png/KrPatch)
  최신 IPS 를 내려받아 **사본**에 입혀 실행한다 (원본 롬은 안 건드린다).
- **사무쇼2 해설·더빙** — 캐릭터 해설 자막 + 한국어 음성(음성팩 자동 다운로드).
- **SvC 원버튼 필살기** — 기술키 하나로 커맨드 대행. 방향에 따라 다른 기술.
- **업데이트 버튼 하나** — 앱·코어·음성팩·한글패치를 전부 확인하고 소식을 보여 준다.
  APK 재설치는 앱이 바뀐 날만.
- 오토세이브(나갈 때 저장, 열면 이어하기) · 게임별 키 배치 · 화면 위치/크기 조절 ·
  배속 · 상태 슬롯 3 · 스크린샷.

## 지원 게임

| 게임 | 한글패치 | 부가 기능 |
|---|---|---|
| SNK vs. Capcom — 정상결전 최강 파이터즈 | v0.18 | 원버튼 필살기 엔진 |
| 사무라이 스피리츠! 2 | v0.99b3 | 캐릭터 해설 + 한국어 더빙(음성팩) |
| 사무라이 스피리츠! | v0.19 |  |
| 월화의 검사 특별편 | v2.2 |  |
| 더 킹 오브 파이터즈 R-2 | v0.2.1 |  |
| 아랑전설 퍼스트 컨택트 | v0.1a |  |
| 메탈슬러그 1st 미션 | v0.1 |  |

표에 없는 NGP(C) 롬도 순정 그대로 돌아간다. `PocketCore/patch/<롬파일명>.ips` 로
직접 구한 패치도 입힐 수 있다.

## 생태계
- 앱(이 저장소) — APK·색인 배포: 태그 [app](https://github.com/rmdkdkr-png/PocketCore/releases/tag/app)
- 코어·음성팩 — [ss2-sp-core 릴리즈](https://github.com/rmdkdkr-png/ss2-sp-core/releases) (core-svc · core-ss2 · ss2-voice)
- 한글패치(IPS) — [KrPatch 릴리즈](https://github.com/rmdkdkr-png/KrPatch/releases), 규칙은
  [RELEASE_RULES.md](https://github.com/rmdkdkr-png/KrPatch/blob/main/RELEASE_RULES.md)

## 주의
- 롬은 없다. 본인 소유 롬을 `PocketCore/roms/` 에 넣을 것.
- 옆설치 경고·Play 프로텍트 안내는 스토어 밖 앱의 공통 안내다.
- 안드로이드 8.0+ · arm64/armv7/x86_64.
