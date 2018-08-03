package org.obolibrary.robot;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.*;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.PosixParser;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOExceptionWithCause;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.geneontology.reasoner.ExpressionMaterializingReasonerFactory;
import org.semanticweb.elk.owlapi.ElkReasonerFactory;
import org.semanticweb.owlapi.model.IRI;
import org.semanticweb.owlapi.model.OWLAnnotationAxiom;
import org.semanticweb.owlapi.model.OWLAxiom;
import org.semanticweb.owlapi.model.OWLClassAssertionAxiom;
import org.semanticweb.owlapi.model.OWLDeclarationAxiom;
import org.semanticweb.owlapi.model.OWLDisjointClassesAxiom;
import org.semanticweb.owlapi.model.OWLDisjointDataPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLDisjointObjectPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLDisjointUnionAxiom;
import org.semanticweb.owlapi.model.OWLEquivalentClassesAxiom;
import org.semanticweb.owlapi.model.OWLEquivalentDataPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLEquivalentObjectPropertiesAxiom;
import org.semanticweb.owlapi.model.OWLLogicalAxiom;
import org.semanticweb.owlapi.model.OWLOntology;
import org.semanticweb.owlapi.model.OWLSubAnnotationPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubClassOfAxiom;
import org.semanticweb.owlapi.model.OWLSubDataPropertyOfAxiom;
import org.semanticweb.owlapi.model.OWLSubObjectPropertyOfAxiom;
import org.semanticweb.owlapi.reasoner.OWLReasonerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.ac.manchester.cs.jfact.JFactFactory;

/** Convenience methods for working with command line options. */
public class CommandLineHelper {
  /** Logger. */
  private static final Logger logger = LoggerFactory.getLogger(CommandLineHelper.class);

  /** Namespace for general input error messages. */
  private static final String NS = "errors#";

  /** Error message when --axioms is not a valid AxiomType. Expects: input string. */
  private static final String axiomTypeError = NS + "AXIOM TYPE ERROR %s is not a valid axiom type";

  /** Error message when a boolean value is not "true" or "false". Expects option name. */
  private static final String booleanValueError =
      NS + "BOOLEAN VALUE ERROR arg for %s must be true or false";

  /** Error message when --input is provided in a chained command. */
  private static final String chainedInputError =
      NS + "CHAINED INPUT ERROR do not use an --input option for chained commands";

  /** Error message when an invalid IRI is provided. Expects the entry field and term. */
  private static final String invalidIRIError =
      NS + "INVALID IRI ERROR %1$s \"%2$s\" is not a valid CURIE or IRI";

  /** Error message when user provides an invalid reasoner. Expects reasonerName in formatting. */
  private static final String invalidReasonerError =
      NS + "INVALID REASONER ERROR unknown reasoner: %s";

  /** Error message when no input ontology is provided for methods that accept multiple inputs. */
  private static final String missingRequirementError = NS + "MISSING REQUIREMENT ERROR %s";

  /** Error message when no input ontology is provided. */
  private static final String missingInputError = NS + "MISSING INPUT ERROR an --input is required";

  /** Error message when no input ontology is provided for methods that accept multiple inputs. */
  private static final String missingInputsError =
      NS + "MISSING INPUT ERROR at least one --input is required";

  /** Error message when input terms are not provided. */
  private static final String missingTermsError =
      NS + "MISSING TERMS ERROR term(s) are required with --term or --term-file";

  /** Error message when more than one --input is specified, excluding merge and unmerge. */
  private static final String multipleInputsError =
      NS + "MULITPLE INPUTS ERROR only one --input is allowed";

  /** Error message when the --inputs pattern does not include * or ?, or is not quoted */
  private static final String wildcardError =
      NS + "WILDCARD ERROR --inputs argument must be a quoted wildcard pattern";

  /**
   * Given a single string, return a list of strings split at whitespace but allowing for quoted
   * values, as a command-line parser does.
   *
   * @param toProcess the string to parse
   * @return a string list, split at whitespace, with quotation marks removed
   * @throws Exception on parsing problems
   */
  public static List<String> parseArgList(String toProcess) throws Exception {
    return new ArrayList<>(Arrays.asList(parseArgs(toProcess)));
  }

