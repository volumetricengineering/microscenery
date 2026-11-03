package microscenery.signals

import com.google.protobuf.ByteString

sealed class BaseClientSignal {

    open fun toProto(): org.withXR.network.v3.BaseClientSignal{
        //val b = org.withXR.network.v3.BaseClientSignal.newBuilder()
        when (this){
            is ClientSignOn -> throw NotImplementedError("This case should be overwritten.")
            is AppSpecific -> throw NotImplementedError("This case should be overwritten.")
        }
        //return b.build()
    }

    data class ClientSignOn(val isProbe: Boolean = false): BaseClientSignal(){
        override fun toProto(): org.withXR.network.v3.BaseClientSignal {
            val b = org.withXR.network.v3.BaseClientSignal.newBuilder()
            val c = b.clientSignOnBuilder
            c.isProbe = isProbe
            return b.build()
        }
    }

    data class AppSpecific(val data: ByteString): BaseClientSignal(){
        override fun toProto(): org.withXR.network.v3.BaseClientSignal {
            val b = org.withXR.network.v3.BaseClientSignal.newBuilder()
            val asb = b.appSpecificBuilder
            asb.data = data
            return b.build()
        }
    }

    companion object {
        fun org.withXR.network.v3.BaseClientSignal.toPoko() =
            when (this.signalCase?: throw IllegalArgumentException("Illegal payload")) {
                org.withXR.network.v3.BaseClientSignal.SignalCase.SIGNAL_NOT_SET ->
                    throw IllegalArgumentException("Signal is not set in Client signal message")
                org.withXR.network.v3.BaseClientSignal.SignalCase.APPSPECIFIC ->
                    AppSpecific(this.appSpecific.data)
                org.withXR.network.v3.BaseClientSignal.SignalCase.CLIENTSIGNON ->
                    ClientSignOn(this.clientSignOn.isProbe)
            }
    }
}
