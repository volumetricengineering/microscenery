package network

import microscenery.lightSleepOnNull
import microscenery.network.*
import microscenery.signals.*
import org.joml.Vector3f
import org.joml.Vector3i
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.zeromq.ZContext
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class RequestSliceTest {

    var ctx = ZContext()
    var lastClientBaseSignal: BaseClientSignal? = null
    var lastServerBaseSignal: BaseServerSignal? = null
    var lastSliceRequestSignal: BaseServerSignal? = null

    var server: ControlSignalsServer = ControlSignalsServer(ctx, 11543, "*", listOf {
        lastClientBaseSignal = it
    })
    var storage = SliceStorage()
    var biggishDataServer = BiggishDataServer(11544, "*", storage,ctx)

    var client: ControlSignalsClient = ControlSignalsClient(ctx, 11543, "*", listOf {
        lastServerBaseSignal = it
    })
    val sliceRequester = SliceRequester(client, listOf{ lastSliceRequestSignal = it})

    @AfterEach
    fun reset() {
        val agents = listOf(server,client,sliceRequester, biggishDataServer)
        agents.map { it.close() }.forEach { it.join() }

        ctx.linger = 0
        ctx.destroy()
        ctx = ZContext()
    }

    @Test
    fun announceAndRequestSlice() {
        lightSleepOnNull { lastClientBaseSignal }
        assertNotNull(lastClientBaseSignal as? BaseClientSignal.ClientSignOn)

        server.sendSignal(
            Dataset(1, Vector3i(10, 10, 1), Vector3f(1f), NumericType.INT8, Vector3f(), Vector3f())
        )
        storage.addSlice(1, ByteBuffer.wrap(ByteArray(100) { it.toByte() }))
        server.sendSignal(
            Slice(
                Id = 1, datasetId = 1, channelIndex = 0, stackId = NO_ID, stackSliceIndex = 0,
                created = System.currentTimeMillis(), stagePos = Vector3f(), sizeBytes = 100, data = null
            )
        )

        lightSleepOnNull { lastServerBaseSignal as? Slice }
        //assert(lastSliceRequestSignal !is Slice) //it should be held back

        lightSleepOnNull { lastSliceRequestSignal as? Slice }
        val slice = lastSliceRequestSignal as Slice
        for (i in 0 until 100){
            assertEquals(i.toByte(), slice.data?.get())
        }
    }
}