package io.vertx.ext.configuration.git;

import io.vertx.core.Vertx;
import io.vertx.core.json.DecodeException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.configuration.ConfigurationRetriever;
import io.vertx.ext.configuration.ConfigurationRetrieverOptions;
import io.vertx.ext.configuration.ConfigurationStoreOptions;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.apache.commons.io.Charsets;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Ref;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.jayway.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.CoreMatchers.is;

/**
 * @author <a href="http://escoffier.me">Clement Escoffier</a>
 */
@RunWith(VertxUnitRunner.class)
public class GitConfigurationStoreTest {

  private Vertx vertx;
  private ConfigurationRetriever retriever;
  private Git git;
  private Git bare;
  private File bareRoot;

  private File root = new File("target/junk/repo");
  private String branch;
  private String remote = "origin";

  @Before
  public void setUp(TestContext context) throws IOException, GitAPIException {
    vertx = Vertx.vertx();
    vertx.exceptionHandler(context.exceptionHandler());

    FileUtils.deleteDirectory(new File("target/junk"));

    bareRoot = new File("target/junk/bare-repo.git");
    bare = createBareRepository(bareRoot);
    git = connect(bareRoot, root);
    branch = "master";
  }

  @After
  public void tearDown() {
    AtomicBoolean done = new AtomicBoolean();
    if (retriever != null) {
      retriever.close();
    }

    if (git != null) {
      git.close();
    }
    if (bare != null) {
      bare.close();
    }

    vertx.close(v -> done.set(true));

    await().untilAtomic(done, is(true));
  }

  @Test
  public void testWithEmptyRepository(TestContext tc) throws GitAPIException, IOException {
    Async async = tc.async();
    add(git, root, new File("src/test/resources/files/some-text.txt"), null);
    push(git);

    retriever = ConfigurationRetriever.create(vertx, new ConfigurationRetrieverOptions().addStore(new
        ConfigurationStoreOptions().setType("git").setConfig(new JsonObject()
        .put("url", bareRoot.getAbsolutePath())
        .put("path", "target/junk/work")
        .put("filesets", new JsonArray().add(new JsonObject().put("pattern", "**/*.json"))))));

    retriever.getConfiguration(ar -> {
      assertThat(ar.succeeded()).isTrue();
      assertThat(ar.result()).isEmpty();
      async.complete();
    });

  }

  @Test
  public void testWithARepositoryWithAMatchingFile(TestContext tc) throws GitAPIException, IOException {
    Async async = tc.async();
    add(git, root, new File("src/test/resources/files/some-text.txt"), null);
    add(git, root, new File("src/test/resources/files/regular.json"), null);
    push(git);

    retriever = ConfigurationRetriever.create(vertx, new ConfigurationRetrieverOptions().addStore(new
        ConfigurationStoreOptions().setType("git").setConfig(new JsonObject()
        .put("url", bareRoot.getAbsolutePath())
        .put("path", "target/junk/work")
        .put("filesets", new JsonArray().add(new JsonObject().put("pattern", "*.json"))))));

    retriever.getConfiguration(ar -> {
      assertThat(ar.succeeded()).isTrue();
      assertThat(ar.result()).isNotEmpty();
      JsonObject json = ar.result();
      assertThat(json).isNotNull();
      assertThat(json.getString("key")).isEqualTo("value");

      assertThat(json.getBoolean("true")).isTrue();
      assertThat(json.getBoolean("false")).isFalse();

      assertThat(json.getString("missing")).isNull();

      assertThat(json.getInteger("int")).isEqualTo(5);
      assertThat(json.getDouble("float")).isEqualTo(25.3);

      assertThat(json.getJsonArray("array").size()).isEqualTo(3);
      assertThat(json.getJsonArray("array").contains(1)).isTrue();
      assertThat(json.getJsonArray("array").contains(2)).isTrue();
      assertThat(json.getJsonArray("array").contains(3)).isTrue();

      assertThat(json.getJsonObject("sub").getString("foo")).isEqualTo("bar");

      async.complete();
    });

  }

