package flightapp;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.junit.*;
import org.junit.runner.*;
import org.junit.runners.*;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.*;
import java.sql.*;

import static org.junit.Assert.assertTrue;

/**
 * Autograder for the transaction assignment
 */
@RunWith(Parameterized.class)
public class FlightServiceTest {
  BufferedWriter report;

  /**
   * Maximum number of concurrent users we will be testing
   */
  private static final int MAX_USERS = 5;
  /**
   * Max time in seconds to wait for a response for a user
   */
  private static final int RESPONSE_TIME = 60;
  /**
   * Thread pool used to run different users
   */
  private static ExecutorService pool;

  /**
   * Denotes a comment
   */
  static final String COMMENTS = "#";
  /**
   * Denotes information mode change
   */
  static final String DELIMITER = "*";
  /**
   * Denotes alternate result
   */
  static final String SEPARATOR = "|";
  /**
   * Denotes public test
   */
  static final String PUBLIC_TEST_MARKER = "public_test_case";

  private static final Set<String> PUBLIC_TEST_LIST = new HashSet<>();

  // Whether to dump detailed failure messages in the test assertion for
  // ALL tests, or just the ones annotated with PUBLIC_TEST_MARKER.
  private static boolean publicTestsOnly = false;
  
  /**
   * Models a single user. Callable from a thread.
   */
  static class User implements Callable<String> {
    private Query q;
    private List<String> cmds; // commands that this user will execute
    private List<String> results; // the expected results from those commands

    public User(List<String> cmds, List<String> results) throws IOException, SQLException {
      this.q = createTestQuery();
      this.cmds = cmds;
      this.results = results;
    }

    public List<String> results() {
      return results;
    }

    @Override
    public String call() {
      StringBuffer sb = new StringBuffer();
      for (String cmd : cmds) {
        sb.append(FlightService.execute(q, cmd));
      }

      return sb.toString();
    }

    public void shutdown() throws Exception {
      this.q.closeConnection();
    }
  }

  /**
   * Parse the input test case. Format expected is
   *
   * @param filename test case's path and file name
   * @return new User objects with commands to run and expected results
   * @throws Exception
   */
  static List<User> parse(String filename) throws IOException, SQLException {
    List<User> users = new ArrayList<>();
    List<String> cmds = new ArrayList<>();
    List<String> results = new ArrayList<>();
    String r = "";
    boolean isCmd = true;
    BufferedReader reader = new BufferedReader(new FileReader(filename));
    String l;
    int lineNumber = 0;
    while ((l = reader.readLine()) != null) {
      lineNumber++;

      // Skip blank and comment lines
      if (l.startsWith(COMMENTS)) {
        String line = l.substring(1).trim();
        String[] tokens = line.split("\\s+");
        if (tokens[0].equals(PUBLIC_TEST_MARKER)) {
          PUBLIC_TEST_LIST.add(filename);
        }
        continue;

      // Switch between recording commands and recording results
      } else if (l.startsWith(DELIMITER)) {
        if (isCmd) {
          isCmd = false;
        } else {
          // Result recordings finished for a user so user is fully specified
          results.add(r);
          users.add(new User(cmds, results));
          cmds = new ArrayList<>();
          results = new ArrayList<>();
          r = "";
          isCmd = true;
        }

        // Record an alternate outcome result
      } else if (l.startsWith(SEPARATOR)) {
        if (isCmd) {
          reader.close();
          throw new IllegalArgumentException(String.format(
            "Input file %s is malformatted on line: %d",
            filename, lineNumber));
        } else {
          results.add(r);
          r = "";
        }
      } else if (l.trim().isEmpty()) {
        continue;
      } else {
        // Build command list or result string

        // Ignore trailing comments
        l = l.split(COMMENTS, 2)[0];
        
        // Add new command or build current result
        if (isCmd) {
          cmds.add(l);
        } else {
          r = r + l + "\n";
        }
      }
    }
    reader.close();

    // Everything should be parsed by now and put into user objects
    if (cmds.size() > 0 || r.length() > 0 || results.size() > 0) {
      throw new IllegalArgumentException(
          String.format("Input file %s is malformatted, extra information found."
                        + "  #commands=%s, len(result)=%s, #results=%s",
                        filename, cmds.size(), r.length(), results.size()));
    }

    // check that all users have the same number of possible scenarios
    int n = users.get(0).results().size();
    for (int i = 1; i < users.size(); ++i) {
      int m = users.get(i).results().size();
      if (m != n) {
        throw new IllegalArgumentException(String.format(
            "Input file %s is malformed, user %s should have %s possible results rather than %s",
            filename, i, n, m));
      }
    }

    return users;
  }

