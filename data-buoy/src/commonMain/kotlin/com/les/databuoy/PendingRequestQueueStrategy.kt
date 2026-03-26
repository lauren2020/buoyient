package com.les.databuoy

public sealed class PendingRequestQueueStrategy {
    public class Squash(
        public val squashUpdateIntoCreate: SquashRequestMerger,
    ) : PendingRequestQueueStrategy()

    public object Queue : PendingRequestQueueStrategy()
}
