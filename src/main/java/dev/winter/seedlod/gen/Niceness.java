package dev.winter.seedlod.gen;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * Java's thread priority means nothing to Linux, so generation threads would compete with the game's render thread
 * at full weight. On Linux this lowers the calling thread's own scheduling priority instead; elsewhere it does nothing.
 */
public final class Niceness {
	private static final MethodHandle GETTID, SETPRIORITY;

	static {
		MethodHandle gettid = null, setpriority = null;
		if (System.getProperty("os.name", "").toLowerCase().contains("linux")) {
			try {
				Linker linker = Linker.nativeLinker();
				var libc = linker.defaultLookup();
				gettid = linker.downcallHandle(libc.find("gettid").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT));
				setpriority = linker.downcallHandle(libc.find("setpriority").orElseThrow(),
					FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
			} catch (Throwable ignored) {
				gettid = setpriority = null;
			}
		}
		GETTID = gettid;
		SETPRIORITY = setpriority;
	}

	/** Sets the calling thread's nice value (0..19, higher is lower priority). */
	public static void lowerCurrentThread(int nice) {
		if (SETPRIORITY == null) return;
		try {
			int tid = (int) GETTID.invokeExact();
			int ignored = (int) SETPRIORITY.invokeExact(0 /* PRIO_PROCESS: on Linux a tid names one thread */, tid, nice);
		} catch (Throwable ignored) {
		}
	}
}
