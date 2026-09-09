package com.judepereira.jupiter.security;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

public final class LinuxProcessHardening {

    private static final int PR_SET_DUMPABLE = 4;
    private static final String LINUX = "Linux";

    @FunctionalInterface
    interface NativeCall {
        int prctl(int option, long value) throws Throwable;
    }

    private LinuxProcessHardening() {
    }

    public static void enforce() {
        enforce(System.getProperty("os.name"), LinuxProcessHardening::callPrctl);
    }

    static void enforce(String osName, NativeCall nativeCall) {
        if (!LINUX.equals(osName)) {
            return;
        }

        final int result;
        try {
            result = nativeCall.prctl(PR_SET_DUMPABLE, 0L);
        } catch (Throwable exception) {
            throw new IllegalStateException("Unable to set Linux process dumpability to 0", exception);
        }
        if (result != 0) {
            throw new IllegalStateException("Linux prctl(PR_SET_DUMPABLE, 0) returned " + result);
        }
    }

    private static int callPrctl(int option, long value) throws Throwable {
        SymbolLookup libc = Linker.nativeLinker().defaultLookup();
        MemorySegment address = libc.find("prctl")
                .orElseThrow(() -> new IllegalStateException("Linux libc symbol prctl is unavailable"));
        // prctl has one fixed int followed by variadic long arguments on Linux ABIs.
        FunctionDescriptor descriptor = FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_LONG);
        MethodHandle handle = Linker.nativeLinker().downcallHandle(
                address, descriptor, Linker.Option.firstVariadicArg(1));
        return (int) handle.invokeExact(option, value);
    }
}
