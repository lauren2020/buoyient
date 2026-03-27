package com.elvdev.buoyient.serviceconfigs

import com.elvdev.buoyient.datatypes.SquashRequestMerger

public sealed class PendingRequestQueueStrategy {
    public class Squash(
        public val squashUpdateIntoCreate: SquashRequestMerger,
    ) : PendingRequestQueueStrategy()

    public object Queue : PendingRequestQueueStrategy()
}