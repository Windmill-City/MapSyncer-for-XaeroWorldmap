package com.mapsyncer.mca.convert.overlay;

import com.mapsyncer.mca.BlockPropertyLookup;
import com.mapsyncer.mca.ChunkParser.BlockState;
import com.mapsyncer.mca.Constants;
import com.mapsyncer.mca.convert.io.XaeroBinaryWriter;
import com.mapsyncer.mca.convert.model.MapRegionData.OverlayEntry;
import java.util.ArrayList;

public final class OverlayAccumulator {

    public static final int MAX_LAYERS = 10;

    public static void add(
            ArrayList<OverlayEntry> list,
            BlockState blockState,
            int y,
            int opacityToAdd,
            int light,
            BlockPropertyLookup blockLookup) {
        if (list.size() >= MAX_LAYERS) {
            return;
        }
        opacityToAdd = normalizeOpacity(blockState.name(), opacityToAdd, blockLookup);
        OverlayEntry last = list.isEmpty() ? null : list.get(list.size() - 1);
        if (last != null
                && XaeroBinaryWriter.PaletteKey.from(last.blockState)
                        .equals(XaeroBinaryWriter.PaletteKey.from(blockState))) {
            last.opacity = Math.min(Constants.MAX_LIGHT_LEVEL, last.opacity + opacityToAdd);
        } else {
            list.add(new OverlayEntry(blockState, opacityToAdd, light));
        }
    }

    private static int normalizeOpacity(String blockName, int opacityToAdd, BlockPropertyLookup blockLookup) {
        if (opacityToAdd > Constants.MAX_LIGHT_LEVEL) {
            opacityToAdd = Constants.MAX_LIGHT_LEVEL;
        }
        if (opacityToAdd == 0 && !blockLookup.isWater(blockName)) {
            String lower = blockName.toLowerCase();
            if (lower.contains("seagrass") || lower.contains("kelp") || blockLookup.isTransparent(blockName)) {
                opacityToAdd = 1;
            }
        }
        return opacityToAdd;
    }
}
