package com.dudu.pocketcore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.Set;

/**
 * 가상 상대 프로필 — pool.json(앱 assets/league/pool.json) + 씨앗 하나 → 닉네임·칭호·지역·성향·한마디·아바타 씨앗.
 * 설계: next/profile/design.md 4장. 원형 profile.py 와 같은 결과(ProfileParity 로 확인). 난수는 AvatarGen.Rng.
 * 만든 프로필은 글자 그대로 league.json 에 저장한다(pool 을 고쳐도 이미 만난 상대는 안 바뀐다).
 */
final class ProfileGen {
    private ProfileGen() { }

    static final long SALT = 0x5052_4F46_494C_4531L;             /* "PROFILE1" */
    private static final int[][] TIER_W = { { 80, 18, 2 }, { 55, 38, 7 }, { 25, 50, 25 } };
    private static final String[] LANGS = { "ko", "jp", "en" };

    private static String pick(AvatarGen.Rng r, JSONArray a) { return a.optString(r.below(a.length())); }
    private static boolean hangul(char c) { return c >= 0xAC00 && c <= 0xD7A3; }

    static boolean nickOk(JSONObject pool, String nick, Set<String> usedLower) {
        JSONObject n = pool.optJSONObject("nick");
        String low = nick.toLowerCase(Locale.ROOT);
        int cells = 0; boolean ko = false;
        for (int i = 0; i < nick.length(); i++) { boolean h = hangul(nick.charAt(i)); cells += h ? 2 : 1; ko |= h; }
        if (cells > n.optInt("max_cells", 14)) return false;
        if (!ko && nick.length() > n.optInt("max_latin", 12)) return false;
        JSONArray bs = pool.optJSONArray("block_sub"), bw = pool.optJSONArray("block_word");
        for (int i = 0; i < bs.length(); i++) if (low.contains(bs.optString(i))) return false;
        String bare = low.replaceAll("[0-9_]", "");
        for (int i = 0; i < bw.length(); i++) if (bare.equals(bw.optString(i))) return false;
        for (String part : low.split("[_0-9]+")) {
            if (part.isEmpty()) continue;
            for (int i = 0; i < bw.length(); i++) if (part.equals(bw.optString(i))) return false;
        }
        return !usedLower.contains(low);
    }

    static String makeNick(JSONObject pool, AvatarGen.Rng r, String cc, int main, Set<String> usedLower) {
        JSONObject n = pool.optJSONObject("nick");
        JSONObject lbc = n.optJSONObject("lang_by_cc");
        JSONArray lw = lbc.has(cc) ? lbc.optJSONArray(cc) : lbc.optJSONArray("*");
        int[] lwi = ints(lw);
        JSONArray pats = n.optJSONArray("patterns");
        for (int attempt = 0; attempt < 24; attempt++) {
            String lang = LANGS[r.pickW(lwi)];
            java.util.List<JSONObject> ps = new java.util.ArrayList<>();
            for (int i = 0; i < pats.length(); i++) if (lang.equals(pats.optJSONObject(i).optString("lang"))) ps.add(pats.optJSONObject(i));
            int[] w = new int[ps.size()];
            for (int i = 0; i < w.length; i++) w[i] = ps.get(i).optInt("w");
            JSONObject p = ps.get(r.pickW(w));
            String s = p.optString("p");
            StringBuilder out = new StringBuilder();
            int i = 0;
            while (i < s.length()) {
                char ch = s.charAt(i);
                if (ch == '{') {
                    int j = s.indexOf('}', i);
                    String key = s.substring(i + 1, j), v;
                    if ("main".equals(key)) v = League.CHAR[main];
                    else if ("num".equals(key)) v = pick(r, n.optJSONArray("num"));
                    else if ("cc".equals(key)) v = cc;
                    else if ("jp_suf".equals(key)) { v = pick(r, n.optJSONArray("jp_suf")); if (v.startsWith("no_")) v = "_" + v; }
                    else v = pick(r, n.optJSONArray(key));
                    out.append(v);
                    i = j + 1;
                } else { out.append(ch); i++; }
            }
            String nick = out.toString();
            if ("lower".equals(p.optString("case"))) nick = nick.toLowerCase(Locale.ROOT);
            if (nickOk(pool, nick, usedLower)) return nick;
        }
        return null;
    }

