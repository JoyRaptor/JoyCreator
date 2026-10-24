package cc.joycreator.joybrush.androidkit.brush

import java.io.FileDescriptor
import java.io.IOException
import java.nio.file.Paths
import kotlin.test.*

/** Injected descriptor operations exercise errors without loading/calling Android Os in a JVM test. */
class AndroidUserModeDurabilityTest {
    private class Calls : UserModeDirectoryCalls {
        val descriptor=FileDescriptor()
        val trace=mutableListOf<String>()
        var openFailure:IOException?=null
        var syncFailure:IOException?=null
        var closeFailure:IOException?=null
        override fun openDirectory(directory:java.nio.file.Path):FileDescriptor {
            trace += "open";openFailure?.let{throw it};return descriptor
        }
        override fun fsync(descriptor:FileDescriptor) { assertSame(this.descriptor,descriptor);trace += "fsync";syncFailure?.let{throw it} }
        override fun close(descriptor:FileDescriptor) { assertSame(this.descriptor,descriptor);trace += "close";closeFailure?.let{throw it} }
    }
    @Test fun directoryIsOpenedSyncedAndClosedInOrder() {
        val calls=Calls();AndroidUserModeDurability(calls).syncDirectory(Paths.get("owned-root"))
        assertEquals(listOf("open","fsync","close"),calls.trace)
    }
    @Test fun primaryFsyncFailureKeepsCloseFailureSuppressed() {
        val calls=Calls().apply{syncFailure=IOException("sync");closeFailure=IOException("close")}
        val error=assertFailsWith<IOException>{AndroidUserModeDurability(calls).syncDirectory(Paths.get("owned-root"))}
        assertSame(calls.syncFailure,error);assertEquals(listOf(calls.closeFailure),error.suppressed.toList())
        assertEquals(listOf("open","fsync","close"),calls.trace)
    }
    @Test fun closeFailureCannotReportDurableSuccessAndOpenFailureCannotCloseAnotherDescriptor() {
        val close=Calls().apply{closeFailure=IOException("close")}
        assertSame(close.closeFailure,assertFailsWith<IOException>{AndroidUserModeDurability(close).syncDirectory(Paths.get("owned-root"))})
        val open=Calls().apply{openFailure=IOException("open")}
        assertSame(open.openFailure,assertFailsWith<IOException>{AndroidUserModeDurability(open).syncDirectory(Paths.get("owned-root"))})
        assertEquals(listOf("open"),open.trace)
    }
}
