package microscenery.signals

import com.google.protobuf.ByteString
import com.google.protobuf.util.Timestamps.fromMillis
import com.google.protobuf.util.Timestamps.toMillis
import microscenery.signals.BaseServerSignal.DataAvailableSignal.Companion.toPoko
import microscenery.signals.BaseServerSignal.ServerHello.Companion.toPoko
import microscenery.signals.ChannelMetadata.Companion.toPoko
import microscenery.signals.Dataset.Companion.toPoko
import org.joml.Vector2i
import org.joml.Vector3f
import org.joml.Vector3i
import org.withXR.network.v4.EnumNumericType
import org.withXR.network.v4.EnumPhysicalUnit
import org.withXR.network.v4.EnumProtocolVersion
import org.withXR.network.v4.EnumServerType
import org.withXR.network.v4.EnumSourceFormat
import java.nio.ByteBuffer
import kotlin.random.Random

/** Protocol version this library speaks. Peers with a different version must be rejected. */
const val PROTOCOL_VERSION: Int = EnumProtocolVersion.PROTOCOL_VERSION_CURRENT_VALUE

/** Peers using a v3 or older library send 0. */
fun isCompatibleProtocolVersion(peerVersion: Int) = peerVersion == PROTOCOL_VERSION

/** Value of IDs ([Dataset.Id], [Stack.Id], [Slice.Id], [Slice.stackId], ...) that means "none". */
const val NO_ID: Long = 0L

sealed class BaseServerSignal {

    abstract fun toProto(): org.withXR.network.v4.BaseServerSignal

    data class AppSpecific(val data: ByteString): BaseServerSignal(){
        override fun toProto(): org.withXR.network.v4.BaseServerSignal {
            val b = org.withXR.network.v4.BaseServerSignal.newBuilder()
            val asb = b.appSpecificBuilder
            asb.setData(data)
            return b.build()
        }
    }

    sealed class DataAvailableSignal : BaseServerSignal() {
        override fun toProto(): org.withXR.network.v4.BaseServerSignal {
            val b = org.withXR.network.v4.BaseServerSignal.newBuilder()
            b.dataAvailableSignal = this.toProtoIntern()
            return b.build()
        }

        protected abstract fun toProtoIntern(): org.withXR.network.v4.DataAvailableSignal

        companion object {
            fun org.withXR.network.v4.DataAvailableSignal.toPoko(): DataAvailableSignal =
                when (this.availableDataCase ?: throw IllegalArgumentException("Illegal payload")) {
                    org.withXR.network.v4.DataAvailableSignal.AvailableDataCase.STACK -> {
                        val s = this.stack
                        Stack(
                            Id = s.id,
                            datasetId = s.datasetId,
                            channelIndex = s.channelIndex,
                            timepointIndex = s.timepointIndex,
                            created = toMillis(s.created)
                        )
                    }

                    org.withXR.network.v4.DataAvailableSignal.AvailableDataCase.SLICE -> {
                        val s = this.slice
                        Slice(
                            Id = s.id,
                            datasetId = s.datasetId,
                            channelIndex = s.channelIndex,
                            stackId = s.stackId,
                            stackSliceIndex = s.stackSliceIndex,
                            created = toMillis(s.created),
                            stagePos = s.stagePos.toPoko(),
                            sizeBytes = s.sizeBytes,
                            data = null
                        )
                    }

                    org.withXR.network.v4.DataAvailableSignal.AvailableDataCase.DATASET ->
                        this.dataset.toPoko()

                    org.withXR.network.v4.DataAvailableSignal.AvailableDataCase.AVAILABLEDATA_NOT_SET ->
                        throw IllegalArgumentException("${this.javaClass.simpleName} is not set")
                }
        }

    }

