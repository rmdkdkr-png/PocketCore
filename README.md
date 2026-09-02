# PocketCore

메뉴 없는 안드로이드 libretro 프론트엔드. C(NDK) 위에 얇은 자바 껍데기만 얹었습니다.

앱을 켜면 **바로 마지막 롬이 실행**됩니다. 설정 화면도, 코어 다운로더도, 메뉴 트리도 없습니다.

## 구조

```
app/src/main/cpp/native.c   libretro 코어 로드 · 프레임 루프 · GLES2 출력 · AAudio · 세이브
app/src/main/cpp/libretro.h libretro API 최소 서브셋
java/.../Emu.java           JNI 껍데기
java/.../MainActivity.java  권한 요청 → 마지막 롬 자동 실행 (없을 때만 목록)
java/.../EmuActivity.java   GLSurfaceView + 롬 변경 감지 + 스크린샷 + 게임패드
java/.../PadView.java       온스크린 패드 (D-pad, A, B, OPTION, 유틸 5개)
```

## 빌드 전 준비 — 코어 넣기

RetroArch 안드로이드용 코어(.so)를 **`libretro_core.so`** 라는 이름으로 아래에 복사:

```
app/src/main/jniLibs/arm64-v8a/libretro_core.so       (요즘 폰은 거의 이것)
app/src/main/jniLibs/armeabi-v7a/libretro_core.so     (구형 32비트용, 선택)
```

NGPC라면 `mednafen_ngp_libretro_android.so` (Beetle NeoPop)를 이름만 바꿔 넣으면 됩니다.
코어는 buildbot.libretro.com 의 `nightly/android/latest/arm64-v8a/` 에서 받거나,
이미 설치된 RetroArch의 코어 폴더에서 꺼내면 됩니다.

> APK 안에 코어를 넣는 이유: 안드로이드 10부터 외부 저장소의 .so는 dlopen이 막힙니다.
> (`/sdcard/PocketCore/cores/` 에 .so를 두면 그쪽을 우선 시도하지만, 기기에 따라 실패합니다.)

## 빌드

Android Studio로 폴더를 열고 Run. 또는:

```
./gradlew assembleDebug     # Android Studio가 wrapper를 만들어 줍니다
```

- minSdk 26 (AAudio), targetSdk 34, arm64-v8a + armeabi-v7a

## 폰에서 쓰기

첫 실행 때 "모든 파일 접근"을 허용하면 아래 폴더가 만들어집니다.

```
/sdcard/PocketCore/
  roms/        ← 롬 파일을 여기에
  saves/       ← .srm, .state 자동 저장
  system/      ← BIOS 등 코어가 요구하는 파일
  shots/       ← 스크린샷 PNG (원본 해상도 그대로)
  options.txt  ← 코어 옵션 (선택)
```

롬이 하나면 그냥 실행됩니다. 여러 개면 처음 한 번만 고르고, 그 뒤로는 계속 그 롬으로 바로 켜집니다.
다른 롬으로 바꾸려면 화면 위쪽 **롬** 버튼.

## 조작

- 왼쪽 D-pad, 오른쪽 A/B, 아래 OPTION(= libretro START)
- 위쪽 유틸: **저장**(상태 저장) · **로드** · **샷** · **리셋** · **롬**
- 블루투스 게임패드 연결하면 바로 인식 (A/B/X/Y/L1/R1/Start/Select/D-pad)

## 롬 해킹용 기능

- **롬 자동 리로드**: `roms/` 안의 실행 중인 롬 파일이 바뀌면 1초 안에 감지해서 다시 로드합니다.
  PC에서 패치 → 폰으로 동기화 → 앱은 그대로 두면 알아서 갱신됩니다.
- **원본 해상도 스크린샷**: 화면 확대·오버레이 없이 코어가 낸 프레임 그대로 PNG 저장.
- **정수배 확대 + NEAREST 필터**: 픽셀이 뭉개지지 않습니다.

## 코어 옵션 (메뉴 대신 텍스트 파일)

`/sdcard/PocketCore/options.txt`:

```
# key=value, 코어가 쓰는 키 이름 그대로
ngp_language=english
```

없으면 코어 기본값을 씁니다.

## 알려진 한계

- 코어 옵션 UI 없음(의도한 것) — 텍스트 파일로만.
- 세로 화면 고정. 가로가 필요하면 Manifest의 `screenOrientation`만 바꾸면 됩니다.
- 오디오는 링버퍼 + 언더런 무음 처리. 리샘플링 없이 코어 샘플레이트를 그대로 AAudio에 넘깁니다.
