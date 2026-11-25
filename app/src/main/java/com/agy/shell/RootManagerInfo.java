package com.agy.shell;

import org.json.JSONObject;

/**
 * Immutable snapshot of live root (`su`) availability and manager guidance.
 */
public final class RootManagerInfo {

    public final boolean granted;
    public final String suPath;
    public final String suVersion;
    public final String managerType;
    public final String detectedManagerPackage;
    public final String selinuxContext;
    public final boolean mountMasterSupported;
    public final String guidance;

    public RootManagerInfo(
            boolean granted,
            String suPath,
            String suVersion,
            String managerType,
            String detectedManagerPackage,
            String selinuxContext,
            boolean mountMasterSupported,
            String guidance) {
        this.granted = granted;
        this.suPath = suPath != null ? suPath : "";
        this.suVersion = suVersion != null ? suVersion : "";
        this.managerType = managerType != null ? managerType : "none";
        this.detectedManagerPackage = detectedManagerPackage != null ? detectedManagerPackage : "";
        this.selinuxContext = selinuxContext != null ? selinuxContext : "";
        this.mountMasterSupported = mountMasterSupported;
        this.guidance = guidance != null ? guidance : "";
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("granted", granted);
            o.put("suPath", suPath);
            o.put("suVersion", suVersion);
            o.put("managerType", managerType);
            o.put("detectedManagerPackage", detectedManagerPackage);
            o.put("selinuxContext", selinuxContext);
            o.put("mountMasterSupported", mountMasterSupported);
            o.put("guidance", guidance);
        } catch (Exception ignored) {
        }
        return o;
    }
}
