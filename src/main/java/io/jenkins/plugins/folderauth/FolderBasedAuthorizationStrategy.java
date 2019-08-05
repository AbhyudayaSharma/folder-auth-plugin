package io.jenkins.plugins.folderauth;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import hudson.Extension;
import hudson.model.AbstractItem;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Job;
import hudson.security.ACL;
import hudson.security.AuthorizationStrategy;
import hudson.security.Permission;
import hudson.security.PermissionGroup;
import hudson.security.SidACL;
import io.jenkins.plugins.folderauth.acls.GlobalAclImpl;
import io.jenkins.plugins.folderauth.acls.GenericAclImpl;
import io.jenkins.plugins.folderauth.misc.PermissionWrapper;
import io.jenkins.plugins.folderauth.roles.AbstractRole;
import io.jenkins.plugins.folderauth.roles.AgentRole;
import io.jenkins.plugins.folderauth.roles.FolderRole;
import io.jenkins.plugins.folderauth.roles.GlobalRole;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.acegisecurity.acls.sid.PrincipalSid;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@ParametersAreNonnullByDefault
public class FolderBasedAuthorizationStrategy extends AuthorizationStrategy {
    private static final Logger LOGGER = Logger.getLogger(FolderBasedAuthorizationStrategy.class.getName());
    private static final String ADMIN_ROLE_NAME = "admin";
    private static final String FOLDER_SEPARATOR = "/";
    private Set<AgentRole> agentRoles;
    private Set<GlobalRole> globalRoles;
    private Set<FolderRole> folderRoles;
    private transient GlobalAclImpl globalAcl;
    /**
     * Maps full name of jobs to their respective {@link ACL}s. The {@link ACL}s here do not
     * get inheritance from their parents.
     */
    private transient ConcurrentHashMap<String, GenericAclImpl> jobAcls;
    /**
     * Maps full name of the Agents to their respective {@link ACL}s. Inheritance is not needed here
     * because Agents are not nestable.
     */
    private transient ConcurrentHashMap<String, GenericAclImpl> agentAcls;
    /**
     * Contains the ACLs for projects that do not need any further inheritance.
     * <p>
     * Invalidate this cache whenever folder roles are updated.
     */
    private transient Cache<String, SidACL> jobAclCache;

    @DataBoundConstructor
    public FolderBasedAuthorizationStrategy(Set<GlobalRole> globalRoles, Set<FolderRole> folderRoles,
                                            Set<AgentRole> agentRoles) {
        init(globalRoles, folderRoles, agentRoles);
    }

    /**
     * This constructor should be used only for XStream serialization and does not call {@link #init(Set, Set, Set)}.
     *
     * @param globalRoles global roles to be serialized
     * @param folderRoles folder roles to be serialized
     * @param agentRoles agent roles to be serialized
     */
    private FolderBasedAuthorizationStrategy(HashSet<GlobalRole> globalRoles, HashSet<FolderRole> folderRoles,
                                             HashSet<AgentRole> agentRoles) {
        this.globalRoles = globalRoles;
        this.folderRoles = folderRoles;
        this.agentRoles = agentRoles;
    }

    /**
     * Clears and recalculates {@code jobAcls}.
     */
    private synchronized void updateJobAcls() {
        jobAcls.clear();

        for (FolderRole role : folderRoles) {
            updateAclForFolderRole(role);
        }
    }

    private synchronized void updateAgentAcls() {
        agentAcls.clear();

        for (AgentRole role : agentRoles) {
            updateAclForAgentRole(role);
        }
    }

    @Nonnull
    @Override
    public GlobalAclImpl getRootACL() {
        return globalAcl;
    }

    /**
     * Used to initialize transient fields when loaded from disk
     *
     * @return {@code this}
     */
    @Nonnull
    @SuppressWarnings("unused")
    private FolderBasedAuthorizationStrategy readResolve() {
        init(globalRoles, folderRoles, agentRoles);
        return this;
    }

    /**
     * Uses {@link #FolderBasedAuthorizationStrategy(HashSet, HashSet, HashSet)} to
     * create a proxy {@link FolderBasedAuthorizationStrategy} to be serialized.
     *
     * @return a proxy {@link FolderBasedAuthorizationStrategy} only for serialization.
     * @see #FolderBasedAuthorizationStrategy(HashSet, HashSet, HashSet)
     */
    @Nonnull
    @SuppressWarnings("unused")
    private FolderBasedAuthorizationStrategy writeReplace() {
        // Return a temporary FolderBasedAuthorizationStrategy that uses HashSets for simpler serialization
        return new FolderBasedAuthorizationStrategy(new HashSet<>(globalRoles), new HashSet<>(folderRoles),
            new HashSet<>(agentRoles));
    }