  /**
   * Given a single string, return an array of strings split at whitespace but allowing for quoted
   * values, as a command-line parser does. Adapted from org.apache.tools.ant.types.Commandline
   *
   * @param toProcess the string to parse
   * @return a string array, split at whitespace, with quotation marks removed
   * @throws Exception on parsing problems
   */
  public static String[] parseArgs(String toProcess) throws Exception {
    if (toProcess == null || toProcess.length() == 0) {
      return new String[0];
    }

    // parse with a simple finite state machine
    final int normal = 0;
    final int inQuote = 1;
    final int inDoubleQuote = 2;
    int state = normal;
    StringTokenizer tok = new StringTokenizer(toProcess, "\"\' ", true);
    Vector<String> v = new Vector<>();
    StringBuffer current = new StringBuffer();
    boolean lastTokenHasBeenQuoted = false;

    while (tok.hasMoreTokens()) {
      String nextTok = tok.nextToken();
      switch (state) {
        case inQuote:
          if ("\'".equals(nextTok)) {
            lastTokenHasBeenQuoted = true;
            state = normal;
          } else {
            current.append(nextTok);
          }
          break;
        case inDoubleQuote:
          if ("\"".equals(nextTok)) {
            lastTokenHasBeenQuoted = true;
            state = normal;
          } else {
            current.append(nextTok);
          }
          break;
        default:
          if ("\'".equals(nextTok)) {
            state = inQuote;
          } else if ("\"".equals(nextTok)) {
            state = inDoubleQuote;
          } else if (" ".equals(nextTok)) {
            if (lastTokenHasBeenQuoted || current.length() != 0) {
              v.addElement(current.toString());
              current = new StringBuffer();
            }
          } else {
            current.append(nextTok);
          }
          lastTokenHasBeenQuoted = false;
          break;
      }
    }

    if (lastTokenHasBeenQuoted || current.length() != 0) {
      v.addElement(current.toString());
    }

    if (state == inQuote || state == inDoubleQuote) {
      throw new Exception("unbalanced quotes in " + toProcess);
    }

    String[] args = new String[v.size()];
    v.copyInto(args);
    return args;
  }

  /**
   * Given a command line, return the values for an option as a list. This is just a convenience
   * function for turning an array into a list.
   *
   * @param line the command line to use
   * @param name the name of the option to find
   * @return the option values as a list of strings, maybe empty
   */
  public static List<String> getOptionValues(CommandLine line, String name) {
    List<String> valueList = new ArrayList<>();
    String[] valueArray = line.getOptionValues(name);
    if (valueArray != null) {
      valueList = new ArrayList<>(Arrays.asList(valueArray));
    }
    return valueList;
  }

  /**
   * Given a command line and an index, return the argument at that index.
   *
   * @param line the command line to use
   * @param index the index of the argument
   * @return the argument at the given index
   */
  public static String getIndexValue(CommandLine line, int index) {
    String result = null;
    List<String> arguments = Arrays.asList(line.getArgs());
    if (arguments.size() > index) {
      result = arguments.get(index);
    }
    return result;
  }

  /**
   * Given a command line, return its first argument, which should be the name of the command to
   * execute.
   *
   * @param line the command line to use
   * @return the name of the command to execute
   */
  public static String getCommand(CommandLine line) {
    return getIndexValue(line, 0);
  }

  /**
   * Return true if a command line include the option with the given name, or the command with the
   * given name.
   *
   * @param line the command line to use
   * @param name the name of the flag or argument to check for
   * @return true if a flag or first argument match the given name, false otherwise
   */
  public static boolean hasFlagOrCommand(CommandLine line, String name) {
    if (line.hasOption(name)) {
      return true;
    }
    String command = getCommand(line);
    return command != null && command.equals(name);
  }

  /**
   * Given a command line, an argument name, the boolean default value, and boolean if the arg is
   * optional, return the value of the command-line option 'name'.
   *
   * @param line the command line to use
   * @param name the name of the option to find
   * @param defaultValue the default value to use if the option is not provided
   * @param optionalArg if true, the option without an arg will return true
   * @return the option value as boolean, or the default if not found
   */
  public static boolean getBooleanValue(
      CommandLine line, String name, boolean defaultValue, boolean optionalArg) {
    if (line.hasOption(name)) {
      if (CommandLineHelper.getOptionalValue(line, name) == null) {
        return true;
      } else {
        return CommandLineHelper.getBooleanValue(line, name, defaultValue);
      }
    } else {
      return false;
    }
  }

