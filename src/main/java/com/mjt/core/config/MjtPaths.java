package com.mjt.core.config;

import java.io.File;

/**
 * Quản lý thư mục gốc làm việc cho cả chế độ Standalone (.)
 * và chế độ Paper Plugin (plugins/MJTCore/).
 */
public class MjtPaths {

    private static volatile File baseDir = new File(".");

    public static void init(File dir) {
        if (dir != null) {
            if (!dir.exists()) {
                dir.mkdirs();
            }
            baseDir = dir;
        } else {
            baseDir = new File(".");
        }
    }

    public static File getBaseDir() {
        return baseDir;
    }

    public static File resolve(String relativePath) {
        File f = new File(relativePath);
        if (f.isAbsolute()) {
            return f;
        }
        return new File(baseDir, relativePath);
    }
}
