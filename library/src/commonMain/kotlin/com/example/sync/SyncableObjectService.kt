package com.example.sync

import io.ktor.http.HttpStatusCode
import kotlinx.serialization.KSerializer

abstract class SyncableObjectService<O : SyncableObject<O>, T : ServiceRequestTag>(
    serializer: KSerializer<O>,
    protected val serverProcessingConfig: ServerProcessingConfig<O>,
    serviceName: String,
    private val connectivityChecker: ConnectivityChecker = createPlatformConnectivityChecker(),
    private val logger: SyncLogger = createPlatformSyncLogger(),
    private val syncScheduleNotifier: SyncScheduleNotifier = createPlatformSyncScheduleNotifier(),
    private val codec: SyncCodec<O> = SyncCodec(serializer),
    private val serverManager: ServerManager = ServerManager(
        serviceBaseHeaders = serverProcessingConfig.globalHeaders,
        logger = logger,
    ),
    private val localStoreManager: LocalStoreManager<O, T> = LocalStoreManager(
        codec = codec,
        serviceName = serviceName,
        logger = logger,
        syncScheduleNotifier = syncScheduleNotifier,
    ),
    private val idGenerator: IdGenerator = createPlatformIdGenerator(),
) : Service<O>,
    SyncDriver<O, T>(serverManager, connectivityChecker, codec, serverProcessingConfig, localStoreManager, logger, syncScheduleNotifier)
{

    init {
        // Deferred initialization — called here rather than in SyncDriver.init so that both
        // the superclass (SyncDriver) and this class are fully constructed. This guarantees
        // the sync coroutine can safely access all properties, including subclass overrides
        // like mergeHandler, without observing half-initialized state.
        initialize()
    }

    /**
     * Creates a syncable object. If the device is online, the object is sent
     * to the remote API via [serverProcessingConfig]. If offline, the object
     * is stored locally for later sync.
     *
     * This function is protected because it is intended that the implementing service define its
     * own public facing api options for the app to interface with and this is only used by the
     * service implementation internally.
     *
     * @param endpoint - the URL that should be used for sending the create request to the server.
     * @param data - the data [O] that the create request is for.
     * @param asyncEndpoint - optionally provide a different url that should be used if the app
     *  is currently considered offline and this be processed at a later point in time after
     *  regaining network connection. If this value is null, the primary [endpoint] will be used.
     * @param allowAsyncCreation - boolean indicating if async processing should be allowed or not.
     * @param request - a lambda that generates the request body to be sent given the data [O] and
     *  the intended idempotency key.
     * @param unpackSyncData - lambda function that will be used to extract the data [O] from the
     * response body when the request is processed synchronously online.
     * @param requestTag - an optional tag parameter to associate with this request attempt. If
     *  processed async, this tag value will be provided back when determining sync success and
     *  when resolving any conflicts if applicable.
     */
    protected suspend fun create(
        data: O,
        processingConstraints: ProcessingConstraints = ProcessingConstraints.NoConstraints,
        request: CreateRequestBuilder<O>,
        unpackSyncData: ResponseUnpacker<O>,
        requestTag: T,
    ): SyncableObjectServiceResponse<O> {
        val idempotencyKey = idGenerator.generateId()
        return if (
            processingConstraints is ProcessingConstraints.OnlineOnly ||
            (connectivityChecker.isOnline() && processingConstraints !is ProcessingConstraints.OfflineOnly)
        ) {
            // Online path: send the request to the remote API
            createSync(
                buildRequest = request,
                idempotencyKey = idempotencyKey,
                data = data,
                processingConstraints = processingConstraints,
                requestTag = requestTag,
                unpackSyncData = unpackSyncData,
            )
        } else {
            // Offline path: persist the object locally for later sync
            createAsync(
                idempotencyKey = idempotencyKey,
                data = data,
                httpRequest = request.buildRequest(data, idempotencyKey, true, null),
                requestTag = requestTag,
            )
        }
    }

    /**
     * Create data [O] by contacting the server immediately & waiting for it to be created by the
     * server and returned to the client before storing the data locally.
     */
    private suspend fun createSync(
        buildRequest: CreateRequestBuilder<O>,
        idempotencyKey: String,
        data: O,
        processingConstraints: ProcessingConstraints,
        requestTag: T,
        unpackSyncData: ResponseUnpacker<O>,
    ): SyncableObjectServiceResponse<O> {
        val request = buildRequest.buildRequest(data, idempotencyKey, false, null)
        when (val response = serverManager.sendRequest(httpRequest = request)) {
            is ServerManager.ServerManagerResponse.ConnectionError ->
                return if (processingConstraints is ProcessingConstraints.OnlineOnly) {
                    SyncableObjectServiceResponse.NoInternetConnection()
                } else {
                    // If the request failed with a connection error, it means ktor errored before
                    // even attempting the request. A request can be re-queued async without any
                    // concern for idempotent retry.
                    createAsync(
                        idempotencyKey = idempotencyKey,
                        data = data,
                        httpRequest = buildRequest.buildRequest(data, idempotencyKey, true, null),
                        requestTag = requestTag,
                    )
                }

            is ServerManager.ServerManagerResponse.ServerResponse -> {
                if (
                    response.statusCode == HttpStatusCode.RequestTimeout.value &&
                    processingConstraints !is ProcessingConstraints.OnlineOnly
                ) {
                    // If the request failed with a timeout status code that means the request was
                    // attempted and it may or may not have reached the server and created the data.
                    // Any request queued here needs to ensure it has considered idempotent retry
                    // concerns. Set attemptedServerRequest to communicate this.
                    return createAsync(
                        idempotencyKey = idempotencyKey,
                        data = data,
                        httpRequest = buildRequest.buildRequest(data, idempotencyKey, true, request),
                        requestTag = requestTag,
                    )
                }
                val lastSyncedTimestamp = TimestampFormatter.fromEpochSeconds(response.responseEpochTimestamp)
                val syncStatus = SyncableObject.SyncStatus.Synced(lastSyncedTimestamp)
                val updatedData = unpackSyncData.unpack(
                    response.responseBody,
                    response.statusCode,
                    syncStatus,
                )
                updatedData?.let {
                    localStoreManager.insertFromServerResponse(
                        serverData = it,
                        responseTimestamp = lastSyncedTimestamp,
                    )
                }
                return SyncableObjectServiceResponse.Finished.NetworkResponseReceived(
                    statusCode = response.statusCode,
                    responseBody = response.responseBody,
                    updatedData = updatedData,
                )
            }
        }
    }

    /**
     * Create the data [O] by storing it in the db & enqueue the entry to be asyncronously sent
     * to the server for server creation as soon as network connection is available.
     *
     * All creations will be considered "successful" if they are successfully stored in the db,
     * however, be aware that there is still potential that these will fail to create on the
     * server upon server upload.
     */
    private fun createAsync(
        idempotencyKey: String,
        data: O,
        httpRequest: HttpRequest,
        requestTag: T,
    ): SyncableObjectServiceResponse<O> {
        val (updatedData, queueResult) = localStoreManager.insertLocalData(
            data = data,
            httpRequest = httpRequest,
            idempotencyKey = idempotencyKey,
            requestTag = requestTag,
        )
        return convertQueueResultToServiceResponse(updatedData, queueResult)
    }

    /**
     * Given an updated object [O], sync updates to the server if online
     * or store the updates locally for future sync if offline.
     *
     * The sparse diff (only the changed fields) is computed automatically by comparing [data]
     * against the base stored in the DB ([last_synced_server_data] for synced rows, or the
     * [data] column for pending-create rows).
     *
     * This function is protected because it is intended that the implementing service define its
     * own public facing api options for the app to interface with and this is only used by the
     * service implementation internally.
     *
     * @param data - the latest local version of data [O] containing the updates to be saved.
     * @param allowAsyncUpdate - boolean flag indicating if processing this request async is
     *  permitted. If this flag is false the request will only be attempted online and will fail
     *  if there is no internet connection.
     * @param request - a lambda that generates the request to be sent given the computed
     *  sparse diff and the intended idempotency key.
     * @param unpackSyncData - lambda function that will be used to extract the data [O] from the
     * response body when the request is processed synchronously online.
     * @param requestTag - an optional tag parameter to associate with this request attempt. If
     *  processed async, this tag value will be provided back when determining sync success and
     *  when resolving any conflicts if applicable.
     */
    protected suspend fun update(
        data: O,
        processingConstraints: ProcessingConstraints = ProcessingConstraints.NoConstraints,
        request: UpdateRequestBuilder<O>,
        unpackSyncData: ResponseUnpacker<O>,
        requestTag: T,
    ): SyncableObjectServiceResponse<O> {
        val idempotencyKey = idGenerator.generateId()
        val effectiveLastSyncedData = try {
            getEffectiveBaseDataForUpdate(data)
        } catch (e: Exception) {
            // The data was not in a valid state to be updated, return an error.
            logger.e(TAG, "Failed to execute update due to being in an invalid state: $e")
            return SyncableObjectServiceResponse.InvalidRequest()
        }

        return if (
            processingConstraints is ProcessingConstraints.OnlineOnly ||
            (connectivityChecker.isOnline() && processingConstraints !is ProcessingConstraints.OfflineOnly)
        ) {
            // For performance, only check if pending requests exist inside the online processing
            // block. There is no need to make this query if we already know we are just going to
            // queue async anyways.
            if (localStoreManager.hasPendingRequests(data.clientId)) {
                // There are pending async requests queued for this item. Sending an online update
                // now would cause the server to receive operations out of order. Force the update
                // to be queued so it is processed after the pending requests during sync-up.
                if (processingConstraints is ProcessingConstraints.OnlineOnly) {
                    // Caller explicitly requires online-only processing, but we can't safely
                    // send online while prior requests are still queued.
                    logger.e(TAG, "Cannot process OnlineOnly update for (client_id: ${data.clientId}) " +
                            "while pending async requests exist.")
                    return SyncableObjectServiceResponse.InvalidRequest()
                }
                updateAsync(
                    idempotencyKey = idempotencyKey,
                    data = data,
                    lastSyncedData = effectiveLastSyncedData,
                    buildRequest = request,
                    requestTag = requestTag,
                )
            } else {
                updateSync(
                    data = data,
                    request = request.buildRequest(effectiveLastSyncedData, data, idempotencyKey),
                    unpackData = unpackSyncData,
                )
            }
        } else {
            updateAsync(
                idempotencyKey = idempotencyKey,
                data = data,
                lastSyncedData = effectiveLastSyncedData,
                buildRequest = request,
                requestTag = requestTag,
            )
        }
    }

    private fun getEffectiveBaseDataForUpdate(data: O): O {
        // Query the base data from the DB to compute the sparse diff.
        val localStoreEntry = localStoreManager.getData(
            clientId = data.clientId,
            serverId = data.serverId,
        )
        return when (localStoreEntry?.syncStatus) {
            is SyncableObject.SyncStatus.LocalOnly ->
                throw Exception("You can't create with an update request.")

            // If the status is pending create or update, there must be a queued request.
            is SyncableObject.SyncStatus.PendingCreate,
            is SyncableObject.SyncStatus.PendingUpdate ->
                localStoreManager.pendingRequestQueueManager.getLatestPendingRequest(data.clientId)!!.data

            is SyncableObject.SyncStatus.PendingVoid ->
                throw Exception("Updates are not permitted to voided items")

            is SyncableObject.SyncStatus.Synced -> localStoreEntry.latestServerData!!

            is SyncableObject.SyncStatus.Conflict ->
                throw Exception("Resolve conflicts first. Updates are not permitted in conflict.")

            null -> throw Exception("Failed to find db entry to update.")
        }
    }

    /**
     * Update data [O] by contacting the server immediately & waiting for it to be updated by the
     * server and returned to the client before storing the updated data locally.
     */
    private suspend fun updateSync(
        data: O,
        request: HttpRequest,
        unpackData: ResponseUnpacker<O>,
    ): SyncableObjectServiceResponse<O> {
        when (val response = serverManager.sendRequest(httpRequest = request)) {
            is ServerManager.ServerManagerResponse.ConnectionError ->
                return SyncableObjectServiceResponse.NoInternetConnection()

            is ServerManager.ServerManagerResponse.ServerResponse -> {
                logger.d(TAG, "[update] response received (${response.statusCode}): ${response.responseBody}")
                val lastSyncedTimestamp = TimestampFormatter.fromEpochSeconds(response.responseEpochTimestamp)
                val updatedData = unpackData.unpack(response.responseBody, response.statusCode, data.syncStatus)
                updatedData?.let {
                    // If the server returned updated data, store the update in the db.
                    localStoreManager.upsertFromServerResponse(
                        serverData = it,
                        responseTimestamp = lastSyncedTimestamp,
                    )
                }
                return SyncableObjectServiceResponse.Finished.NetworkResponseReceived(
                    statusCode = response.statusCode,
                    responseBody = response.responseBody,
                    updatedData = updatedData,
                )
            }
        }
    }

    /**
     * Update the data [O] by storing it in the db & enqueue the entry to be asyncronously sent
     * to the server for server update as soon as network connection is available.
     *
     * All updates will be considered "successful" if they are successfully stored in the db,
     * however, be aware that there is still potential that these will fail to update on the
     * server upon server upload.
     */
    private fun updateAsync(
        idempotencyKey: String,
        data: O,
        lastSyncedData: O,
        buildRequest: UpdateRequestBuilder<O>,
        requestTag: T,
    ): SyncableObjectServiceResponse<O> {
        val (updatedData, queueResult) = localStoreManager.updateLocalData(
            data = data,
            httpRequest = buildRequest.buildRequest(lastSyncedData, data, idempotencyKey),
            idempotencyKey = idempotencyKey,
            buildRequest = buildRequest,
            lastSyncedData = lastSyncedData,
            requestTag = requestTag,
        )
        return convertQueueResultToServiceResponse(updatedData, queueResult)
    }

    /**
     * Voids a syncable object. If the object exists on the server (has a
     * non-null [serverId]) and the device is online, the void request is
     * sent immediately. If offline, the request is stored locally for
     * later sync. Objects that have never been synced to the server are
     * simply marked as voided in the local DB.
     *
     * This function is protected because it is intended that the implementing service define its
     * own public facing api options for the app to interface with and this is only used by the
     * service implementation internally.
     *
     * @param requestTag - an optional tag parameter to associate with this request attempt. If
     *  processed async, this tag value will be provided back when determining sync success and
     *  when resolving any conflicts if applicable.
     */
    protected suspend fun void(
        data: O,
        request: VoidRequestBuilder<O>,
        unpackData: ResponseUnpacker<O>,
        requestTag: T,
    ): SyncableObjectServiceResponse<O> {
        val pendingSyncRequests = localStoreManager.pendingRequestQueueManager.getPendingRequests(data.clientId)
        val serverAttemptedPendingRequests = pendingSyncRequests.filter { it.serverAttemptMade }
        // If the object has never been synced to the server & no pending sync request has been
        // attempted sending to the server, just mark it voided locally — no server request needed.
        // Also clear any pending create/update since the object is being voided before it was
        // synced.
        if (data.serverId == null && serverAttemptedPendingRequests.isEmpty()) {
            val updatedData = localStoreManager.voidLocalOnlyData(data = data)
            return SyncableObjectServiceResponse.Finished.StoredLocally(updatedData = updatedData)
        }

        // Object exists on server — use the online/offline dual-path.
        return if (connectivityChecker.isOnline()) {
            voidSync(
                data = data,
                request = request.buildRequest(data, serverAttemptedPendingRequests),
                unpackData = unpackData,
            )
        } else {
            val idempotencyKey = idGenerator.generateId()
            voidAsync(
                data = data,
                request = request.buildRequest(data, serverAttemptedPendingRequests),
                idempotencyKey = idempotencyKey,
                requestTag = requestTag,
            )
        }
    }

    /**
     * Void data [O] by contacting the server immediately & waiting for it to be voided by the
     * server and returned to the client before storing the voided data locally.
     */
    private suspend fun voidSync(
        data: O,
        request: HttpRequest,
        unpackData: ResponseUnpacker<O>,
    ): SyncableObjectServiceResponse<O> {
        when (val response = serverManager.sendRequest(httpRequest = request)) {
            is ServerManager.ServerManagerResponse.ConnectionError ->
                return SyncableObjectServiceResponse.NoInternetConnection()

            is ServerManager.ServerManagerResponse.ServerResponse -> {
                val lastSyncedTimestamp = TimestampFormatter.fromEpochSeconds(response.responseEpochTimestamp)
                val updatedData = unpackData.unpack(response.responseBody, response.statusCode, data.syncStatus)
                updatedData?.let {
                    localStoreManager.upsertFromVoidServerResponse(
                        serverData = it,
                        responseTimestamp = lastSyncedTimestamp,
                    )
                }
                return SyncableObjectServiceResponse.Finished.NetworkResponseReceived(
                    statusCode = response.statusCode,
                    responseBody = response.responseBody,
                    updatedData = updatedData,
                )
            }
        }
    }

    /**
     * Void the data [O] by storing the void request in the db & enqueue the entry to be
     * asynchronously sent to the server as soon as network connection is available.
     *
     * All voids will be considered "successful" if they are successfully stored in the db,
     * however, be aware that there is still potential that these will fail to void on the
     * server upon server upload.
     */
    private fun voidAsync(
        data: O,
        request: HttpRequest,
        idempotencyKey: String,
        requestTag: T,
    ): SyncableObjectServiceResponse<O> {
        val (updatedData, queueResult) = localStoreManager.voidData(
            data = data,
            httpRequest = request,
            idempotencyKey = idempotencyKey,
            requestTag = requestTag,
        )
        return convertQueueResultToServiceResponse(updatedData, queueResult)
    }

    /**
     * Fetches a single data item [O] by its client or server id. If the device is online, the
     * item is fetched from the server using the provided [request]. If the device is offline,
     * the item is retrieved from the local sync_data store instead.
     *
     * Unlike create/update/void, get requests are never stored in sync_pending_events — this
     * is a point-in-time read only.
     *
     * This function is protected because it is intended that the implementing service define its
     * own public facing api options for the app to interface with and this is only used by the
     * service implementation internally.
     *
     * @param clientId - the client id of the data item to fetch.
     * @param serverId - the server id of the data item to fetch, if known.
     * @param request - the [HttpRequest] config describing the GET endpoint.
     * @param unpackData - lambda function that will be used to extract the data [O] from the
     *  server response body.
     */
    protected suspend fun get(
        clientId: String,
        serverId: String?,
        request: HttpRequest,
        unpackData: ResponseUnpacker<O>,
    ): GetResponse<O> {
        return if (connectivityChecker.isOnline()) {
            when (val response = serverManager.sendRequest(httpRequest = request)) {
                is ServerManager.ServerManagerResponse.ConnectionError -> {
                    // Connection failed despite appearing online — fall back to local store.
                    getFromLocalStore(clientId = clientId, serverId = serverId)
                }

                is ServerManager.ServerManagerResponse.ServerResponse -> {
                    val syncStatus = SyncableObject.SyncStatus.Synced(TimestampFormatter.fromEpochSeconds(response.responseEpochTimestamp))
                    val data = unpackData.unpack(response.responseBody, response.statusCode, syncStatus)
                    GetResponse.ReceivedServerResponse(
                        statusCode = response.statusCode,
                        responseBody = response.responseBody,
                        data = data,
                    )
                }
            }
        } else {
            getFromLocalStore(clientId = clientId, serverId = serverId)
        }
    }

    private fun getFromLocalStore(clientId: String, serverId: String?): GetResponse<O> {
        val entry = localStoreManager.getData(clientId = clientId, serverId = serverId)
        return if (entry != null) {
            GetResponse.RetrievedFromLocalStore(data = entry.data)
        } else {
            GetResponse.NotFound()
        }
    }

    /**
     * Retrieve all [O] items from the db that meet the given filter criteria.
     */
    fun getAllFromLocalStore(limit: Int = 100): List<O> =
        localStoreManager.getAllData(limit = limit).map { it.data }

    override fun close() {
        serverManager.close()
        localStoreManager.close()
        super.close()
    }

    private fun convertQueueResultToServiceResponse(
        data: O,
        queueResult: PendingRequestQueueManager.QueueResult,
    ): SyncableObjectServiceResponse<O> = when (queueResult) {
        is PendingRequestQueueManager.QueueResult.Stored -> {
            syncScheduleNotifier.scheduleSyncIfNeeded()
            logger.d(TAG, "Queue for (client_id: ${data.clientId}) succeeded.")
            SyncableObjectServiceResponse.Finished.StoredLocally(updatedData = data)
        }
        is PendingRequestQueueManager.QueueResult.StoreFailed -> {
            logger.e(TAG, "Queue for (client_id: ${data.clientId}) failed.")
            SyncableObjectServiceResponse.LocalStoreFailed(
                exception = IllegalStateException("Failed to persist data locally for client_id: ${data.clientId}")
            )
        }
        is PendingRequestQueueManager.QueueResult.InvalidQueueRequest -> {
            logger.e(TAG, "Queue for (client_id: ${data.clientId}) was invalid: ${queueResult.errorMessage}")
            SyncableObjectServiceResponse.LocalStoreFailed(
                exception = IllegalStateException(queueResult.errorMessage)
            )
        }
    }

    sealed class ProcessingConstraints {
        object OfflineOnly : ProcessingConstraints()
        object OnlineOnly : ProcessingConstraints()
        object NoConstraints : ProcessingConstraints()
    }

    companion object {
        private const val TAG = "SyncableObjectService"
    }
}
