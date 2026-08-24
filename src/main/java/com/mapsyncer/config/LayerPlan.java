package com.mapsyncer.config;

import java.util.ArrayList;
import java.util.List;

public record LayerPlan(boolean surface, List<Integer> caves) {

    @Override
    public String toString() {
        List<String> parts = new ArrayList<>();
        if (surface) {
            parts.add("SURFACE");
        }
        caves.forEach(y -> parts.add(String.valueOf(y)));
        return String.join(",", parts);
    }

    public static LayerPlan parse(String planStr) {
        if (planStr == null || planStr.isBlank()) {
            return new LayerPlan(false, List.of());
        }

        boolean hasSurface = false;
        List<Integer> caves = new ArrayList<>();
        for (String part : planStr.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (token.equalsIgnoreCase("SURFACE")) {
                hasSurface = true;
            } else {
                try {
                    caves.add(Integer.parseInt(token));
                } catch (NumberFormatException e) {
                }
            }
        }

        return new LayerPlan(hasSurface, caves);
    }
}