    /**
     * Self identification message that will be send to new clients.
     * @param serverName be careful with special characters. This variable is also the bonjour name which prefers plain letters.
     * @param sessionId random non-zero value chosen at server start. All Dataset/Stack/Slice IDs
     * are only valid within this session. Servers stamp their own session id in before sending.
     * @param protocolVersion protocol version of the server. Peers using a v3 or older library send 0.
     */
    data class ServerHello(
        val serverName: String,
        val serverType: ServerType,
        val subType: String,
        val sessionId: Long = NO_ID,
        val protocolVersion: Int = PROTOCOL_VERSION
    ) : BaseServerSignal() {

        val isCompatible: Boolean
            get() = isCompatibleProtocolVersion(protocolVersion)

        override fun toProto(): org.withXR.network.v4.BaseServerSignal {
            val b = org.withXR.network.v4.BaseServerSignal.newBuilder()
            val b2 = b.serverHelloBuilder
            b2.serverName = serverName
            b2.serverType = serverType.toProto()
            b2.subType = subType
            b2.sessionId = sessionId
            b2.protocolVersion = protocolVersion

            return b.build()
        }

        companion object{
            fun org.withXR.network.v4.ServerHello.toPoko() =
                ServerHello(this.serverName, this.serverType.toPoko(), this.subType, this.sessionId, this.protocolVersion)
        }
    }


    companion object {
        fun org.withXR.network.v4.BaseServerSignal.toPoko() =
            when (this.signalCase?: throw IllegalArgumentException("Illegal payload")) {
                org.withXR.network.v4.BaseServerSignal.SignalCase.SIGNAL_NOT_SET ->
                    throw IllegalArgumentException("Signal is not set in Client signal message")
                org.withXR.network.v4.BaseServerSignal.SignalCase.DATAAVAILABLESIGNAL ->
                    this.dataAvailableSignal.toPoko()
                org.withXR.network.v4.BaseServerSignal.SignalCase.APPSPECIFIC ->
                    AppSpecific(this.appSpecific.data)
                org.withXR.network.v4.BaseServerSignal.SignalCase.SERVERHELLO ->
                    this.serverHello.toPoko()
            }
    }
}

/**
 * Describes everything that all stacks of a recording share. Mirrors withXR's VolumeMetadata.
 * Must be sent before any of its stacks or slices. Re-sending a Dataset with the same [Id] replaces it.
 *
 * @param Id > 0, unique within a server session
 * @param created in millis since epoch
 * @param dimensions x,y = image size in pixels, z = number of slices per stack
 * @param voxelSize size of one voxel in [physicalUnit]. z is the step between slices.
 * @param from stage position of the first slice of every stack, in [physicalUnit]
 * @param to stage position of the last slice of every stack, in [physicalUnit]
 * @param channels in channel order. [Stack.channelIndex] and [Slice.channelIndex] index into this list.
 * @param timepointCount number of timepoints known so far. 0 = unknown / open-ended (live).
 */
