package com.les.databuoy.publicconfigs

import com.les.databuoy.syncableobjectservicedatatypes.SquashRequestMerger

public sealed class PendingRequestQueueStrategy {
    public class Squash(
        public val squashUpdateIntoCreate: SquashRequestMerger,
    ) : PendingRequestQueueStrategy()

    public object Queue : PendingRequestQueueStrategy()
}