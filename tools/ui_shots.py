#!/usr/bin/env python3
"""UI 스크린샷 — CI 에뮬레이터에서 앱 화면을 찍는다(세션이 화면을 눈으로 확인하려고).

    python3 tools/ui_shots.py app-debug.apk outdir

가짜 롬 두 개(사무쇼2 머리표 SAMURAI2 + 표에 없는 롬)를 만들어 넣는다 — 진짜 롬은 안 쓴다(배포 금지).
가짜 롬은 시작 주소에서 제자리 돌기(JR T,$)만 하므로 게임 그림은 검은 화면이고, 앱이 그리는 것(패드·메뉴·창)만 보인다.
폴드 큰 화면(1856x2160)과 덮개 화면(904x2316)을 차례로 찍는다.
"""
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.dudu.legacito.fgtest"
APK, OUT = sys.argv[1], sys.argv[2]
os.makedirs(OUT, exist_ok=True)


def adb(*a, check=False, out=False):
    r = subprocess.run(["adb"] + list(a), capture_output=True)
    if check and r.returncode != 0:
        print("adb", a, r.stderr.decode(errors="replace"))
    return r.stdout if out else r.returncode


def sh(cmd):
    return adb("shell", cmd, out=True).decode(errors="replace")


def shot(name):
    png = adb("exec-out", "screencap", "-p", out=True)
    open(os.path.join(OUT, name + ".png"), "wb").write(png)
    print("shot", name, len(png))


def fake_rom(path, tag):
    rom = bytearray(b"\xff" * (2 << 20))
    rom[0:28] = b" LICENSED BY SNK CORPORATION"
    rom[0x1C:0x20] = (0x00200040).to_bytes(4, "little")      # 시작 주소
    rom[0x20:0x22] = b"\x00\x00"
    rom[0x22] = 0
    rom[0x23] = 0x10                                          # 컬러
    rom[0x24:0x30] = tag.encode().ljust(12, b"\x00")
    rom[0x40:0x42] = b"\x68\xfe"                              # JR T,$ — 제자리 돌기
    open(path, "wb").write(rom)


def dump():
    sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1")
    x = adb("exec-out", "cat", "/sdcard/ui.xml", out=True)
    try:
        return ET.fromstring(x)
    except Exception:
        return None


def bounds(node):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    return tuple(int(v) for v in m.groups()) if m else None


def find(pred):
    root = dump()
    if root is None:
        return None
    for n in root.iter("node"):
        if pred(n):
            return bounds(n)
    return None


def tap_text(text, exact=True):
    b = find(lambda n: (n.get("text") == text) if exact else (text in (n.get("text") or "")))
    if not b:
        print("no text", text)
        return False
    sh("input tap %d %d" % ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2))
    return True


def view_bounds(cls_suffix):
    return find(lambda n: (n.get("class") or "").endswith(cls_suffix))


def scroll_shots(name, pages=3):
    shot(name + "_1")
    for i in range(2, pages + 1):
        sh("input swipe 500 1500 500 500 300")
        time.sleep(1.0)
        shot("%s_%d" % (name, i))


def bar_cells(w, h, labels):
    """PadView.layoutBar 와 같은 계산 — 보이는 칸들의 가운데 좌표(뷰 기준)."""
    base = min(w, h)
    vis = len(labels)
    uh = base * 0.056
    gap = w * 0.006
    gap_y = base * 0.010
    rows = 2 if vis > 6 else 1
    if os.environ.get("BAR_V2"):
        dp = 420 / 160.0
        rows = 1 if (w * 0.96 - gap * (vis - 1)) / vis >= 52 * dp else 2
    per = (vis + rows - 1) // rows
    maxw = base * 0.15 if w > h else w * 0.20
    uw = min(maxw, (w * (0.96 if os.environ.get("BAR_V2") else 0.90) - gap * (per - 1)) / per)
    y = base * 0.052
    out = {}
    placed = 0
    in_row = 0
    n_this = min(per, vis)
    x = (w - (uw * n_this + gap * (n_this - 1))) / 2
    for lab in labels:
        out[lab] = (x + uw / 2, y + uh / 2)
        x += uw + gap
        placed += 1
        in_row += 1
        if in_row == per and placed < vis:
            in_row = 0
            y += uh + gap_y
            n_this = min(per, vis - placed)
            x = (w - (uw * n_this + gap * (n_this - 1))) / 2
    return out


