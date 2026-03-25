package com.les.databuoy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer

abstract class SyncableObjectService<O : SyncableObject<O>, T : ServiceRequestTag>(
    serializer: KSerializer<O>,
    protected val serverProcessingConfig: ServerProcessingConfig<O>,
    val serviceName: String,
    private val connectivityChecker: ConnectivityChecker = createPlatformConnectivityChecker(),
    private val codec: SyncCodec<O> = SyncCodec(serializer),
    private val serverManager: ServerManager = ServerManager(
        serviceBaseHeaders = serverProcessingConfig.globalHeaders,
    ),
    private val localStoreManager: LocalStoreManager<O, T> = LocalStoreManager(
        codec = codec,
        serviceName = serviceName,
        syncScheduleNotifier = createPlatformSyncScheduleNotifier(),
    ),
    private val backgroundRequestScheduler: BackgroundRequestScheduler = createPlatformBackgroundRequestScheduler(),
) : Service<O> {

    /**
     * Handles 3-way merge conflict detection and resolution during sync-down.
     * Override this property in a service subclass to provide a custom [SyncableObjectRebaseHandler]
     * with domain-specific merge policies.
     */
    protected open val rebaseHandler: SyncableObjectRebaseHandler<O> by lazy {
        SyncableObjectRebaseHandler(codec)
    }

    /**
     * The sync engine that handles sync-down, sync-up, conflict resolution, and periodic
     * scheduling. Constructed lazily so that subclass property overrides (e.g., [rebaseHandler])
     * are fully initialized before the sync engine starts.
     *
     * Register this with [DataBuoy.registerDrivers] or include it in your Hilt multibinding
     * set so background sync picks up pending requests for this service.
     */
    val syncDriver: SyncDriver<O, T> by lazy {
        SyncDriver(
            serverManager = serverManager,
            connectivityChecker = connectivityChecker,
            codec = codec,
            serverProcessingConfig = serverProcessingConfig,
            localStoreManager = localStoreManager,
            serviceName = serviceName,
            rebaseHandler = rebaseHandler,
        )
    }

    init {
        // Force lazy initialization of syncDriver, which starts periodic sync.
        // By this point, subclass property initializers (including rebaseHandler overrides)
        // are complete because Kotlin evaluates lazy properties on first access, not at
        // declaration time.
        syncDriver
    }

    /**
     * Stops the periodic sync-down loop.
     */
    fun stopPeriodicSyncDown() = syncDriver.stopPeriodicSyncDown()

    /**
     * Fetches all data from the server and upserts it into the local db.
     */
    suspend fun syncDownFromServer() = syncDriver.syncDownFromServer()

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
    ): SyncableObjectServiceResponse<O> = syncDriver.withClientLock(data.clientId) {
        val idempotencyKey = IdGenerator.generateId()
        if (
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

            is ServerManager.ServerManagerResponse.RequestTimedOut ->
                return if (processingConstraints is ProcessingConstraints.OnlineOnly) {
                    SyncableObjectServiceResponse.RequestTimedOut(idempotencyKey)
                } else {
                    // Timeout means the request was attempted and may or may not have reached the
                    // server. Queue for async retry with serverAttemptMade=true so that idempotent
                    // retry concerns are considered.
                    createAsync(
                        idempotencyKey = idempotencyKey,
                        data = data,
                        httpRequest = buildRequest.buildRequest(data, idempotencyKey, true, request),
                        requestTag = requestTag,
                        serverAttemptMade = true,
                    )
                }

            is ServerManager.ServerManagerResponse.ServerError ->
                return SyncableObjectServiceResponse.ServerError(
                    statusCode = response.statusCode,
                    responseBody = response.responseBody,
                    idempotencyKey = idempotencyKey,
                )

            is ServerManager.ServerManagerResponse.Failed ->
                return SyncableObjectServiceResponse.Failed.NetworkResponseReceived(
                    statusCode = response.statusCode,
                    responseBody = response.responseBody,
                )

            is ServerManager.ServerManagerResponse.Success -> {
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
                return SyncableObjectServiceResponse.Success.NetworkResponseReceived(
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
        serverAttemptMade: Boolean = false,
    ): SyncableObjectServiceResponse<O> {
        val (updatedData, queueResult) = localStoreManager.insertLocalData(
            data = data,
            httpRequest = httpRequest,
            idempotencyKey = idempotencyKey,
            requestTag = requestTag,
            serverAttemptMade = serverAttemptMade,
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
    ): SyncableObjectServiceResponse<O> = syncDriver.withClientLock(data.clientId) {
        val idempotencyKey = IdGenerator.generateId()
        when (
            val effectiveUpdateContext = localStoreManager.getEffectiveUpdateContext(data)
        ) {
            is LocalStoreManager.UpdateContext.ValidUpdate -> {
                if (effectiveUpdateContext.hasPendingRequests && processingConstraints is ProcessingConstraints.OnlineOnly) {
                    // There are pending async requests queued for this item. Sending an online update
                    // now would cause the server to receive operations out of order. Force the update
                    // to be queued so it is processed after the pending requests during sync-up.
                    // However - caller explicitly requires online-only processing, but we can't safely
                    // send online while prior requests are still queued.
                    SyncLog.e(TAG, "Cannot process OnlineOnly update for (client_id: ${data.clientId}) " +
                            "while pending async requests exist.")
                    return@withClientLock SyncableObjectServiceResponse.InvalidRequest()
                }
                if (
                    processingConstraints is ProcessingConstraints.OnlineOnly ||
                    (connectivityChecker.isOnline() && !effectiveUpdateContext.hasPendingRequests && processingConstraints !is ProcessingConstraints.OfflineOnly)
                ) {
                    updateSync(
                        data = data,
                        unpackData = unpackSyncData,
                        processingConstraints = processingConstraints,
                        idempotencyKey = idempotencyKey,
                        updateContext = effectiveUpdateContext,
                        buildRequest = request,
                        requestTag = requestTag,
                    )
                } else {
                    updateAsync(
                        idempotencyKey = idempotencyKey,
                        data = data,
                        updateRequest = request.buildRequest(
                            lastSyncedData = effectiveUpdateContext.baseData,
                            updatedData = data,
                            idempotencyKey = idempotencyKey,
                            isAsync = true,
                            attemptedServerRequest = null,
                        ),
                        serverAttemptMadeForCurrentRequest = false,
                        updateContext = effectiveUpdateContext,
                        requestTag = requestTag,
                    )
                }
            }

            is LocalStoreManager.UpdateContext.InvalidState -> {
                SyncLog.e(TAG, "Failed to execute update due to being in an invalid state")
                return@withClientLock SyncableObjectServiceResponse.InvalidRequest()
            }
        }
    }

    /**
     * Update data [O] by contacting the server immediately & waiting for it to be updated by the
     * server and returned to the client before storing the updated data locally.
     *
     * If the server request fails due to a connection error or 408 timeout and
     * [processingConstraints] allows async processing, the update is queued for later sync
     * rather than returning a hard failure. This mirrors the fallback behavior in [createSync].
     */
    private suspend fun updateSync(
        data: O,
        unpackData: ResponseUnpacker<O>,
        processingConstraints: ProcessingConstraints,
        idempotencyKey: String,
        updateContext: LocalStoreManager.UpdateContext.ValidUpdate<O>,
        buildRequest: UpdateRequestBuilder<O>,
        requestTag: T,
    ): SyncableObjectServiceResponse<O> {
        val request = buildRequest.buildRequest(
            lastSyncedData = updateContext.baseData,
            updatedData = data,
            idempotencyKey = idempotencyKey,
            isAsync = false,
            attemptedServerRequest = null,
        )
        when (val response = serverManager.sendRequest(httpRequest = request)) {
            is ServerManager.ServerManagerResponse.ConnectionError ->
                return if (processingConstraints is ProcessingConstraints.OnlineOnly) {
                    SyncableObjectServiceResponse.NoInternetConnection()
                } else {
                    // Connection error means ktor failed before even attempting the request.
                    // Safe to re-queue async without idempotent retry concerns.
                    updateAsync(
                        idempotencyKey = idempotencyKey,
                        data = data,
                        updateRequest = buildRequest.buildRequest(
                            lastSyncedData = updateContext.baseData,
                            updatedData = data,
                            idempotencyKey = idempotencyKey,
                            isAsync = true,
                            attemptedServerRequest = null,
                        ),
                        serverAttemptMadeForCurrentRequest = false,
                        updateContext = updateContext,
                        requestTag = requestTag,
                    )
                }

            is ServerManager.ServerManagerResponse.RequestTimedOut ->
                return if (processingConstraints is ProcessingConstraints.OnlineOnly) {
                    SyncableObjectServiceResponse.RequestTimedOut(idempotencyKey)
                } else {
                    // Timeout means the request was attempted and may or may not have reached the
                    // server. Use StoreAfterServerAttempt to prevent squashing with other pending
                    // requests, since the original request body may have already been processed.
                    updateAsync(
                        idempotencyKey = idempotencyKey,
                        data = data,
                        updateRequest = buildRequest.buildRequest(
                            lastSyncedData = updateContext.baseData,
                            updatedData = data,
                            idempotencyKey = idempotencyKey,
                            isAsync = true,
                            attemptedServerRequest = request,
                        ),
                        updateContext = LocalStoreManager.UpdateContext.ValidUpdate.Queue.ForcedAfterServerAttempt(
                            baseData = updateContext.baseData,
                            hasPendingRequests = updateContext.hasPendingRequests,
                        ),
                        serverAttemptMadeForCurrentRequest = true,
                        requestTag = requestTag,
                    )
                }

            is ServerManager.ServerManagerResponse.ServerError ->
                return SyncableObjectServiceResponse.ServerError(
                    statusCode = response.statusCode,
                    responseBody = response.responseBody,
                    idempotencyKey = idempotencyKey,
                )

            is ServerManager.ServerManagerResponse.Failed ->
                return SyncableObjectServiceResponse.Failed.NetworkResponseReceived(
                    statusCode = response.statusCode,
                    responseBody = response.responseBody,
                )

            is ServerManager.ServerManagerResponse.Success -> {
                SyncLog.d(TAG, "[update] response received (${response.statusCode}): ${response.responseBody}")
                val lastSyncedTimestamp = TimestampFormatter.fromEpochSeconds(response.responseEpochTimestamp)
                val updatedData = unpackData.unpack(response.responseBody, response.statusCode, data.syncStatus)
                updatedData?.let {
                    // If the server returned updated data, store the update in the db.
                    localStoreManager.upsertFromServerResponse(
                        serverData = it,
                        responseTimestamp = lastSyncedTimestamp,
                    )
                }
                return SyncableObjectServiceResponse.Success.NetworkResponseReceived(
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
        updateRequest: HttpRequest,
        serverAttemptMadeForCurrentRequest: Boolean,
        updateContext: LocalStoreManager.UpdateContext.ValidUpdate<O>,
        requestTag: T,
    ): SyncableObjectServiceResponse<O> {
        val (updatedData, queueResult) = localStoreManager.updateLocalData(
            data = data,
            idempotencyKey = idempotencyKey,
            updateRequest = updateRequest,
            serverAttemptMadeForCurrentRequest = serverAttemptMadeForCurrentRequest,
            updateContext = updateContext,
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
        processingConstraints: ProcessingConstraints = ProcessingConstraints.NoConstraints,
        request: VoidRequestBuilder<O>,
        unpackData: ResponseUnpacker<O>,
        requestTag: T,
    ): SyncableObjectServiceResponse<O> = syncDriver.withClientLock(data.clientId) {
        val pendingSyncRequests = localStoreManager.pendingRequestQueueManager.getPendingRequests(data.clientId)
        val serverAttemptedPendingRequests = pendingSyncRequests.filter { it.serverAttemptMade }
        // If the object has never been synced to the server & no pending sync request has been
        // attempted sending to the server, just mark it voided locally — no server request needed.
        // Also clear any pending create/update since the object is being voided before it was
        // synced.
        if (data.serverId == null && serverAttemptedPendingRequests.isEmpty()) {
            return@withClientLock try {
                val updatedData = localStoreManager.voidLocalOnlyData(data = data)
                SyncableObjectServiceResponse.Success.StoredLocally(updatedData = updatedData)
            } catch (e: Exception) {
                SyncLog.e(TAG, "Failed to void local-only object (client_id: ${data.clientId}): ", e)
                SyncableObjectServiceResponse.Failed.LocalStoreFailed(exception = e)
            }
        }

        val idempotencyKey = IdGenerator.generateId()
        val httpRequest = request.buildRequest(data, serverAttemptedPendingRequests)

        // Object exists on server — use the online/offline dual-path.
        if (
            processingConstraints is ProcessingConstraints.OnlineOnly ||
            (connectivityChecker.isOnline() && processingConstraints !is ProcessingConstraints.OfflineOnly)
        ) {
            voidSync(
                data = data,
                request = httpRequest,
                unpackData = unpackData,
                processingConstraints = processingConstraints,
                idempotencyKey = idempotencyKey,
                requestTag = requestTag,
            )
        } else {
            voidAsync(
                data = data,
                request = httpRequest,
                idempotencyKey = idempotencyKey,
                requestTag = requestTag,
            )
        }
    }

    /**
     * Void data [O] by contacting the server immediately & waiting for it to be voided by the
     * server and returned to the client before storing the voided data locally.
     *
     * If the server request fails due to a connection error or 408 timeout and
     * [processingConstraints] allows async processing, the void is queued for later sync
     * rather than returning a hard failure. This mirrors the fallback behavior in [createSync]
     * and [updateSync].
     */
    private suspend fun voidSync(
        data: O,
        request: HttpRequest,
        unpackData: ResponseUnpacker<O>,
        processingConstraints: ProcessingConstraints,
        idempotencyKey: String,
        requestTag: T,
    ): SyncableObjectServiceResponse<O> {
        when (val response = serverManager.sendRequest(httpRequest = request)) {
            is ServerManager.ServerManagerResponse.ConnectionError ->
                return if (processingConstraints is ProcessingConstraints.OnlineOnly) {
                    SyncableObjectServiceResponse.NoInternetConnection()
                } else {
                    // Connection error means ktor failed before even attempting the request.
                    // Safe to re-queue async without idempotent retry concerns.
                    voidAsync(
                        data = data,
                        request = request,
                        idempotencyKey = idempotencyKey,
                        requestTag = requestTag,
                    )
                }

            is ServerManager.ServerManagerResponse.RequestTimedOut ->
                return if (processingConstraints is ProcessingConstraints.OnlineOnly) {
                    SyncableObjectServiceResponse.RequestTimedOut(idempotencyKey)
                } else {
                    // Timeout means the request was attempted and may or may not have reached the
                    // server. Mark serverAttemptMade=true to prevent squashing with other pending
                    // requests, since the original request body may have already been processed.
                    voidAsync(
                        data = data,
                        request = request,
                        idempotencyKey = idempotencyKey,
                        requestTag = requestTag,
                        serverAttemptMade = true,
                    )
                }

            is ServerManager.ServerManagerResponse.ServerError ->
                return SyncableObjectServiceResponse.ServerError(
                    statusCode = response.statusCode,
                    responseBody = response.responseBody,
                    idempotencyKey = idempotencyKey,
                )

            is ServerManager.ServerManagerResponse.Failed ->
                return SyncableObjectServiceResponse.Failed.NetworkResponseReceived(
                    statusCode = response.statusCode,
                    responseBody = response.responseBody,
                )

            is ServerManager.ServerManagerResponse.Success -> {
                val lastSyncedTimestamp = TimestampFormatter.fromEpochSeconds(response.responseEpochTimestamp)
                val updatedData = unpackData.unpack(response.responseBody, response.statusCode, data.syncStatus)
                updatedData?.let {
                    localStoreManager.upsertFromVoidServerResponse(
                        serverData = it,
                        responseTimestamp = lastSyncedTimestamp,
                    )
                }
                return SyncableObjectServiceResponse.Success.NetworkResponseReceived(
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
        serverAttemptMade: Boolean = false,
    ): SyncableObjectServiceResponse<O> {
        val (updatedData, queueResult) = localStoreManager.voidData(
            data = data,
            httpRequest = request,
            idempotencyKey = idempotencyKey,
            requestTag = requestTag,
            serverAttemptMade = serverAttemptMade,
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
        // If there are pending requests for this object, the local store has the most
        // up-to-date version of the data — skip the server call.
        if (localStoreManager.hasPendingRequests(clientId)) {
            return getFromLocalStore(clientId = clientId, serverId = serverId)
        }

        return if (connectivityChecker.isOnline()) {
            when (val response = serverManager.sendRequest(httpRequest = request)) {
                is ServerManager.ServerManagerResponse.ConnectionError -> {
                    // Connection failed despite appearing online — fall back to local store.
                    getFromLocalStore(clientId = clientId, serverId = serverId)
                }

                is ServerManager.ServerManagerResponse.RequestTimedOut -> {
                    // Request timed out — fall back to local store.
                    getFromLocalStore(clientId = clientId, serverId = serverId)
                }

                is ServerManager.ServerManagerResponse.ServerError -> {
                    // Server error — fall back to local store.
                    getFromLocalStore(clientId = clientId, serverId = serverId)
                }

                is ServerManager.ServerManagerResponse.Failed -> {
                    GetResponse.ReceivedServerResponse(
                        statusCode = response.statusCode,
                        responseBody = response.responseBody,
                        data = null,
                    )
                }

                is ServerManager.ServerManagerResponse.Success -> {
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

    private fun getFromLocalStore(
        clientId: String,
        serverId: String?,
    ): GetResponse<O> {
        val entry = localStoreManager.getData(clientId = clientId, serverId = serverId)
        return if (entry != null) {
            GetResponse.RetrievedFromLocalStore(data = entry.data)
        } else if (!connectivityChecker.isOnline()) {
            GetResponse.NoInternetConnection()
        } else {
            GetResponse.NotFound()
        }
    }

    /**
     * Resolves a conflict for the given object. When an object enters
     * [SyncableObject.SyncStatus.Conflict], the consumer must choose which values
     * to keep and call this method with the resolved data.
     *
     * This clears the conflict on the pending request, applies the provided resolution,
     * re-rebases any subsequent pending requests, and transitions the sync_data
     * entry back to a pending state so sync-up can proceed.
     *
     * This function is protected because it is intended that the implementing service define its
     * own public facing api for the app to interface with and this is only used by the
     * service implementation internally.
     *
     * @param resolution - a [SyncableObjectRebaseHandler.ConflictResolution.Resolved] containing the
     *  consumer's resolved data and the rebuilt HTTP request to use for the pending upload.
     */
    protected fun resolveConflict(
        resolution: SyncableObjectRebaseHandler.ConflictResolution.Resolved<O>,
    ): ResolveConflictResult<O> {
        val clientId = resolution.resolvedData.clientId
        val conflictingRequest = localStoreManager.pendingRequestQueueManager
            .getConflictingPendingRequest(clientId)
        if (conflictingRequest?.conflict == null) {
            // No pending request has conflict_info. This is an inconsistent state — sync_data
            // may be stuck in CONFLICT without a corresponding conflicting request. Self-heal
            // by rebasing any pending requests to verify no real conflicts and restoring the
            // correct sync_status.
            SyncLog.w(TAG, "No conflicting pending request found for (client_id: $clientId), repairing orphaned conflict status.")
            return localStoreManager.repairOrphanedConflictStatus(
                clientId = clientId,
                serverId = resolution.resolvedData.serverId,
                mergeHandler = rebaseHandler,
            )
        }

        val result = localStoreManager.resolveConflictData(
            resolvedData = resolution.resolvedData,
            resolvedHttpRequest = resolution.updatedHttpRequest
                ?: conflictingRequest.request,
            mergeHandler = rebaseHandler,
        )

        when (result) {
            is ResolveConflictResult.Resolved ->
                SyncLog.d(TAG, "Conflict resolved for (client_id: $clientId).")

            is ResolveConflictResult.RebaseConflict ->
                SyncLog.w(TAG, "Conflict resolved for (client_id: $clientId) but a subsequent pending request also has a conflict.")

            is ResolveConflictResult.Failed ->
                SyncLog.e(TAG, "Failed to resolve conflict for (client_id: $clientId): ${result.exception}")
        }

        return result
    }

    /**
     * Retrieve all [O] items from the db that meet the given filter criteria.
     */
    fun getAllFromLocalStore(limit: Int = 100): List<O> =
        localStoreManager.getAllData(limit = limit).map { it.data }

    /**
     * Fires a background HTTP request to void a previous server request by its idempotency key.
     *
     * Unlike [void], this method does NOT modify the local SQLite store. It is a fire-and-forget
     * server-side operation. On Android, the request is scheduled via a dedicated WorkManager
     * queue (separate from the sync queue) with a network connectivity constraint and
     * exponential backoff.
     *
     * @param voidRequest the fully-constructed [HttpRequest] to send to the server.
     *   The caller is responsible for including the idempotency key in the request
     *   body or headers as required by the server API.
     */
    protected fun voidRequestByIdempotencyKey(voidRequest: HttpRequest) {
        backgroundRequestScheduler.scheduleRequest(
            httpRequest = voidRequest,
            globalHeaders = serverProcessingConfig.globalHeaders,
        )
    }

    /**
     * Non-suspend wrapper for [create] that launches the operation in the sync driver's scope
     * and returns a [StateFlow] of [SyncableObjectServiceRequestState].
     *
     * The flow emits [SyncableObjectServiceRequestState.Loading] immediately and then
     * [SyncableObjectServiceRequestState.Result] once the create operation completes.
     */
    protected fun createWithFlow(
        data: O,
        processingConstraints: ProcessingConstraints = ProcessingConstraints.NoConstraints,
        request: CreateRequestBuilder<O>,
        unpackSyncData: ResponseUnpacker<O>,
        requestTag: T,
    ): StateFlow<SyncableObjectServiceRequestState<O>> {
        return launchRequestFlow {
            create(data, processingConstraints, request, unpackSyncData, requestTag)
        }
    }

    /**
     * Non-suspend wrapper for [update] that launches the operation in the sync driver's scope
     * and returns a [StateFlow] of [SyncableObjectServiceRequestState].
     *
     * The flow emits [SyncableObjectServiceRequestState.Loading] immediately and then
     * [SyncableObjectServiceRequestState.Result] once the update operation completes.
     */
    protected fun updateWithFlow(
        data: O,
        processingConstraints: ProcessingConstraints = ProcessingConstraints.NoConstraints,
        request: UpdateRequestBuilder<O>,
        unpackSyncData: ResponseUnpacker<O>,
        requestTag: T,
    ): StateFlow<SyncableObjectServiceRequestState<O>> {
        return launchRequestFlow {
            update(data, processingConstraints, request, unpackSyncData, requestTag)
        }
    }

    /**
     * Non-suspend wrapper for [void] that launches the operation in the sync driver's scope
     * and returns a [StateFlow] of [SyncableObjectServiceRequestState].
     *
     * The flow emits [SyncableObjectServiceRequestState.Loading] immediately and then
     * [SyncableObjectServiceRequestState.Result] once the void operation completes.
     */
    protected fun voidWithFlow(
        data: O,
        processingConstraints: ProcessingConstraints = ProcessingConstraints.NoConstraints,
        request: VoidRequestBuilder<O>,
        unpackData: ResponseUnpacker<O>,
        requestTag: T,
    ): StateFlow<SyncableObjectServiceRequestState<O>> {
        return launchRequestFlow {
            void(data, processingConstraints, request, unpackData, requestTag)
        }
    }

    private fun launchRequestFlow(
        block: suspend () -> SyncableObjectServiceResponse<O>,
    ): StateFlow<SyncableObjectServiceRequestState<O>> {
        val flow = MutableStateFlow<SyncableObjectServiceRequestState<O>>(SyncableObjectServiceRequestState.Loading())
        syncDriver.serviceScope.launch {
            val response = try {
                block()
            } catch (e: Exception) {
                SyncLog.e(TAG, "Request flow failed: ${e.message}")
                SyncableObjectServiceResponse.Failed.LocalStoreFailed(e)
            }
            flow.value = SyncableObjectServiceRequestState.Result(response)
        }
        return flow.asStateFlow()
    }

    fun close() {
        serverManager.close()
        localStoreManager.close()
        syncDriver.close()
    }

    private fun convertQueueResultToServiceResponse(
        data: O,
        queueResult: PendingRequestQueueManager.QueueResult,
    ): SyncableObjectServiceResponse<O> = when (queueResult) {
        is PendingRequestQueueManager.QueueResult.Stored -> {
            SyncLog.d(TAG, "Queue for (client_id: ${data.clientId}) succeeded.")
            SyncableObjectServiceResponse.Success.StoredLocally(updatedData = data)
        }
        is PendingRequestQueueManager.QueueResult.StoreFailed -> {
            SyncLog.e(TAG, "Queue for (client_id: ${data.clientId}) failed.")
            SyncableObjectServiceResponse.Failed.LocalStoreFailed(
                exception = IllegalStateException("Failed to persist data locally for client_id: ${data.clientId}")
            )
        }
        is PendingRequestQueueManager.QueueResult.InvalidQueueRequest -> {
            SyncLog.e(TAG, "Queue for (client_id: ${data.clientId}) was invalid: ${queueResult.errorMessage}")
            SyncableObjectServiceResponse.Failed.LocalStoreFailed(
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
