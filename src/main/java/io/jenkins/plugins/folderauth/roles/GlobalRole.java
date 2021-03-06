package io.jenkins.plugins.folderauth.roles;

import io.jenkins.plugins.folderauth.misc.PermissionWrapper;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.Collections;
import java.util.Set;

/**
 * An {@link AbstractRole} that's applicable everywhere inside Jenkins.
 */
public class GlobalRole extends AbstractRole {
    @DataBoundConstructor
    public GlobalRole(String name, Set<PermissionWrapper> permissions, Set<String> sids) {
        super(name, permissions, sids);
        this.sids.addAll(sids);
    }

    public GlobalRole(String name, Set<PermissionWrapper> permissions) {
        this(name, permissions, Collections.emptySet());
    }
}
