package com.les.databuoy

import com.les.databuoy.managers.BackgroundRequestScheduler
import com.les.databuoy.managers.LocalStoreManager
import com.les.databuoy.managers.PendingRequestQueueManager
import com.les.databuoy.managers.ServerManager
import com.les.databuoy.managers.createPlatformBackgroundRequestScheduler
import com.les.databuoy.serviceconfigs.ConnectivityChecker
import com.les.databuoy.serviceconfigs.EncryptionProvider
import com.les.databuoy.serviceconfigs.PendingRequestQueueStrategy
import com.les.databuoy.serviceconfigs.ServerProcessingConfig
import com.les.databuoy.serviceconfigs.SyncableObjectRebaseHandler
import com.les.databuoy.serviceconfigs.createPlatformConnectivityChecker
import com.les.databuoy.sync.SyncDriver
import com.les.databuoy.sync.createPlatformSyncScheduleNotifier
import com.les.databuoy.syncableobjectservicedatatypes.CreateRequestBuilder
import com.les.databuoy.syncableobjectservicedatatypes.GetResponse
import com.les.databuoy.syncableobjectservicedatatypes.HttpRequest
import com.les.databuoy.syncableobjectservicedatatypes.ResolveConflictResult
import com.les.databuoy.syncableobjectservicedatatypes.ResponseUnpacker
import com.les.databuoy.syncableobjectservicedatatypes.SyncableObjectServiceRequestState
import com.les.databuoy.syncableobjectservicedatatypes.SyncableObjectServiceResponse
import com.les.databuoy.syncableobjectservicedatatypes.UpdateRequestBuilder
import com.les.databuoy.syncableobjectservicedatatypes.VoidRequestBuilder
import com.les.databuoy.utils.IdGenerator
import com.les.databuoy.utils.SyncCodec
import com.les.databuoy.utils.SyncLog
import com.les.databuoy.utils.TimestampFormatter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.KSerializer

/**
 * Base class for offline-first syncable services. Subclass this to expose domain-specific
 * CRUD operations that work seamlessly online and offline.
 *
 * **AI agents:** See `CLAUDE.md` / `CODEX.md` at the repository root (or under `META-INF/`
 * in the published JAR) and `docs/creating-a-service.md` for a step-by-step guide to
 * implementing a service.
 *
 * @param serializer - The [KSerializer] for the domain model [O], used to construct the
 *   internal [com.les.databuoy.utils.SyncCodec] for serialization and deserialization.
 * @property serverProcessingConfig - Defines how this service communicates with the remote API:
 *   endpoints, headers, request builders, and response unpacking.
 * @property serviceName - A unique identifier for this service, used as the SQLite partition key
 *   for local storage.
 * @param connectivityChecker - Determines whether the device is online. Defaults to the
 *   platform-specific implementation; override in tests to simulate offline scenarios.
 * @param encryptionProvider - Optional [com.les.databuoy.serviceconfigs.EncryptionProvider] for encrypting data at rest in the
 *   local store. Pass `null` (the default) to store data unencrypted.
 * @param queueStrategy - Controls how offline requests are queued. [com.les.databuoy.serviceconfigs.PendingRequestQueueStrategy.Queue]
 *   (default) keeps one entry per operation; [com.les.databuoy.serviceconfigs.PendingRequestQueueStrategy.Squash]
 *   collapses consecutive offline edits into a single request.
 * @param rebaseHandler - Handles 3-way merge conflict detection and resolution during sync-up.
 *   Defaults to a standard handler built from the provided [serializer].
 */
