package microscenery.signals

import fromScenery.utils.extensions.minus
import fromScenery.utils.extensions.plus
import fromScenery.utils.extensions.times
import microscenery.signals.HardwareDimensions.Companion.toPoko
import microscenery.signals.MicroscopeStatus.Companion.toPoko
import microscenery.toReadableString
import org.joml.Vector3f
import org.withXR.network.v4.microscopeApi.EnumServerState


sealed class MicroscopeSignal {

    abstract fun toProto(): org.withXR.network.v4.microscopeApi.MicroscopeSignal

    companion object {
        fun org.withXR.network.v4.microscopeApi.MicroscopeSignal.toPoko() =
            when (this.signalCase ?: throw IllegalArgumentException("Illegal payload")) {
                org.withXR.network.v4.microscopeApi.MicroscopeSignal.SignalCase.STATUS -> {
                    this.status.toPoko()
                }

                org.withXR.network.v4.microscopeApi.MicroscopeSignal.SignalCase.SIGNAL_NOT_SET ->
                    throw IllegalArgumentException("Signal is not set in Server signal message")

                org.withXR.network.v4.microscopeApi.MicroscopeSignal.SignalCase.HARDWAREDIMENSIONS -> {
                    this.hardwareDimensions.toPoko()
                }

                org.withXR.network.v4.microscopeApi.MicroscopeSignal.SignalCase.ABLATIONRESULTS -> {
                    val ar = this.ablationResults
                    AblationResults(ar.totalTimeMillis, ar.perPointTimeList)
                }
            }
    }
}

/** workaround for transitioning from v2 to v3 */
data class MicroscopeDataset(val dataset: Dataset) : MicroscopeSignal() {
    override fun toProto(): org.withXR.network.v4.microscopeApi.MicroscopeSignal {
        TODO("Not yet implemented")
    }
}

/** workaround for transitioning from v2 to v3 */
data class MicroscopeStack(val stack: Stack) : MicroscopeSignal() {
    override fun toProto(): org.withXR.network.v4.microscopeApi.MicroscopeSignal {
        TODO("Not yet implemented")
    }
}

/** workaround for transitioning from v2 to v3 */
data class MicroscopeSlice(val slice: Slice) : MicroscopeSignal() {
    override fun toProto(): org.withXR.network.v4.microscopeApi.MicroscopeSignal {
        TODO("Not yet implemented")
    }
}

/**
 * Stage limits of the microscope. Image geometry (size, voxel size, numeric type) is described by [Dataset].
 */
data class HardwareDimensions(
    val stageMin: Vector3f,
    val stageMax: Vector3f
) : MicroscopeSignal() {

    override fun toProto(): org.withXR.network.v4.microscopeApi.MicroscopeSignal {
        val microscopeSignal = org.withXR.network.v4.microscopeApi.MicroscopeSignal.newBuilder()

        val hd = microscopeSignal.hardwareDimensionsBuilder
        hd.stageMin = this.stageMin.toProto()
        hd.stageMax = this.stageMax.toProto()
        hd.build()

        return microscopeSignal.build()
    }

    companion object {
        val EMPTY = HardwareDimensions(Vector3f(), Vector3f())

        fun org.withXR.network.v4.microscopeApi.HardwareDimensions.toPoko(): HardwareDimensions {
            val hwd = this
            return HardwareDimensions(
                hwd.stageMin.toPoko(),
                hwd.stageMax.toPoko()
            )
        }
    }

    /**
     * Limits a position within the stage limits.
     * @param imageSpaceOf if set, the limits are extended by half the image extent of this dataset in x and y.
     */
    fun coercePosition(
        target: Vector3f,
        logger: org.slf4j.Logger?,
        imageSpaceOf: Dataset? = null
    ): Vector3f {
        val safeTarget = Vector3f()

        val halfImageExtent = imageSpaceOf?.let {
            Vector3f(it.dimensions.x * it.voxelSize.x, it.dimensions.y * it.voxelSize.y, 0f) * 0.5f
        }

        val min = if (halfImageExtent == null)
            stageMin
        else
            stageMin - halfImageExtent

        val max = if (halfImageExtent == null)
            stageMax
        else
            stageMax + halfImageExtent

        for (i in 0..2) {
            safeTarget.setComponent(i, target[i].coerceIn(min[i], max[i]))
        }
        if (safeTarget != target) {
            logger?.warn("Had to coerce stage parameters! From ${target.toReadableString()} to ${safeTarget.toReadableString()}")
        }
        return safeTarget
    }
}


data class MicroscopeStatus(
    val state: ServerState,
    val stagePosition: Vector3f,
    val live: Boolean
) : MicroscopeSignal() {
    override fun toProto(): org.withXR.network.v4.microscopeApi.MicroscopeSignal {
        val microscopeSignal = org.withXR.network.v4.microscopeApi.MicroscopeSignal.newBuilder()

        val status = microscopeSignal.statusBuilder
        status.state = this.state.toProto()
        status.stagePosition = this.stagePosition.toProto()
        status.live = this.live
        status.build()

        return microscopeSignal.build()
    }

    companion object {
        fun org.withXR.network.v4.microscopeApi.MicroscopeStatus.toPoko(): MicroscopeStatus {
            val ss = this
            return MicroscopeStatus(
                ss.state.toPoko(),
                ss.stagePosition.toPoko(),
                ss.live
            )
        }
    }
}

data class AblationResults(val totalTimeMillis: Int, val perPointTime: List<Int>) : MicroscopeSignal() {
    override fun toProto(): org.withXR.network.v4.microscopeApi.MicroscopeSignal {
        val microscopeSignal = org.withXR.network.v4.microscopeApi.MicroscopeSignal.newBuilder()
        val ar = microscopeSignal.ablationResultsBuilder
        ar.totalTimeMillis = totalTimeMillis
        ar.addAllPerPointTime(perPointTime)
        return microscopeSignal.build()
    }

    fun mean(): Int = perPointTime.reduce { acc, i -> acc + i }.div(perPointTime.size)
}

enum class ServerState {
    LIVE, MANUAL, SHUTTING_DOWN, STACK, STARTUP, ABLATION
}

fun ServerState.toProto() = when (this) {
    ServerState.LIVE -> EnumServerState.SERVER_STATE_LIVE
    ServerState.MANUAL -> EnumServerState.SERVER_STATE_MANUAL
    ServerState.SHUTTING_DOWN -> EnumServerState.SERVER_STATE_SHUTTING_DOWN
    ServerState.STACK -> EnumServerState.SERVER_STATE_STACK
    ServerState.STARTUP -> EnumServerState.SERVER_STATE_STARTUP
    ServerState.ABLATION -> EnumServerState.SERVER_STATE_ABLATION
}

fun EnumServerState.toPoko() = when (this) {
    EnumServerState.SERVER_STATE_UNKNOWN -> throw IllegalArgumentException("Cant convert to ServerState")
    EnumServerState.SERVER_STATE_LIVE -> ServerState.LIVE
    EnumServerState.SERVER_STATE_MANUAL -> ServerState.MANUAL
    EnumServerState.SERVER_STATE_SHUTTING_DOWN -> ServerState.SHUTTING_DOWN
    EnumServerState.SERVER_STATE_STACK -> ServerState.STACK
    EnumServerState.SERVER_STATE_STARTUP -> ServerState.STARTUP
    EnumServerState.SERVER_STATE_ABLATION -> ServerState.ABLATION
    EnumServerState.UNRECOGNIZED -> throw IllegalArgumentException("Cant convert to ServerState")
}


