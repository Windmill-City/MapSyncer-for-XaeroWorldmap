package com.mapsyncer.util;

public final class PathMapping {

    private static final String OVERWORLD_XAERO = "null";
    private static final String NETHER_XAERO = "DIM-1";
    private static final String END_XAERO = "DIM1";

    private PathMapping() {}

    public static String toServerFolderName(String dimId) {
        String canonical = canonicalId(dimId);
        return canonical.replace(':', '$').replace('/', '%');
    }

    public static String toXaeroDimension(String dimId) {
        String canonical = canonicalId(dimId);

        switch (canonical) {
            case "overworld":
                return OVERWORLD_XAERO;
            case "the_nether":
                return NETHER_XAERO;
            case "the_end":
                return END_XAERO;
            default:
                break;
        }

        if (canonical.contains("$") || canonical.startsWith("DIM")) {
            return canonical;
        }

        String[] parts = canonical.split(":", 2);
        String namespace = parts.length == 2 ? parts[0] : "minecraft";
        String path = parts.length == 2 ? parts[1] : canonical;
        return namespace + "$" + xaeroEscape(path);
    }

    private static String canonicalId(String dimId) {
        if (dimId == null || dimId.isEmpty() || "null".equals(dimId) || "minecraft:null".equals(dimId)) {
            return "overworld";
        }
        return dimId.startsWith("minecraft:") ? dimId.substring("minecraft:".length()) : dimId;
    }

    private static String xaeroEscape(String path) {
        return replaceTrailingDots(path.replace('/', '%'), ',');
    }

    private static String replaceTrailingDots(String value, char replacement) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '.') {
            end--;
        }
        if (end == value.length()) {
            return value;
        }
        StringBuilder sb = new StringBuilder(value);
        for (int i = sb.length() - 1; i >= end; i--) {
            sb.setCharAt(i, replacement);
        }
        return sb.toString();
    }
}
