package com.mjt.box.registry;

public class DistroManifest {
    private final String id;
    private final String displayName;
    private final String shellPath;
    private final String downloadUrl; // URL này đã được chốt cứng theo CPU (không lưu cả 2 link nữa)

    public DistroManifest(String id, String displayName, String shellPath, String downloadUrl) {
        this.id = id;
        this.displayName = displayName;
        this.shellPath = shellPath;
        this.downloadUrl = downloadUrl;
    }

    public String getId() { return id; }
    public String getDisplayName() { return displayName; }
    public String getShellPath() { return shellPath; }
    public String getDownloadUrl() { return downloadUrl; }
}