data class Dataset(
    val Id: Long,
    val dimensions: Vector3i,
    val voxelSize: Vector3f,
    val numericType: NumericType,
    val from: Vector3f,
    val to: Vector3f,
    val channels: List<ChannelMetadata> = listOf(ChannelMetadata()),
    val physicalUnit: PhysicalUnit = PhysicalUnit.UM,
    val timepointCount: Int = 0,
    val sourceFormat: SourceFormat = SourceFormat.LIVE,
    val displayName: String = "",
    val created: Long = System.currentTimeMillis(),
    val warnings: List<String> = emptyList(),
    val attributes: Map<String, String> = emptyMap()
) : BaseServerSignal.DataAvailableSignal() {

    /** in pixels */
    val imageSize: Vector2i
        get() = Vector2i(dimensions.x, dimensions.y)

    /** Number of slices per stack */
    val slicesPerStack: Int
        get() = dimensions.z

    /** Size of one slice of one channel in bytes */
    val sliceByteSize: Int
        get() = dimensions.x * dimensions.y * numericType.bytes

    override fun toProtoIntern(): org.withXR.network.v4.DataAvailableSignal {
        val microscopeSignal = org.withXR.network.v4.DataAvailableSignal.newBuilder()

        val d = microscopeSignal.datasetBuilder
        d.id = this.Id
        d.displayName = this.displayName
        d.created = fromMillis(this.created)
        d.sourceFormat = this.sourceFormat.toProto()
        d.dimensions = this.dimensions.toProto()
        d.voxelSize = this.voxelSize.toProto()
        d.physicalUnit = this.physicalUnit.toProto()
        d.numericType = this.numericType.toProto()
        d.from = this.from.toProto()
        d.to = this.to.toProto()
        d.addAllChannels(this.channels.map { it.toProto() })
        d.timepointCount = this.timepointCount
        d.addAllWarnings(this.warnings)
        d.putAllAttributes(this.attributes)
        d.build()

        return microscopeSignal.build()
    }

    companion object {
        fun org.withXR.network.v4.Dataset.toPoko() = Dataset(
            Id = this.id,
            dimensions = this.dimensions.toPoko(),
            voxelSize = this.voxelSize.toPoko(),
            numericType = this.numericType.toPoko(),
            from = this.from.toPoko(),
            to = this.to.toPoko(),
            channels =this.channelsList.map { it.toPoko() },
            physicalUnit = this.physicalUnit.toPoko(),
            timepointCount = this.timepointCount,
            sourceFormat = this.sourceFormat.toPoko(),
            displayName = this.displayName,
            created = toMillis(this.created),
            warnings = this.warningsList.toList(),
            attributes = this.attributesMap.toMap()
        )
    }
}

/**
 * Per-channel description, mirroring withXR's ChannelMetadata.
 * A preset the user saved on the client takes precedence over all of these.
 *
 * @param displayMin suggested display window in raw data units. null = no suggestion.
 * @param displayMax suggested display window in raw data units. null = no suggestion.
 * @param color suggested display colour, linear RGB in 0..1. null = no suggestion.
 * @param active whether the source marked this channel as visible. Unset on the wire means false.
 */
data class ChannelMetadata(
    val label: String = "",
    val displayMin: Double? = null,
    val displayMax: Double? = null,
    val color: Vector3f? = null,
    val active: Boolean = false
) {
    fun toProto(): org.withXR.network.v4.ChannelMetadata {
        val b = org.withXR.network.v4.ChannelMetadata.newBuilder()
        b.label = label
        displayMin?.let { b.displayMin = it }
        displayMax?.let { b.displayMax = it }
        color?.let { b.color = it.toProto() }
        b.active = active
        return b.build()
    }

    companion object {
        fun org.withXR.network.v4.ChannelMetadata.toPoko() = ChannelMetadata(
            label = this.label,
            displayMin = if (this.hasDisplayMin()) this.displayMin else null,
            displayMax = if (this.hasDisplayMax()) this.displayMax else null,
            color = if (this.hasColor()) this.color.toPoko() else null,
            active = if (this.hasActive()) this.active else false
        )
    }
}

/**
 * One image plane. Either part of a stack ([stackId] > 0) or a standalone slice of the dataset,
 * e.g. a live preview ([stackId] == [NO_ID]).
 *
 * @param Id > 0, unique within a server session
 * @param datasetId always set. Must match the stack's for stack slices.
 * @param channelIndex index into [Dataset.channels]. Always set. Must match the stack's for stack slices.
 * @param stackId [NO_ID] if not associated with a stack
 * @param stackSliceIndex index within the stack. Only meaningful if [stackId] is set.
 * @param created in millis since epoch
 * @param stagePos in [Dataset.physicalUnit]
 * @param sizeBytes size of the image data in bytes
 */
