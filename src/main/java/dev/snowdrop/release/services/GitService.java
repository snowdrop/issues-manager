package dev.snowdrop.release.services;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import javax.enterprise.context.ApplicationScoped;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.transport.CredentialsProvider;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.jboss.logging.Logger;

@ApplicationScoped
public class GitService {

    static final Logger LOG = Logger.getLogger(GitService.class);
    private final Map<GitConfig, CompletableFuture<Git>> repositories = new ConcurrentHashMap<>(7);

    public static InputStream getStreamFrom(GitConfig config, String relativePath) throws IOException {
        return URI.create(config.getURI(relativePath)).toURL().openStream();
    }

    public void initRepository(GitConfig config) throws IOException {
        final var repository = Files.createTempDirectory(config.directoryPrefix()).toFile();
        repository.deleteOnExit();

        repositories.put(config, config.cloneFrom().thenApplyAsync(branch -> {
            try {
                LOG.infof("Cloned %s in %s", config.remoteURI(), repository.getPath());
                return Git.cloneRepository()
                    .setURI(config.remoteURI())
                    .setBranchesToClone(Collections.singleton(branch))
                    .setBranch(branch)
                    .setDirectory(repository)
                    .setCredentialsProvider(config.getCredentialProvider())
                    .call();
            } catch (GitAPIException e) {
                throw new RuntimeException(e);
            }
        }).thenApplyAsync(config::checkout));
    }

    public void commitAndPush(String commitMessage, GitConfig config, FileModifier... changed) throws IOException {
        CompletableFuture<Git> git = repositories.get(config);
        if (git == null) {
            throw new IllegalStateException("must call initRepository first");
        }
        try {
            git.thenAcceptAsync(g -> {
                final File repository = g.getRepository().getWorkTree();
                try {
                    // process the potential changes
                    var files = Arrays.stream(changed).map(fm -> fm.modify(repository)).collect(Collectors.toList());
                    final var status = g.status().call();
                    final var uncommittedChanges = status.getUncommittedChanges();
                    final var untracked = status.getUntracked();
                    final var hasChanges = new boolean[]{false};
                    if (!uncommittedChanges.isEmpty() || !untracked.isEmpty()) {
                        final var addCommand = g.add();
                        files.forEach(file -> {
                            final var path = file.getAbsolutePath().replace(repository.getAbsolutePath() + "/", "");
                            if (uncommittedChanges.contains(path) || untracked.contains(path)) {
                                // only add file to be committed if it's part of the modified set or untracked
                                LOG.infof("Added %s", path);
                                addCommand.addFilepattern(path);
                                hasChanges[0] = true;
                            }
                        });
                        if (hasChanges[0]) {
                            addCommand.call();
                            final var commit = g.commit().setMessage(commitMessage).call();
                            LOG.infof("Committed: %s", commit.getFullMessage());
                            final String branch = config.getBranch();
                            g.push().setRefSpecs(new RefSpec(branch + ":" + branch))
                                .setCredentialsProvider(config.getCredentialProvider()).call();
                            LOG.infof("Pushed");
                            return;
                        }
                    }
                    LOG.infof("No changes detected");
                } catch (GitAPIException e) {
                    throw new RuntimeException(e);
                }
            }).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new IOException(e);
        }
    }

    @FunctionalInterface
    public interface FileModifier {

        File modify(File repo);
    }

    public static class GitHubConfig extends GitConfig {

        private final String token;

        public GitHubConfig(String org, String repo, String branch, String token) {
            super(org, repo, branch);
            this.token = token;
        }

        CredentialsProvider getCredentialProvider() {
            return new UsernamePasswordCredentialsProvider(token, "");
        }

        @Override
        String directoryPrefix() {
            return "snowdrop-bom";
        }

        @Override
        public String remoteURI() {
            return "https://github.com/" + org + "/" + repo + ".git";
        }

        @Override
        public String getURI(String relativePath) {
            return "https://raw.githubusercontent.com/" + org + "/" + repo + "/" +  branch + "/" + relativePath;
        }
    }

    private static class GitLabConfig extends GitConfig {

        private final String user;
        private final String token;

        private GitLabConfig(String org, String repo, String branch, String user, String token) {
            super(org, repo, branch);
            this.user = user;
            this.token = token;
        }

        @Override
        CredentialsProvider getCredentialProvider() {
            return new UsernamePasswordCredentialsProvider(user, token);
        }

        @Override
        String directoryPrefix() {
            return "release-manager-" + org + "-" + repo;
        }

        @Override
        public String remoteURI() {
            return "https://gitlab.cee.redhat.com/" + org + "/" + repo + ".git";
        }

        @Override
        public String getURI(String relativePath) {
            return "https://gitlab.cee.redhat.com/" + org + "/" + repo + "/-/raw/" + branch + "/" + relativePath;
        }
    }

    public abstract static class GitConfig {

        protected final String org;
        protected final String repo;
        protected final String branch;
        private final CompletableFuture<Boolean> branchMissing;


        private GitConfig(String org, String repo, String branch) {
            this.org = org;
            this.repo = repo;
            this.branch = branch;
            branchMissing = CompletableFuture.supplyAsync(() -> {
                try {
                    Collection<Ref> refs = Git.lsRemoteRepository().setHeads(true)
                        .setCredentialsProvider(getCredentialProvider())
                        .setRemote(remoteURI()).call();
                    return refs;
                } catch (GitAPIException e) {
                    throw new RuntimeException(e);
                }
            }).thenApplyAsync(branches -> branches.stream().noneMatch(ref -> ref.getName().contains(branch)));
        }

        public static GitConfig githubConfig(String gitRef, String token) {
            final var split = gitRef.split("/");
            if (split.length != 3) {
                throw new IllegalArgumentException("Invalid git reference: " + gitRef
                    + ". Must follow organization/repository/branch format.");
            }
            return new GitHubConfig(split[0], split[1], split[2], token);
        }

        public static GitConfig gitlabConfig(String gitRef, String release, String username, String token) {
            final var split = gitRef.split("/");
            if (split.length != 2) {
                throw new IllegalArgumentException("Invalid git reference: " + gitRef
                    + ". Must follow organization/repository format.");
            }
            final var branch = "snowdrop-release-manager-" + release;
            return new GitLabConfig(split[0], split[1], branch, username, token);
        }

        abstract CredentialsProvider getCredentialProvider();

        abstract String directoryPrefix();

        public abstract String remoteURI();

        public abstract String getURI(String relativePath);

        public String getBranch() {
            return branch;
        }

        CompletableFuture<String> cloneFrom() {
            return branchMissing.thenApplyAsync(missing -> "refs/heads/" + (missing ? defaultBranch() : branch));
        }

        protected String defaultBranch() {
            return "main";
        }

        public Git checkout(Git git) {
            branchMissing.thenAccept(missing -> {
                try {
                    if (missing) {
                        git.branchCreate().setName(branch).call();
                    }
                    git.checkout().setName(branch).call();
                } catch (GitAPIException e) {
                    throw new RuntimeException(e);
                }
            });
            return git;
        }
    }
}