  @Test
  public void testWithACustomBranch(TestContext tc) throws GitAPIException, IOException {
    Async async = tc.async();
    add(git, root, new File("src/test/resources/files/a.json"), null);
    branch = "dev";
    add(git, root, new File("src/test/resources/files/regular.json"), null);
    push(git);

    retriever = ConfigurationRetriever.create(vertx, new ConfigurationRetrieverOptions().addStore(new
        ConfigurationStoreOptions().setType("git").setConfig(new JsonObject()
        .put("url", bareRoot.getAbsolutePath())
        .put("path", "target/junk/work")
        .put("branch", branch)
        .put("filesets", new JsonArray().add(new JsonObject().put("pattern", "*.json"))))));

    retriever.getConfiguration(ar -> {
      assertThat(ar.succeeded()).isTrue();
      assertThat(ar.result()).isNotEmpty();
      JsonObject json = ar.result();
      assertThat(json).isNotNull();
      assertThat(json.getString("key")).isEqualTo("value");
      async.complete();
    });

  }

  @Test
  public void testWithACustomBranchAndRemote(TestContext tc) throws GitAPIException, IOException {
    git.close();
    remote = "acme";
    FileUtils.deleteQuietly(root);
    git = connect(bareRoot, root);
    Async async = tc.async();
    add(git, root, new File("src/test/resources/files/a.json"), null);
    branch = "dev";
    add(git, root, new File("src/test/resources/files/regular.json"), null);
    push(git);

    retriever = ConfigurationRetriever.create(vertx, new ConfigurationRetrieverOptions().addStore(new
        ConfigurationStoreOptions().setType("git").setConfig(new JsonObject()
        .put("url", bareRoot.getAbsolutePath())
        .put("path", "target/junk/work")
        .put("branch", branch)
        .put("remote", remote)
        .put("filesets", new JsonArray().add(new JsonObject().put("pattern", "*.json"))))));

    retriever.getConfiguration(ar -> {
      if (ar.failed()) {
        ar.cause().printStackTrace();
      }
      assertThat(ar.succeeded()).isTrue();
      assertThat(ar.result()).isNotEmpty();
      JsonObject json = ar.result();
      assertThat(json).isNotNull();
      assertThat(json.getString("key")).isEqualTo("value");
      async.complete();
    });

  }

  @Test
  public void testWithARepositoryWithAMatchingPropertiesFile(TestContext tc) throws GitAPIException, IOException {
    Async async = tc.async();
    add(git, root, new File("src/test/resources/files/some-text.txt"), null);
    add(git, root, new File("src/test/resources/files/regular.json"), null);
    add(git, root, new File("src/test/resources/files/regular.properties"), null);
    push(git);

    retriever = ConfigurationRetriever.create(vertx, new ConfigurationRetrieverOptions().addStore(new
        ConfigurationStoreOptions().setType("git").setConfig(new JsonObject()
        .put("url", bareRoot.getAbsolutePath())
        .put("path", "target/junk/work")
        .put("filesets", new JsonArray().add(new JsonObject().put("pattern", "*.properties")
            .put("format", "properties"))))));

    retriever.getConfiguration(ar -> {
      if (ar.failed()) {
        ar.cause().printStackTrace();
      }
      assertThat(ar.succeeded()).isTrue();
      assertThat(ar.result()).isNotEmpty();
      JsonObject json = ar.result();
      assertThat(json).isNotNull();
      assertThat(json.getString("key")).isEqualTo("value");

      assertThat(json.getBoolean("true")).isTrue();
      assertThat(json.getBoolean("false")).isFalse();

      assertThat(json.getString("missing")).isNull();

      assertThat(json.getInteger("int")).isEqualTo(5);
      assertThat(json.getDouble("float")).isEqualTo(25.3);

      async.complete();
    });

  }

  @Test(expected = NullPointerException.class)
  public void testWithMissingPathInConf() {
    new GitConfigurationStoreFactory().create(vertx, new JsonObject()
        .put("no-path", "")
        .put("url", "git url")
        .put("filesets", new JsonArray()));
  }

  @Test(expected = NullPointerException.class)
  public void testWithMissingGitRepoUrlInConf() {
    new GitConfigurationStoreFactory().create(vertx, new JsonObject()
        .put("path", "target")
        .put("filesets", new JsonArray()));
  }

