package org.obolibrary.robot;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.*;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.jena.query.Dataset;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.tdb.TDBFactory;
import org.semanticweb.owlapi.model.OWLOntology;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles inputs and outputs for the {@link QueryOperation}.
 *
 * @author <a href="mailto:james@overton.ca">James A. Overton</a>
 */
public class QueryCommand implements Command {
  /** Logger. */
  private static final Logger logger = LoggerFactory.getLogger(QueryCommand.class);

  /** Namespace for error messages. */
  private static final String NS = "query#";

  /** Error message when update file provided does not exist. */
  private static final String missingFileError = NS + "MISSING FILE ERROR file '%s' does not exist";

  /** Error message when a query is not provided */
  private static final String missingQueryError =
      NS + "MISSING QUERY ERROR at least one query must be provided";

  /** Error message when --tdb is true but the input is not RDF/XML (including OWL) or TTL */
  protected static final String tdbFormatError =
      NS + "TDB FORMAT ERROR input file must be owl, rdf, or ttl.";

  /** Store the command-line options for the command. */
  private Options options;

  /** Initialze the command. */
  public QueryCommand() {
    Options o = CommandLineHelper.getCommonOptions();
    o.addOption("i", "input", true, "load ontology from a file");
    o.addOption("I", "input-iri", true, "load ontology from an IRI");
    o.addOption("f", "format", true, "the query result format: CSV, TSV," + " TTL, JSONLD, etc.");
    o.addOption("o", "output", true, "save updated ontology to a file");
    o.addOption("O", "output-dir", true, "Directory for output");
    o.addOption("g", "use-graphs", true, "if true, load imports as named graphs");
    o.addOption("u", "update", true, "run a SPARQL UPDATE");
    o.addOption("t", "tdb", true, "if true, load RDF/XML or TTL onto disk");
    o.addOption("k", "keep-tdb-mappings", true, "if true, do not remove the TDB directory");
    o.addOption("d", "tdb-directory", true, "directory to put TDB mappings (default: .tdb)");

    Option opt;

    opt = new Option("s", "select", true, "run a SPARQL SELECT query (deprecated)");
    opt.setArgs(2);
    o.addOption(opt);

    opt = new Option("c", "construct", true, "run a SPARQL CONSTRUCT query (deprecated)");
    opt.setArgs(2);
    o.addOption(opt);

    opt = new Option("q", "query", true, "run a SPARQL query");
    opt.setArgs(2);
    o.addOption(opt);

    opt = new Option("Q", "queries", true, "verify one or more SPARQL queries");
    opt.setArgs(Option.UNLIMITED_VALUES);
    o.addOption(opt);

    options = o;
  }

  /**
   * Name of the command.
   *
   * @return name
   */
  public String getName() {
    return "query";
  }

  /**
   * Brief description of the command.
   *
   * @return description
   */
  public String getDescription() {
    return "query an ontology";
  }

  /**
   * Command-line usage for the command.
   *
   * @return usage
   */
  public String getUsage() {
    return "robot query --input <file> --query <query> <output>";
  }

  /**
   * Command-line options for the command.
   *
   * @return options
   */
  public Options getOptions() {
    return options;
  }

  /**
   * Handle the command-line and file operations for the QueryOperation.
   *
   * @param args strings to use as arguments
   */
  public void main(String[] args) {
    try {
      execute(null, args);
    } catch (Exception e) {
      CommandLineHelper.handleException(e);
    }
  }

