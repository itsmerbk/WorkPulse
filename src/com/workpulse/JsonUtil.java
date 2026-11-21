package com.workpulse;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JsonUtil {
    private static final Pattern PAIR_PATTERN =
        Pattern.compile("\"([^\"]+)\"\\s*:\\s*(?:\"([^\"]*)\"|(-?\\d+(?:\\.\\d+)?)|true|false|null)");

    public static Map<String, String> parseObject(String json) {
        Map<String, String> map = new HashMap<>();
        if (json == null || json.isBlank()) {
            return map;
        }
        Matcher m = PAIR_PATTERN.matcher(json);
        while (m.find()) {
            String key = m.group(1);
            String val = m.group(2) != null ? m.group(2) : (m.group(3) != null ? m.group(3) : "");
            map.put(key, val);
        }
        return map;
    }

    public static String escape(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "");
    }
}