  @Test(expected = NullPointerException.class)
  public void testWithMissingFileSets() {
    new GitConfigurationStoreFactory().create(vertx,
        new JsonObject().put("path", "src/test/resources").put("url", "git url"));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testWithMissingPatternInAFileSet() {
    new GitConfigurationStoreFactory().create(vertx,
        new JsonObject().put("path", "src/test/resources").put("filesets", new JsonArray().add(new JsonObject().put("format", "properties"))));
  }

  @Test
  public void testName() {
    assertThat(new GitConfigurationStoreFactory().name()).isNotNull().isEqualTo("git");
  }

  @Test
  public void testWithNonExistingPath(TestContext tc) throws IOException, GitAPIException {
    add(git, root, new File("src/test/resources/files/some-text.txt"), null);
    add(git, root, new File("src/test/resources/files/regular.json"), null);
    push(git);

    Async async = tc.async();

    retriever = ConfigurationRetriever.create(vertx, new ConfigurationRetrieverOptions().addStore(new
        ConfigurationStoreOptions().setType("git").setConfig(new JsonObject()
        .put("url", bareRoot.getAbsolutePath())
        .put("path", "target/junk/do-not-exist")
        .put("filesets", new JsonArray().add(new JsonObject().put("pattern", "*.json"))))));
    retriever.getConfiguration(ar -> {
      assertThat(ar.succeeded()).isTrue();
      assertThat(ar.result()).isNotEmpty();
      async.complete();
    });
  }

  @Test(expected = IllegalArgumentException.class)
  public void testWithAPathThatIsAFile() {
    new GitConfigurationStoreFactory().create(vertx, new JsonObject()
        .put("path", "src/test/resources/files/regular.json")
        .put("url", bareRoot.getAbsolutePath())
        .put("filesets", new JsonArray().add(new JsonObject().put("pattern", "*.json"))));
  }

  @Test(expected = IllegalArgumentException.class)
  public void testWhenTheFormatIsUnknown() {
    new GitConfigurationStoreFactory().create(vertx, new JsonObject()
        .put("path", "src/test/resources/files/regular.json")
        .put("url", bareRoot.getAbsolutePath())
        .put("filesets", new JsonArray().add(new JsonObject().put("pattern", "*.json").put("format", "unknown"))));
  }

  @Test
  public void testWithoutAnExistingRepo(TestContext tc) throws IOException, GitAPIException {
    Async async = tc.async();

    retriever = ConfigurationRetriever.create(vertx, new ConfigurationRetrieverOptions().addStore(new
        ConfigurationStoreOptions().setType("git").setConfig(new JsonObject()
        .put("url", bareRoot.getAbsolutePath())
        .put("path", "target/junk/do-not-exist")
        .put("filesets", new JsonArray().add(new JsonObject().put("pattern", "*.json"))))));
    retriever.getConfiguration(ar -> {
      assertThat(ar.failed()).isTrue();
      assertThat(ar.cause().getMessage()).contains("origin", "master");
      async.complete();
    });
  }

  @Test
  public void testWith2FileSetsAndNoIntersection(TestContext tc) throws GitAPIException, IOException {
    Async async = tc.async();
    add(git, root, new File("src/test/resources/files/regular.json"), "file");
    add(git, root, new File("src/test/resources/files/a.json"), "dir");
    push(git);

    retriever = ConfigurationRetriever.create(vertx, new ConfigurationRetrieverOptions().addStore(new
        ConfigurationStoreOptions().setType("git").setConfig(new JsonObject()
        .put("url", bareRoot.getAbsolutePath())
        .put("path", "target/junk/work")
        .put("filesets", new JsonArray()
            .add(new JsonObject().put("pattern", "file/reg*.json"))
            .add(new JsonObject().put("pattern", "dir/a.*son"))
        ))));

    retriever.getConfiguration(ar -> {
      assertThat(ar.result().getString("key")).isEqualTo("value");
      assertThat(ar.result().getString("a.name")).isEqualTo("A");
      async.complete();
    });

  }

  @Test
  public void testWith2FileSetsAndWithIntersection(TestContext tc) throws GitAPIException, IOException {
    Async async = tc.async();
    add(git, root, new File("src/test/resources/files/b.json"), "dir");
    add(git, root, new File("src/test/resources/files/a.json"), "dir");
    push(git);

    retriever = ConfigurationRetriever.create(vertx, new ConfigurationRetrieverOptions().addStore(new
        ConfigurationStoreOptions().setType("git").setConfig(new JsonObject()
        .put("url", bareRoot.getAbsolutePath())
        .put("path", "target/junk/work")
        .put("filesets", new JsonArray()
            .add(new JsonObject().put("pattern", "dir/b.json"))
            .add(new JsonObject().put("pattern", "dir/a.*son"))
        ))));

    retriever.getConfiguration(ar -> {
      assertThat(ar.result().getString("a.name")).isEqualTo("A");
      assertThat(ar.result().getString("b.name")).isEqualTo("B");
      assertThat(ar.result().getString("conflict")).isEqualTo("A");
      async.complete();
    });

  }

  @Test
  public void testWith2FileSetsAndWithIntersectionReversed(TestContext tc) throws GitAPIException, IOException {
    Async async = tc.async();
    add(git, root, new File("src/test/resources/files/b.json"), "dir");
    add(git, root, new File("src/test/resources/files/a.json"), "dir");
    push(git);

    retriever = ConfigurationRetriever.create(vertx, new ConfigurationRetrieverOptions().addStore(new
        ConfigurationStoreOptions().setType("git").setConfig(new JsonObject()
        .put("url", bareRoot.getAbsolutePath())
        .put("path", "target/junk/work")
        .put("filesets", new JsonArray()
            .add(new JsonObject().put("pattern", "dir/a.*son"))
            .add(new JsonObject().put("pattern", "dir/b.json"))
        ))));

    retriever.getConfiguration(ar -> {
      assertThat(ar.result().getString("conflict")).isEqualTo("B");
      assertThat(ar.result().getString("a.name")).isEqualTo("A");
      assertThat(ar.result().getString("b.name")).isEqualTo("B");
      async.complete();
    });

  }

  @Test
  public void testWithAFileSetMatching2FilesWithConflict(TestContext tc) throws GitAPIException, IOException {
    Async async = tc.async();
    add(git, root, new File("src/test/resources/files/b.json"), "dir");
    add(git, root, new File("src/test/resources/files/a.json"), "dir");
    push(git);

    retriever = ConfigurationRetriever.create(vertx, new ConfigurationRetrieverOptions().addStore(new
        ConfigurationStoreOptions().setType("git").setConfig(new JsonObject()
        .put("url", bareRoot.getAbsolutePath())
        .put("path", "target/junk/work")
        .put("filesets", new JsonArray()
            .add(new JsonObject().put("pattern", "dir/?.*son"))
        ))));

    retriever.getConfiguration(ar -> {
      assertThat(ar.result().getString("b.name")).isEqualTo("B");
      assertThat(ar.result().getString("a.name")).isEqualTo("A");
      // Alphabetical order, so B is last.
      assertThat(ar.result().getString("conflict")).isEqualTo("B");
      async.complete();
    });

  }

  @Test
  public void testWithAFileSetMatching2FilesOneNotBeingAJsonFile(TestContext tc) throws GitAPIException, IOException {
    Async async = tc.async();
    add(git, root, new File("src/test/resources/files/a-bad.json"), "dir");
    add(git, root, new File("src/test/resources/files/a.json"), "dir");
    push(git);

    retriever = ConfigurationRetriever.create(vertx, new ConfigurationRetrieverOptions().addStore(new
        ConfigurationStoreOptions().setType("git").setConfig(new JsonObject()
        .put("url", bareRoot.getAbsolutePath())
        .put("path", "target/junk/work")
        .put("filesets", new JsonArray()
            .add(new JsonObject().put("pattern", "dir/a?*.*son"))
        ))));

    retriever.getConfiguration(ar -> {
      assertThat(ar.failed());
      assertThat(ar.cause()).isInstanceOf(DecodeException.class);
      async.complete();
    });

  }

  @Test
  public void testConfigurationUpdate() throws IOException, GitAPIException {
    add(git, root, new File("src/test/resources/files/a.json"), "dir");
    push(git);

    retriever = ConfigurationRetriever.create(vertx, new ConfigurationRetrieverOptions()
        .setScanPeriod(1000)
        .addStore(new ConfigurationStoreOptions().setType("git").setConfig(new JsonObject()
            .put("url", bareRoot.getAbsolutePath())
            .put("path", "target/junk/work")
            .put("filesets", new JsonArray()
                .add(new JsonObject().put("pattern", "dir/*.json"))
            ))));

    AtomicBoolean done = new AtomicBoolean();
    retriever.getConfiguration(ar -> {
      assertThat(ar.succeeded()).isTrue();
      assertThat(ar.result().getString("a.name")).isEqualTo("A");
      done.set(true);
    });
    await().untilAtomic(done, is(true));

    updateA();

    await().until(() ->
        "A2".equals(retriever.getCachedConfiguration().getString("a.name"))
            && "B".equalsIgnoreCase(retriever.getCachedConfiguration().getString("b.name")));
  }

  @Test
  public void testConfigurationUpdateWithMergeIssue_Commit(TestContext tc) throws IOException, GitAPIException {
    add(git, root, new File("src/test/resources/files/a.json"), "dir");
    push(git);

    retriever = ConfigurationRetriever.create(vertx, new ConfigurationRetrieverOptions()
        .setScanPeriod(1000)
        .addStore(new ConfigurationStoreOptions().setType("git").setConfig(new JsonObject()
            .put("url", bareRoot.getAbsolutePath())
            .put("path", "target/junk/work")
            .put("filesets", new JsonArray()
                .add(new JsonObject().put("pattern", "dir/*.json"))
            ))));

    AtomicBoolean done = new AtomicBoolean();
    retriever.getConfiguration(ar -> {
      assertThat(ar.succeeded()).isTrue();
      assertThat(ar.result().getString("a.name")).isEqualTo("A");
      done.set(true);
    });
    await().untilAtomic(done, is(true));


    // Edit the file in the work dir
    File a = new File("target/junk/work/dir/a.json");
    assertThat(a).isFile();
    FileUtils.write(a, new JsonObject().put("a.name", "A").put("conflict", "A").put("added", "added")
            .encodePrettily(), Charsets.UTF_8);
    git.add().addFilepattern("dir/a.json").call();
    git.commit().setMessage("update A").setAuthor("clement", "clement@apache.org")
        .setCommitter("clement", "clement@apache.org").call();

    done.set(false);
    retriever.getConfiguration(ar -> {
      assertThat(ar.succeeded()).isTrue();
      assertThat(ar.result().getString("a.name")).isEqualTo("A");
      assertThat(ar.result().getString("added")).isEqualTo("added");
      done.set(true);
    });
    await().untilAtomic(done, is(true));

    updateA();

    Async async = tc.async();
    retriever.getConfiguration(ar -> {
      assertThat(ar.succeeded()).isFalse();
      assertThat(ar.cause().getMessage()).contains("conflict");
      async.complete();
    });
  }

  @Test
  public void testConfigurationUpdateWithMergeIssue_Edit(TestContext tc) throws IOException, GitAPIException {
    add(git, root, new File("src/test/resources/files/a.json"), "dir");
    push(git);

    retriever = ConfigurationRetriever.create(vertx, new ConfigurationRetrieverOptions()
        .setScanPeriod(1000)
        .addStore(new ConfigurationStoreOptions().setType("git").setConfig(new JsonObject()
            .put("url", bareRoot.getAbsolutePath())
            .put("path", "target/junk/work")
            .put("filesets", new JsonArray()
                .add(new JsonObject().put("pattern", "dir/*.json"))
            ))));

    AtomicBoolean done = new AtomicBoolean();
    retriever.getConfiguration(ar -> {
      assertThat(ar.succeeded()).isTrue();
      assertThat(ar.result().getString("a.name")).isEqualTo("A");
      done.set(true);
    });
    await().untilAtomic(done, is(true));


    // Edit the file in the work dir
    File a = new File("target/junk/work/dir/a.json");
    assertThat(a).isFile();
    FileUtils.write(a, new JsonObject().put("a.name", "A-modified").put("conflict", "A").encodePrettily(), Charsets.UTF_8);

    done.set(false);
    retriever.getConfiguration(ar -> {
      assertThat(ar.succeeded()).isTrue();
      assertThat(ar.result().getString("a.name")).isEqualTo("A-modified");
      done.set(true);
    });
    await().untilAtomic(done, is(true));

    updateA();

    Async async = tc.async();
    retriever.getConfiguration(ar -> {
      assertThat(ar.succeeded()).isFalse();
      assertThat(ar.cause().getMessage()).contains("conflict");
      async.complete();
    });
  }

  @Test
  public void testUsingAnExistingRepo() throws IOException, GitAPIException {
    git.close();
    root = new File("target/junk/work");
    git = connect(bareRoot, root);
    add(git, root, new File("src/test/resources/files/a.json"), "dir");
    push(git);

    retriever = ConfigurationRetriever.create(vertx, new ConfigurationRetrieverOptions()
        .setScanPeriod(1000)
        .addStore(new ConfigurationStoreOptions().setType("git").setConfig(new JsonObject()
            .put("url", bareRoot.getAbsolutePath())
            .put("path", "target/junk/work")
            .put("filesets", new JsonArray()
                .add(new JsonObject().put("pattern", "dir/*.json"))
            ))));

    AtomicBoolean done = new AtomicBoolean();
    retriever.getConfiguration(ar -> {
      assertThat(ar.succeeded()).isTrue();
      assertThat(ar.result().getString("a.name")).isEqualTo("A");
      done.set(true);
    });
    await().untilAtomic(done, is(true));

    updateA();

    await().until(() ->
        "A2".equals(retriever.getCachedConfiguration().getString("a.name"))
            && "B".equalsIgnoreCase(retriever.getCachedConfiguration().getString("b.name")));
  }

  @Test
  public void testWithExistingRepoOnTheWrongBranch() throws Exception {
    git.close();
    root = new File("target/junk/work");
    git = connect(bareRoot, root);
    add(git, root, new File("src/test/resources/files/a.json"), "dir");
    push(git);
    branch = "dev";
    add(git, root, new File("src/test/resources/files/b.json"), "dir");
    push(git);

    retriever = ConfigurationRetriever.create(vertx, new ConfigurationRetrieverOptions()
        .setScanPeriod(1000)
        .addStore(new ConfigurationStoreOptions().setType("git").setConfig(new JsonObject()
            .put("url", bareRoot.getAbsolutePath())
            .put("path", "target/junk/work")
            .put("filesets", new JsonArray()
                .add(new JsonObject().put("pattern", "dir/*.json"))
            ))));

    AtomicBoolean done = new AtomicBoolean();
    retriever.getConfiguration(ar -> {
      assertThat(ar.succeeded()).isTrue();
      assertThat(ar.result().getString("a.name")).isEqualTo("A");
      done.set(true);
    });
    await().untilAtomic(done, is(true));

    updateA();

    await().until(() ->
        "A2".equals(retriever.getCachedConfiguration().getString("a.name"))
            && "B".equalsIgnoreCase(retriever.getCachedConfiguration().getString("b.name")));
  }

  private void updateA() {
    try {
      add(git, root, new File("src/test/resources/files/b.json"), "dir");
      FileUtils.copyFile(new File("src/test/resources/files/a-v2.json"), new File(root, "dir/a.json"));

      git.add()
          .addFilepattern("dir/a.json")
          .call();

      git.commit()
          .setAuthor("clement", "clement@apache.org")
          .setCommitter("clement", "clement@apache.org")
          .setMessage("Update a.json")
          .call();

      push(git);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void push(Git git) throws GitAPIException {
    git.push()
        .setRemote(remote)
        .setForce(true)
        .call();
  }


  private Git connect(File bareRoot, File root) throws MalformedURLException, GitAPIException {
    return Git.cloneRepository()
        .setURI(bareRoot.getAbsolutePath())
        .setRemote(remote)
        .setDirectory(root).call();
  }

  private Git createBareRepository(File root) throws GitAPIException {
    return Git.init().setDirectory(root).setBare(true).call();
  }

  private GitConfigurationStoreTest add(Git git, File root, File file, String directory) throws IOException, GitAPIException {
    if (!file.isFile()) {
      throw new RuntimeException("File not found " + file.getAbsolutePath());
    }

    if (!"master".equalsIgnoreCase(git.getRepository().getBranch())) {
      git.checkout()
          .setCreateBranch(true)
          .setName("master")
          .call();
    }

    if (!branch.equalsIgnoreCase(git.getRepository().getBranch())) {
      boolean create = true;
      for (Ref ref : git.branchList().call()) {
        if (ref.getName().equals("refs/heads/" + branch)) {
          create = false;
        }
      }
      git.checkout()
          .setCreateBranch(create)
          .setName(branch)
          .call();
    }

    String relative;
    if (directory != null) {
      relative = directory + File.separator + file.getName();
    } else {
      relative = file.getName();
    }

    File output = new File(root, relative);
    if (output.exists()) {
      output.delete();
    }
    if (!output.getParentFile().isDirectory()) {
      output.getParentFile().mkdirs();
    }

    FileUtils.copyFile(file, output);

    git.add()
        .addFilepattern(relative)
        .call();

    git.commit()
        .setAuthor("clement", "clement@apache.org")
        .setCommitter("clement", "clement@apache.org")
        .setMessage("Add " + relative)
        .call();

    return this;
  }

}