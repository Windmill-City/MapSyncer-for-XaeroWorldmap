package com.mapsyncer.mca;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record Plan(boolean surface, Set<Integer> caves) {

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
                    caves.add((Integer.parseInt(token) >> 4) << 4);
                } catch (NumberFormatException e) {
                }
            }
        }

        return new Plan(hasSurface, Set.copyOf(caves));
    }
}
