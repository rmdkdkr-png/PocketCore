<div align="center">

# 🕹 PocketCore

**네오지오 포켓 컬러, 한국어로.**

한글패치 **전** 순정 롬을 넣으면 — 게임을 알아보고, 제작한 한글패치를 GitHub 에서 받아
실행할 때 입혀 주는 안드로이드 에뮬레이터 앱. 메뉴 숲 없이 — **켜면 바로 게임.**

**[⬇ 최신 APK 받기](https://github.com/rmdkdkr-png/PocketCore/releases/tag/app)**

*아직 프로토타입입니다 — 미숙한 구석이 있고, 지원하는 롬도 아래 표의 일부(네오지오 포켓)뿐입니다.*

<img src="https://github.com/rmdkdkr-png/PocketCore/releases/download/app/thumb_svc.png" width="11%"> <img src="https://github.com/rmdkdkr-png/PocketCore/releases/download/app/thumb_ss2.png" width="11%"> <img src="https://github.com/rmdkdkr-png/PocketCore/releases/download/app/thumb_ss1.png" width="11%"> <img src="https://github.com/rmdkdkr-png/PocketCore/releases/download/app/thumb_lb.png" width="11%"> <img src="https://github.com/rmdkdkr-png/PocketCore/releases/download/app/thumb_kofr2.png" width="11%"> <img src="https://github.com/rmdkdkr-png/PocketCore/releases/download/app/thumb_ffc.png" width="11%"> <img src="https://github.com/rmdkdkr-png/PocketCore/releases/download/app/thumb_ms1.png" width="11%"> <img src="https://github.com/rmdkdkr-png/PocketCore/releases/download/app/thumb_ms2.png" width="11%">

</div>

## 이렇게 생겼다

| | | |
|---|---|---|
| <img src="https://github.com/rmdkdkr-png/PocketCore/releases/download/app/ui1_launcher.png" width="230"> | <img src="https://github.com/rmdkdkr-png/PocketCore/releases/download/app/ui2_firstrun.png" width="230"> | <img src="https://github.com/rmdkdkr-png/PocketCore/releases/download/app/ui3_scan.png" width="230"> |
| 진열장 런처 — 실화면 썸네일 카드 | 첫 화면 — 롬 가져오는 세 가지 길 | 저장소 스캔 — 찾아서 골라 담기 |
| <img src="https://github.com/rmdkdkr-png/PocketCore/releases/download/app/ui4_layout.png" width="230"> | <img src="https://github.com/rmdkdkr-png/PocketCore/releases/download/app/ui5_patch.png" width="230"> |
| 배치 편집 — 버튼도 게임 화면도 끌어서 | 실행하면 한글패치 자동 적용 |

## 이런 앱이다

| | |
|---|---|
| 🈶 **한글패치 자동 적용** | 롬 헤더로 게임을 알아보고 [KrPatch](https://github.com/rmdkdkr-png/KrPatch) 최신 IPS 를 받아 **사본**에 입힌다 — 원본 롬은 절대 안 건드린다. 썸네일·SP 버튼 같은 아트·조작 자산도 같은 방식으로 배달된다 |
| 🎙 **해설·더빙 — 사무쇼2 전용** | 대전 해설 자막 + 한국어 음성. 음성팩은 업데이트 버튼이 알아서 받는다 |
| ⚡ **원버튼 필살기 — 정상결전(SvC) 전용** | 기술키 하나로 커맨드 대행 — 잡은 방향에 따라 다른 기술, 누르는 길이로 약/강. 추가 기능 패치는 지금 이 두 게임 정도다 |
| 🔄 **업데이트 버튼 하나** | 앱·코어·음성팩·한글패치를 전부 확인하고 새 소식을 보여 준다. APK 재설치는 앱이 바뀐 날만 |
| 💾 **오토세이브** | 나갈 때 저장, 열면 「이어하기」. 수동 슬롯 3개는 따로 |
| 🎛 **내 마음대로 배치** | 게임별 가상 패드 배치·크기, 게임 화면 위치·크기까지 「배치」 편집으로 끌어서 조정 |
| 🎨 **진열장 런처** | 게임을 몰래 부팅해 찍은 실화면 썸네일 카드, 도트 부팅 연출, 테마곡 |
| 🎮 **물리 패드 지원** | 블루투스/USB 패드 자동 매핑, 터치 패드와 병행 |

## 지원 게임

| 게임 | 한글패치 | 부가 기능 |
|---|---|---|
| SNK vs. Capcom — 정상결전 최강 파이터즈 | v0.18 | 원버튼 필살기 엔진 |
| 사무라이 스피리츠! 2 | v0.99b3 | 캐릭터 해설 + 한국어 더빙(음성팩) |
| 사무라이 스피리츠! | v0.19 |  |
| 월화의 검사 특별편 | v2.3 |  |
| 더 킹 오브 파이터즈 R-2 | v0.2.1 |  |
| 아랑전설 퍼스트 컨택트 | v0.1a |  |
| 메탈슬러그 1st 미션 | v0.1 |  |
| 메탈슬러그 2nd 미션 | v0.2 |  |

표에 없는 네오지오 포켓(컬러) 롬도 순정 그대로 돌아간다. 직접 구한 IPS 가 있으면
`PocketCore/patch/<롬파일명>.ips` 로 넣어 어느 롬에든 입힐 수 있다.

## 시작하기 — 3분

1. [릴리즈 app 태그](https://github.com/rmdkdkr-png/PocketCore/releases/tag/app)에서 최신 `PocketCore-v○.○○.apk` 설치
   (「출처를 알 수 없는 앱」 경고는 스토어 밖 설치에 늘 뜨는 안내다 — 허용하고 진행)
2. 첫 실행에서 **모든 파일 접근** 허용
3. 내장 저장소 `PocketCore/roms/` 에 본인 소유 롬(.ngc/.ngp)을 넣고 앱을 다시 열기

이후는 앱이 알아서 한다 — 목록에서 **「업데이트 확인」** 을 한 번 누르면 한글패치·코어·음성팩이
차례로 내려와 다음 실행부터 적용된다.

## 조작 — 게임 안에서

- 화면 위 **「메뉴 ▾」** — 저장·로드·샷·리셋·종료·배치(버튼과 게임 화면을 끌어서 배치)
- **아래(↓) + OPTION 동시** — 빠른 설정 오버레이. 항목은 코어가 정한다 — 게임에 없는
  기능은 아예 안 나온다 (순정 게임이면 오버레이 자체가 없다)
- 롬 가져오기 — 첫 화면(롬이 없을 때)이나 설정에서: 폴더 주소 복사 · 파일 골라 오기 · 저장소 스캔

## 취향대로

- **테마곡 교체** — `PocketCore/system/theme.mp3`(wav/ogg 가능)를 넣으면 런처 음악이 바뀐다
- **썸네일 지정** — 게임 중 「샷」을 찍으면 그 장면을 카드 그림으로 지정할 수 있다
- **설정 파일** — `PocketCore/options.txt` 에서 언어(한/일/영)·해설·기술명 띠 등을 바꾼다

## 생태계 — 각자 자기 릴리즈로 산다

| 저장소 | 내용물 |
|---|---|
| 이 저장소 · 태그 [app](https://github.com/rmdkdkr-png/PocketCore/releases/tag/app) | 앱 APK + 색인(version/patches/cores/news/design) |
| [ss2-sp-core](https://github.com/rmdkdkr-png/ss2-sp-core/releases) | 에뮬 코어(core-svc·core-ss2) · 사무쇼2 음성팩 · 코어 소스 전체 |
| [KrPatch](https://github.com/rmdkdkr-png/KrPatch/releases) | 게임별 한글패치(IPS) — 규칙은 [RELEASE_RULES.md](https://github.com/rmdkdkr-png/KrPatch/blob/main/RELEASE_RULES.md) |

## 저작권 · 책임

- 게임과 게임 속 그림·음악·이름의 권리는 **SNK 등 원 권리자**의 것이다.
  이 앱과 한글패치는 **비영리 팬 프로젝트**이며 원 권리자와 아무 관련이 없다.
- **롬은 배포하지 않는다.** 여기서 배포하는 것은 앱·코어·차분 패치(IPS)·자막·음성뿐이고,
  전부 본인 소유의 롬이 있어야만 쓸 수 있다.
- 에뮬 코어는 [beetle-ngp-libretro](https://github.com/libretro/beetle-ngp-libretro)(GPL) 기반이며
  개조 소스 전체를 [ss2-sp-core](https://github.com/rmdkdkr-png/ss2-sp-core)에 공개한다.
- 본문 폰트 [Galmuri](https://github.com/quiple/galmuri)(SIL OFL 1.1) · 런처 음악은 자작이다.
- **권리자의 요청이 있으면 해당 배포물은 바로 내린다.**
- 앱과 패치는 있는 그대로(as-is) 제공된다 — **내려받고 쓰는 데 따르는 문제는 사용자 책임이다.**
