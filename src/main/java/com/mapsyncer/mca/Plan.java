package com.mapsyncer.mca;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record Plan(boolean surface, Set<Integer> caves) {

    public List<Integer> caveStarts() {
        List<Integer> starts = new ArrayList<>();
        if (surface) {
            starts.add(Integer.MAX_VALUE);
        }
        caves.stream().sorted().forEach(starts::add);
        return List.copyOf(starts);
    }

    public static boolean isSurface(int caveStart) {
        return caveStart == Integer.MAX_VALUE;
    }

    public static int caveLayer(int caveStart) {
        return isSurface(caveStart) ? Integer.MAX_VALUE : caveStart >> 4;
    }

    public static LightMode lightMode(int caveStart) {
        return isSurface(caveStart) ? LightMode.SURFACE : LightMode.CAVE;
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
}