data class Slice(
    val Id: Long,
    val datasetId: Long,
    val channelIndex: Int,
    val stackId: Long,
    val stackSliceIndex: Int,
    val created: Long,
    val stagePos: Vector3f,
    val sizeBytes: Long,
    val data: ByteBuffer?
) : BaseServerSignal.DataAvailableSignal() {

    val isStandalone: Boolean
        get() = stackId == NO_ID

    override fun toProtoIntern(): org.withXR.network.v4.DataAvailableSignal {
        val microscopeSignal = org.withXR.network.v4.DataAvailableSignal.newBuilder()

        val s = microscopeSignal.sliceBuilder
        s.id = this.Id
        s.datasetId = this.datasetId
        s.channelIndex = this.channelIndex
        s.stackId = this.stackId
        s.stackSliceIndex = if (isStandalone) 0 else this.stackSliceIndex
        s.created = fromMillis(this.created)
        s.stagePos = this.stagePos.toProto()
        s.sizeBytes = this.sizeBytes
        s.build()

        return microscopeSignal.build()
    }
}

/**
 * One volume of one channel at one timepoint. Time series are grouped by (datasetId, channelIndex, timepointIndex).
 * The number of slices is [Dataset.slicesPerStack], the stage positions are [Dataset.from] and [Dataset.to].
 *
 * @param Id > 0, unique within a server session
 * @param channelIndex index into [Dataset.channels]
 * @param created in millis since epoch
 */
data class Stack(
    val Id: Long,
    val datasetId: Long,
    val channelIndex: Int,
    val timepointIndex: Int,
    val created: Long
) : BaseServerSignal.DataAvailableSignal() {
    override fun toProtoIntern(): org.withXR.network.v4.DataAvailableSignal {
        val microscopeSignal = org.withXR.network.v4.DataAvailableSignal.newBuilder()

        val s = microscopeSignal.stackBuilder
        s.id = this.Id
        s.datasetId = this.datasetId
        s.channelIndex = this.channelIndex
        s.timepointIndex = this.timepointIndex
        s.created = fromMillis(this.created)
        s.build()

        return microscopeSignal.build()
    }
}


enum class NumericType(val bytes: Int) {
    INT8(1),
    INT16(2),
    UINT8(1),
    UINT16(2),
    FLOAT32(4);

    companion object {
        /** Ambiguous for 1 and 2 bytes, returns the signed type in these cases. */
        fun fromNumBytes(bytes: Int) = entries.firstOrNull { it.bytes == bytes }
    }
}

fun NumericType.toProto() = when (this) {
    NumericType.INT8 -> EnumNumericType.VALUE_NUMERIC_INT8
    NumericType.INT16 -> EnumNumericType.VALUE_NUMERIC_INT16
    NumericType.UINT8 -> EnumNumericType.VALUE_NUMERIC_UINT8
    NumericType.UINT16 -> EnumNumericType.VALUE_NUMERIC_UINT16
    NumericType.FLOAT32 -> EnumNumericType.VALUE_NUMERIC_FLOAT32
}

fun EnumNumericType.toPoko() = when (this) {
    EnumNumericType.VALUE_NUMERIC_UNKNOWN -> throw IllegalArgumentException("Cant convert to NumericType")
    EnumNumericType.VALUE_NUMERIC_INT8 -> NumericType.INT8
    EnumNumericType.VALUE_NUMERIC_INT16 -> NumericType.INT16
    EnumNumericType.VALUE_NUMERIC_UINT8 -> NumericType.UINT8
    EnumNumericType.VALUE_NUMERIC_UINT16 -> NumericType.UINT16
    EnumNumericType.VALUE_NUMERIC_FLOAT32 -> NumericType.FLOAT32
    EnumNumericType.UNRECOGNIZED -> throw IllegalArgumentException("Cant convert to NumericType")
}

/** Matches withXR's Volume.PhysicalUnit */
enum class PhysicalUnit {
    UNDEFINED, UM, NM, MM;

