package net.neoforged.neoform.runtime.utils;

import com.google.gson.annotations.SerializedName;

public enum OsType {
    @SerializedName("windows")
    WINDOWS,
    @SerializedName("linux")
    LINUX,
    @SerializedName("osx")
    MAC,
    UNKNOWN
}
