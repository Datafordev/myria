package edu.washington.escience.myria.operator;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.SequenceInputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.InputMismatchException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Scanner;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.codehaus.commons.compiler.CompilerFactoryFactory;
import org.codehaus.commons.compiler.IScriptEvaluator;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;

import edu.washington.escience.myria.DbException;
import edu.washington.escience.myria.Schema;
import edu.washington.escience.myria.TupleBatch;
import edu.washington.escience.myria.TupleBatchBuffer;

/**
 * Reads data from a file.
 * 
 * @author dhalperi
 * 
 */
public final class FileScan extends LeafOperator {
  /** The input stream that this scan is reading from. */
  private transient InputStream inputStream = null;
  /** The Schema of the relation stored in this file. */
  private final Schema schema;
  /** Scanner used to parse the file. */
  private transient Scanner scanner = null;
  /** A user-provided file delimiter; if null, the system uses the default whitespace delimiter. */
  private final String delimiter;

  /** The filename of the input, if any. */
  private String filename = null;

  /** Holds the tuples that are ready for release. */
  private transient TupleBatchBuffer buffer;
  /** Which line of the file the scanner is currently on. */
  private int lineNumber = 0;
  /** Janino evaluator to that compiles reading. */
  private TextTupleReader evaluator;

  /** Required for Java serialization. */
  private static final long serialVersionUID = 1L;

  /**
   * The logger for debug, trace, etc. messages in this class.
   */
  private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(FileScan.class);

  /**
   * Construct a new FileScan object to read from the specified file. This file is assumed to be whitespace-separated
   * and have one record per line.
   * 
   * @param filename the file containing the relation.
   * @param schema the Schema of the relation contained in the file.
   * @throws FileNotFoundException if the given filename does not exist.
   */
  public FileScan(final String filename, final Schema schema) throws FileNotFoundException {
    this(filename, schema, null);
  }

  /**
   * Construct a new FileScan object to read from the specified file. This file is assumed to be whitespace-separated
   * and have one record per line. If delimiter is non-null, the system uses its value as a delimiter.
   * 
   * @param filename the file containing the relation.
   * @param schema the Schema of the relation contained in the file.
   * @param delimiter An optional override file delimiter.
   * @throws FileNotFoundException if the given filename does not exist.
   */
  public FileScan(final String filename, final Schema schema, final String delimiter) throws FileNotFoundException {
    Objects.requireNonNull(filename);
    Objects.requireNonNull(schema);
    this.schema = schema;
    this.delimiter = delimiter;
    this.filename = filename;
  }

  /**
   * Construct a new FileScan object to read from a inputStream. If commaIsDelimiter is true, then records may be
   * whitespace or comma-separated. inputStream is assumed to be set later by setInputStream().
   * 
   * @param schema the Schema of the relation contained in the file.
   * @param delimiter An optional non-default file delimiter
   */
  public FileScan(final Schema schema, final String delimiter) {
    this.schema = schema;
    this.delimiter = delimiter;
  }

  /**
   * Construct a new FileScan object to read from a input stream. The data is assumed to be whitespace-separated and
   * have one record per line. inputStream is assumed to be set later by setInputStream().
   * 
   * @param schema the Schema of the relation contained in the file.
   */
  public FileScan(final Schema schema) {
    this(schema, null);
  }

  /**
   * @param inputStream the data containing the relation.
   */
  public void setInputStream(final InputStream inputStream) {
    this.inputStream = inputStream;
  }

  @Override
  public void cleanup() {
    scanner = null;
    while (buffer.numTuples() > 0) {
      buffer.popAny();
    }
  }

  @Override
  protected TupleBatch fetchNextReady() throws DbException {
    /* Let's assume that the scanner always starts at the beginning of a line. */
    int lineNumberBegin = lineNumber;

    while (scanner.hasNext() && (buffer.numTuples() < TupleBatch.BATCH_SIZE)) {
      lineNumber++;

      try {
        evaluator.read(buffer, scanner);
      } catch (final InputMismatchException e) {
        throw new DbException("Error parsing row " + lineNumber + ": ", e);
      }
      String rest = null;
      try {
        rest = scanner.nextLine().trim();
      } catch (final NoSuchElementException e) {
        rest = "";
      }

      if (rest.length() > 0) {
        throw new DbException("Unexpected output at the end of line " + lineNumber + ": " + rest);
      }
    }

    LOGGER.debug("Scanned " + (lineNumber - lineNumberBegin) + " input lines");

    return buffer.popAny();
  }

  @Override
  public Schema generateSchema() {
    return schema;
  }

  @Override
  protected void init(final ImmutableMap<String, Object> execEnvVars) throws DbException {
    buffer = new TupleBatchBuffer(getSchema());
    if (filename != null) {
      // Use Hadoop's URI parsing machinery to extract an input stream for the underlying "file"
      Configuration conf = new Configuration();
      try {
        FileSystem fs = FileSystem.get(URI.create(filename), conf);
        Path rootPath = new Path(filename);
        FileStatus[] statii = fs.globStatus(rootPath);

        if (statii == null || statii.length == 0) {
          throw new FileNotFoundException(filename);
        }

        List<InputStream> streams = new ArrayList<InputStream>();
        for (FileStatus status : statii) {
          Path path = status.getPath();

          LOGGER.debug("Incorporating input file: " + path);
          streams.add(fs.open(path));
        }

        inputStream = new SequenceInputStream(java.util.Collections.enumeration(streams));
      } catch (IOException ex) {
        throw new DbException(ex);
      }
    }
    Preconditions.checkArgument(inputStream != null, "FileScan input stream has not been set!");
    scanner = new Scanner(new BufferedReader(new InputStreamReader(inputStream)));
    if (delimiter != null) {
      scanner.useDelimiter("(\\r\\n)|\\n|(\\Q" + delimiter + "\\E)");
    }
    lineNumber = 0;

    // compile reader script
    StringBuilder eb = new StringBuilder();
    for (int count = 0; count < schema.numColumns(); ++count) {
      switch (schema.getColumnType(count)) {
        case BOOLEAN_TYPE:
          eb.append("if (scanner.hasNextBoolean()) {");
          eb.append("    buffer.putBoolean(").append(count).append(", scanner.nextBoolean());\n");
          eb.append("} else if (scanner.hasNextFloat()) {");
          eb.append("    buffer.putBoolean(").append(count).append(", scanner.nextFloat() != 0);\n");
          eb.append("}");
          break;
        case DOUBLE_TYPE:
          eb.append("buffer.putDouble(").append(count).append(", scanner.nextDouble());\n");
          break;
        case FLOAT_TYPE:
          eb.append("buffer.putFloat(").append(count).append(", scanner.nextFloat());\n");
          break;
        case INT_TYPE:
          eb.append("buffer.putInt(").append(count).append(", scanner.nextInt());\n");
          break;
        case LONG_TYPE:
          eb.append("buffer.putLong(").append(count).append(", scanner.nextLong());\n");
          break;
        case STRING_TYPE:
          eb.append("buffer.putString(").append(count).append(", scanner.next());\n");
          break;
        case DATETIME_TYPE:
          eb.append("buffer.putDateTime(").append(count).append(", DateTimeUtils.parse(scanner.next()));\n");
          break;
      }
    }

    try {
      IScriptEvaluator se = CompilerFactoryFactory.getDefaultCompilerFactory().newScriptEvaluator();
      evaluator =
          (TextTupleReader) se.createFastEvaluator(eb.toString(), TextTupleReader.class, new String[] {
              "buffer", "scanner" });
    } catch (Exception e) {
      throw new DbException("Error when compiling script " + this, e);
    }
  }
}