  /**
   * Given a command line, return the value of --axioms as a set of classes that extend OWLAxiom.
   *
   * @param line the command line to use
   * @return set of OWLAxiom types
   */
  public static Set<Class<? extends OWLAxiom>> getAxiomValues(CommandLine line) {
    Set<Class<? extends OWLAxiom>> axiomTypes = new HashSet<>();
    List<String> axiomTypeStrings = getOptionValues(line, "axioms");
    if (axiomTypeStrings.isEmpty()) {
      axiomTypeStrings.add("all");
    }
    // Split if it's one arg with spaces
    List<String> axiomTypeFixedStrings = new ArrayList<>();
    for (String axiom : axiomTypeStrings) {
      if (axiom.contains(" ")) {
        axiomTypeFixedStrings.addAll(Arrays.asList(axiom.split(" ")));
      } else {
        axiomTypeFixedStrings.add(axiom);
      }
    }
    // Then get the actual types
    for (String axiom : axiomTypeFixedStrings) {
      if (axiom.equalsIgnoreCase("all")) {
        axiomTypes.add(OWLAxiom.class);
      } else if (axiom.equalsIgnoreCase("logical")) {
        axiomTypes.add(OWLLogicalAxiom.class);
      } else if (axiom.equalsIgnoreCase("annotation")) {
        axiomTypes.add(OWLAnnotationAxiom.class);
      } else if (axiom.equalsIgnoreCase("subclass")) {
        axiomTypes.add(OWLSubClassOfAxiom.class);
      } else if (axiom.equalsIgnoreCase("subproperty")) {
        axiomTypes.add(OWLSubObjectPropertyOfAxiom.class);
        axiomTypes.add(OWLSubDataPropertyOfAxiom.class);
        axiomTypes.add(OWLSubAnnotationPropertyOfAxiom.class);
      } else if (axiom.equalsIgnoreCase("equivalent")) {
        axiomTypes.add(OWLEquivalentClassesAxiom.class);
        axiomTypes.add(OWLEquivalentObjectPropertiesAxiom.class);
        axiomTypes.add(OWLEquivalentDataPropertiesAxiom.class);
      } else if (axiom.equalsIgnoreCase("disjoint")) {
        axiomTypes.add(OWLDisjointClassesAxiom.class);
        axiomTypes.add(OWLDisjointObjectPropertiesAxiom.class);
        axiomTypes.add(OWLDisjointDataPropertiesAxiom.class);
        axiomTypes.add(OWLDisjointUnionAxiom.class);
      } else if (axiom.equalsIgnoreCase("type")) {
        axiomTypes.add(OWLClassAssertionAxiom.class);
        // TODO: Make a-box, t-box, r-box
      } else if (axiom.equalsIgnoreCase("declaration")) {
        axiomTypes.add(OWLDeclarationAxiom.class);
      } else {
        throw new IllegalArgumentException(String.format(axiomTypeError, axiom));
      }
    }
    return axiomTypes;
  }

  /**
   * Get the boolean value of a command-line option with the given name.
   *
   * @param line the command line to use
   * @param name the name of the option to find
   * @param defaultValue the default value to use
   * @return the option value as boolean, or the default if not found
   */
  public static boolean getBooleanValue(CommandLine line, String name, boolean defaultValue) {
    String val = getDefaultValue(line, name, String.valueOf(defaultValue));
    if ("true".equals(val)) {
      return true;
    } else if ("false".equals(val)) {
      return false;
    } else {
      throw new IllegalArgumentException(String.format(booleanValueError, name));
    }
  }

  /**
   * Get the value of the command-line option with the given name.
   *
   * @param line the command line to use
   * @param name the name of the option to find
   * @return the option value as a string, or null if not found
   */
  public static String getOptionalValue(CommandLine line, String name) {
    return getDefaultValue(line, name, null);
  }

  /**
   * Get the value of the command-line options with the given name.
   *
   * @param line the command line to use
   * @param name the name of the option to find
   * @return the option value as a list of strings, maybe empty
   */
  public static List<String> getOptionalValues(CommandLine line, String name) {
    if (line.hasOption(name)) {
      return new ArrayList<>(Arrays.asList(line.getOptionValues(name)));
    } else {
      return new ArrayList<>();
    }
  }

  /**
   * Get the value of the command-line option with the given name, or return a default value if the
   * option is not found.
   *
   * @param line the command line to use
   * @param name the name of the option to find
   * @param defaultValue the default value to use
   * @return the option value as a string, or the default if the option not found
   */
  public static String getDefaultValue(CommandLine line, String name, String defaultValue) {
    String result = defaultValue;
    if (line.hasOption(name)) {
      // Only one arg should be passed per option
      result = line.getOptionValue(name);
    }
    return result;
  }

