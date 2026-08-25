package com.mapsyncer.mca;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public record Plan(boolean surface, Set<Integer> caves) {

    private static final Logger LOGGER = LogManager.getLogger(Plan.class);

    private static volatile Map<String, Plan> Plans = Map.of();

    @Override
    public String toString() {
        List<String> parts = new ArrayList<>();
        if (surface) {
            parts.add("SURFACE");
        }
        caves.forEach(y -> parts.add(String.valueOf(y)));
        return String.join(",", parts);
    }

    public static Plan getPlan(String dimId) {
        return Plans.getOrDefault(dimId, new Plan(false, Set.of()));
    }

    public static void build(List<? extends String> planEntries) {
        LOGGER.info("Building dimension plans from {} entries", planEntries.size());
        Map<String, Plan> rebuilt = new LinkedHashMap<>();
        for (var entryStr : planEntries) {
            if (entryStr == null || entryStr.isBlank()) {
                LOGGER.warn("Skipping blank or null plan entry");
                continue;
            }
            var entry = parsePlanEntry(entryStr);
            if (entry != null) {
                rebuilt.put(entry.getKey(), entry.getValue());
                LOGGER.info("Registered plan for dimension [{}]: {}", entry.getKey(), entry.getValue());
            } else {
                LOGGER.warn("Skipping invalid plan entry: [{}]", entryStr);
            }
        }
        Plans = rebuilt;
        LOGGER.info("Dimension plans rebuilt: {} dimensions registered", rebuilt.size());
    }

    public static List<String> getDefaults() {
        var defaults = new LinkedHashMap<String, Plan>();
        defaults.put("minecraft:overworld", new Plan(true, Set.of()));
        defaults.put("minecraft:the_nether", new Plan(true, Set.of(64)));
        defaults.put("minecraft:the_end", new Plan(true, Set.of()));
        return defaults.entrySet().stream().map(Map.Entry::toString).toList();
    }

    private static Plan parse(String planStr) {
        if (planStr == null || planStr.isBlank()) {
            return new Plan(false, Set.of());
        }

        boolean hasSurface = false;
        Set<Integer> caves = new HashSet<>();
        for (String part : planStr.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (token.equalsIgnoreCase("SURFACE")) {
                hasSurface = true;
            } else {
                try {
                    int y = Integer.parseInt(token);
                    caves.add(Math.floorDiv(y, 16) * 16);
                } catch (NumberFormatException e) {
                }
            }
        }

        return new Plan(hasSurface, Set.copyOf(caves));
    }

    private static Map.Entry<String, Plan> parsePlanEntry(String configStr) {
        if (configStr == null || configStr.isBlank()) {
            return null;
        }
        String trimmed = configStr.trim();
        int eq = trimmed.indexOf('=');
        String dimension;
        String planStr;
        if (eq > 0) {
            dimension = trimmed.substring(0, eq).trim();
            planStr = trimmed.substring(eq + 1).trim();
        } else {
            LOGGER.warn("Invalid dimension config: [{}]", configStr);
            return null;
        }
        return Map.entry(dimension, Plan.parse(planStr));
    }
}
