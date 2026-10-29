# 잔심 v21 — 메뉴·OPTION 복원

2026-10-29 공개 · 앱 표시 버전 **1.20 (21)**

## 다운로드

- [일반판 APK · 68.0 MB](https://github.com/rmdkdkr-png/PocketCore/releases/download/app/zanshin-21.apk)
- [라이트판 APK · 6.2 MB](https://github.com/rmdkdkr-png/PocketCore/releases/download/app/zanshin-lite-21.apk)
- [v21 대응 소스·개발자 노트](https://github.com/rmdkdkr-png/PocketCore/releases/download/app/zanshin-21-source.zip)
- [릴리스 페이지와 이전 버전](https://github.com/rmdkdkr-png/PocketCore/releases/tag/app)

일반판과 라이트판은 서로 다른 앱입니다. 사용 중인 판의 APK로 업데이트하세요. 기존 앱 삭제나 데이터 초기화는 필요하지 않습니다. ROM·개인 저장·서명키는 배포물에 포함하지 않습니다.

## 바뀐 점

- 게임 중 메뉴를 기존 상단 버튼 바로 복원했습니다.
- 설정·치트·버튼 배치는 기존 Android 창으로 열립니다.
- 새 인게임 General 메뉴를 걷어내고 기존 OPTION 동작을 복원했습니다. 캐릭터 선택에서는 색 변경, 일반 경기에서는 일시정지, 리그에서는 기존의 작은 쉼 메뉴로 동작합니다.

v20의 게임별 베기색, 대전 안팎의 기술 배치, 스테이지 도입 연출과 기존 카드·저장 기능은 유지합니다.

## 확인한 범위

일반판 v21을 Android 에뮬레이터에서 실행해 터치와 패드 키 이벤트로 다음 동작을 확인했습니다.

- 상단 메뉴, 설정·치트 진입과 복귀, 버튼 배치 편집
- OPTION 색 변경, 일반 경기 일시정지·재개
- 대전 안팎의 기술 배치, 리셋
- 리그 진입·상대 확인, 쉼 메뉴, 저장 후 나가기

일반판·라이트판의 빌드 기록, 대응 소스, 해시와 기존 서명을 대조했습니다. 라이트판은 이번 검수에서 별도로 실행하지 않았습니다. 실제 휴대폰·물리 게임패드, 모든 무대·저장 슬롯·리그 결과 정산은 이번 검수 범위에 포함하지 않습니다.

## 파일 확인용 SHA-256

| 파일 | 바이트 | SHA-256 |
|---|---:|---|
| zanshin-21.apk | 67997666 | `229a94ddd485879724579caf3f18bce4d14e9da943b9f7da7e4aa2d44443c964` |
| zanshin-lite-21.apk | 6234007 | `863512c9697ab3d99b016ebc57b1b4e808834d361d68ae7bcf9e80f6ac64b79f` |
| zanshin-21-source.zip | 67991366 | `c81c44520b88a3ad80860a9a3921a162afbdfd57a298a6eef729f2e871c4927a` |

일반판의 `zanshin-version.json`과 라이트판의 `zanshin-lite-app-version.json` 업데이트 안내는 내부 버전 21을 가리킵니다.