  /**
   * Creates the thread pool to execute test cases with multiple users.
   */
  @BeforeClass
  public static void setup() {
    System.out.println("Running test setup...");

    pool = Executors.newFixedThreadPool(MAX_USERS);
    
    try {
      System.out.println("... using dbconn.properties for test credentials");
      Connection conn = DBConnUtils.openConnection();

      // We drop the tables instead of asking students to submit a dropTables.sql because we
      // don't trust them to drop tables correctly :)
      //
      // Basically, we identify student-created tables by querying for every table in the DB
      // (optionally specifying a tablename suffix) and deleting everything that's not our
      // four domain tables (ie, FLIGHTS, CARRIERS, MONTHS, WEEKDAYS).
      //
      // TODO(hctang): in 22au, we ran into an issue where a simple "DROP TABLE x" would time
      // out.  If this happens again, we should update the test instructions to tell students
      // to disable table resetting.
      boolean dropTables = System.getProperty("flightapp.droptables", "true")
        .equalsIgnoreCase("true");
      if (dropTables) {
        String tableSuffix = DBConnUtils.getTableSuffix();
        if (tableSuffix != null) {
          System.out.println("... resetting database (ie, dropping all tables with suffix: "
                             + tableSuffix + ")");
        } else {
          System.out.println("... fully resetting database (ie, dropping everything except "
                             + "domain tables)");
        }
        TestUtils.dropTablesWithOptionalSuffix(conn, tableSuffix);

        System.out.println("... running createTables.sql");
        TestUtils.runCreateTables(conn);
      } else {
        System.out.println("... not resetting student-created tables [WARNING!  WARNING!]");
      }

      TestUtils.checkTables(conn);
      conn.close();
    } catch (Exception e) {
      System.err.println("Failed to drop tables and/or run createTables.sql");
      e.printStackTrace(System.out);
      System.exit(1);
    }

    publicTestsOnly = System.getProperty("public_test_only", "false").equalsIgnoreCase("true");

    String reportPath = System.getProperty("report_pass");
    if (reportPath != null) {
      FileUtils.deleteQuietly(new File(reportPath));
    }

    System.out.println("\nStarting tests");
  }

  /**
   * A file that will be parsed as a test case scenario
   */
  protected String file;

  /**
   * Initialize a test case with a file name
   */
  public FlightServiceTest(String file) {
    this.file = file;
  }

