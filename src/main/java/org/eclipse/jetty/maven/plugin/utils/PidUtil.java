package org.eclipse.jetty.maven.plugin.utils;

import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.logging.Logger;

import sun.management.VMManagement;

/**
 * PidUtil
 *
 * @author mnova
 */
public class PidUtil {

    private static final Logger LOGGER = Logger.getLogger(PidUtil.class.getName());

    public static int getMyPid() {
        final int pid = getMyPidWithGetProcessId();
        if (pid < 0) {
            return getMyPidWithVMName();
        }
        return pid;
    }

    private static int getMyPidWithGetProcessId() {
        try {
            final RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
            final Field jvm = runtime.getClass().getDeclaredField("jvm");
            jvm.setAccessible(true);

            final VMManagement management = (VMManagement) jvm.get(runtime);
            final Method method = management.getClass().getDeclaredMethod("getProcessId");
            method.setAccessible(true);

            return (Integer) method.invoke(management);
        } catch (NoSuchFieldException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            return -1;
        }
    }

    private static int getMyPidWithVMName() {
        try {
            final RuntimeMXBean runtime = ManagementFactory.getRuntimeMXBean();
            final String vmName = runtime.getName();
            final int p = vmName.indexOf("@");
            return Integer.parseInt(vmName.substring(0, p));
        } catch (IllegalArgumentException e) {
            return -1;
        }
    }
}
