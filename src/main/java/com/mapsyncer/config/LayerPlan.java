package com.mapsyncer.config;

import java.util.ArrayList;
import java.util.List;

public record LayerPlan(boolean surface, List<Integer> caves) {

    @Override
    public String toString() {
        if (!surface && caves.isEmpty()) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (surface) {
            parts.add("SURFACE");
        }
        caves.forEach(y -> parts.add(String.valueOf(y)));
        return String.join(",", parts);
    }

    public static LayerPlan parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return new LayerPlan(false, List.of());
        }

        String trimmed = raw.trim();
        if (trimmed.equalsIgnoreCase("SURFACE")) {
            return new LayerPlan(true, List.of());
        }

        if (!trimmed.contains(",")) {
            try {
                return new LayerPlan(false, List.of(Integer.parseInt(trimmed)));
            } catch (NumberFormatException e) {
                return new LayerPlan(false, List.of());
            }
        }

        boolean hasSurface = false;
        List<Integer> starts = new ArrayList<>();
        for (String part : trimmed.split(",")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            if (token.equalsIgnoreCase("SURFACE")) {
                hasSurface = true;
            } else {
                try {
                    starts.add(Integer.parseInt(token));
                } catch (NumberFormatException e) {
                }
            }
        }

        if (!hasSurface && starts.isEmpty()) {
            return new LayerPlan(false, List.of());
        }
        return new LayerPlan(hasSurface, starts);
    }
}
