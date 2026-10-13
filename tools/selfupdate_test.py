"""자동 업데이트(SelfUpdate, 120~) 에뮬레이터 시험 — CI 작업 selfupdate 가 부른다(커밋 메시지에 [upd]).

판 A(내부 500)를 깔고, 러너에서 판 B(내부 501, 같은 시험 서명)를 http 로 내놓은 뒤
앱을 한 번 열었다 홈으로 나가고 주기 작업(7760)을 강제로 돌린다. 확인 창 없이 501 로 바뀌면 통과.
다시 열었을 때 «새 판으로 바뀌었습니다» 토스트가 뜨는 화면도 찍는다.

사용: python3 tools/selfupdate_test.py <a.apk> <b.apk> <출력 폴더>"""
import http.server
import json
import os
import shutil
import socketserver
import subprocess
import sys
import threading
import time

PKG = "com.dudu.legacito.fgtest"
ACT = PKG + "/com.dudu.pocketcore.MainActivity"
A_APK, B_APK, OUT = sys.argv[1], sys.argv[2], sys.argv[3]
os.makedirs(OUT, exist_ok=True)
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
    srv_dir = os.path.abspath(os.path.join(OUT, "srv"))
    os.makedirs(srv_dir, exist_ok=True)
    shutil.copy(B_APK, os.path.join(srv_dir, "b.apk"))
    json.dump({"versionCode": 501, "versionName": "0.9", "apk": "b.apk"},
              open(os.path.join(srv_dir, "legacito-version.json"), "w"))
    out_abs = os.path.abspath(OUT)
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
    open(os.path.join(out_abs, "options.txt"), "w").write(
        "pocketcore_update_url=http://10.0.2.2:8765\npocketcore_level=stable\npocketcore_launcher_snd=disabled\n")
    adb("push", os.path.join(out_abs, "options.txt"), "/sdcard/PocketCore/options.txt")
    say("before", version())

    adb("logcat", "-c")
    say("start", sh("am start -W -n %s" % ACT).strip().replace("\n", " | "))
    time.sleep(8)
    shot("1_launcher")
    sh("input keyevent HOME")
    time.sleep(4)
    say("jobs", sh("cmd jobscheduler get-job-state %s 7760" % PKG).strip())
    say("run job", sh("cmd jobscheduler run -f %s 7760" % PKG).strip())

    ok = False
    for i in range(60):
        time.sleep(2)
        v = version()
        say("poll", i, v)
        if v == "versionCode=501":
            ok = True
            break
    shot("2_after_job")
    open(os.path.join(out_abs, "logcat.txt"), "wb").write(
        adb("logcat", "-d", "-v", "time", out=True))
    say("after", version())

    sh("am start -W -n %s" % ACT)
    time.sleep(1.5)
    shot("3_reopen_toast")
    time.sleep(4)
    shot("4_reopen")
    say("RESULT", "PASS" if ok else "FAIL")
    open(os.path.join(out_abs, "result.txt"), "w").write("PASS\n" if ok else "FAIL\n")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