  /**
   * Get the value of the command-line option with the given name, or throw an
   * IllegalArgumentException with a given message if the option is not found.
   *
   * @param line the command line to use
   * @param name the name of the option to find
   * @param message the message for the exception
   * @return the option value as a string
   * @throws IllegalArgumentException if the option is not found
   */
  public static String getRequiredValue(CommandLine line, String name, String message)
      throws IllegalArgumentException {
    String result = getOptionalValue(line, name);
    if (result == null) {
      throw new IllegalArgumentException(String.format(missingRequirementError, message));
    }
    return result;
  }

  /**
   * Given a command line, return an initialized IOHelper. The --prefix, --prefixes, --noprefixes
   * and --xml-entities options are handled.
   *
   * @param line the command line to use
   * @return an initialized IOHelper
   */
  public static IOHelper getIOHelper(CommandLine line) {
    IOHelper ioHelper;
    String prefixes = getOptionalValue(line, "prefixes");
    if (prefixes != null) {
      ioHelper = new IOHelper(prefixes);
    } else {
      ioHelper = new IOHelper(!line.hasOption("noprefixes"));
    }

    for (String prefix : getOptionalValues(line, "prefix")) {
      try {
        ioHelper.addPrefix(prefix);
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException(String.format(IOHelper.invalidPrefixError, prefix), e);
      }
    }

    ioHelper.setXMLEntityFlag(line.hasOption("xml-entities"));

    return ioHelper;
  }

  /**
   * Given an IOHelper and a command line, check for required options and return a loaded input
   * ontology. Currently handles --input and --input-iri options.
   *
   * @param ioHelper the IOHelper to load the ontology with
   * @param line the command line to use
   * @return the input ontology
   * @throws IllegalArgumentException if requires options are missing
   * @throws IOException if the ontology cannot be loaded
   */
  public static OWLOntology getInputOntology(IOHelper ioHelper, CommandLine line)
      throws IllegalArgumentException, IOException {
    OWLOntology inputOntology;
    // Check for multiple inputs
    List<String> inputOntologyPaths = getOptionalValues(line, "input");
    List<String> inputOntologyIRIs = getOptionalValues(line, "input-iri");
    String catalogPath = getOptionalValue(line, "catalog");

    Integer check = inputOntologyPaths.size() + inputOntologyIRIs.size();
    if (check > 1) {
      throw new IllegalArgumentException(multipleInputsError);
    }

    if (!inputOntologyPaths.isEmpty()) {
      if (catalogPath != null) {
        inputOntology = ioHelper.loadOntology(inputOntologyPaths.get(0), catalogPath);
      } else {
        inputOntology = ioHelper.loadOntology(inputOntologyPaths.get(0));
      }
    } else if (!inputOntologyIRIs.isEmpty()) {
      if (catalogPath != null) {
        inputOntology = ioHelper.loadOntology(IRI.create(inputOntologyIRIs.get(0)), catalogPath);
      } else {
        inputOntology = ioHelper.loadOntology(IRI.create(inputOntologyIRIs.get(0)));
      }
    } else {
      // Both input options are empty
      throw new IllegalArgumentException(missingInputError);
    }

    return inputOntology;
  }

  /**
   * Given an IOHelper and a command line, check for required options and return a list of loaded
   * input ontologies. Currently handles --input, --input-iri, and --inputs options.
   *
   * @param ioHelper the IOHelper to load the ontology with
   * @param line the command line to use
   * @param allowEmpty if an empty list may be returned (when chaining commands, an input was
   *     already provided)
   * @return the list of input ontologies
   * @throws IllegalArgumentException if requires options are missing
   * @throws IOException if the ontology cannot be loaded
   */
  public static List<OWLOntology> getInputOntologies(
      IOHelper ioHelper, CommandLine line, boolean allowEmpty)
      throws IllegalArgumentException, IOException {
    List<OWLOntology> inputOntologies = new ArrayList<>();
    String catalogPath = getOptionalValue(line, "catalog");

    if (catalogPath != null) {
      inputOntologies.addAll(getInputOntologies(ioHelper, line, catalogPath));
    } else {
      inputOntologies.addAll(getInputOntologies(ioHelper, line));
    }

    if (inputOntologies.isEmpty() && !allowEmpty) {
      throw new IllegalArgumentException(missingInputsError);
    }

    return inputOntologies;
  }

