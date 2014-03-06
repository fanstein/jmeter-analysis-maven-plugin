package com.lazerycode.jmeter.analyzer;

import com.lazerycode.jmeter.analyzer.config.Environment;
import com.lazerycode.jmeter.analyzer.writer.*;
import com.lazerycode.jmeter.analyzer.writer.Writer;
import freemarker.template.TemplateException;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.xml.sax.SAXException;

import java.io.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

import static com.lazerycode.jmeter.analyzer.config.Environment.*;

/**
 * Analyzes JMeter XML test report file and generates a report
 *
 * @author Dennis Homann, Arne Franken, Peter Kaul
 */
@Mojo(name="analyze")
@SuppressWarnings({"UnusedDeclaration", "FieldCanBeLocal"}) // Mojos get their fields set via reflection
public class AnalyzeMojo extends AbstractMojo {

  /**
   * An AntPath-Style pattern matching a JMeter XML result file to analyze. Must be a fully qualified path.
   * File may be GZiped, must end in .gz then.
   */
  @Parameter(required = true)
  private String source;

  /**
   * Directory to store result files in.
   * defaultValue = "${project.build.directory}"
   */
  @Parameter(required = true, defaultValue = "${project.build.directory}")
  private File targetDirectory;

  /**
   * Maximum number of samples to keep (in main memory) before compressing.
   * defaultValue = "50000"
   */
  @Parameter(defaultValue = "50000")
  private int maxSamples = Environment.DEFAULT_MAXSAMPLES;

  /**
   * Width use to generate a chart. 
   * defaultValue = "800"
   */
  @Parameter(defaultValue = "800")
  private int chartWidth;


  /**
   * Height use to generate a chart. 
   * defaultValue = "600"
   */
  @Parameter(defaultValue = "600")
  private int chartHeight;

  /**
   * True if all files found by pattern used in ${source} should be processed
   * defaultValue = "false" for following reasons:
   * - Previously we only processed the first file so default functionality is consistent with previous versions
   * - Processing everything will increase run time, that should be an explicit choice to keep things fast by default
   */
  @Parameter(defaultValue = "false")
  private boolean processAllFilesFound;

  /**
   * True, if the directory structure relative to {@link #source} should be preserved during output.
   * defaultValue = "false" for backward compatibility
   */
  @Parameter(defaultValue = "false")
  private boolean preserveDirectories;

  /**
   * Set&lt;String&gt; of sample names that should be processed when analysing a results file.
   * Defaults to {@link Environment#HTTPSAMPLE_ELEMENT_NAME} and {@link Environment#SAMPLE_ELEMENT_NAME}
   */
  @Parameter
  private Set<String> sampleNames = new HashSet<String>(
          Arrays.asList( new String[]{
                           HTTPSAMPLE_ELEMENT_NAME,
                           SAMPLE_ELEMENT_NAME
                         }));

  /**
   * Request groups as a mapping from "group name" to "ant pattern".
   * A request uri that matches an ant pattern will be associated with the group name.
   * Request details, charts and CSV files are generated per requestGroup.
   *
   * The order is honored, a sample will be added to the first matching pattern. So it's possible
   * to define various patterns and one catch all pattern.
   *
   * If not set, the threadgroup name of the request will be used.
   */
  @Parameter
  private List<RequestGroup> requestGroups;

  /**
   * URLs of resources to be downloaded and to be stored in target directory.
   * Mapping from URL to filename.
   * URL may contain placeholders _FROM_ and _TO_. Those placeholders will be replaced by ISO8601 formatted timestamps
   * (e.g. 20120116T163600%2B0100) that are extracted from the JMeter result file (min/max time)
   */
  @Parameter
  private Properties remoteResources;

  /**
   * Template directory where custom freemarker templates are stored.
   * Freemarker templates are used for all generated output. (CSV files, HTML files, console output)
   *
   * Templates must be stored in one of the following three subfolders of the templateDirectory:
   *
   * csv, html, text.
   *
   * The entry template must be called "main.ftl".
   *
   * For example,
   * &lt;templateDirectory&gt;/text/main.ftl will be used for generating the console output.
   */
  @Parameter
  private File templateDirectory;

  /**
   * List of writers that handle all output of the plugin.
   * Defaults to:
   * {@link ChartWriter} (generates detailed charts as PNGs),
   * {@link DetailsToCsvWriter} (generates CSV files for every request group),
   * {@link DetailsToHtmlWriter} (generates HTML files for every request group),
   * {@link HtmlWriter} (generates an HTML overview file),
   * {@link SummaryTextToFileWriter} (generates a TXT overview file),
   * {@link SummaryTextToStdOutWriter} (generates overview output to stdout)
   *
   * If one of those should be deactivated or a new {@link Writer} implementation should be added,
   * all desired writers need to be configured.
   */
  @Parameter
  private List<Writer> writers;