  /**
   * Gets test case scenario files from the specified folder.
   */
  @Parameterized.Parameters
  public static Collection<String> files() throws IOException {
    String pathString = System.getProperty("test.cases");
    return Arrays.stream(pathString.split(":", -1)).map(Paths::get).flatMap(path -> {
      try {
        if (Files.isDirectory(path)) {
          try (Stream<Path> paths = Files.walk(path, 5, FileVisitOption.FOLLOW_LINKS)) {
            return paths.filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".txt")).map(p -> {
                  try {
                    return p.toFile().getCanonicalPath().toString();
                  } catch (IOException e) {
                    return null;
                  }
                }).filter(p -> p != null).collect(Collectors.toList()).stream();
          }
        } else if (Files.isRegularFile(path)) {
          return Stream.of(path.toFile().getCanonicalPath().toString());
        } else {
          System.err.println(path + " does not exists.");
        }
      } catch (Exception e) {
        return Stream.empty();
      }
      return Stream.empty();
    }).collect(Collectors.toCollection(LinkedHashSet::new));
  }

  public static Query createTestQuery() throws SQLException, IOException {
    return new Query();
  }

  @Before
  public void clearDB() throws SQLException, IOException {
    Query query = createTestQuery();
    query.clearTables();
    query.closeConnection();

    String reportPath = System.getProperty("report_pass");

    if (reportPath != null) {
      report = new BufferedWriter(new FileWriter(reportPath, true));
    }
  }

  @After
  public void after() throws SQLException, IOException {
    if (report != null) {
      report.close();
      report = null;
    }
  }

  /**
   * Runs the test case scenario
   */
  @Test
  public void runTest() throws Exception {
    System.out.println("Running test file: " + this.file);

    // Loads the scenario and initializes users
    List<User> users = parse(this.file);
    List<Future<String>> futures = new ArrayList<>();
    for (User user : users) {
      futures.add(pool.submit(user));
    }

    try {
      // Waits for an output for each user
      List<String> outputs = new ArrayList<>();
      long waitTime = RESPONSE_TIME * futures.size();
      long endTime = System.currentTimeMillis() + waitTime * 1000;
      for (Future<String> f : futures) {
        try {
          outputs.add(f.get(waitTime, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
          System.out.println("Timed out!");
        } finally {
          waitTime = (endTime - System.currentTimeMillis()) / 1000;
          waitTime = waitTime <= 0 ? 1 : waitTime;
        }
      }

      // For each possible outcome, check if each user matches the respective output
      // for the given outcome
      boolean passed = false;
      Map<Integer, List<String>> outcomes = new HashMap<Integer, List<String>>();
      int n = users.get(0).results().size(); // number of possible outcomes
      for (int i = 0; i < n; ++i) {
        boolean isSame = true;
        for (int j = 0; j < users.size(); ++j) {
          isSame = isSame && outputs.get(j).equals(users.get(j).results().get(i));
          if (!outcomes.containsKey(i)) {
            outcomes.put(i, new ArrayList<String>());
          }
          outcomes.get(i).add(users.get(j).results().get(i));
        }
        passed = passed || isSame;
      }

      // Print the result and debugging info (if applicable) under the assertion
      String error_message = "";
      if (!passed) {
        if (publicTestsOnly && !PUBLIC_TEST_LIST.contains(file)) {
          error_message = String.format("Failed: %s. No output since this test is private.",
                                        this.file);
        } else {
          String outcomesFormatted = "";
          for (Map.Entry<Integer, List<String>> outcome : outcomes.entrySet()) {
            outcomesFormatted += "===== Outcome " + outcome.getKey() + " =====\n";
            outcomesFormatted += formatOutput(outcome.getValue()) + "\n";
          }
          error_message = String.format(
            "Failed: %s. Actual outcome were: \n%s\n\nPossible outcomes were: \n%s\n",
            this.file, formatOutput(outputs), outcomesFormatted);
        }
      } else {
        if (report != null) {
          report.write(FilenameUtils.separatorsToUnix(this.file));
          report.newLine();
        }
      }

      assertTrue(error_message, passed);
    } catch (Exception e) {
      System.out.println("failed");
      e.printStackTrace(System.out);
      throw e;
    } finally {
      // Cleanup
      for (User u : users) {
        u.shutdown();
      }
    }
  }

  public static String formatOutput(List<String> output) {
    StringBuilder sb = new StringBuilder();
    int i = 0;
    for (String s : output) {
      sb.append("---Terminal " + i + " begin\n");
      sb.append(s);
      sb.append("---Terminal " + i + " end\n");
      ++i;
    }

    return sb.toString();
  }
}
