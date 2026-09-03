package com.dudu.pocketcore;

import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/** 물리 패드 매핑 — 기능 → 안드로이드 키코드. 설정 키 pocketcore_keymap ("up=19,down=20,…").
 *  기본표는 예전 EmuActivity.mapKey 고정표와 같다. 게임기(물리 패드) 지원 요청(2026-09-04)으로 분리.
 *  스틱·HAT 십자는 axisMask() 가 MotionEvent 에서 따로 읽는다(많은 패드가 십자를 HAT 축으로 보낸다). */
final class KeyMap {
    /** 기능 목록 — 표시 순서 = 매핑 화면 순서. {id, 라벨, 레트로패드 비트} */
    static final Object[][] FUNCS = {
        { "up",     "위",            Emu.UP },
        { "down",   "아래",          Emu.DOWN },
        { "left",   "왼쪽",          Emu.LEFT },
        { "right",  "오른쪽",        Emu.RIGHT },
        { "b",      "A (펀치 / 약P)", Emu.B },        /* NGP A = 레트로패드 B */
        { "a",      "B (킥 / 약K)",   Emu.A },        /* NGP B = 레트로패드 A */
        { "y",      "강P (SvC) / A+B (SS2)", Emu.Y },
        { "x",      "강K (SvC) / SP (SS2)",  Emu.X },
        { "r",      "SP·기술키 (SvC·KOF)",   Emu.R },
        { "l",      "A+B (SvC·KOF)",         Emu.L },
        { "start",  "OPTION",        Emu.START },
        { "select", "SELECT",        Emu.SELECT },
        { "menu",   "앱 메뉴 열기",   -1 },           /* 앱 기능 — 게임 비트 아님 */
        { "turbo",  "배속(누르는 동안)", -2 },
    };
    private static final Map<String, Integer> DEF = new LinkedHashMap<>();
    static {
        DEF.put("up", KeyEvent.KEYCODE_DPAD_UP);       DEF.put("down", KeyEvent.KEYCODE_DPAD_DOWN);
        DEF.put("left", KeyEvent.KEYCODE_DPAD_LEFT);   DEF.put("right", KeyEvent.KEYCODE_DPAD_RIGHT);
        DEF.put("b", KeyEvent.KEYCODE_BUTTON_B);       DEF.put("a", KeyEvent.KEYCODE_BUTTON_A);
        DEF.put("y", KeyEvent.KEYCODE_BUTTON_Y);       DEF.put("x", KeyEvent.KEYCODE_BUTTON_X);
        DEF.put("r", KeyEvent.KEYCODE_BUTTON_R1);      DEF.put("l", KeyEvent.KEYCODE_BUTTON_L1);
        DEF.put("start", KeyEvent.KEYCODE_BUTTON_START); DEF.put("select", KeyEvent.KEYCODE_BUTTON_SELECT);
        DEF.put("menu", KeyEvent.KEYCODE_BUTTON_THUMBL); DEF.put("turbo", KeyEvent.KEYCODE_BUTTON_R2);
    }

    private final Map<String, Integer> map = new LinkedHashMap<>(DEF);

    static KeyMap load() {
        KeyMap k = new KeyMap();
        String s = Settings.load().get("pocketcore_keymap");
        if (s != null) for (String kv : s.split(",")) {
            String[] p = kv.trim().split("=");
            if (p.length == 2 && k.map.containsKey(p[0])) {
                try { k.map.put(p[0], Integer.parseInt(p[1])); } catch (NumberFormatException ignored) { }
            }
        }
        return k;
    }

    void save() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            if (sb.length() > 0) sb.append(',');
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        Settings.put("pocketcore_keymap", sb.toString());
    }

    int codeOf(String id) { Integer c = map.get(id); return c == null ? 0 : c; }
    /** 같은 키가 딴 기능에 있으면 그쪽을 비운다(이동 의미) — 조용한 죽은 매핑 방지(리뷰 F13). */
    void set(String id, int code) {
        if (code != 0) for (Map.Entry<String, Integer> e : map.entrySet())
            if (e.getValue() == code && !e.getKey().equals(id)) e.setValue(0);
        map.put(id, code);
    }
    void reset() { map.clear(); map.putAll(DEF); }

    /** 키코드 → 기능 id (없으면 null). 같은 키를 두 기능에 두면 먼저 나온 기능이 이긴다. */
    String funcOf(int code) {
        if (code == 0) return null;
        for (Map.Entry<String, Integer> e : map.entrySet()) if (e.getValue() == code) return e.getKey();
        return null;
    }

    /** 키코드 → 게임 비트 마스크(앱 기능이면 0). */
    int bitOf(int code) {
        String f = funcOf(code);
        if (f == null) return 0;
        for (Object[] r : FUNCS) if (r[0].equals(f)) { int b = (Integer) r[2]; return b >= 0 ? 1 << b : 0; }
        return 0;
    }

    static String keyName(int code) {
        if (code == 0) return "(없음)";
        String s = KeyEvent.keyCodeToString(code);
        return s.startsWith("KEYCODE_") ? s.substring(8) : s;
    }

    /** 스틱·HAT 십자 → 방향 비트. 왼쪽 스틱(AXIS_X/Y)과 HAT(AXIS_HAT_X/Y) 둘 다 받는다. */
    static int axisMask(MotionEvent e) {
        if ((e.getSource() & InputDevice.SOURCE_JOYSTICK) == 0
         && (e.getSource() & InputDevice.SOURCE_GAMEPAD) == 0) return -1;
        if (e.getActionMasked() == MotionEvent.ACTION_CANCEL) return 0;   /* 포커스 상실·장치 제거 시 합성 CANCEL = 전부 뗌(리뷰 F1) */
        float x = e.getAxisValue(MotionEvent.AXIS_HAT_X), y = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
        if (Math.abs(x) < 0.5f && Math.abs(y) < 0.5f) {
            x = e.getAxisValue(MotionEvent.AXIS_X); y = e.getAxisValue(MotionEvent.AXIS_Y);
        }
        int m = 0;
        if (x < -0.5f) m |= 1 << Emu.LEFT;  else if (x > 0.5f) m |= 1 << Emu.RIGHT;
        if (y < -0.5f) m |= 1 << Emu.UP;    else if (y > 0.5f) m |= 1 << Emu.DOWN;
        return m;
    }

    static boolean isGamepad(KeyEvent e) {
        int s = e.getSource(), c = e.getKeyCode();
        return (s & InputDevice.SOURCE_GAMEPAD) != 0 || (s & InputDevice.SOURCE_DPAD) != 0
            || (s & InputDevice.SOURCE_JOYSTICK) != 0
            || (c >= KeyEvent.KEYCODE_BUTTON_A && c <= KeyEvent.KEYCODE_BUTTON_16);   /* 출처 표기가 이상한 패드도(리뷰 F19) */
    }
    static boolean isPadButton(int c) { return c >= KeyEvent.KEYCODE_BUTTON_A && c <= KeyEvent.KEYCODE_BUTTON_16; }

    /** 실제(가상 아닌) 게임패드·조이스틱이 연결돼 있나 — 터치 패드 자동 숨김 판단. */
    static boolean physicalPresent() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d == null || d.isVirtual()) continue;
            int s = d.getSources();
            if ((s & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
             || (s & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK) return true;
        }
        return false;
    }
}
