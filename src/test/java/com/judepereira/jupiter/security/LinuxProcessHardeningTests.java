package com.judepereira.jupiter.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class LinuxProcessHardeningTests {

    @Test
    void nonLinuxDoesNothing() {
        LinuxProcessHardening.NativeCall call = mock(LinuxProcessHardening.NativeCall.class);

        assertDoesNotThrow(() -> LinuxProcessHardening.enforce("Windows 11", call));

        verifyNoInteractions(call);
    }

    @Test
    void linuxCallsPrctlWithDumpableZero() throws Throwable {
        LinuxProcessHardening.NativeCall call = mock(LinuxProcessHardening.NativeCall.class);
        when(call.prctl(4, 0L)).thenReturn(0);

        LinuxProcessHardening.enforce("Linux", call);

        verify(call).prctl(4, 0L);
    }

    @Test
    void nonzeroReturnFailsClosed() throws Throwable {
        LinuxProcessHardening.NativeCall call = mock(LinuxProcessHardening.NativeCall.class);
        when(call.prctl(4, 0L)).thenReturn(1);

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> LinuxProcessHardening.enforce("Linux", call));

        assertEquals("Linux prctl(PR_SET_DUMPABLE, 0) returned 1", exception.getMessage());
    }

    @Test
    void nativeExceptionFailsClosed() throws Throwable {
        LinuxProcessHardening.NativeCall call = mock(LinuxProcessHardening.NativeCall.class);
        when(call.prctl(4, 0L)).thenThrow(new RuntimeException("native failure"));

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> LinuxProcessHardening.enforce("Linux", call));

        assertEquals("Unable to set Linux process dumpability to 0", exception.getMessage());
    }
}