def screen_box():
    m = re.search(r"(\d+)x(\d+)", sh("wm size").split("Override size:")[-1])
    return (0, 0, int(m.group(1)), int(m.group(2))) if m else (0, 0, 1080, 1920)


def settings_pages(tag):
    shot(tag + "_06_settings_top")
    for page in ["화면", "움직임·반응", "조작", "게임", "소리", "업데이트"]:
        if tap_text(page):
            time.sleep(2)
            scroll_shots("%s_07_%s" % (tag, page), 3)
            sh("input keyevent 4")
            time.sleep(1.5)


def run(tag, size, density, bar_labels, with_settings):
    sh("wm size %s" % size)
    sh("wm density %d" % density)
    sh("am force-stop %s" % PKG)
    time.sleep(1)
    sh("rm -f /sdcard/PocketCore/saves/*.auto")
    adb("shell", "am", "start", "-n", PKG + "/com.dudu.pocketcore.MainActivity", "--ez", "menu", "true")
    time.sleep(8)
    shot(tag + "_01_launcher")
    if with_settings:
        lv = view_bounds("LauncherView") or screen_box()
        print("LauncherView", lv)
        lw, lh = lv[2] - lv[0], lv[3] - lv[1]
        sh("input tap %d %d" % (lv[0] + lw // 2, lv[1] + int(lh * 0.955)))     # OPTION = 설정
        time.sleep(2.5)
        settings_pages(tag)
        sh("input keyevent 4")
        time.sleep(2)
    sh("input keyevent 66")            # ENTER → 실행 전 선택창
    time.sleep(2.5)
    shot(tag + "_02_launchsheet")
    sh("input keyevent 66")            # ENTER(포커스 = 시작) → 게임
    time.sleep(9)
    shot(tag + "_03_game")
    pv = view_bounds("PadView") or screen_box()
    print("PadView", pv)
    w, h = pv[2] - pv[0], pv[3] - pv[1]
    base = min(w, h)
    sh("input tap %d %d" % (pv[0] + w // 2, pv[1] + int(base * 0.023)))   # 메뉴 알약
    time.sleep(1.5)
    shot(tag + "_04_menu")
    cells = bar_cells(w, h, bar_labels)
    if "설정" in cells:
        cx, cy = cells["설정"]
        sh("input tap %d %d" % (pv[0] + int(cx), pv[1] + int(cy)))
        time.sleep(2.5)
        shot(tag + "_05_ingame_sheet")
        sh("input keyevent 4")
        time.sleep(1.5)
    sh("input tap %d %d" % (pv[0] + w // 2, pv[1] + int(base * 0.023)))   # 메뉴 닫기
    time.sleep(1)
    shot(tag + "_08_game_after")


def main():
    adb("install", "-r", "-g", APK, check=True)
    sh("appops set --uid %s MANAGE_EXTERNAL_STORAGE allow" % PKG)
    fake_rom("/tmp/a_ss2.ngc", "SAMURAI2")
    fake_rom("/tmp/b_test.ngc", "UITESTROM")
    sh("mkdir -p /sdcard/PocketCore/roms")
    adb("push", "/tmp/a_ss2.ngc", "/sdcard/PocketCore/roms/a_ss2.ngc", check=True)
    adb("push", "/tmp/b_test.ngc", "/sdcard/PocketCore/roms/b_test.ngc", check=True)
    opts = ("pocketcore_lang=ja\npocketcore_ss2_music=off\npocketcore_launcher_snd=disabled\n"
            "pocketcore_padskin=art\npocketcore_level=test\n")
    open("/tmp/options.txt", "w").write(opts)
    adb("push", "/tmp/options.txt", "/sdcard/PocketCore/options.txt", check=True)
    labels = os.environ.get("BAR_LABELS", "슬롯1,저장,로드,샷,리셋,앱보간,코어120,설정,배치,종료").split(",")
    run("main", "1856x2160", 420, labels, True)
    run("cover", "904x2316", 420, labels, False)
    sh("wm size reset")
    sh("wm density reset")


if __name__ == "__main__":
    main()