  @Override
  public void execute() throws MojoExecutionException, MojoFailureException {
    getLog().info(" ");
    getLog().info("-------------------------------------------------------");
    getLog().info(" A N A L Y S I N G    J M E T E R    R E S U L T S");
    getLog().info("-------------------------------------------------------");
    getLog().info(" ");

    initializeEnvironment();

    try {

      CustomPathMatchingResourcePatternResolver resolver = new CustomPathMatchingResourcePatternResolver();
      Resource[] resultDataFiles = resolver.getResources("file:" + source);
      String rootPath = resolver.getRootDir(source);

      //No JMeter result file found, makes no sense to go on
      if (resultDataFiles.length == 0) {
        throw new MojoExecutionException("Property source not set correctly, no JMeter Result XML file found matching " + source);
      }

      for (int dataFileIdentifier = 0; dataFileIdentifier < resultDataFiles.length; dataFileIdentifier++) {

        //Drop out of the loop after processing first file if we only want to process the first file found.
        if (dataFileIdentifier == 1 && !processAllFilesFound) {
          break;
        }

        File resultDataFile = resultDataFiles[dataFileIdentifier].getFile();
        getLog().info("Analysing '" + resultDataFile.getName() + "'...");

        analyze(resultDataFile, rootPath);

        getLog().info("Results Generated for '" + resultDataFile.getName() + "'.");
        getLog().info(" ");
      }
    }
    catch (Exception e) {
      throw new MojoExecutionException("Error analysing", e);
    }

  }

  //====================================================================================================================

  /**
   * Store all necessary configuration in current {@link Environment#ENVIRONMENT} instance so that
   * other objects have access to it.
   */
  private void initializeEnvironment() throws MojoExecutionException {

    if(writers != null) {
      //<writers> property was configured by user, use configured Writer implementations

      if(writers.contains(null)) {
        //if Maven can't find the configured class, it will insert a "null" element into the list.
        //throw exception here to warn the user about this.
        throw new MojoExecutionException("One of the configured writers could not be found by Maven.");
      }

    }
    else {
      //user did not specify custom Writers, use default list
      writers = new ArrayList<Writer>();
      writers.add(new SummaryTextToStdOutWriter());
      writers.add(new SummaryTextToFileWriter());
      writers.add(new SummaryJsonFileWriter());
      writers.add(new HtmlWriter());
      writers.add(new DetailsToCsvWriter());
      writers.add(new DetailsToHtmlWriter());
      writers.add(new ChartWriter(chartWidth, chartHeight));
    }

    ENVIRONMENT.setWriters(writers);

    //MUST be called after initialization of writers List !!!
    ENVIRONMENT.setGenerateCharts(writers.contains(new ChartWriter(chartWidth, chartHeight)));
    ENVIRONMENT.setGenerateDetails(writers.contains(new DetailsToHtmlWriter()));


    ENVIRONMENT.setMaxSamples(maxSamples);
    ENVIRONMENT.setRemoteResources(remoteResources);
    ENVIRONMENT.setRequestGroups(requestGroups);
    ENVIRONMENT.setTemplateDirectory(templateDirectory);
    ENVIRONMENT.setTargetDirectory(targetDirectory);
    ENVIRONMENT.initializeFreemarkerConfiguration();
    ENVIRONMENT.setPreserveDirectories(preserveDirectories);
    ENVIRONMENT.setLog(getLog());
    ENVIRONMENT.setSampleNames(sampleNames);
  }

  /**
   * Analyze given file.
   *
   * @param resultDataFile the file to analyze
   * @param rootPath the root path of the resultDataFile
   */
  private void analyze(File resultDataFile, String rootPath) throws IOException, SAXException, TemplateException {

    Reader resultData;
    if (resultDataFile.getName().endsWith(".gz")) {
      resultData = new InputStreamReader(new GZIPInputStream(new FileInputStream(resultDataFile)));
    }
    else {
      resultData = new FileReader(resultDataFile);
    }

    try {

      String resultDataFileName = resultDataFile.getName();

      String relativePath = null;
      if (preserveDirectories) {
        //get relative path from source pattern to the resultDataFile
        relativePath = resultDataFile.getAbsolutePath().replace(rootPath, "").replace(resultDataFileName, "");
      }

      //only use data file name, do not use file extension
      resultDataFileName = resultDataFileName.substring(0, resultDataFileName.lastIndexOf('.'));

      ResultAnalyzer reportAnalyser = new ResultAnalyzer(relativePath, resultDataFileName);

      reportAnalyser.analyze(resultData);
    }
    finally {
      resultData.close();
    }

  }

  //--------------------------------------------------------------------------------------------------------------------

  /**
   * needed to call protected method {@link #determineRootDir(String)}
   */
  private static class CustomPathMatchingResourcePatternResolver extends PathMatchingResourcePatternResolver {

    /**
     * Redirect to protected method {@link #determineRootDir(String)}
     *
     * @param pattern the location to check
     * @return the part of the location that denotes the root directory
     */
    public String getRootDir(String pattern) {
      return super.determineRootDir(pattern);
    }

  }

}