  /**
   * Given an input state and command line arguments, query the ontolgy. The input ontology is not
   * changed.
   *
   * @param state the state from the previous command, or null
   * @param args the command-line arguments
   * @return the unchanged state
   * @throws Exception on any problem
   */
  public CommandState execute(CommandState state, String[] args) throws Exception {
    CommandLine line = CommandLineHelper.getCommandLine(getUsage(), getOptions(), args);
    if (line == null) {
      return null;
    }

    IOHelper ioHelper = CommandLineHelper.getIOHelper(line);

    // If an update(s) are provided, run then return the OWLOntology
    // This is different than the rest of the Query operations because it returns an ontology
    // Whereas the others return query results
    List<String> updatePaths = CommandLineHelper.getOptionalValues(line, "update");
    if (!updatePaths.isEmpty()) {
      state = CommandLineHelper.updateInputOntology(ioHelper, state, line);
      OWLOntology inputOntology = state.getOntology();

      OWLOntology outputOntology = executeUpdate(state, inputOntology, ioHelper, updatePaths);
      CommandLineHelper.maybeSaveOutput(line, outputOntology);
      state.setOntology(outputOntology);
      return state;
    }

    List<List<String>> queries = getQueries(line);

    boolean useTDB = CommandLineHelper.getBooleanValue(line, "tdb", false);
    if (useTDB) {
      // DOES NOT UPDATE STATE
      // This will not work with chained commands as it uses the `--input` option
      // Updating the state results in loading the ontology to memory
      executeOnDisk(line, queries);
    } else {
      state = CommandLineHelper.updateInputOntology(ioHelper, state, line);
      executeInMemory(line, state.getOntology(), queries);
    }

    return state;
  }

  /**
   * Given a command line, an ontology, and a list of queries, run the queries over the ontology
   * with any options.
   *
   * @param line CommandLine with options
   * @param inputOntology OWLOntology to query
   * @param queries List of queries
   * @throws Exception on issue loading ontology or running queries
   */
  private static void executeInMemory(
      CommandLine line, OWLOntology inputOntology, List<List<String>> queries) throws Exception {
    boolean useGraphs = CommandLineHelper.getBooleanValue(line, "use-graphs", false);
    Dataset dataset = QueryOperation.loadOntologyAsDataset(inputOntology, useGraphs);
    try {
      runQueries(line, dataset, queries);
    } finally {
      dataset.close();
      TDBFactory.release(dataset);
    }
  }

  /**
   * Given a command line and a list of queries, execute 'query' using TDB and writing mappings to
   * disk.
   *
   * @param line CommandLine with options
   * @param queries List of queries
   * @throws IOException on problem running queries
   */
  private static void executeOnDisk(CommandLine line, List<List<String>> queries)
      throws IOException {
    String inputPath =
        CommandLineHelper.getRequiredValue(line, "input", "an input is required for TDB");
    String tdbDir = CommandLineHelper.getDefaultValue(line, "tdb-directory", ".tdb");
    boolean keepMappings = CommandLineHelper.getBooleanValue(line, "keep-tdb-mappings", false);
    Dataset dataset;
    if (inputPath.endsWith(".rdf") || inputPath.endsWith(".owl")) {
      dataset = QueryOperation.loadTriplesAsDataset(inputPath, tdbDir);
    } else if (inputPath.endsWith(".ttl")) {
      dataset = QueryOperation.loadTriplesAsDataset(inputPath, tdbDir);
    } else {
      throw new IllegalArgumentException(tdbFormatError);
    }
    try {
      runQueries(line, dataset, queries);
    } finally {
      dataset.close();
      TDBFactory.release(dataset);
      if (!keepMappings) {
        boolean success = clean(tdbDir);
        if (!success) {
          logger.error(String.format("Unable to remove directory '%s'", tdbDir));
        }
      }
    }
  }

  /**
   * Given an updated command state, an input ontology, an IOHelper, and a list of paths to update
   * queries, run the updates on the input ontology and return an updated ontology.
   *
   * @param state the current state
   * @param inputOntology the ontology to update
   * @param ioHelper IOHelper to handle loading OWLOntology objects
   * @param updatePaths paths to update queries
   * @return updated OWLOntology
   * @throws Exception on file or ontology loading issues
   */
  private static OWLOntology executeUpdate(
      CommandState state, OWLOntology inputOntology, IOHelper ioHelper, List<String> updatePaths)
      throws Exception {
    Map<String, String> updates = new LinkedHashMap<>();
    for (String updatePath : updatePaths) {
      File f = new File(updatePath);
      if (!f.exists()) {
        throw new Exception(String.format(missingFileError, updatePath));
      }
      updates.put(f.getPath(), FileUtils.readFileToString(f));
    }

    // Load the ontology as a model, ignoring imports
    Model model = QueryOperation.loadOntologyAsModel(inputOntology);

    // Execute the updates
    for (Map.Entry<String, String> update : updates.entrySet()) {
      logger.debug(String.format("Running update '%s'", update.getKey()));
      QueryOperation.execUpdate(model, update.getValue());
    }

    // Re-load the updated model as an OWLOntology
    // We need to handle imports while loading
    // User may have specified a path to a catalog in the CLI options
    // Check for this path in state, or check for ontology path in state to guess catalog
    String catalogPath = state.getCatalogPath();
    if (catalogPath == null) {
      String ontologyPath = state.getOntologyPath();
      // If loading from IRI, ontologyPath might be null
      // In which case, we cannot get a catalog
      if (ontologyPath == null) {
        catalogPath = null;
      } else {
        File catalogFile = ioHelper.guessCatalogFile(new File(ontologyPath));
        if (catalogFile != null) {
          catalogPath = catalogFile.getPath();
        }
      }
    }
    // Make sure the file exists
    if (catalogPath != null) {
      File catalogFile = new File(catalogPath);
      if (!catalogFile.exists()) {
        // If it does not, set the path to null
        catalogPath = null;
      }
    }
    return QueryOperation.convertModel(model, ioHelper, catalogPath);
  }