    /**
     * Gets the {@link ACL} for a {@link Job}
     *
     * @return the {@link ACL} for the {@link Job}
     */
    @Nonnull
    @Override
    public SidACL getACL(@Nonnull Job<?, ?> project) {
        return getACL((AbstractItem) project);
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    public SidACL getACL(@Nonnull AbstractItem item) {
        String fullName = item.getFullName();
        SidACL acl = jobAclCache.getIfPresent(fullName);

        if (acl != null) {
            return acl;
        }

        String[] splits = fullName.split(FOLDER_SEPARATOR);
        StringBuilder sb = new StringBuilder(fullName.length());
        acl = globalAcl;

        // Roles on a folder are applicable to all children
        for (String str : splits) {
            sb.append(str);
            SidACL newAcl = jobAcls.get(sb.toString());
            if (newAcl != null) {
                acl = acl.newInheritingACL(newAcl);
            }
            sb.append(FOLDER_SEPARATOR);
        }

        jobAclCache.put(fullName, acl);
        return acl;
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    public SidACL getACL(@Nonnull Computer computer) {
        String name = computer.getName();
        SidACL acl = agentAcls.get(name);
        if (acl == null) {
            return globalAcl;
        } else {
            // TODO: cache these ACLs
            return globalAcl.newInheritingACL(acl);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Nonnull
    @Override
    public Collection<String> getGroups() {
        Set<String> groups = ConcurrentHashMap.newKeySet();
        agentRoles.stream().parallel().map(AbstractRole::getSids).forEach(groups::addAll);
        globalRoles.stream().parallel().map(AbstractRole::getSids).forEach(groups::addAll);
        folderRoles.stream().parallel().map(AbstractRole::getSids).forEach(groups::addAll);
        return Collections.unmodifiableCollection(groups);
    }

    private synchronized void generateNewGlobalAcl() {
        globalAcl = new GlobalAclImpl(globalRoles);
    }

    public void addGlobalRole(@Nonnull GlobalRole globalRole) throws IOException {
        globalRoles.add(globalRole);
        try {
            Jenkins.get().save();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Unable to save config file, not adding global role", e);
            globalRoles.remove(globalRole);
            throw e;
        } finally {
            generateNewGlobalAcl();
            jobAclCache.invalidateAll();
        }
    }

    @Nonnull
    public Set<GlobalRole> getGlobalRoles() {
        return Collections.unmodifiableSet(globalRoles);
    }

    @Nonnull
    public Set<AgentRole> getAgentRoles() {
        return Collections.unmodifiableSet(agentRoles);
    }

    /**
     * Assigns SID to a {@link GlobalRole}.
     *
     * @param roleName the name of the {@link GlobalRole}
     * @param sid      the sid to be assigned
     * @throws IOException            when unable to save the configuration to disk
     * @throws NoSuchElementException when no {@link GlobalRole} with name equal to {@code roleName} exists
     */
    public void assignSidToGlobalRole(String roleName, String sid) throws IOException {
        GlobalRole role = globalRoles.stream()
                .filter(r -> r.getName().equals(roleName))
                .findAny().orElseThrow(() ->
                        new NoSuchElementException("No GlobalRole with the " + "name " + roleName + "exists."));

        role.assignSids(sid);

        try {
            Jenkins.get().save();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Unable to save config file, not assigning the sids.", e);
            role.unassignSids(sid);
            throw e;
        } finally {
            generateNewGlobalAcl();
            // inherited jobAcls depend on globalAcl
            jobAclCache.invalidateAll();
        }
    }

    /**
     * Returns the {@link FolderRole}s on which this {@link AuthorizationStrategy} works.
     *
     * @return {@link FolderRole}s on which this {@link AuthorizationStrategy} works
     */
    @Nonnull
    public Set<FolderRole> getFolderRoles() {
        return Collections.unmodifiableSet(folderRoles);
    }

    /**
     * Adds a FolderRole to this strategy
     *
     * @param folderRole the {@link FolderRole} to be added
     * @throws IOException when unable to save configuration to disk
     */
    public void addFolderRole(@Nonnull FolderRole folderRole) throws IOException {
        folderRoles.add(folderRole);
        try {
            Jenkins.get().save();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Unable to save configuration when adding folder role.", e);
            folderRoles.remove(folderRole);
            throw e;
        } finally {
            jobAclCache.invalidateAll();
            updateAclForFolderRole(folderRole);
        }
    }

    /**
     * Adds an {@link AgentRole} to this strategy
     *
     * @param agentRole the {@link FolderRole} to be added
     * @throws IOException when unable to save configuration to disk
     */
    public void addAgentRole(@Nonnull AgentRole agentRole) throws IOException {
        agentRoles.add(agentRole);
        try {
            Jenkins.get().save();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Unable to save configuration when adding agent role.", e);
            agentRoles.remove(agentRole);
            throw e;
        } finally {
            updateAclForAgentRole(agentRole);
        }
    }

    /**
     * Assigns a SID to a {@link FolderRole}.
     *
     * @param roleName the name of the {@link FolderRole}
     * @param sid      the sid to be assigned
     * @throws IOException            when unable to save the configuration to disk
     * @throws NoSuchElementException when no {@link GlobalRole} with name equal to {@code roleName} exists
     */
    public void assignSidToFolderRole(String roleName, String sid) throws IOException {
        FolderRole role = folderRoles.stream()
                .filter(r -> r.getName().equals(roleName))
                .findAny().orElseThrow(() ->
                     new NoSuchElementException("No FolderRole with the name " + roleName + " exists."));

        role.assignSids(sid);
        try {
            Jenkins.get().save();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Unable to save config file, not assigning the sids.", e);
            role.unassignSids(sid);
            throw e;
        } finally {
            // no cache invalidation required here because the inheritance of
            // folder roles does not change and we're directly modifying the ACL
            // whose references are kept inside the inheriting SidACL.
            updateAclForFolderRole(role);
        }
    }

    /**
     * Assigns a SID to an {@link AgentRole}.
     *
     * @param roleName the name of the {@link FolderRole}
     * @param sid      the sid to be assigned
     * @throws IOException            when unable to save the configuration to disk
     * @throws NoSuchElementException when no {@link GlobalRole} with name equal to {@code roleName} exists
     */
    public void assignSidToAgentRole(String roleName, String sid) throws IOException {
        AgentRole role = agentRoles.stream()
                .filter(r -> r.getName().equals(roleName))
                .findAny().orElseThrow(() ->
                        new NoSuchElementException("No AgentRole with the name " + roleName + " exists."));

        role.assignSids(sid);
        try {
            Jenkins.get().save();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Unable to save config file, not assigning the sids.", e);
            role.unassignSids(sid);
            throw e;
        } finally {
            // no cache invalidation required here because the inheritance of
            // folder roles does not change and we're directly modifying the ACL
            // whose references are kept inside the inheriting SidACL.
            updateAclForAgentRole(role);
        }
    }

    /**
     * Updates the ACL for the folder role
     * <p>
     * <b>Note: does not invalidate the cache</b>
     * <p>
     * Should be called when a folderRole has been updated.
     *
     * @param role the role to be updated
     */
    private void updateAclForFolderRole(@Nonnull FolderRole role) {
        for (String name : role.getFolderNames()) {
            updateGenericAcl(name, jobAcls, role);
        }
    }

    /**
     * Updates the ACL for the agent role
     * <p>
     * <b>Note: does not invalidate the cache</b>
     * <p>
     * Should be called when an agentRole has been updated.
     *
     * @param role the role to be updated
     */
    private void updateAclForAgentRole(@Nonnull AgentRole role) {
        for (String agent : role.getAgents()) {
            updateGenericAcl(agent, agentAcls, role);
        }
    }

    private void updateGenericAcl(String fullName, ConcurrentHashMap<String, GenericAclImpl> acls, AbstractRole role) {
        GenericAclImpl acl = acls.get(fullName);
        if (acl == null) {
            acl = new GenericAclImpl();
        }
        acl.assignPermissions(role.getSids(),
                role.getPermissions().stream().map(PermissionWrapper::getPermission).collect(Collectors.toSet()));
        acls.put(fullName, acl);
    }

    /**
     * Deletes the global role identified by its name.
     *
     * @param roleName the name of the role to be deleted
     * @throws IOException              when unable to save the configuration
     * @throws NoSuchElementException   when no {@link GlobalRole} with name equal to {@code roleName} exists
     * @throws IllegalArgumentException when trying to delete the admin role
     */
    public void deleteGlobalRole(String roleName) throws IOException {
        if (roleName.equals(ADMIN_ROLE_NAME)) {
            // the admin role cannot be deleted
            throw new IllegalArgumentException("The admin role cannot be deleted.");
        }

        GlobalRole role = globalRoles.stream()
                .filter(r -> r.getName().equals(roleName))
                .findAny().orElseThrow(() ->
                        new NoSuchElementException("No GlobalRole with the name " + roleName + " exists."));

        globalRoles.remove(role);

        try {
            Jenkins.get().save();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Unable to save the config when deleting global role. " +
                    "The role was not deleted.", e);
            globalRoles.add(role);
            throw e;
        } finally {
            generateNewGlobalAcl();
        }
    }

    /**
     * Deletes the folder role identified by its name.
     *
     * @param roleName the name of the role to be deleted
     * @throws IOException            when unable to save the configuration
     * @throws NoSuchElementException when no {@link FolderRole} with name equal to {@code roleName} exists
     */
    public void deleteFolderRole(String roleName) throws IOException {
        FolderRole role = folderRoles.stream()
                .filter(r -> r.getName().equals(roleName))
                .findAny().orElseThrow(() ->
                        new NoSuchElementException("No FolderRole with the name " + roleName + " exists."));

        folderRoles.remove(role);

        try {
            Jenkins.get().save();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Unable to save the config when deleting folder role. " +
                    "The role was not deleted.", e);
            folderRoles.add(role);
            throw e;
        } finally {
            // TODO update jobACLs manually?
            updateJobAcls();
            jobAclCache.invalidateAll();
        }
    }

    /**
     * Initializes the cache, generates ACLs and makes the {@link FolderBasedAuthorizationStrategy}
     * ready for work.
     */
    private void init(Set<GlobalRole> globalRoles, Set<FolderRole> folderRoles,
                      Set<AgentRole> agentRoles) {
        this.globalRoles = ConcurrentHashMap.newKeySet();
        this.folderRoles = ConcurrentHashMap.newKeySet();
        this.agentRoles = ConcurrentHashMap.newKeySet();

        this.globalRoles.addAll(globalRoles);
        this.folderRoles.addAll(folderRoles);
        this.agentRoles.addAll(agentRoles);

        jobAcls = new ConcurrentHashMap<>();
        agentAcls = new ConcurrentHashMap<>();

        jobAclCache = CacheBuilder.newBuilder()
                       .expireAfterWrite(1, TimeUnit.HOURS)
                       .maximumSize(2048)
                       .build();

        generateNewGlobalAcl();
        updateJobAcls();
        updateAgentAcls();
    }

    /**
     * Deletes the {@link AgentRole} identified by its name.
     *
     * @param roleName the name of the role to be deleted
     * @throws IOException            when unable to save the configuration
     * @throws NoSuchElementException when no {@link AgentRole} with name equal to {@code roleName} exists
     */
    public void deleteAgentRole(String roleName) throws IOException {
        AgentRole role = agentRoles.stream()
                             .filter(r -> r.getName().equals(roleName))
                             .findAny().orElseThrow(() ->
                                  new NoSuchElementException("No AgentRole with the name " + roleName + " exists."));

        agentRoles.remove(role);

        try {
            Jenkins.get().save();
        } catch (IOException e) {
            LOGGER.log(Level.SEVERE, "Unable to save the config when deleting agent role. " +
                                         "The role was not deleted.", e);
            agentRoles.add(role);
            throw e;
        } finally {
            // TODO update jobACLs manually?
            updateJobAcls();
            jobAclCache.invalidateAll();
        }
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<AuthorizationStrategy> {
        @Nonnull
        @Override
        public String getDisplayName() {
            return Messages.FolderBasedAuthorizationStrategy_DisplayName();
        }

        @Nonnull
        @Override
        public FolderBasedAuthorizationStrategy newInstance(@Nullable StaplerRequest req, @Nonnull JSONObject formData) {
            AuthorizationStrategy strategy = Jenkins.get().getAuthorizationStrategy();
            if (strategy instanceof FolderBasedAuthorizationStrategy) {
                // this action was invoked from the 'Configure Global Security' page when the
                // old strategy was FolderBasedAuthorizationStrategy; return it back as formData would be empty
                return (FolderBasedAuthorizationStrategy) strategy;
            } else {
                // when this AuthorizationStrategy is selected for the first time, this makes the current
                // user admin (give all permissions) and prevents him/her from getting access denied.
                // The same thing happens in Role Strategy plugin. See RoleBasedStrategy.DESCRIPTOR.newInstance()

                HashSet<PermissionGroup> groups = new HashSet<>(PermissionGroup.getAll());
                groups.remove(PermissionGroup.get(Permission.class));
                Set<PermissionWrapper> adminPermissions = PermissionWrapper.wrapPermissions(
                    FolderAuthorizationStrategyManagementLink.getSafePermissions(groups));

                GlobalRole adminRole = new GlobalRole(ADMIN_ROLE_NAME, adminPermissions);
                adminRole.assignSids(new PrincipalSid(Jenkins.getAuthentication()).getPrincipal());

                return new FolderBasedAuthorizationStrategy(Collections.singleton(adminRole), Collections.emptySet(),
                    Collections.emptySet());
            }
        }
    }
}
