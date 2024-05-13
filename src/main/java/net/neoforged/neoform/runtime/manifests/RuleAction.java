package net.neoforged.neoform.runtime.manifests;

import com.google.gson.annotations.SerializedName;

enum RuleAction {
    @SerializedName("allow")
    ALLOWED,
    @SerializedName("disallow")
    DISALLOWED;

    boolean isAllowed() {
        return ALLOWED == this;
    }

    boolean isDisallowed() {
        return DISALLOWED == this;
    }
}