  /**
   * Given a command line, get a list of queries.
   *
   * @param line CommandLine with options
   * @return List of queries
   */
  private static List<List<String>> getQueries(CommandLine line) {
    // Collect all queries as (queryPath, outputPath) pairs.
    List<List<String>> queries = new ArrayList<>();
    List<String> qs = CommandLineHelper.getOptionalValues(line, "query");
    for (int i = 0; i < qs.size(); i += 2) {
      queries.add(qs.subList(i, i + 2));
    }
    qs = CommandLineHelper.getOptionalValues(line, "select");
    for (int i = 0; i < qs.size(); i += 2) {
      queries.add(qs.subList(i, i + 2));
    }
    qs = CommandLineHelper.getOptionalValues(line, "construct");
    for (int i = 0; i < qs.size(); i += 2) {
      queries.add(qs.subList(i, i + 2));
    }
    qs = CommandLineHelper.getOptionalValues(line, "queries");
    for (String q : qs) {
      List<String> xs = new ArrayList<>();
      xs.add(q);
      xs.add(null);
      queries.add(xs);
    }
    if (queries.isEmpty()) {
      throw new IllegalArgumentException(missingQueryError);
    }
    return queries;
  }

  /**
   * Given a command line, a dataset to query, and a list of queries, run the queries with any
   * options from the command line.
   *
   * @param line CommandLine with options
   * @param dataset Dataset to run queries on
   * @param queries List of queries
   * @throws IOException on issue reading or writing files
   */
  private static void runQueries(CommandLine line, Dataset dataset, List<List<String>> queries)
      throws IOException {
    String format = CommandLineHelper.getOptionalValue(line, "format");
    String outputDir = CommandLineHelper.getDefaultValue(line, "output-dir", "");

    for (List<String> q : queries) {
      String queryPath = q.get(0);
      String outputPath = q.get(1);

      String query = FileUtils.readFileToString(new File(queryPath));

      String formatName = format;
      if (formatName == null) {
        if (outputPath == null) {
          formatName = QueryOperation.getDefaultFormatName(query);
        } else {
          formatName = FilenameUtils.getExtension(outputPath);
        }
      }

      if (outputPath == null) {
        String fileName = FilenameUtils.getBaseName(queryPath) + "." + formatName;
        outputPath = new File(outputDir).toPath().resolve(fileName).toString();
      }

      OutputStream output = new FileOutputStream(outputPath);
      QueryOperation.runSparqlQuery(dataset, query, formatName, output);
    }
  }

  /**
   * Given a directory containing TDB mappings, remove the files and directory. If successful,
   * return true.
   *
   * @param tdbDir directory to remove
   * @return boolean indicating success
   */
  protected static boolean clean(String tdbDir) {
    File dir = new File(tdbDir);
    boolean success = true;
    if (dir.exists()) {
      String[] files = dir.list();
      if (files != null) {
        for (String file : files) {
          File f = new File(dir.getPath(), file);
          success = f.delete();
        }
      }
      // Only delete if all the files in dir were deleted
      if (success) {
        success = dir.delete();
      }
    }
    return success;
  }
}