  /**
   * Given an IOHelper, a state object, and a command line, update the state with the ontology.
   *
   * @param ioHelper the IOHelper to load the ontology with
   * @param state the input state, maybe null
   * @param line the command line to use
   * @return the updated state
   * @throws IllegalArgumentException if requires options are missing
   */
  public static CommandState updateInputOntology(
      IOHelper ioHelper, CommandState state, CommandLine line) throws IllegalArgumentException {
    return updateInputOntology(ioHelper, state, line, true);
  }

  /**
   * Given an IOHelper, a state object, a command line, and a "required" flag, update the state with
   * the ontology. If the state contains an ontology, use it. If the state is null or does not
   * contain an ontology, use the `--input` option. If the state has an ontology and there's an
   * `--input` throw an exception warning the use to use two commands instead of a chain of
   * commands.
   *
   * @param ioHelper the IOHelper to load the ontology with
   * @param state the input state, maybe null
   * @param line the command line to use
   * @param required when true, throw an exception if ontology is not found
   * @return the updated state
   * @throws IllegalArgumentException if requires options are missing
   */
  public static CommandState updateInputOntology(
      IOHelper ioHelper, CommandState state, CommandLine line, boolean required)
      throws IllegalArgumentException {
    if (state != null && state.getOntology() != null) {
      if (line.hasOption("input") || line.hasOption("input-IRI")) {
        throw new IllegalArgumentException(chainedInputError);
      } else {
        return state;
      }
    }

    OWLOntology ontology = null;
    try {
      ontology = getInputOntology(ioHelper, line);
    } catch (Exception e) {
      if (required) {
        // Throw message from IOHelper
        throw new IllegalArgumentException(e);
      }
    }

    if (state == null) {
      state = new CommandState();
    }
    state.setOntology(ontology);
    return state;
  }

  /**
   * Given a command line, check for the required options and return a File for saving data.
   *
   * @param line the command line to use
   * @return the File for output; may be null; may not exist!
   * @throws IllegalArgumentException if required options are not found
   */
  public static File getOutputFile(CommandLine line) throws IllegalArgumentException {
    String outputPath = getOptionalValue(line, "output");
    File outputFile = null;
    if (outputPath != null) {
      outputFile = new File(outputPath);
    }
    return outputFile;
  }

  /**
   * Given a command line, check for the required options and return an IRI to be used as the
   * OntologyIRI for the output ontology.
   *
   * @param line the command line to use
   * @return the IRI for the output ontology, or null
   */
  public static IRI getOutputIRI(CommandLine line) {
    String outputIRIString = getOptionalValue(line, "output-iri");
    IRI outputIRI = null;
    if (outputIRIString != null) {
      outputIRI = IRI.create(outputIRIString);
    }
    return outputIRI;
  }

  /**
   * Given a command line and an ontology, for each `--output` option (if any), save a copy of the
   * ontology to the specified path.
   *
   * @param line the command lien to use
   * @param ontology the ontology to save
   * @throws IOException on any problem
   */
  public static void maybeSaveOutput(CommandLine line, OWLOntology ontology) throws IOException {
    IOHelper ioHelper = getIOHelper(line);
    for (String path : getOptionValues(line, "output")) {
      try {
        ioHelper.saveOntology(ontology, path);
      } catch (IllegalArgumentException e) {
        // Exception from getFormat -- invalid format
        throw new IllegalArgumentException(
            String.format(IOHelper.invalidFormatError, path.substring(path.lastIndexOf(".") + 1)),
            e);
      }
    }
  }

  /**
   * Try to create an IRI from a string input. If the term is not in a valid format (null), an
   * IllegalArgumentException is thrown to prevent null from being passed into other methods.
   *
   * @param term the term to convert to an IRI
   * @param field the field in which the term was entered, for reporting
   * @return the new IRI if successful
   */
  public static IRI maybeCreateIRI(IOHelper ioHelper, String term, String field) {
    IRI iri = ioHelper.createIRI(term);
    if (iri == null) {
      throw new IllegalArgumentException(String.format(invalidIRIError, field, term));
    }
    return iri;
  }

  /**
   * Given an IOHelper and a command line, check for the required options and return a set of IRIs
   * for terms. Handles --terms and --term-file options.
   *
   * @param ioHelper the IOHelper to use for loading the terms
   * @param line the command line to use
   * @return a set of term IRIs
   * @throws IllegalArgumentException if the required options are not found
   * @throws IOException if the term file cannot be loaded
   */
  public static Set<IRI> getTerms(IOHelper ioHelper, CommandLine line)
      throws IllegalArgumentException, IOException {
    return getTerms(ioHelper, line, false);
  }