public abstract class SyncableObjectService<O : SyncableObject<O>, T : ServiceRequestTag>(
    serializer: KSerializer<O>,
    protected val serverProcessingConfig: ServerProcessingConfig<O>,
    protected val serviceName: String,
    protected val connectivityChecker: ConnectivityChecker = createPlatformConnectivityChecker(),
    encryptionProvider: EncryptionProvider? = null,
    queueStrategy: PendingRequestQueueStrategy =
        PendingRequestQueueStrategy.Queue,
    protected val rebaseHandler: SyncableObjectRebaseHandler<O> = SyncableObjectRebaseHandler(
        SyncCodec(serializer)
    ),
) : Service<O> {

    private val codec: SyncCodec<O> = SyncCodec(serializer)

    /**
     * Manages the online server interactions.
     */
    private val serverManager: ServerManager = ServerManager(
        serviceBaseHeaders = serverProcessingConfig.serviceHeaders,
    )

    /**
     * Manages the local data store interactions.
     */
    private val localStoreManager: LocalStoreManager<O, T> = LocalStoreManager(
        codec = codec,
        serviceName = serviceName,
        syncScheduleNotifier = createPlatformSyncScheduleNotifier(),
        encryptionProvider = encryptionProvider,
        queueStrategy = queueStrategy,
    )

    private val backgroundRequestScheduler: BackgroundRequestScheduler =
        createPlatformBackgroundRequestScheduler()

    /**
     * The sync engine that handles sync-down, sync-up, conflict resolution, and periodic
     * scheduling.
     *
     * Register this with [com.les.databuoy.globalconfigs.DataBuoy.registerDrivers] or include it in your Hilt multibinding
     * set so background sync picks up pending requests for this service.
     */
    public val syncDriver: SyncDriver<O, T> = SyncDriver(
        serverManager = serverManager,
        connectivityChecker = connectivityChecker,
        codec = codec,
        serverProcessingConfig = serverProcessingConfig,
        localStoreManager = localStoreManager,
        serviceName = serviceName,
        rebaseHandler = rebaseHandler,
    )

    /**
     * Stops the periodic sync-down loop.
     */
    public fun stopPeriodicSyncDown(): Unit = syncDriver.stopPeriodicSyncDown()

    /**
     * Fetches all data from the server and upserts it into the local db.
     */
    public suspend fun syncDownFromServer(): Unit = syncDriver.syncDownFromServer()

    /**
     * Resolves all placeholders in [request] using the given object's [serverId] and [version],
     * plus the cross-service resolver from the local store.
     */
    private fun resolveRequest(
        request: HttpRequest,
        serverId: String?,
        version: Int?,
    ): HttpRequest.PlaceholderResolutionResult = request.resolveAllPlaceholders(
        serverId = serverId,
        version = version?.toString(),
        crossServiceResolver = localStoreManager.crossServiceIdResolver,
    )

    /**
     * Creates a new syncable object. If the device is online, the object is sent
     * to the remote API via [serverProcessingConfig]. If offline, the object
     * is stored locally for later sync. This should be used to facilitate and request that
     * instantiates a new object that does not exist yet.
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
        val rawRequest = buildRequest.buildRequest(data, idempotencyKey, false, null)
        val request = when (val resolution = resolveRequest(rawRequest, data.serverId, data.version)) {
            is HttpRequest.PlaceholderResolutionResult.Resolved -> resolution.request
            is HttpRequest.PlaceholderResolutionResult.UnresolvedServerId,
            is HttpRequest.PlaceholderResolutionResult.UnresolvedCrossService -> {
                if (processingConstraints is ProcessingConstraints.OnlineOnly) {
                    return SyncableObjectServiceResponse.InvalidRequest()
                }
                return createAsync(
                    idempotencyKey = idempotencyKey,
                    data = data,
                    httpRequest = buildRequest.buildRequest(data, idempotencyKey, true, null),
                    requestTag = requestTag,
                )
            }
        }
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
     * Given a modified object [O], sync changes to the server if online
     * or store the changes locally for future sync if offline. This should be used to facilitate
     * any request that makes a change to an existing object.
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
                            baseData = effectiveUpdateContext.baseData,
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
        val rawRequest = buildRequest.buildRequest(
            baseData = updateContext.baseData,
            updatedData = data,
            idempotencyKey = idempotencyKey,
            isAsync = false,
            attemptedServerRequest = null,
        )
        val request = when (val resolution = resolveRequest(rawRequest, updateContext.baseData.serverId, updateContext.baseData.version)) {
            is HttpRequest.PlaceholderResolutionResult.Resolved -> resolution.request
            is HttpRequest.PlaceholderResolutionResult.UnresolvedServerId,
            is HttpRequest.PlaceholderResolutionResult.UnresolvedCrossService -> {
                if (processingConstraints is ProcessingConstraints.OnlineOnly) {
                    return SyncableObjectServiceResponse.InvalidRequest()
                }
                return updateAsync(
                    idempotencyKey = idempotencyKey,
                    data = data,
                    updateRequest = buildRequest.buildRequest(
                        baseData = updateContext.baseData,
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
        }
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
                            baseData = updateContext.baseData,
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
                            baseData = updateContext.baseData,
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
        val httpRequest = request.buildRequest(data, idempotencyKey, serverAttemptedPendingRequests)

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
        val resolvedRequest = when (val resolution = resolveRequest(request, data.serverId, data.version)) {
            is HttpRequest.PlaceholderResolutionResult.Resolved -> resolution.request
            is HttpRequest.PlaceholderResolutionResult.UnresolvedServerId,
            is HttpRequest.PlaceholderResolutionResult.UnresolvedCrossService -> {
                if (processingConstraints is ProcessingConstraints.OnlineOnly) {
                    return SyncableObjectServiceResponse.InvalidRequest()
                }
                return voidAsync(
                    data = data,
                    request = request,
                    idempotencyKey = idempotencyKey,
                    requestTag = requestTag,
                )
            }
        }
        when (val response = serverManager.sendRequest(httpRequest = resolvedRequest)) {
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
     * @param resolution - a [SyncableObjectRebaseHandler.ConflictResolution.Resolved] containing the
     *  consumer's resolved data and the rebuilt HTTP request to use for the pending upload.
     */
    public fun resolveConflict(
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
    public fun getAllFromLocalStore(limit: Int = 100): List<O> =
        localStoreManager.getAllData(limit = limit).map { it.data }

    /**
     * Returns a [Flow] that emits the current list of all [O] items from the local store
     * whenever the underlying data changes (after sync-down, create, update, void, etc.).
     *
     * Ideal for Compose or other reactive UIs that need to stay in sync with local state
     * without manual refresh calls.
     */
    public fun getAllFromLocalStoreAsFlow(limit: Int = 100): Flow<List<O>> =
        localStoreManager.getAllDataAsFlow(limit = limit).map { entries -> entries.map { it.data } }

    /**
     * Retrieve [O] items from the local store filtered by sync metadata.
     *
     * @param syncStatus if non-null, only items whose sync status matches this value are returned.
     *   Use the string constants from [SyncableObject.SyncStatus] (e.g. [SyncableObject.SyncStatus.SYNCED],
     *   [SyncableObject.SyncStatus.PENDING_UPDATE]).
     * @param includeVoided when `false` (default), voided items are excluded.
     * @param limit maximum number of rows to return from the database.
     */
    public fun getFromLocalStore(
        syncStatus: String? = null,
        includeVoided: Boolean = false,
        limit: Int = 100,
    ): List<O> =
        localStoreManager.getFilteredData(syncStatus = syncStatus, includeVoided = includeVoided, limit = limit)
            .map { it.data }

    /**
     * Returns a [Flow] that emits filtered [O] items from the local store whenever the
     * underlying data changes. See [getFromLocalStore] for parameter details.
     */
    public fun getFromLocalStoreAsFlow(
        syncStatus: String? = null,
        includeVoided: Boolean = false,
        limit: Int = 100,
    ): Flow<List<O>> =
        localStoreManager.getFilteredDataAsFlow(syncStatus = syncStatus, includeVoided = includeVoided, limit = limit)
            .map { entries -> entries.map { it.data } }

    /**
     * Retrieve [O] items from the local store that match the given [predicate].
     *
     * This loads all items up to [limit] from SQLite, then filters in memory.
     * Suitable for small-to-medium datasets; for large datasets consider
     * the metadata-filter overload instead.
     */
    public fun getFromLocalStore(
        predicate: (O) -> Boolean,
        limit: Int = 100,
    ): List<O> = getAllFromLocalStore(limit).filter(predicate)

    /**
     * Returns a [Flow] that emits [O] items matching [predicate] from the local store
     * whenever the underlying data changes. Filtering is applied in memory after
     * each emission.
     */
    public fun getFromLocalStoreAsFlow(
        predicate: (O) -> Boolean,
        limit: Int = 100,
    ): Flow<List<O>> =
        getAllFromLocalStoreAsFlow(limit).map { items -> items.filter(predicate) }

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
            serviceHeaders = serverProcessingConfig.serviceHeaders,
        )
    }

    /**
     * Non-suspend wrapper for [create] that launches the operation in the sync driver's scope
     * and returns a [StateFlow] of [com.les.databuoy.syncableobjectservicedatatypes.SyncableObjectServiceRequestState].
     *
     * The flow emits [com.les.databuoy.syncableobjectservicedatatypes.SyncableObjectServiceRequestState.Loading] immediately and then
     * [com.les.databuoy.syncableobjectservicedatatypes.SyncableObjectServiceRequestState.Result] once the create operation completes.
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
        val flow = MutableStateFlow<SyncableObjectServiceRequestState<O>>(
            SyncableObjectServiceRequestState.Loading())
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

    public fun close() {
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

    public sealed class ProcessingConstraints {
        public object OfflineOnly : ProcessingConstraints()
        public object OnlineOnly : ProcessingConstraints()
        public object NoConstraints : ProcessingConstraints()
    }

    public companion object {
        private const val TAG: String = "SyncableObjectService"
    }
}
