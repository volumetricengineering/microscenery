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
}