  /**
   * As getTerms, but allow the list to be empty.
   *
   * @param ioHelper the IOHelper to use for loading the terms
   * @param line the command line to use
   * @param allowEmpty true if empty lists of properties are allowed
   * @return a set of term IRIs
   * @throws IllegalArgumentException if the required options are not found
   * @throws IOException if the term file cannot be loaded
   */
  public static Set<IRI> getTerms(IOHelper ioHelper, CommandLine line, boolean allowEmpty)
      throws IllegalArgumentException, IOException {
    Set<IRI> terms = getTerms(ioHelper, line, "term", "term-file");

    if (terms.size() == 0 && !allowEmpty) {
      throw new IllegalArgumentException(missingTermsError);
    }
    return terms;
  }

  /**
   * Given an IOHelper and a command line, and the names of two options, check for the required
   * options and return a set of IRIs for terms. Handles single term options and term-file options.
   * Allows empty returns.
   *
   * @param ioHelper the IOHelper to use for loading the terms
   * @param line the command line to use
   * @param singles the option name for single terms, or null
   * @param paths the option name for term file paths, or null
   * @return a set of term IRIs
   * @throws IllegalArgumentException if the required options are not found
   * @throws IOException if the term file cannot be loaded
   */
  public static Set<IRI> getTerms(IOHelper ioHelper, CommandLine line, String singles, String paths)
      throws IllegalArgumentException, IOException {
    Set<String> termStrings = new HashSet<>();
    if (singles != null) {
      termStrings.addAll(getOptionValues(line, singles));
    }
    if (paths != null) {
      for (String path : getOptionValues(line, paths)) {
        termStrings.add(FileUtils.readFileToString(new File(path)));
      }
    }

    Set<IRI> terms = new HashSet<>();
    for (String termString : termStrings) {
      terms.addAll(ioHelper.parseTerms(termString));
    }

    return terms;
  }

  /**
   * Given a string of a reasoner name from user input, return the reasoner factory. If the user
   * input is not valid, throw IllegalArgumentExcepiton. By default, EMR is not allowed.
   *
   * @param line the command line to use
   * @return OWLReasonerFactory if successful
   */
  public static OWLReasonerFactory getReasonerFactory(CommandLine line) {
    return getReasonerFactory(line, false);
  }

  /**
   * Given a string of a reasoner name from user input, return the reasoner factory. If the user
   * input is not valid, throw IllegalArgumentExcepiton.
   *
   * @param line the command line to use
   * @param allowEMR boolean specifying if EMR can be returned
   * @return OWLReasonerFactory if successful
   */
  public static OWLReasonerFactory getReasonerFactory(CommandLine line, boolean allowEMR) {
    // ELK is the default reasoner
    String reasonerName = getDefaultValue(line, "reasoner", "ELK").trim().toLowerCase();
    logger.info("Reasoner: " + reasonerName);

    if (reasonerName.equals("structural")) {
      return new org.semanticweb.owlapi.reasoner.structural.StructuralReasonerFactory();
    } else if (reasonerName.equals("hermit")) {
      return new org.semanticweb.HermiT.ReasonerFactory();
    } else if (reasonerName.equals("jfact")) {
      return new JFactFactory();
      // Reason must change behavior with EMR, so not all commands can use it
    } else if (reasonerName.equals("emr") && allowEMR) {
      ElkReasonerFactory innerReasonerFactory = new org.semanticweb.elk.owlapi.ElkReasonerFactory();
      return new ExpressionMaterializingReasonerFactory(innerReasonerFactory);
    } else if (reasonerName.equals("elk")) {
      return new org.semanticweb.elk.owlapi.ElkReasonerFactory();
    } else {
      throw new IllegalArgumentException(String.format(invalidReasonerError, reasonerName));
    }
  }

  /**
   * Print a help message for a command.
   *
   * @param usage the usage information for the command
   * @param options the command line options for the command
   */
  public static void printHelp(String usage, Options options) {
    HelpFormatter formatter = new HelpFormatter();
    formatter.printHelp(usage, options);
  }

