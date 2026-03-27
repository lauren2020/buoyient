package com.les.buoyient.serviceconfigs

import com.les.buoyient.datatypes.SquashRequestMerger

public sealed class PendingRequestQueueStrategy {
    public class Squash(
        public val squashUpdateIntoCreate: SquashRequestMerger,
    ) : PendingRequestQueueStrategy()

    public object Queue : PendingRequestQueueStrategy()
}