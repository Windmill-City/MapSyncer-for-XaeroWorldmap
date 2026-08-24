package com.mapsyncer.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.LoggerFactory;

public class PlanConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger(PlanConfig.class);

    private static volatile Map<String, LayerPlan> Plans = Map.of();

    public static LayerPlan getPlan(String dimId) {
        return Plans.getOrDefault(dimId, new LayerPlan(false, Set.of()));
    }

    public static void rebuildPlans(List<? extends String> planEntries) {
        Map<String, LayerPlan> rebuilt = new LinkedHashMap<>();
        for (var entryStr : planEntries) {
            if (entryStr == null || entryStr.isBlank()) {
                continue;
            }
            var entry = parsePlanEntry(entryStr);
            if (entry != null) {
                rebuilt.put(entry.getKey(), entry.getValue());
            }
        }
        Plans = rebuilt;
    }

    public static List<String> getDefaultPlans() {
        var defaults = new LinkedHashMap<String, LayerPlan>();
        defaults.put("minecraft:overworld", new LayerPlan(true, Set.of()));
        defaults.put("minecraft:the_nether", new LayerPlan(true, Set.of(64)));
        defaults.put("minecraft:the_end", new LayerPlan(true, Set.of()));
        return defaults.entrySet().stream()
                .map(Map.Entry::toString)
                .toList();
    }

    private static Map.Entry<String, LayerPlan> parsePlanEntry(String configStr) {
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
        return Map.entry(dimension, LayerPlan.parse(planStr));
    }
}
