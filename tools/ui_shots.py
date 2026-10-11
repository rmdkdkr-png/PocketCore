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
_LOG = open(os.path.join(OUT, "log.txt"), "a", encoding="utf-8")
_print = print


def print(*a):                      # 로그를 결과 묶음에도 남긴다(CI 로그는 세션에서 못 받음)
    _print(*a)
    _LOG.write(" ".join(str(x) for x in a) + "\n")
    _LOG.flush()


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


def bar_cells(w, h, labels, density=420):
    """PadView.layoutBar 와 같은 계산 — 보이는 칸들의 가운데 좌표(뷰 기준)."""
    dp = density / 160.0
    base = min(w, h)
    vis = len(labels)
    uh = max(base * 0.050, 34 * dp)
    hh = max(base * 0.046, 30 * dp)                  # 메뉴 알약 높이(PadView.handleH)
    gap = max(w * 0.006, 4 * dp)
    gap_y = base * 0.010
    row_w = w * (0.80 if w > h else 0.96)
    rows = 1 if (row_w - gap * (vis - 1)) / vis >= 52 * dp else 2
    per = (vis + rows - 1) // rows
    maxw = base * 0.16 if w > h else w * 0.22
    uw = min(maxw, (row_w - gap * (per - 1)) / per)
    y = max(base * 0.052, hh + 4 * dp)
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


def dismiss_anr():
    """에뮬레이터의 시스템 앱(Pixel Launcher 등)이 「isn't responding」 창을 띄우면 모든 탭이 막힌다 — 「Wait」 를 눌러 치운다."""
    for _ in range(3):
        root = dump()
        if root is None:
            return
        hit = any("isn't responding" in (n.get("text") or "") for n in root.iter("node"))
        if not hit:
            return
        for n in root.iter("node"):
            if (n.get("text") or "") in ("Wait", "Close app"):
                b = bounds(n)
                if b:
                    print("dismiss anr", n.get("text"))
                    sh("input tap %d %d" % ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2))
                    time.sleep(1.5)
                    break


def opt_chip():
    """런처 카드 아래 실행 옵션 칩 자리 — LauncherView 가 logcat(PocketUi)에 남긴다(캔버스라 접근성 노드가 없다)."""
    out = adb("logcat", "-d", "-s", "PocketUi:I", out=True).decode(errors="replace")
    m = None
    for m2 in re.finditer(r"optchip (\d+) (\d+)", out):
        m = m2
    return (int(m.group(1)), int(m.group(2))) if m else None


def screen_box():
    m = re.search(r"(\d+)x(\d+)", sh("wm size").split("Override size:")[-1])
    return (0, 0, int(m.group(1)), int(m.group(2))) if m else (0, 0, 1080, 1920)


def dial_under(help_prefix, density=420):
    """설명 글 바로 아래 다이얼 바(DialBar 는 캔버스라 노드가 없다) — SettingsActivity.row: 설명 아래 8dp, 높이 36dp."""
    b = find(lambda n: (n.get("text") or "").startswith(help_prefix))
    if not b:
        print("no help", help_prefix)
        return None
    dp = density / 160.0
    return (b[0], int(b[3] + 8 * dp), b[2], int(b[3] + 44 * dp))


def scroll_top():
    for _ in range(3):
        sh("input swipe 500 500 500 1700 200")
        time.sleep(0.6)


