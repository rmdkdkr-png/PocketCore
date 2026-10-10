# Legacito 서명·배포 절차

Legacito(`com.dudu.legacito`)는 옛 PocketCore(`com.dudu.pocketcore`)의 뒤를 잇는 새 앱이다.
옛 앱의 서명 키(`CN=dudu, O=SS2SP`)는 2026-10-10 에 잃은 것으로 확인돼(빌드하던 클라우드 세션과 함께 소멸)
패키지 이름을 바꿔 새로 시작했다. 옛 앱은 `version.json`·`cores.json` 을, Legacito 는
`legacito-version.json`·`legacito-cores.json` 을 본다(같은 릴리즈 태그 `app`).

## 키

- 파일: `legacito-release.jks` (PKCS12), 별칭 `legacito`, RSA 4096, 100년
- 인증서 SHA-256: `59:15:AB:E0:B8:CC:90:E3:E5:1D:73:E9:17:51:DD:85:CE:79:BC:7C:0F:D3:19:56:FA:99:68:EA:F1:B6:4F:16`
- **키 파일과 비밀번호는 이 공개 저장소에 없다.** 보관처: ① 유저 본인(파일로 받음) ② claude.ai 프로젝트 「패치 포팅」 문서 `claude/Legacito_서명키.md`
- 이 키를 잃으면 또 새 앱으로 갈라서야 한다. 두 곳 모두에 남아 있는지 판마다 확인할 것.

## 배포 한 판

1. `feat/framegen-art`(또는 배포 브랜치)에 푸시 → Actions `release-unsigned` 가 태그 `ci-release` 에
   `Legacito-unsigned.apk`(zipalign 끝) 와 `apksigner.jar` 를 올린다.
2. 둘을 받아 서명:
   ```
   java -jar apksigner.jar sign --ks legacito-release.jks --ks-key-alias legacito \
        --ks-pass pass:<비번> --key-pass pass:<비번> --out Legacito-v<판>.apk Legacito-unsigned.apk
   java -jar apksigner.jar verify --print-certs Legacito-v<판>.apk   # SHA-256 가 위와 같아야 한다
   ```
3. 태그 `app` 에 `Legacito-v<판>.apk` 를 올리고 `legacito-version.json` 을 갈아 끼운다:
   `{"versionCode":<판×100>,"versionName":"<판>","apk":"Legacito-v<판>.apk"}`
   시험만 먼저 내려면 최상위는 그대로 두고 `"test":{...}` 만 넣는다(설정 「배포 레벨: 시험」 기기만 받음).
4. 코어를 바꿀 때는 `legacito-cores.json` 만 고친다. 사무쇼2(`ss2`)는 동봉 코어를 쓰려고 일부러 비워 뒀다 —
   새 ss2 코어(프레임 생성 포함)를 낼 때만 넣을 것. 옛 `cores.json` 의 ss2 3.71 은 프레임 생성이 없다.