  /** Print the ROBOT version. */
  public static void printVersion() throws IOException {
    Properties p = new Properties();
    // The resource can be accessed from the class, except when running as a JAR
    URL resource =
        CommandLineHelper.class
            .getClassLoader()
            .getResource("/META-INF/maven/org.obolibrary.robot/robot-command/pom.properties");
    if (resource != null) {
      URI uri;
      try {
        uri = resource.toURI();
      } catch (URISyntaxException e) {
        throw new IOExceptionWithCause(e);
      }
      File f = new File(uri);
      InputStream is = new FileInputStream(f);
      p.load(is);
    } else {
      // Brute-force to get properties file from JAR
      // This will be used any time `robot --version` is entered on command line
      String cls = CommandLineHelper.class.getName().replace(".", "/") + ".class";
      resource = CommandLineHelper.class.getClassLoader().getResource(cls);
      String protocol;
      try {
        protocol = resource.getProtocol();
      } catch (NullPointerException e) {
        throw new IOException(
            "Cannot access version information from JAR. The directory address has no protocol.");
      }
      if (protocol.equals("jar")) {
        // Get the JAR path and open as JAR file
        String jarPath = resource.getPath().substring(5, resource.getPath().indexOf("!"));
        try (JarFile jar = new JarFile(URLDecoder.decode(jarPath, "UTF-8"))) {
          ZipEntry entry =
              jar.getEntry("META-INF/maven/org.obolibrary.robot/robot-command/pom.properties");
          if (entry != null) {
            InputStream is = jar.getInputStream(entry);
            p.load(is);
          } else {
            throw new IOException(
                "Cannot access version information from JAR. The properties file does not exist.");
          }
        }
      }
    }
    String version = p.getProperty("version");
    System.out.println("ROBOT version " + version);
  }

  /**
   * Create a new Options object with shared options for 'help' and 'version'.
   *
   * @return a new Options object with some options added
   */
  public static Options getCommonOptions() {
    Options o = new Options();
    o.addOption("h", "help", false, "print usage information");
    o.addOption("V", "version", false, "print version information");
    o.addOption("v", "verbose", false, "increased logging");
    o.addOption("vv", "very-verbose", false, "high logging");
    o.addOption("vvv", "very-very-verbose", false, "maximum logging, including stack traces");
    o.addOption(null, "catalog", true, "use catalog from provided file");
    o.addOption("p", "prefix", true, "add a prefix 'foo: http://bar'");
    o.addOption("P", "prefixes", true, "use prefixes from JSON-LD file");
    o.addOption("noprefixes", false, "do not use default prefixes");
    o.addOption("x", "xml-entities", false, "use entity substitution with ontology XML output");
    return o;
  }

  /**
   * Parse the command line, handle help and other common options, and return null or a CommandLine.
   *
   * @param usage the usage string for this command
   * @param options the command-line options for this command
   * @param args the command-line arguments provided
   * @param stopAtNonOption same as CommandLineParser
   * @return a new CommandLine object or null
   * @throws ParseException if the arguments cannot be parsed
   */
  public static CommandLine maybeGetCommandLine(
      String usage, Options options, String[] args, boolean stopAtNonOption)
      throws ParseException, IOException {
    CommandLineParser parser = new PosixParser();
    CommandLine line = parser.parse(options, args, stopAtNonOption);

    String level;
    if (line.hasOption("very-very-verbose")) {
      level = "DEBUG";
    } else if (line.hasOption("very-verbose")) {
      level = "INFO";
    } else if (line.hasOption("verbose")) {
      level = "WARN";
    } else {
      level = "ERROR";
    }
    org.apache.log4j.Logger root = org.apache.log4j.Logger.getRootLogger();
    root.setLevel(org.apache.log4j.Level.toLevel(level));

    if (hasFlagOrCommand(line, "help")) {
      printHelp(usage, options);
      return null;
    }
    if (hasFlagOrCommand(line, "version")) {
      printVersion();
      return null;
    }

    return line;
  }

  /**
   * Parse the command line, handle help and other common options, (May exit!) and return a
   * CommandLine.
   *
   * @param usage the usage string for this command
   * @param options the command-line options for this command
   * @param args the command-line arguments provided
   * @return a new CommandLine object or exit(0)
   * @throws ParseException if the arguments cannot be parsed
   */
  public static CommandLine getCommandLine(String usage, Options options, String[] args)
      throws ParseException, IOException {
    CommandLine line = maybeGetCommandLine(usage, options, args, false);
    if (line == null) {
      System.exit(0);
    }
    return line;
  }

  /**
   * Shared method for dealing with exceptions, printing help, and exiting. Currently prints the
   * error message, stack trace (DEBUG), usage, and then exits.
   *
   * @param exception the exception to handle
   */
  public static void handleException(Exception exception) {
    ExceptionHelper.handleException(exception);
    System.exit(1);
  }

