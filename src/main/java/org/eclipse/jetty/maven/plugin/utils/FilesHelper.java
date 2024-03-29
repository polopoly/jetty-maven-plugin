package org.eclipse.jetty.maven.plugin.utils;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.plugin.logging.Log;
import org.eclipse.jetty.maven.plugin.PluginLog;

/**
 * FilesHelper
 *
 * @author mnova
 */
public class FilesHelper {

    private FilesHelper() {
    }

    public static void removeDuplicates(final List<File> files) {
        if (files != null && files.size() > 0) {
            getLog().debug("removeDuplicates - before " + files);
            final Set<File> existingFiles = new HashSet<>();
            int idx = 0;
            while (idx < files.size()) {
                final File file = files.get(idx);
                if (existingFiles.contains(file)) {
                    files.remove(idx);
                    getLog().debug("remove " + file);
                } else {
                    existingFiles.add(file);
                    idx += 1;
                }
            }
            getLog().debug("removeDuplicates - after " + files);
        }
    }

    public static void removeList(final List<File> files,
                                  final List<File> excludedFiles) {
        if (files != null && files.size() > 0 && excludedFiles != null && excludedFiles.size() > 0) {
            getLog().debug("removeList - before " + files);
            int idx = 0;
            while (idx < files.size()) {
                final File file = files.get(idx);
                if (excludedFiles.contains(file)) {
                    files.remove(idx);
                    getLog().debug("remove " + file);
                } else {
                    idx += 1;
                }
            }
            getLog().debug("removeList - after " + files);
        }
    }

    public static File ensureDirectoryExists(final File dir) {
        if (!dir.exists()) {
            getLog().debug("Creating " + dir);
            if (!dir.mkdir()) {
                getLog().warn("Cannot create " + dir);
            }
        }
        return dir;
    }

    public static String toOSPath(final String...parts) {
        final StringBuilder sb = new StringBuilder();
        for (int idx = 0; idx < parts.length; idx++) {
            if (idx != 0) {
                sb.append(File.separator);
            }
            sb.append(parts[idx]);
        }
        return sb.toString();
    }

    private static Log getLog() {
        return PluginLog.getLog();
    }

}