def settings_pages(tag, pages, custom_check=False):
    shot(tag + "_06_settings_top")
    for page in pages:
        if tap_text(page):
            time.sleep(2)
            scroll_shots("%s_07_%s" % (tag, page), 3)
            if page == "움직임·반응" and tap_text("보간 고급"):      # 숨은 쪽(고급)
                time.sleep(2)
                if custom_check and tap_text("배수"):                 # 세부 하나를 바꾸면 보간 = 「커스텀」(108)
                    time.sleep(1)
                scroll_shots("%s_07_adv" % tag, 2)
                sh("input keyevent 4")
                time.sleep(1.5)
                if custom_check:
                    # 돌아온 「움직임·반응」 — 다시 짜여 「커스텀」 칸이 켜져야 한다(107 까지는 옛 칸이 켜진 채)
                    scroll_top()
                    shot("%s_07c_custom" % tag)
                    bar = dial_under("120Hz = ")
                    if bar:                                            # 120Hz 칸을 눌러 묶음으로 되돌린다
                        sh("input tap %d %d" % (bar[0] + (bar[2] - bar[0]) // 6, (bar[1] + bar[3]) // 2))
                        time.sleep(1)
                        shot("%s_07d_back120" % tag)
                        if tap_text("보간 고급"):                      # 배수가 4배로 돌아왔나
                            time.sleep(2)
                            shot("%s_07e_adv_after" % tag)
                            sh("input keyevent 4")
                            time.sleep(1.5)
            sh("input keyevent 4")
            time.sleep(1.5)


PAGES = ["화면", "움직임·반응", "조작", "게임", "소리", "업데이트"]


def save_logcat(tag):
    """앱 로그(오류·ANR·네이티브 로그)를 결과 묶음에 — 109 덮개 화면에서 게임이 검게 멈춘 원인 보려고"""
    out = adb("logcat", "-d", "-v", "time", out=True)
    open(os.path.join(OUT, "logcat_%s.txt" % tag), "wb").write(out)
    adb("logcat", "-c")


def run(tag, size, density, bar_labels, with_settings):
    adb("logcat", "-c")
    sh("wm size %s" % size)
    sh("wm density %d" % density)
    sh("am force-stop %s" % PKG)
    time.sleep(1)
    sh("rm -f /sdcard/PocketCore/saves/*.auto")
    adb("shell", "am", "start", "-n", PKG + "/com.dudu.pocketcore.MainActivity", "--ez", "menu", "true")
    time.sleep(8)
    dismiss_anr()
    shot(tag + "_01_launcher")
    sw, sh_ = screen_box()[2], screen_box()[3]
    if with_settings and tap_text("설정"):                       # 런처 아래 줄 「설정」
        time.sleep(2.5)
        settings_pages(tag, PAGES, custom_check=(tag == "main"))
        sh("input keyevent 4")
        time.sleep(2)
    chip = opt_chip()                                              # 카드 아래 「옵션 ›」 칩 → 실행 전 선택창
    print("optchip", chip)
    lv = view_bounds("LauncherView") or (0, 0, sw, sh_)
    if chip:
        sh("input tap %d %d" % (lv[0] + chip[0], lv[1] + chip[1]))
    else:
        sh("input keyevent 109")                                   # SELECT = 옵션 창
    time.sleep(2.5)
    shot(tag + "_02_launchsheet")
    b = find(lambda n: (n.get("text") or "").startswith("시작"))         # 「시작  ▶」 단추(설명 글의 «라운드 시작» 말고)
    if b:
        sh("input tap %d %d" % ((b[0] + b[2]) // 2, (b[1] + b[3]) // 2))
    else:
        print("no start button")
    time.sleep(9)
    dismiss_anr()
    shot(tag + "_03_game")
    pv = view_bounds("PadView") or screen_box()
    print("PadView", pv)
    w, h = pv[2] - pv[0], pv[3] - pv[1]
    base = min(w, h)
    sh("input tap %d %d" % (pv[0] + w // 2, pv[1] + int(max(base * 0.046, 30 * density / 160.0) / 2)))   # 메뉴 알약
    time.sleep(1.5)
    shot(tag + "_04_menu")
    cells = bar_cells(w, h, bar_labels, density)
    if "설정" in cells:
        cx, cy = cells["설정"]
        sh("input tap %d %d" % (pv[0] + int(cx), pv[1] + int(cy)))
        time.sleep(2.5)
        shot(tag + "_05_ingame_sheet")
        if with_settings and tap_text("앱 전체 설정 열기", exact=False):
            time.sleep(2.5)
            settings_pages(tag + "_ingame", ["움직임·반응", "조작"])
            sh("input keyevent 4")
            time.sleep(2)
        else:
            sh("input keyevent 4")
            time.sleep(1.5)
    shot(tag + "_08_game_after")
    # 저장 → (칸 아래 «방금») → 로드 → 메뉴가 닫히고 «되돌리기» 칩
    if "저장" in cells:
        cx, cy = cells["저장"]
        sh("input tap %d %d" % (pv[0] + int(cx), pv[1] + int(cy)))
        time.sleep(1.5)
        shot(tag + "_05b_saved")
    if "로드" in cells:
        cx, cy = cells["로드"]
        sh("input tap %d %d" % (pv[0] + int(cx), pv[1] + int(cy)))
        time.sleep(1.2)
        shot(tag + "_06_undo")
        time.sleep(5)
    # 메뉴를 다시 연다(로드가 닫았다)
    sh("input tap %d %d" % (pv[0] + w // 2, pv[1] + int(max(base * 0.046, 30 * density / 160.0) / 2)))
    time.sleep(1.2)
    # 메뉴 줄은 「설정」 칸을 눌러도 열린 채다(순수 토글 — 목록·종료만 접힘). 여기서 알약을 또 누르면 닫혀 버려
    # 「배치」 칸이 헛손질이 된다(2026-10-11 찍은 판에서 확인).
    if "배치" in cells:
        cx, cy = cells["배치"]
        sh("input tap %d %d" % (pv[0] + int(cx), pv[1] + int(cy)))       # 배치(편집) 켜기
        time.sleep(1.5)
        shot(tag + "_09_edit")
        sh("input tap %d %d" % (pv[0] + int(cx), pv[1] + int(cy)))       # 배치 끄기(저장)
        time.sleep(1)
    sh("input tap %d %d" % (pv[0] + w // 2, pv[1] + int(max(base * 0.046, 30 * density / 160.0) / 2)))   # 메뉴 닫기(이어하기)
    time.sleep(1)
    hx, hy = pv[0] + w // 2, pv[1] + int(max(base * 0.046, 30 * density / 160.0) / 2)
    sh("input swipe %d %d %d %d 900" % (hx, hy, hx, hy))                 # 알약 길게 = 빠른 저장
    time.sleep(0.6)
    shot(tag + "_10_qsave")
    save_logcat(tag)


def played_check(tag, size, density, bar_labels):
    """지난번 게임에 커서 — 두 번째 카드로 옮겨 켠 뒤 「목록」으로 나와도, 앱을 새로 켜도 그 카드(2 / 2)에 있어야 한다
    (유저 2026-10-11 「앞에 했던 게임에 커서 두는 거(처음 프론트엔드 때) 되냥」 — 105 에 넣은 것 확인)."""
    sh("wm size %s" % size)
    sh("wm density %d" % density)
    sh("am force-stop %s" % PKG)
    time.sleep(1)
    adb("shell", "am", "start", "-n", PKG + "/com.dudu.pocketcore.MainActivity", "--ez", "menu", "true")
    time.sleep(6)
    dismiss_anr()
    shot(tag + "_1_before")
    adb("logcat", "-c")
    sh("input keyevent 22")                                            # 십자 오른쪽 = 다음 카드(터치 뒤 첫 입력도 먹혀야 — 109)
    time.sleep(1)
    moved = "sel 2 / 2" in adb("logcat", "-d", "-s", "PocketUi:I", out=True).decode(errors="replace")
    print("dpad moved", moved)
    if not moved:                                                      # 안 넘어갔으면 오른쪽 카드를 눌러 넘긴다
        sw, sh_ = screen_box()[2], screen_box()[3]
        sh("input tap %d %d" % (int(sw * 0.87), int(sh_ * 0.27)))
        time.sleep(1)
    shot(tag + "_2_moved")
    sh("input keyevent 66")                                            # 확인 = 바로 시작
    time.sleep(8)
    dismiss_anr()
    shot(tag + "_3_game")
    pv = screen_box()
    w, h = pv[2] - pv[0], pv[3] - pv[1]
    base = min(w, h)
    sh("input tap %d %d" % (pv[0] + w // 2, pv[1] + int(max(base * 0.046, 30 * density / 160.0) / 2)))   # 메뉴 알약
    time.sleep(1.5)
    cells = bar_cells(w, h, bar_labels, density)
    if "목록" in cells:
        cx, cy = cells["목록"]
        sh("input tap %d %d" % (pv[0] + int(cx), pv[1] + int(cy)))
    time.sleep(6)
    dismiss_anr()
    shot(tag + "_4_list")                                              # 「목록」으로 나옴
    sh("am force-stop %s" % PKG)
    time.sleep(1)
    adb("shell", "am", "start", "-n", PKG + "/com.dudu.pocketcore.MainActivity")   # 앱을 새로 켬(목록 표시 없이)
    time.sleep(9)
    dismiss_anr()
    shot(tag + "_5_cold")


def wait_ready():
    adb("wait-for-device")
    for i in range(120):
        if sh("getprop sys.boot_completed").strip() == "1" and "Android" in sh("ls /sdcard/"):
            break
        time.sleep(2)
    time.sleep(5)
    print("ready", sh("getprop sys.boot_completed").strip(), sh("ls /sdcard/").split())


def push_checked(src, dst):
    for i in range(10):
        adb("push", src, dst, check=True)
        if os.path.basename(dst) in sh("ls %s" % os.path.dirname(dst)):
            return True
        time.sleep(3)
    print("push failed", dst)
    return False


def main():
    wait_ready()
    print("install", adb("install", "-r", "-g", APK, check=True))
    sh("appops set --uid %s MANAGE_EXTERNAL_STORAGE allow" % PKG)
    fake_rom("/tmp/a_ss2.ngc", "SAMURAI2")
    fake_rom("/tmp/b_test.ngc", "UITESTROM")
    sh("mkdir -p /sdcard/PocketCore/roms")
    push_checked("/tmp/a_ss2.ngc", "/sdcard/PocketCore/roms/a_ss2.ngc")
    push_checked("/tmp/b_test.ngc", "/sdcard/PocketCore/roms/b_test.ngc")
    opts = ("pocketcore_lang=ja\npocketcore_ss2_music=off\npocketcore_launcher_snd=disabled\n"
            "pocketcore_padskin=art\npocketcore_level=test\n")
    open("/tmp/options.txt", "w").write(opts)
    push_checked("/tmp/options.txt", "/sdcard/PocketCore/options.txt")
    print("roms", sh("ls -l /sdcard/PocketCore/roms"))
    print("opts", sh("cat /sdcard/PocketCore/options.txt"))
    labels = os.environ.get("BAR_LABELS", "슬롯 1,저장,로드,리셋,설정,배치,목록,종료").split(",")
    run("main", "1856x2160", 420, labels, True)
    run("cover", "904x2160", 420, labels, False)
    played_check("played", "1856x2160", 420, labels)
    sh("wm size reset")
    sh("wm density reset")


if __name__ == "__main__":
    main()
