package org.warcbase.ingest;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.log4j.Logger;
import org.archive.io.ArchiveRecord;
import org.archive.io.arc.ARCReader;
import org.archive.io.arc.ARCReaderFactory;
import org.archive.io.arc.ARCRecord;
import org.archive.io.arc.ARCRecordMetaData;
import org.warcbase.data.HBaseTableManager;
import org.warcbase.data.UrlUtils;

public class IngestFiles {
  private static final String CREATE_OPTION = "create";
  private static final String APPEND_OPTION = "append";
  private static final String NAME_OPTION = "name";
  private static final String DIR_OPTION = "dir";
  private static final String START_OPTION = "start";

  private static final Logger LOG = Logger.getLogger(IngestFiles.class);

  public static final int MAX_CONTENT_SIZE = 10 * 1024 * 1024;

  private int cnt = 0;
  private int errors = 0;
  private int toolarge = 0;
  private int invalidUrls = 0;

  private final HBaseTableManager hbaseManager;

  public IngestFiles(String name, boolean create) throws Exception {
    hbaseManager = new HBaseTableManager(name, create);
  }

  protected final byte [] scratchbuffer = new byte[4 * 1024];

  protected long copyStream(final InputStream is, final long recordLength,
      boolean enforceLength, final DataOutputStream out) throws IOException {
    int read = scratchbuffer.length;
    long tot = 0;
    while ((tot < recordLength) && (read = is.read(scratchbuffer)) != -1) {
      int write = read;
      // never write more than enforced length
      write = (int) Math.min(write, recordLength - tot);
      tot += read;
      out.write(scratchbuffer, 0, write);
    }
    if (enforceLength && tot != recordLength) {
      LOG.error("Read " + tot + " bytes but expected " + recordLength + " bytes. Continuing...");
    }

    return tot;
  }

  private void ingestArcFile(File inputArcFile) {
    ARCReader reader = null;

    // Per file trapping of exceptions so a corrupt file doesn't blow up entire ingest.
    try {
      reader = ARCReaderFactory.get(inputArcFile);

      // The following snippet of code was adapted from the dump method in ARCReader.
      boolean firstRecord = true;
      for (Iterator<ArchiveRecord> ii = reader.iterator(); ii.hasNext();) {
        ARCRecord r = (ARCRecord) ii.next();
        ARCRecordMetaData meta = r.getMetaData();
        if (firstRecord) {
          firstRecord = false;
          while (r.available() > 0) {
            r.read();
          }
          continue;
        }

        if (meta.getUrl().startsWith("dns:")) {
            invalidUrls++;
            continue;
        }

        String metaline = meta.getUrl() + " " + meta.getIp() + " " + meta.getDate() + " "
            + meta.getMimetype() + " " + (int) meta.getLength();

        String date = meta.getDate();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dout = new DataOutputStream(baos);
        dout.write(metaline.getBytes());
        dout.write("\n".getBytes());
        copyStream(r, (int) meta.getLength(), true, dout);

        String key = UrlUtils.urlToKey(meta.getUrl());
        String type = meta.getMimetype();

        if (key == null) {
          LOG.error("Invalid URL: " + meta.getUrl());
          invalidUrls++;
          continue;
        }

        if (type == null) {
          type = "text/plain";
        }

        if ((int) meta.getLength() > MAX_CONTENT_SIZE) {
          toolarge++;
        } else {
          if (hbaseManager.insertRecord(key, date, baos.toByteArray(), type)) {
            cnt++;
          } else {
            errors++;
          }
        }

        if (cnt % 10000 == 0 && cnt > 0) {
          LOG.info("Ingested " + cnt + " records into HBase.");
        }
      }
    } catch (Exception e) {
      LOG.error("Error ingesting file: " + inputArcFile);
      e.printStackTrace();
    } catch (OutOfMemoryError e) {
      LOG.error("Encountered OutOfMemoryError ingesting file: " + inputArcFile);
      LOG.error("Attempting to continue...");
    } finally {
      if (reader != null)
        try {
          reader.close();
        } catch (IOException e) {
          e.printStackTrace();
        }
    }
  }

  private void ingestWarcFile(File inputWarcFile) throws IOException {
    throw new UnsupportedOperationException("WARC files not supported yet!");
  }

  private void ingestFolder(File inputFolder, int i) throws Exception {
    long startTime = System.currentTimeMillis();

    for (; i < inputFolder.listFiles().length; i++) {
      File inputFile = inputFolder.listFiles()[i];
      if (!(inputFile.getName().endsWith(".warc.gz") || inputFile.getName().endsWith(".arc.gz")
          || inputFile.getName().endsWith(".warc") || inputFile.getName().endsWith(".arc"))) {
        continue;
      }

      LOG.info("processing file " + i + ": " + inputFile.getName());

      if ( inputFile.getName().endsWith(".warc.gz") || inputFile.getName().endsWith(".warc") ) {
        ingestWarcFile(inputFile);
      } else if (inputFile.getName().endsWith(".arc.gz") || inputFile.getName().endsWith(".arc")) {
        ingestArcFile(inputFile);
      }
    }

    long totalTime = System.currentTimeMillis() - startTime;
    LOG.info("Total " + cnt + " records inserted, " + toolarge + " records too large, " + invalidUrls + " invalid URLs, " + errors + " insertion errors.");
    LOG.info("Total time: " + totalTime + "ms");
    LOG.info("Ingest rate: " + cnt / (totalTime / 1000) + " records per second.");
  }

  @SuppressWarnings("static-access")
  public static void main(String[] args) throws Exception {
    Options options = new Options();
    options.addOption(OptionBuilder.withArgName("name").hasArg()
        .withDescription("name of the archive").create(NAME_OPTION));
    options.addOption(OptionBuilder.withArgName("path").hasArg()
        .withDescription("WARC files location").create(DIR_OPTION));
    options.addOption(OptionBuilder.withArgName("n").hasArg()
        .withDescription("Start from the n-th WARC file").create(START_OPTION));

    options.addOption("create", false, "create new table");
    options.addOption("append", false, "append to existing table");

    CommandLine cmdline = null;
    CommandLineParser parser = new GnuParser();
    try {
      cmdline = parser.parse(options, args);
    } catch (ParseException exp) {
      System.err.println("Error parsing command line: " + exp.getMessage());
      System.exit(-1);
    }

    if (!cmdline.hasOption(DIR_OPTION) || !cmdline.hasOption(NAME_OPTION)) {
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp(IngestFiles.class.getCanonicalName(), options);
      System.exit(-1);
    }

    if (!cmdline.hasOption(CREATE_OPTION) && !cmdline.hasOption(APPEND_OPTION)) {
      System.err.println(String.format("Must specify either -%s or -%s", CREATE_OPTION,
          APPEND_OPTION));
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp(IngestFiles.class.getCanonicalName(), options);
      System.exit(-1);
    }

    String path = cmdline.getOptionValue(DIR_OPTION);
    File inputFolder = new File(path);

    int i = 0;
    if (cmdline.hasOption(START_OPTION)) {
      i = Integer.parseInt(cmdline.getOptionValue(START_OPTION));
    }

    String name = cmdline.getOptionValue(NAME_OPTION);
    boolean create = cmdline.hasOption(CREATE_OPTION);
    IngestFiles load = new IngestFiles(name, create);
    load.ingestFolder(inputFolder, i);
  }
}
