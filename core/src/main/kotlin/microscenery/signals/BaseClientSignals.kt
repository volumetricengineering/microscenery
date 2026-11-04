package microscenery.signals

import com.google.protobuf.ByteString

sealed class BaseClientSignal {

    open fun toProto(): org.withXR.network.v4.BaseClientSignal{
        //val b = org.withXR.network.v4.BaseClientSignal.newBuilder()
        when (this){
            is ClientSignOn -> throw NotImplementedError("This case should be overwritten.")
            is AppSpecific -> throw NotImplementedError("This case should be overwritten.")
        }
        //return b.build()
    }

    /**
     * @param protocolVersion protocol version of the client. Peers using a v3 or older library send 0.
     * The server must close the connection if it differs from [PROTOCOL_VERSION].
     */
    data class ClientSignOn(
        val isProbe: Boolean = false,
        val protocolVersion: Int = PROTOCOL_VERSION
    ): BaseClientSignal(){

        val isCompatible: Boolean
            get() = isCompatibleProtocolVersion(protocolVersion)

        override fun toProto(): org.withXR.network.v4.BaseClientSignal {
            val b = org.withXR.network.v4.BaseClientSignal.newBuilder()
            val c = b.clientSignOnBuilder
            c.isProbe = isProbe
            c.protocolVersion = protocolVersion
            return b.build()
        }
    }

    data class AppSpecific(val data: ByteString): BaseClientSignal(){
        override fun toProto(): org.withXR.network.v4.BaseClientSignal {
            val b = org.withXR.network.v4.BaseClientSignal.newBuilder()
            val asb = b.appSpecificBuilder
            asb.data = data
            return b.build()
        }
    }

    companion object {
        fun org.withXR.network.v4.BaseClientSignal.toPoko() =
            when (this.signalCase?: throw IllegalArgumentException("Illegal payload")) {
                org.withXR.network.v4.BaseClientSignal.SignalCase.SIGNAL_NOT_SET ->
                    throw IllegalArgumentException("Signal is not set in Client signal message")
                org.withXR.network.v4.BaseClientSignal.SignalCase.APPSPECIFIC ->
                    AppSpecific(this.appSpecific.data)
                org.withXR.network.v4.BaseClientSignal.SignalCase.CLIENTSIGNON ->
                    ClientSignOn(this.clientSignOn.isProbe, this.clientSignOn.protocolVersion)
            }
    }
}
