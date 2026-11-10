package microscenery.network

import fromScenery.lazyLogger
import microscenery.Agent
import microscenery.MicroscenerySettings
import microscenery.Settings
import microscenery.hardware.MicroscopeHardware
import microscenery.signals.*
import microscenery.signals.MicroscopeControlSignal.Companion.toPoko
import org.joml.Vector3f
import org.zeromq.ZContext
import java.util.concurrent.TimeUnit
import kotlin.properties.Delegates

/**
 * Wraps an [MicroscopeHardware] and sends its output as [MicoscopeSignal]s wrapped in [BaseClientSignal].
 * Also puts captured slice data into [SliceStorage] to be requested by [BiggishDataClient].
 *
 * New clients get the live dataset (if any) and the last dataset in full: the [Dataset], the latest [Stack] of every
 * channel and their [Slice]s.
 *
 * @param acquireOnConnect triggers a new capture on client connect if there is nothing to resend
 * @param serverHello the [BaseServerSignal.ServerHello.sessionId] is replaced by [sessionId] before sending
 */
@Suppress("MemberVisibilityCanBePrivate", "CanBeParameter")
class RemoteMicroscopeServer @JvmOverloads constructor(
    val microscope: MicroscopeHardware,
    private val zContext: ZContext,
    val storage: SliceStorage = SliceStorage(),
    val basePort: Int = MicroscenerySettings.get(Settings.Network.BasePort, 4000),
    val host: String = MicroscenerySettings.get(Settings.Network.Host,"*").trim(),
    val acquireOnConnect: Boolean = false,
    val announceWithBonjour: Boolean = MicroscenerySettings.get(Settings.Network.AnnounceBonjour,true),
    var serverHello: BaseServerSignal.ServerHello =
        BaseServerSignal.ServerHello("a microscope", ServerType.MICROSCOPE, "")
) : Agent(false) {
    private val logger by lazyLogger(System.getProperty("scenery.LogLevel", "info"))

    /** All Dataset/Stack/Slice ids are only valid within this session. */
    val sessionId = ControlSignalsServer.newSessionId()

    // ---- resend state, guarded by [resendLock] ----
    // declared before [controlConnection] because its network thread may call [processClientSignal] right away
    private val resendLock = Any()
    private val datasets = mutableMapOf<Long, Dataset>()
    /** datasetId of the most recent standalone slice */
    private var liveDatasetId = NO_ID
    /** id of the most recently announced dataset */
    private var latestDatasetId = NO_ID
    /** datasets sent since the last client signed on. Others are sent before data referencing them. */
    private val announcedDatasets = mutableSetOf<Long>()
    /** datasetId of the most recent stack */
    private var lastDatasetId = NO_ID
    /** latest stack per channel index of [lastDatasetId] */
    private val lastStacks = mutableMapOf<Int, Stack>()
    /** slice metadata (without data) of [lastStacks] by stack id */
    private val lastStackSlices = mutableMapOf<Long, MutableList<Slice>>()

    private val controlConnection = ControlSignalsServer(
        zContext, basePort, host = host, listOf(this::processClientSignal),
        serverHello = { serverHello.copy(sessionId = sessionId) }
    )
    val dataSender = BiggishDataServer(basePort + 1, host = host, storage, zContext)
    val bonjourService = if (announceWithBonjour) BonjourService() else null

    var status: RemoteMicroscopeStatus by Delegates.observable(
        RemoteMicroscopeStatus(emptyList(), 0)
    ) { _, _, newStatus: RemoteMicroscopeStatus ->
        sendBaseWrappedSignal(newStatus)
        logger.info("Status: $newStatus")
    }

    init {
        status = RemoteMicroscopeStatus(listOf(dataSender.port), 0)
        startAgent()
    }

    override fun onStart() {
        bonjourService?.register(serverHello.serverName, basePort, "RemoteMicroscope")
    }

    override fun onLoop() {
        val signal = microscope.output.poll(200, TimeUnit.MILLISECONDS) ?: return

        when (signal) {
            is MicroscopeSlice -> {
                signal.slice.data?.let {
                    storage.addSlice(signal.slice.Id, signal.slice.data)
                    sendBaseWrappedSignal(ActualMicroscopeSignal(MicroscopeSlice(signal.slice.copy(data = null))))
                }
            }

            else -> sendBaseWrappedSignal(ActualMicroscopeSignal(signal))
        }
    }

    private fun sendBaseWrappedSignal(signal: RemoteMicroscopeSignal, isResend: Boolean = false){
        val wrapped = when (signal) {
            is RemoteMicroscopeStatus -> {
                BaseServerSignal.AppSpecific(signal.toProto().toByteString())
            }
            is ActualMicroscopeSignal -> when (val s = signal.signal) {
                is MicroscopeDataset -> synchronized(resendLock) {
                    datasets[s.dataset.Id] = s.dataset
                    latestDatasetId = s.dataset.Id
                    announcedDatasets += s.dataset.Id
                    controlConnection.sendSignal(s.dataset)
                    return
                }
                is MicroscopeStack -> synchronized(resendLock) {
                    rememberStack(s.stack)
                    announceDatasetIfNeeded(s.stack.datasetId)
                    controlConnection.sendSignal(s.stack)
                    return
                }
                is MicroscopeSlice -> synchronized(resendLock) {
                    rememberSlice(s.slice)
                    announceDatasetIfNeeded(s.slice.datasetId)
                    controlConnection.sendSignal(s.slice)
                    return
                }
                else -> {
                    if (s is HardwareDimensions && !isResend){
                        synchronized(resendLock) { forgetLastDataset() }
                    }
                    BaseServerSignal.AppSpecific(signal.toProto().toByteString())
                }
            }
        }
        controlConnection.sendSignal(wrapped)
    }

    /**
     * Makes sure clients know the dataset before they get a stack or slice referencing it.
     * needs [resendLock]
     */
    private fun announceDatasetIfNeeded(datasetId: Long) {
        if (datasetId in announcedDatasets) return
        val dataset = datasets[datasetId] ?: run {
            logger.warn("Data references unknown dataset $datasetId.")
            return
        }
        controlConnection.sendSignal(dataset)
        announcedDatasets += datasetId
    }

    /** needs [resendLock] */
    private fun rememberStack(stack: Stack) {
        if (stack.datasetId != lastDatasetId) {
            forgetLastDataset()
            lastDatasetId = stack.datasetId
        }
        lastStacks.put(stack.channelIndex, stack)?.let { replaced ->
            lastStackSlices.remove(replaced.Id)
        }
        lastStackSlices[stack.Id] = mutableListOf()
    }

    /** needs [resendLock] */
    private fun rememberSlice(slice: Slice) {
        if (slice.isStandalone) {
            liveDatasetId = slice.datasetId
        } else {
            lastStackSlices[slice.stackId]?.add(slice)
        }
    }

    /** needs [resendLock] */
    private fun forgetLastDataset() {
        lastDatasetId = NO_ID
        lastStacks.clear()
        lastStackSlices.clear()
    }

    /**
     * Sends the live dataset, the most recently announced dataset and the last dataset in full.
     * In order: Datasets, Stacks, Slices.
     * Datasets not resent here are announced lazily before data referencing them, see [announceDatasetIfNeeded].
     * @return false if there was no stack to resend
     */
    private fun resendData(): Boolean = synchronized(resendLock) {
        val toSend = mutableListOf<BaseServerSignal>()
        announcedDatasets.clear()

        listOf(liveDatasetId, latestDatasetId)
            .filter { it != NO_ID && it != lastDatasetId }
            .distinct()
            .forEach { id -> datasets[id]?.let { toSend += it; announcedDatasets += id } }

        if (lastStacks.isNotEmpty()) {
            datasets[lastDatasetId]?.let { toSend += it; announcedDatasets += lastDatasetId }
                ?: logger.warn("Dataset $lastDatasetId of the last stacks is unknown.")
            val stacks = lastStacks.values.sortedBy { it.channelIndex }
            toSend += stacks
            stacks.forEach { stack -> lastStackSlices[stack.Id]?.let { toSend += it } }
        }

        if (toSend.isNotEmpty()) logger.info("Resending ${toSend.size} data signals for new client")
        // sent within the lock so no data of the agent thread can overtake the resent datasets
        toSend.forEach(controlConnection::sendSignal)
        lastStacks.isNotEmpty()
    }

    /**
     * Executed by the network thread of [ControlSignalsServer]
     */
    private fun processClientSignal(bcs: BaseClientSignal) {
        when (bcs) {
            is BaseClientSignal.ClientSignOn -> {
                // the hello and the version check are done by [ControlSignalsServer]
                status = status.copy(connectedClients = status.connectedClients + 1)
                sendBaseWrappedSignal(ActualMicroscopeSignal(microscope.hardwareDimensions()), isResend = true)
                sendBaseWrappedSignal(ActualMicroscopeSignal(microscope.status()), isResend = true)
                if (!resendData() && acquireOnConnect) {
                    logger.info("No previous stack found, acquiring new stack for new client.")
                    microscope.acquireStack(MicroscopeControlSignal.AcquireStack(Vector3f(), Vector3f(), 1f))
                }
            }

            is BaseClientSignal.AppSpecific -> {
                val it = org.withXR.network.v4.microscopeApi.MicroscopeControlSignal.parseFrom(bcs.data).toPoko()
                when (it) {
                    is MicroscopeControlSignal.AcquireStack -> {
                        microscope.acquireStack(it)
                    }

                    MicroscopeControlSignal.Live -> microscope.goLive()
                    is MicroscopeControlSignal.MoveStage -> microscope.stagePosition = it.target
                    MicroscopeControlSignal.Shutdown -> {
                        logger.info("Shutting down server.")
                        microscope.shutdown()
                        controlConnection.shutdown = true
                        dataSender.close()
                        close()
                    }

                    MicroscopeControlSignal.SnapImage -> microscope.snapSlice()
                    MicroscopeControlSignal.Stop -> microscope.stop()
                    is MicroscopeControlSignal.PhotoManipulation -> microscope.ablatePoints(it)
                    is MicroscopeControlSignal.AblationShutter -> TODO()
                    MicroscopeControlSignal.StartAcquisition -> microscope.startAcquisition()
                    is MicroscopeControlSignal.DeviceSpecific -> microscope.deviceSpecificCommands(it.data)
                }
            }
        }
    }

    @Suppress("unused")
    fun stop() {
        logger.info("Got stop Command")
        controlConnection.sendInternalSignals(listOf(MicroscopeControlSignal.Stop.toBaseSignal()))
    }

    @Suppress("unused")
    fun shutdown() {
        logger.info("Got Stop Command")
        controlConnection.sendInternalSignals(listOf(MicroscopeControlSignal.Shutdown.toBaseSignal()))
    }

    /**
     * Access settings. Java compatability function
     */
    @Suppress("unused")
    fun getSettings() = MicroscenerySettings

    override fun onClose() {
        dataSender.close().join()
        bonjourService?.close()
    }
}
