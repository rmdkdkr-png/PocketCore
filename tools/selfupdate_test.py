"""「새 판 받기」 세션 설치(SelfUpdate, 120~) 에뮬레이터 시험 — CI 작업 selfupdate 가 부른다(커밋 메시지에 [upd]).

판 A(내부 500)를 깔고, 러너에서 판 B(내부 501, 같은 시험 서명)를 http 로 내놓은 뒤
런처를 열어 「새 판 받기」 버튼을 누른다. 확인 창(패키지 설치기) 없이 501 로 바뀌고, 앱이 저절로 다시 열리면 통과.
뒤에서 저절로 까는 것은 없다(유저가 누를 때만).

사용: python3 tools/selfupdate_test.py <a.apk> <b.apk> <출력 폴더>"""
import http.server
import json
import os
import re
import shutil
import socketserver
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET

PKG = "com.dudu.legacito.fgtest"
ACT = PKG + "/com.dudu.pocketcore.MainActivity"
A_APK, B_APK, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(OUT, exist_ok=True)
OUT = os.path.abspath(OUT)
LOG = open(os.path.join(OUT, "log.txt"), "w")


def say(*a):
    s = " ".join(str(x) for x in a)
    LOG.write(s + "\n"); LOG.flush()
    sys.stdout.write(s + "\n"); sys.stdout.flush()


def adb(*a, out=False):
    r = subprocess.run(["adb"] + list(a), capture_output=True)
    if r.returncode != 0:
        say("adb", a, "rc", r.returncode, r.stderr.decode(errors="replace").strip())
    return r.stdout if out else r.returncode


def sh(cmd):
    return adb("shell", cmd, out=True).decode(errors="replace")


def shot(name):
    png = adb("exec-out", "screencap", "-p", out=True)
    open(os.path.join(OUT, name + ".png"), "wb").write(png)
    say("shot", name, len(png))


def version():
    for ln in sh("dumpsys package %s" % PKG).splitlines():
        ln = ln.strip()
        if ln.startswith("versionCode="):
            return ln.split()[0]
    return "?"


def resumed():
    t = sh("dumpsys activity activities")
    m = re.findall(r"(?:mResumedActivity|topResumedActivity)[:=][^\n]*", t)
    return " | ".join(x.strip() for x in m[:2])


def find_button(prefixes):
    sh("uiautomator dump /sdcard/ui.xml")
    xml = sh("cat /sdcard/ui.xml")
    open(os.path.join(OUT, "ui.xml"), "w").write(xml)
    try:
        root = ET.fromstring(xml[xml.index("<?xml"):] if "<?xml" in xml else xml)
    except Exception as e:
        say("ui parse", e)
        return None
    for n in root.iter("node"):
        t = n.get("text", "")
        if any(t.startswith(p) for p in prefixes):
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
            return t, (x1 + x2) // 2, (y1 + y2) // 2
    return None


def serve(root):
    class Q(http.server.SimpleHTTPRequestHandler):
        def __init__(self, *a, **k):
            super().__init__(*a, directory=root, **k)

        def log_message(self, fmt, *args):
            say("http", fmt % args)

    srv = socketserver.TCPServer(("0.0.0.0", 8765), Q)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


def main():
    srv_dir = os.path.join(OUT, "srv")
    os.makedirs(srv_dir, exist_ok=True)
    shutil.copy(B_APK, os.path.join(srv_dir, "b.apk"))
    json.dump({"versionCode": 501, "versionName": "0.9", "apk": "b.apk"},
              open(os.path.join(srv_dir, "legacito-version.json"), "w"))
    a_abs = os.path.abspath(A_APK)
    serve(srv_dir)

    adb("wait-for-device")
    for i in range(120):
        if sh("getprop sys.boot_completed").strip() == "1" and "Android" in sh("ls /sdcard/"):
            break
        time.sleep(2)
    time.sleep(5)
    say("sdk", sh("getprop ro.build.version.sdk").strip())

    say("install A", adb("install", "-r", "-g", a_abs))
    sh("appops set --uid %s MANAGE_EXTERNAL_STORAGE allow" % PKG)
    sh("appops set %s REQUEST_INSTALL_PACKAGES allow" % PKG)
    sh("mkdir -p /sdcard/PocketCore/roms")
    open(os.path.join(OUT, "options.txt"), "w").write(
        "pocketcore_update_url=http://10.0.2.2:8765\npocketcore_level=stable\npocketcore_launcher_snd=disabled\n")
    adb("push", os.path.join(OUT, "options.txt"), "/sdcard/PocketCore/options.txt")
    say("before", version())

    adb("logcat", "-c")
    say("start", sh("am start -W -n %s" % ACT).strip().replace("\n", " | "))
    time.sleep(8)
    shot("1_launcher")
    b = None
    for i in range(10):
        b = find_button(["새 판 받기"])
        if b: break
        time.sleep(2)
    say("button", b)
    if not b:
        b = find_button(["업데이트 확인"])
        say("fallback button", b)
    ok = False
    relaunched = False
    installer_seen = False
    if b:
        sh("input tap %d %d" % (b[1], b[2]))
        t0 = time.time()
        for i in range(60):
            time.sleep(1)
            r = resumed()
            if "packageinstaller" in r: installer_seen = True
            if i in (1, 3): shot("2_after_tap_%ds" % (i + 1))
            v = version()
            say("poll", i, v, r)
            if v == "versionCode=501":
                ok = True
                break
        say("installed in %.1fs" % (time.time() - t0) if ok else "not installed")
        for i in range(10):
            time.sleep(1)
            r = resumed()
            say("after", i, r)
            if "packageinstaller" in r: installer_seen = True
            if PKG in r and ("MainActivity" in r or ".Home" in r):   # 121~ 런처 클래스는 Home(MainActivity 는 아이콘 별칭)
                relaunched = True
                break
        shot("3_after_install")
    open(os.path.join(OUT, "logcat.txt"), "wb").write(adb("logcat", "-d", "-v", "time", out=True))
    say("after", version())
    say("RESULT", "PASS" if ok and not installer_seen else "FAIL",
        "installed=%s installer_ui=%s relaunched=%s" % (ok, installer_seen, relaunched))
    open(os.path.join(OUT, "result.txt"), "w").write(
        "%s installed=%s installer_ui=%s relaunched=%s\n" % ("PASS" if ok and not installer_seen else "FAIL", ok, installer_seen, relaunched))
    sys.exit(0 if ok and not installer_seen else 1)


if __name__ == "__main__":
    main()
