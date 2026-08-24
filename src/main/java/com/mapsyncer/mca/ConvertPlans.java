package com.mapsyncer.mca;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ConvertPlans {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConvertPlans.class);

    private static volatile Map<String, Plan> Plans = Map.of();

    public static Plan getPlan(String dimId) {
        return Plans.getOrDefault(dimId, new Plan(false, Set.of()));
    }

    public static void build(List<? extends String> planEntries) {
        Map<String, Plan> rebuilt = new LinkedHashMap<>();
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
        var defaults = new LinkedHashMap<String, Plan>();
        defaults.put(Constants.DIM_OVERWORLD, new Plan(true, Set.of()));
        defaults.put(Constants.DIM_THE_NETHER, new Plan(true, Set.of(64)));
        defaults.put(Constants.DIM_THE_END, new Plan(true, Set.of()));
        return defaults.entrySet().stream().map(Map.Entry::toString).toList();
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