  /**
   * Shared method for dealing with exceptions, printing help, and exiting. Currently prints the
   * error message, stack trace (DEBUG), usage, and then exits.
   *
   * @param usage the usage string for this command; WARN: not used
   * @param options the command-line options for this command; WARN: not used
   * @param exception the exception to handle
   */
  public static void handleException(String usage, Options options, Exception exception) {
    ExceptionHelper.handleException(exception);
    System.exit(1);
  }

  /**
   * Given an input string, return a list of the string split on whitespace, while ignoring any
   * whitespace in single string quotes.
   *
   * @param selects String of select options to split
   * @return List of split strings
   */
  protected static List<String> splitSelects(String selects) {
    List<String> split = new ArrayList<>();
    Matcher m = Pattern.compile("([^\\s]+=.*'[^']+'[^\\s']*|[^\\s']+)").matcher(selects);
    while (m.find()) {
      String s = m.group(1).trim();
      split.add(s);
    }
    return split;
  }

  /**
   * Given a wildcard pattern as string, return an array of files matching that pattern.
   *
   * @param pattern wildcard pattern to match
   * @return array of files
   */
  private static File[] getFilesByPattern(String pattern) {
    if (!pattern.contains("*") && !pattern.contains("?")) {
      throw new IllegalArgumentException(wildcardError);
    }
    FileFilter fileFilter = new WildcardFileFilter(pattern);
    File[] files = new File(".").listFiles(fileFilter);
    if (files == null || files.length < 1) {
      // Warn user, but continue (empty input checked later)
      logger.error("No files match pattern: {}", pattern);
    }
    return files;
  }

  /**
   * Given an IOHelper and a command line, check input options and return a list of loaded input
   * ontologies.
   *
   * @param ioHelper the IOHelper to load the ontology with
   * @param line the command line to use
   * @return the list of input ontologies
   * @throws IOException if the ontology cannot be loaded
   */
  private static List<OWLOntology> getInputOntologies(IOHelper ioHelper, CommandLine line)
      throws IOException {
    List<OWLOntology> inputOntologies = new ArrayList<>();
    // Check for input files
    List<String> inputOntologyPaths = getOptionalValues(line, "input");
    for (String inputOntologyPath : inputOntologyPaths) {
      inputOntologies.add(ioHelper.loadOntology(inputOntologyPath));
    }
    // Check for input IRIs
    List<String> inputOntologyIRIs = getOptionalValues(line, "input-iri");
    for (String inputOntologyIRI : inputOntologyIRIs) {
      inputOntologies.add(ioHelper.loadOntology(IRI.create(inputOntologyIRI)));
    }
    // Check for input patterns (wildcard)
    String pattern = getOptionalValue(line, "inputs");
    if (pattern != null) {
      for (File inputOntologyFile : getFilesByPattern(pattern)) {
        inputOntologies.add(ioHelper.loadOntology(inputOntologyFile));
      }
    }
    return inputOntologies;
  }

  /**
   * Given an IOHelper, a command line, and the path to a catalog file, check input options and
   * return a list of loaded input ontologies with the catalog file.
   *
   * @param ioHelper the IOHelper to load the ontology with
   * @param line the command line to use
   * @param catalogPath the catalog file to use
   * @return the list of input ontologies
   * @throws IOException if the ontology cannot be loaded
   */
  private static List<OWLOntology> getInputOntologies(
      IOHelper ioHelper, CommandLine line, String catalogPath) throws IOException {
    List<OWLOntology> inputOntologies = new ArrayList<>();
    // Check for input files
    List<String> inputOntologyPaths = getOptionalValues(line, "input");
    for (String inputOntologyPath : inputOntologyPaths) {
      inputOntologies.add(ioHelper.loadOntology(inputOntologyPath, catalogPath));
    }
    // Check for input IRIs
    List<String> inputOntologyIRIs = getOptionalValues(line, "input-iri");
    for (String inputOntologyIRI : inputOntologyIRIs) {
      inputOntologies.add(ioHelper.loadOntology(IRI.create(inputOntologyIRI), catalogPath));
    }
    // Check for input patterns (wildcard)
    String pattern = getOptionalValue(line, "inputs");
    if (pattern != null) {
      File catalogFile = new File(catalogPath);
      for (File inputOntologyFile : getFilesByPattern(pattern)) {
        inputOntologies.add(ioHelper.loadOntology(inputOntologyFile, catalogFile));
      }
    }
    return inputOntologies;
  }
}
