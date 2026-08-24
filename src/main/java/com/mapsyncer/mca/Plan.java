package com.mapsyncer.mca;

import com.mapsyncer.network.RegionRef;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public record Plan(boolean surface, Set<Integer> caves) {

    private static final Logger LOGGER = LoggerFactory.getLogger(Plan.class);

    private static volatile Map<String, Plan> Plans = Map.of();

    public List<Integer> caveLayers() {
        List<Integer> layers = new ArrayList<>();
        if (surface) {
            layers.add(RegionRef.SURFACE_CAVE);
        }
        caves.stream().mapToInt(cave -> cave >> 4).sorted().forEach(layers::add);
        return List.copyOf(layers);
    }

    @Override
    public String toString() {
        List<String> parts = new ArrayList<>();
        if (surface) {
            parts.add("SURFACE");
        }
        caves.forEach(y -> parts.add(String.valueOf(y)));
        return String.join(",", parts);
    }

    public static Plan parse(String planStr) {
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
