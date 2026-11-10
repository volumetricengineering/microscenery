package network

import microscenery.hardware.MicroscopeHardware
import microscenery.lightSleepOnCondition
import microscenery.lightSleepOnNull
import microscenery.network.ControlSignalsClient
import microscenery.network.RemoteMicroscopeServer
import microscenery.signals.*
import org.joml.Vector3f
import org.joml.Vector3i
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.lwjgl.system.MemoryUtil
import org.zeromq.ZContext
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Semaphore
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RemoteMicroscopeServerTest {

    private class FakeHardware : MicroscopeHardware {
        override var stagePosition = Vector3f()
        override val output: BlockingQueue<MicroscopeSignal> = ArrayBlockingQueue(100)
        override fun goLive() {}
        override fun stop() {}
        override fun snapSlice() {}
        override fun shutdown() {}
        override fun acquireStack(meta: MicroscopeControlSignal.AcquireStack) {}
        override fun status() = MicroscopeStatus(ServerState.MANUAL, Vector3f(), false)
        override fun hardwareDimensions() = HardwareDimensions.EMPTY
        override fun ablatePoints(signal: MicroscopeControlSignal.PhotoManipulation) {}
        override fun startAcquisition() {}
        override fun sync() = Semaphore(1)
    }

    private val port = 11600
    private var ctx = ZContext()
    private val hardware = FakeHardware()
    private val server = RemoteMicroscopeServer(hardware, ctx, basePort = port, announceWithBonjour = false)
    private val clients = mutableListOf<ControlSignalsClient>()

    private fun connectClient(): Pair<ControlSignalsClient, List<BaseServerSignal>> {
        val received = CopyOnWriteArrayList<BaseServerSignal>()
        val client = ControlSignalsClient(ctx, port, "localhost", listOf { received += it })
        clients += client
        lightSleepOnNull { client.serverHello }
        return client to received
    }

    private val dataset = Dataset(
        Id = 7,
        dimensions = Vector3i(4, 4, 2),
        voxelSize = Vector3f(0.5f, 0.5f, 2f),
        numericType = NumericType.UINT16,
        from = Vector3f(1f, 2f, 3f),
        to = Vector3f(1f, 2f, 5f),
        channels = listOf(ChannelMetadata("a"), ChannelMetadata("b", color = Vector3f(1f, 0f, 0f))),
        timepointCount = 1,
        displayName = "test"
    )

    private fun stack(id: Long, channel: Int) = Stack(id, dataset.Id, channel, 0, System.currentTimeMillis())

    private fun slice(id: Long, stack: Stack, index: Int) = Slice(
        id, dataset.Id, stack.channelIndex, stack.Id, index, System.currentTimeMillis(), Vector3f(),
        sizeBytes = dataset.sliceByteSize.toLong(), data = MemoryUtil.memAlloc(dataset.sliceByteSize)
    )

    @AfterEach
    fun reset() {
        clients.map { it.close() }.forEach { it.join() }
        server.shutdown()
        server.close().join()
        ctx.linger = 0
        ctx.destroy()
        ctx = ZContext()
    }

    @Test
    fun helloCarriesSessionAndVersion() {
        val (client, _) = connectClient()
        val hello = assertNotNull(client.serverHello)
        assertEquals(server.sessionId, hello.sessionId)
        assertEquals(PROTOCOL_VERSION, hello.protocolVersion)
        assert(hello.sessionId != NO_ID)
    }

    @Test
    fun datasetRoundTrip() {
        val (_, received) = connectClient()
        hardware.output.put(MicroscopeDataset(dataset))

        lightSleepOnNull { received.firstOrNull { it is Dataset } }
        assertEquals(dataset, received.firstOrNull { it is Dataset })
    }

    @Test
    fun resendLastDatasetOnConnect() {
        val (_, first) = connectClient()

        val stack0 = stack(100, 0)
        val stack1 = stack(101, 1)
        val slices = listOf(slice(1000, stack0, 0), slice(1001, stack0, 1), slice(1002, stack1, 0), slice(1003, stack1, 1))
        hardware.output.put(MicroscopeDataset(dataset))
        hardware.output.put(MicroscopeStack(stack0))
        hardware.output.put(MicroscopeStack(stack1))
        slices.forEach { hardware.output.put(MicroscopeSlice(it)) }
        lightSleepOnCondition { first.count { it is Slice } == slices.size }

        val (_, second) = connectClient()
        lightSleepOnCondition { second.count { it is Slice } == slices.size }

        val data = second.filterIsInstance<BaseServerSignal.DataAvailableSignal>()
        val expected = listOf(dataset, stack0, stack1) + slices.map { it.copy(data = null) }
        assertEquals(expected, data)
    }

    /** e.g. a live dataset that hardware announces at startup before any client is connected */
    @Test
    fun datasetAnnouncedBeforeConnectIsSentOnConnect() {
        hardware.output.put(MicroscopeDataset(dataset))
        lightSleepOnCondition { hardware.output.isEmpty() }

        val (_, received) = connectClient()
        lightSleepOnNull { received.firstOrNull { it is Dataset } }
        assertEquals(dataset, received.firstOrNull { it is Dataset })

        val liveSlice = Slice(
            2000, dataset.Id, 0, NO_ID, 0, System.currentTimeMillis(), Vector3f(),
            sizeBytes = dataset.sliceByteSize.toLong(), data = MemoryUtil.memAlloc(dataset.sliceByteSize)
        )
        hardware.output.put(MicroscopeSlice(liveSlice))
        lightSleepOnNull { received.firstOrNull { it is Slice } }

        assertEquals(liveSlice.copy(data = null), received.firstOrNull { it is Slice })
        assertDatasetsBeforeData(received)
    }

    /**
     * Every stack and slice has to arrive after its dataset. A dataset may arrive more than once, e.g. when a
     * broadcast and the resend for a new client overlap. That is fine, the later one replaces the earlier one.
     */
    private fun assertDatasetsBeforeData(received: List<BaseServerSignal>) {
        val known = mutableSetOf<Long>()
        received.filterIsInstance<BaseServerSignal.DataAvailableSignal>().forEach {
            when (it) {
                is Dataset -> known += it.Id
                is Stack -> assert(it.datasetId in known) { "Stack ${it.Id} arrived before its dataset ${it.datasetId}" }
                is Slice -> assert(it.datasetId in known) { "Slice ${it.Id} arrived before its dataset ${it.datasetId}" }
            }
        }
    }

    /** a client that connected after the dataset was resent still gets it before data referencing it */
    @Test
    fun datasetIsAnnouncedBeforeDataOfOlderDataset() {
        val otherDataset = dataset.copy(Id = 8)
        hardware.output.put(MicroscopeDataset(dataset))
        hardware.output.put(MicroscopeDataset(otherDataset))
        lightSleepOnCondition { hardware.output.isEmpty() }

        // on connect only the most recent dataset is resent
        val (_, received) = connectClient()
        lightSleepOnNull { received.firstOrNull { it is Dataset } }

        val stack = stack(100, 0)
        hardware.output.put(MicroscopeStack(stack))
        lightSleepOnNull { received.firstOrNull { it is Stack } }

        assertEquals(stack, received.firstOrNull { it is Stack })
        assertDatasetsBeforeData(received)
    }
}
