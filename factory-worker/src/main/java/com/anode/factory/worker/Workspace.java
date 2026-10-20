package com.anode.factory.worker;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.MergeResult;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.diff.Edit;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryState;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.BundleWriter;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.util.io.DisabledOutputStream;

/**
 * A run's clone, on its work branch. Its git directory is kept apart from the work tree: sandboxes
 * get the tree (and the git directory read-only), so nothing they run can change the repository
 * the worker commits and pushes from.
 */
final class Workspace implements AutoCloseable {

    record DiffStats(int files, int insertions, int deletions) {}

    private static final PersonIdent FACTORY = new PersonIdent("Digital Factory", "factory@users.noreply.github.com");

    private final Git git;
    private final Path tree;
    private final Path gitDir;
    private final String defaultBranch;
    private final String branch;
    private final ControlPlane.GitCredential credential;

    private Workspace(Git git, Path tree, Path gitDir, String defaultBranch, String branch,
                      ControlPlane.GitCredential credential) {
        this.git = git;
        this.tree = tree;
        this.gitDir = gitDir;
        this.defaultBranch = defaultBranch;
        this.branch = branch;
        this.credential = credential;
    }

    /** The default branch cloned, then the work branch: the bundle's if there is one, a new one otherwise. */
    // ponytail: full clones; shallow ones (setDepth) once big repositories make runs slow to start
    static Workspace clone(ControlPlane.GitCredential credential, String defaultBranch, String branch, byte[] bundle,
                           Path dir) {
        Path tree = dir.resolve("tree");
        Path gitDir = dir.resolve("git");
        try {
            Git git = Git.cloneRepository()
                    .setURI(credential.cloneUrl())
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(credential.username(),
                            credential.password()))
                    .setDirectory(tree.toFile())
                    .setGitDir(gitDir.toFile())
                    .setBranch(defaultBranch)
                    .call();
            if (bundle == null) {
                git.checkout().setCreateBranch(true).setName(branch).call();
            } else {
                Path file = dir.resolve("work.bundle");
                Files.write(file, bundle);
                git.fetch().setRemote(file.toString())
                        .setRefSpecs(new RefSpec("+refs/heads/" + branch + ":refs/heads/" + branch))
                        .call();
                git.checkout().setName(branch).call();
            }
            return new Workspace(git, tree, gitDir, defaultBranch, branch, credential);
        } catch (GitAPIException | IOException e) {
            throw new IllegalStateException("Cannot clone " + credential.cloneUrl() + ": " + e.getMessage(), e);
        }
    }

    Path tree() {
        return tree;
    }

    Path gitDir() {
        return gitDir;
    }

    String branch() {
        return branch;
    }

    /**
     * Merges the default branch into the work branch, not committed yet: the files in conflict, with
     * their markers left in the tree (none: the merge is ready to commit, or there was nothing to merge).
     */
    List<String> mergeDefaultBranch() {
        try {
            MergeResult result = git.merge().include(base()).setCommit(false)
                    .setFastForward(MergeCommand.FastForwardMode.NO_FF)
                    .setMessage("Merge " + defaultBranch + " into " + branch)
                    .call();
            return result.getConflicts() == null ? List.of() : result.getConflicts().keySet().stream().sorted().toList();
        } catch (GitAPIException | IOException e) {
            throw new IllegalStateException("Cannot merge " + defaultBranch + " into " + branch + ": " + e.getMessage(), e);
        }
    }

    /** Commits whatever the tree holds now (ignored files aside), without hooks; false when nothing changed. */
    boolean commitAll(String message) {
        try {
            git.add().addFilepattern(".").call();
            git.add().setUpdate(true).addFilepattern(".").call(); // deletions
            boolean merging = git.getRepository().getRepositoryState() == RepositoryState.MERGING_RESOLVED
                    || git.getRepository().getRepositoryState() == RepositoryState.MERGING;
            if (git.status().call().isClean() && !merging) {
                return false;
            }
            if (merging) {
                message = "Merge " + defaultBranch + " into " + branch + "\n\n" + message; // a merge commit
            }
            git.commit().setMessage(message).setAuthor(FACTORY).setCommitter(FACTORY).setNoVerify(true).call();
            return true;
        } catch (GitAPIException e) {
            throw new IllegalStateException("Cannot commit: " + e.getMessage(), e);
        }
    }

    /** The work branch from the default branch: what the next run of the work order starts from. */
    byte[] bundle() {
        Repository repository = git.getRepository();
        try (RevWalk walk = new RevWalk(repository)) {
            BundleWriter writer = new BundleWriter(repository);
            writer.include("refs/heads/" + branch, repository.resolve("refs/heads/" + branch));
            writer.assume(walk.parseCommit(base()));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            writer.writeBundle(null, out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    DiffStats diffStats() {
        try (DiffFormatter formatter = new DiffFormatter(DisabledOutputStream.INSTANCE)) {
            formatter.setRepository(git.getRepository());
            formatter.setDiffComparator(RawTextComparator.DEFAULT);
            int insertions = 0;
            int deletions = 0;
            var entries = formatter.scan(baseTree(), headTree());
            for (DiffEntry entry : entries) {
                for (Edit edit : formatter.toFileHeader(entry).toEditList()) {
                    insertions += edit.getLengthB();
                    deletions += edit.getLengthA();
                }
            }
            return new DiffStats(entries.size(), insertions, deletions);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The unified diff of the work branch from the default branch. */
    String diff() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DiffFormatter formatter = new DiffFormatter(out)) {
            formatter.setRepository(git.getRepository());
            formatter.format(baseTree(), headTree());
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Pushes the work branch with the (write) credential the clone was made with; the pushed commit. */
    String push() {
        try {
            ObjectId head = git.getRepository().resolve("refs/heads/" + branch);
            if (head.equals(base())) {
                throw new IllegalStateException("Nothing to push: " + branch + " has no commit of its own");
            }
            RefSpec spec = new RefSpec("refs/heads/" + branch + ":refs/heads/" + branch);
            for (PushResult result : git.push().setRemote("origin").setRefSpecs(spec)
                    .setCredentialsProvider(new UsernamePasswordCredentialsProvider(credential.username(),
                            credential.password()))
                    .call()) {
                for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                    if (update.getStatus() != RemoteRefUpdate.Status.OK
                            && update.getStatus() != RemoteRefUpdate.Status.UP_TO_DATE) {
                        throw new IllegalStateException("Push of " + branch + " refused: " + update.getStatus()
                                + (update.getMessage() == null ? "" : " " + update.getMessage()));
                    }
                }
            }
            return head.name();
        } catch (GitAPIException | IOException e) {
            throw new IllegalStateException("Cannot push " + branch + ": " + e.getMessage(), e);
        }
    }

    private ObjectId base() throws IOException {
        return git.getRepository().resolve("refs/remotes/origin/" + defaultBranch);
    }

    private AbstractTreeIterator baseTree() throws IOException {
        return tree(base());
    }

    private AbstractTreeIterator headTree() throws IOException {
        return tree(git.getRepository().resolve("refs/heads/" + branch));
    }

    private AbstractTreeIterator tree(ObjectId commitId) throws IOException {
        try (RevWalk walk = new RevWalk(git.getRepository())) {
            RevCommit commit = walk.parseCommit(commitId);
            var iterator = new CanonicalTreeParser();
            try (var reader = git.getRepository().newObjectReader()) {
                iterator.reset(reader, commit.getTree());
            }
            return iterator;
        }
    }

    @Override
    public void close() {
        git.close();
    }
}
