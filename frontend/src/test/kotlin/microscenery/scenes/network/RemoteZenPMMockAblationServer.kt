package microscenery.scenes.network

import microscenery.MicroscenerySettings
import microscenery.Settings
import microscenery.signals.BaseServerSignal
import microscenery.signals.ServerType
import microscenery.zenSysConCon.ZenBlueTCPConnector
import microscenery.zenSysConCon.ZenSysConMicroscope
import microscenery.zenSysConCon.apps.ZenServerApplication
import microscenery.zenSysConCon.sysCon.SysConConnection
import org.mockito.Mockito
import org.mockito.kotlin.whenever

class RemoteZenPMMockAblationServer {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            MicroscenerySettings.set(Settings.Ablation.SizeUM, 30f)
            MicroscenerySettings.set(Settings.Network.AnnounceBonjour, false)

            val initalExperimentFile = """zenSysConCon/src/test/resources/OriginalTestExperiment.czexp"""
            val marinaMac = """/Users/jan/volumes/Zeiss/marina-sd3-drosophila1.czi"""

            val zenBlue: ZenBlueTCPConnector = Mockito.mock(ZenBlueTCPConnector::class.java)
            val sysCon: SysConConnection = Mockito.mock(SysConConnection::class.java)

            whenever(zenBlue.getCurrentDocument()).thenReturn(marinaMac)
            whenever(zenBlue.saveExperimentAndGetFilePath()).thenReturn(initalExperimentFile)

            val zenMicroscope = ZenSysConMicroscope(zenBlue, sysCon)

            ZenServerApplication.startServerWithGui(zenMicroscope, BaseServerSignal.ServerHello("Mock Zen Server",
                ServerType.MICROSCOPE,"Zen"))
            //RemoteMicroscopeServer(AblationSimulationMicroscope(zenMicroscope, imgOrigin = Origin.FrontBottomLeft), ZContext(), acquireOnConnect = true)
        }
    }
}