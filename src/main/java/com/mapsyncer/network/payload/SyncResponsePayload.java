package com.mapsyncer.network.payload;

import java.util.List;

public record SyncResponsePayload(List<ChunkMapData> chunks, boolean isComplete, int worldId, String status) {}
