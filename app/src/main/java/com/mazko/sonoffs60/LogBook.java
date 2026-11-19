package com.mazko.sonoffs60;

import org.json.JSONArray;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** Intentionally never stores device keys, cloud tokens, SSIDs or raw encrypted payloads. */
public final class LogBook {
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    public synchronized void add(String stage, String detail) {
        String t = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date());
        String safe = detail == null ? "" : detail.replace('\n',' ').replace('\r',' ');
        safe = safe.length() > 750 ? safe.substring(0,750) + "…" : safe;
        lines.addLast(t + "  [" + stage + "] " + safe);
        while (lines.size() > 1500) lines.removeFirst();
    }
    public synchronized String asText() {
        StringBuilder out = new StringBuilder("Sonoff Gateway — діагностичний журнал (секрети виключено)\n");
        for (String s : lines) out.append(s).append('\n');
        return out.toString();
    }
    public synchronized JSONArray asJson() {
        JSONArray items = new JSONArray();
        for (String s : lines) items.put(s);
        return items;
    }
}
