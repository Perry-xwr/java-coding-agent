package com.agent;

/** Display-only metadata; never modifies stored messages or Memory. */
final class SessionUiText {
    private SessionUiText() {}
    static String localTime(String instant) {
        return localTime(instant, java.time.ZoneId.systemDefault());
    }
    static String localTime(String instant, java.time.ZoneId zone) {
        return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(zone).format(java.time.Instant.parse(instant));
    }

    static String safe(String text, int limit) {
        if (text == null) return "";
        if (text.matches("(?is).*(api[ _-]?key|authorization|password|passwd|secret|bearer\\s+|private[ _-]?key|access[ _-]?token|凭据|密码|密钥|\\bsk-[a-z0-9_-]{8,}|[a-f0-9]{32,}).*"))
            return "[potentially sensitive text hidden]";
        String clean = text.replaceAll("[\\p{Cc}\\p{Cf}\\s]+", " ").trim();
        int count = clean.codePointCount(0, clean.length());
        return count <= limit ? clean : clean.substring(0, clean.offsetByCodePoints(0, limit)) + "…";
    }

    static String title(String task) {
        String text = safe(task, 200);
        if (text.startsWith("[potentially sensitive")) return "New session";
        text = text.replaceFirst("^(请|麻烦|帮我|请帮我|能否|可以帮我)[，, ]*", "")
                .replaceFirst("^(帮我|请)[，, ]*", "")
                .replace("一下这个", "").replace("一下", "").replace("的整体", "");
        if (text.isBlank()) return "New session";
        return safe(text, 23);
    }
}