    private static int[] ints(JSONArray a) { int[] v = new int[a.length()]; for (int i = 0; i < v.length; i++) v[i] = a.optInt(i); return v; }

    /** 씨앗 → 프로필. main<0 이면 주캐·검질도 뽑는다(CPU 가 싸우는 조합만). 닉네임을 못 만들면 nick 이 null — 호출자가 씨앗+1 로 다시. */
    static JSONObject make(JSONObject pool, long seed, int main, int style, int diff, Set<String> usedLower) throws org.json.JSONException {
        AvatarGen.Rng r = new AvatarGen.Rng(seed ^ SALT);
        if (main < 0) {
            JSONObject mwo = pool.optJSONObject("main_w");
            int[] mw = new int[15];
            for (int k = 0; k < 15; k++) mw[k] = mwo.optInt(String.valueOf(k), 0);
            while (true) {
                main = r.pickW(mw);
                style = r.below(2);
                if (League.oppSupported(main, style)) break;
            }
        } else if (style < 0) {
            style = League.oppSupported(main, 0) ? 0 : 1;
        }
        JSONObject regs = pool.optJSONObject("regions"), gw = regs.optJSONObject("group_w");
        String[] groups = { "KR", "JP", "X" };
        String group = groups[r.pickW(gw.optInt("KR"), gw.optInt("JP"), gw.optInt("X"))];
        JSONArray ga = regs.optJSONArray(group);
        int[] rw = new int[ga.length()];
        for (int i = 0; i < rw.length; i++) rw[i] = ga.optJSONObject(i).optInt("w");
        JSONObject reg = ga.optJSONObject(r.pickW(rw));
        String cc = "X".equals(group) ? reg.optString("cc", group) : group;
        String nick = makeNick(pool, r, cc, main, usedLower);
        int tier = r.pickW(TIER_W[diff]);
        JSONArray titles = pool.optJSONArray("titles");
        java.util.List<String> cand = new java.util.ArrayList<>();
        for (int i = 0; i < titles.length(); i++) {
            JSONObject t = titles.optJSONObject(i);
            if (t.optInt("tier") == tier && (!t.has("style") || t.optInt("style") == style)) cand.add(t.optString("t"));
        }
        String title = cand.get(r.below(cand.size()));
        JSONArray tags = pool.optJSONArray("tags");
        JSONArray ct = pool.optJSONObject("char_tags").optJSONArray(String.valueOf(main));
        String tag1 = (ct != null && r.chance(60)) ? pick(r, ct) : pick(r, tags);
        String tag2 = pick(r, tags);
        if (tag2.equals(tag1)) {
            int k = 0;
            for (int i = 0; i < tags.length(); i++) if (tags.optString(i).equals(tag1)) { k = i; break; }
            tag2 = tags.optString((k + 7) % tags.length());
        }
        String mood = pool.optJSONArray("moods").optString(r.pickW(ints(pool.optJSONObject("mood_w").optJSONArray(String.valueOf(diff)))));
        String cmt = pick(r, pool.optJSONObject("greet").optJSONArray(mood));
        long av = r.next();
        JSONObject o = new JSONObject();
        o.put("nick", nick == null ? JSONObject.NULL : nick);
        o.put("char", main); o.put("style", style); o.put("diff", diff);
        o.put("region", reg.optString("n")); o.put("cc", cc);
        o.put("title", title); o.put("tier", tier);
        o.put("tags", new JSONArray().put(tag1).put(tag2));
        o.put("mood", mood); o.put("cmt", cmt);
        o.put("av", String.format("%016x", av)); o.put("seed", String.format("%016x", seed));
        return o;
    }

    /** 경기마다 한마디 — 경기 id 로 정해져 결과 창을 다시 열어도 같다. kind: greet · after_win · after_lose */
    static String matchLine(JSONObject pool, String mood, String matchId, String kind) {
        AvatarGen.Rng r = new AvatarGen.Rng(AvatarGen.fnv1a64(matchId + ":" + kind));
        return pick(r, pool.optJSONObject(kind).optJSONArray(mood));
    }

    static String regionLabel(JSONObject pf) {
        String cc = pf.optString("cc");
        return ("KR".equals(cc) || "--".equals(cc)) ? pf.optString("region") : cc + " " + pf.optString("region");
    }
}