    fun toProto() = when (this) {
        UNDEFINED -> EnumPhysicalUnit.PHYSICAL_UNIT_UNDEFINED
        UM -> EnumPhysicalUnit.PHYSICAL_UNIT_UM
        NM -> EnumPhysicalUnit.PHYSICAL_UNIT_NM
        MM -> EnumPhysicalUnit.PHYSICAL_UNIT_MM
    }
}

fun EnumPhysicalUnit.toPoko() = when (this) {
    EnumPhysicalUnit.PHYSICAL_UNIT_UNDEFINED -> PhysicalUnit.UNDEFINED
    EnumPhysicalUnit.PHYSICAL_UNIT_UM -> PhysicalUnit.UM
    EnumPhysicalUnit.PHYSICAL_UNIT_NM -> PhysicalUnit.NM
    EnumPhysicalUnit.PHYSICAL_UNIT_MM -> PhysicalUnit.MM
    EnumPhysicalUnit.UNRECOGNIZED -> PhysicalUnit.UNDEFINED
}

/** Mirrors withXR's VolumeSourceFormat. Live microscopes report [LIVE]. */
enum class SourceFormat {
    UNKNOWN, LIVE, TIFF, RAW, HDF5, DICOM, OME_ZARR, PROCEDURAL;

    fun toProto() = when (this) {
        UNKNOWN -> EnumSourceFormat.SOURCE_FORMAT_UNKNOWN
        LIVE -> EnumSourceFormat.SOURCE_FORMAT_LIVE
        TIFF -> EnumSourceFormat.SOURCE_FORMAT_TIFF
        RAW -> EnumSourceFormat.SOURCE_FORMAT_RAW
        HDF5 -> EnumSourceFormat.SOURCE_FORMAT_HDF5
        DICOM -> EnumSourceFormat.SOURCE_FORMAT_DICOM
        OME_ZARR -> EnumSourceFormat.SOURCE_FORMAT_OME_ZARR
        PROCEDURAL -> EnumSourceFormat.SOURCE_FORMAT_PROCEDURAL
    }
}

fun EnumSourceFormat.toPoko() = when (this) {
    EnumSourceFormat.SOURCE_FORMAT_UNKNOWN -> SourceFormat.UNKNOWN
    EnumSourceFormat.SOURCE_FORMAT_LIVE -> SourceFormat.LIVE
    EnumSourceFormat.SOURCE_FORMAT_TIFF -> SourceFormat.TIFF
    EnumSourceFormat.SOURCE_FORMAT_RAW -> SourceFormat.RAW
    EnumSourceFormat.SOURCE_FORMAT_HDF5 -> SourceFormat.HDF5
    EnumSourceFormat.SOURCE_FORMAT_DICOM -> SourceFormat.DICOM
    EnumSourceFormat.SOURCE_FORMAT_OME_ZARR -> SourceFormat.OME_ZARR
    EnumSourceFormat.SOURCE_FORMAT_PROCEDURAL -> SourceFormat.PROCEDURAL
    EnumSourceFormat.UNRECOGNIZED -> SourceFormat.UNKNOWN
}


enum class ServerType{
    OTHER, VIEWER, MICROSCOPE;

    fun toProto() = when(this) {
        OTHER -> EnumServerType.SERVER_TYPE_OTHER
        VIEWER -> EnumServerType.SERVER_TYPE_VIEWER
        MICROSCOPE -> EnumServerType.SERVER_TYPE_MICROSCOPE
    }
}

fun EnumServerType.toPoko() = when(this) {
    EnumServerType.SERVER_TYPE_UNKNOWN -> throw IllegalArgumentException("Cant convert to ServerType")
    EnumServerType.SERVER_TYPE_OTHER -> ServerType.OTHER
    EnumServerType.SERVER_TYPE_VIEWER -> ServerType.VIEWER
    EnumServerType.SERVER_TYPE_MICROSCOPE -> ServerType.MICROSCOPE
    EnumServerType.UNRECOGNIZED -> throw IllegalArgumentException("Cant convert to ServerType")
}
