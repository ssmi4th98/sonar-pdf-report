package org.sonar.report.pdf.builder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.httpclient.HttpException;
import org.sonar.report.pdf.PDFReporter;
import org.sonar.report.pdf.entity.FileInfo;
import org.sonar.report.pdf.entity.Measures;
import org.sonar.report.pdf.entity.Priority;
import org.sonar.report.pdf.entity.Project;
import org.sonar.report.pdf.entity.Rule;
import org.sonar.report.pdf.entity.exception.ReportException;
import org.sonar.report.pdf.util.Credentials;
import org.sonar.report.pdf.util.Logger;
import org.sonar.report.pdf.util.MetricKeys;
import org.sonar.report.pdf.util.UrlPath;
import org.sonar.wsclient.Sonar;
import org.sonar.wsclient.services.Resource;
import org.sonar.wsclient.services.ResourceQuery;

import com.lowagie.text.DocumentException;

public class ProjectBuilder {

  private static ProjectBuilder builder;

  private Credentials credentials;

  private Sonar sonar;

  private PDFReporter pdfRefporter;

  public ProjectBuilder(Credentials credentials, Sonar sonar,
      PDFReporter pdfRefporter) {
    this.credentials = credentials;
    this.sonar = sonar;
    this.pdfRefporter = pdfRefporter;
  }

  public static ProjectBuilder getInstance(Credentials credentials,
      Sonar sonar, PDFReporter pdfRefporter) {
    if (builder == null) {
      return new ProjectBuilder(credentials, sonar, pdfRefporter);
    }

    return builder;
  }

  /**
   * Initialize: - Project basic data - Project measures - Project categories
   * violations - Project most violated rules - Project most violated files -
   * Project most duplicated files
   * 
   * @param sonarAccess
   * @throws HttpException
   * @throws IOException
   * @throws DocumentException
   * @throws ReportException
   */
  public Project initializeProject(String key) throws IOException,
      ReportException {
    Project project = new Project(key);

    Logger.info("Retrieving project info for " + project.getKey());

    ResourceQuery rq = ResourceQuery.create(project.getKey());
    rq.setDepth(0);
    Resource resource = sonar.find(rq);

    if (resource != null) {
      initFromNode(project, resource);
      initMeasures(project);
      initMostViolatedRules(project);
      initMostViolatedFiles(project);
      initMostComplexElements(project);
      initMostDuplicatedFiles(project);
      Logger.debug("Accessing Sonar: getting child projects");

      ResourceQuery resourceQueryChild = ResourceQuery.create(project.getKey());
      resourceQueryChild.setDepth(1);
      List<Resource> childNodes = sonar.findAll(resourceQueryChild);

      Iterator<Resource> it = childNodes.iterator();
      project.setSubprojects(new ArrayList<Project>(0));
      if (!it.hasNext()) {
        Logger.debug(project.getKey() + " project has no childs");
      }
      while (it.hasNext()) {
        Resource childNode = it.next();

        String scope = childNode.getScope();
        if (scope.equals("PRJ")) {
          Project childProject = initializeProject(childNode.getKey());
          project.getSubprojects().add(childProject);
        }
      }
    } else {
      Logger
          .info("Can't retrieve project info. Have you set username/password in Sonar settings?");
      throw new ReportException(
          "Can't retrieve project info. Parent project node is empty. Authentication?");
    }

    return project;
  }

  /**
   * Initialize project object and his childs (except categories violations).
   */
  private void initFromNode(Project project, final Resource resourceNode) {
    project.setName(resourceNode.getName());
    project.setDescription(resourceNode.getDescription());
    project.setLinks(new LinkedList<String>());
    project.setSubprojects(new LinkedList<Project>());
    project.setMostViolatedRules(new LinkedList<Rule>());
    project.setMostComplexFiles(new LinkedList<FileInfo>());
    project.setMostDuplicatedFiles(new LinkedList<FileInfo>());
    project.setMostViolatedFiles(new LinkedList<FileInfo>());
  }

  private void initMeasures(Project project) throws IOException {
    Logger.info("    Retrieving measures");
    MeasuresBuilder measuresBuilder = MeasuresBuilder.getInstance(sonar);
    Measures measures = measuresBuilder.initMeasuresByProjectKey(project
        .getKey());
    project.setMeasures(measures);
  }

  private void initMostViolatedRules(Project project) throws IOException,
      ReportException {
    Logger.info("    Retrieving most violated rules");
    Logger.debug("Accessing Sonar: getting most violated rules");
    String[] priorities = Priority.getPrioritiesArray();

    // Reverse iteration to get violations with upper level first
    int limit = 10;
    for (int i = priorities.length - 1; i >= 0 && limit > 0; i--) {

      ResourceQuery query = ResourceQuery.create(project.getKey());
      query.setDepth(0);
      query.setLimit(limit);
      query.setMetrics(UrlPath.getViolationsLevelPath(priorities[i]));

      // "&filter_rules=false&filter_rules_cats=true" ??
      query.setExcludeRules(false);
      // query.setExcludeRuleCategories(true);

      Resource mostViolatedRulesByLevel = sonar.find(query);
      if (mostViolatedRulesByLevel != null) {
        int count = initMostViolatedRulesFromNode(project,
            mostViolatedRulesByLevel);
        Logger.debug("\t " + count + " " + priorities[i] + " violations");
        limit = limit - count;
      } else {
        Logger.debug("There is not result on select //resources/resource");
        Logger.debug("There are no violations with level " + priorities[i]);
      }
    }
  }

  private void initMostViolatedFiles(Project project) throws IOException {
    Logger.info("    Retrieving most violated files");
    Logger.debug("Accessing Sonar: getting most violated files");

    ResourceQuery resourceQuery = ResourceQuery.createForMetrics(
        project.getKey(), MetricKeys.VIOLATIONS);
    resourceQuery.setScopes("FIL");
    resourceQuery.setDepth(-1);
    resourceQuery.setLimit(5);
    List<Resource> resources = sonar.findAll(resourceQuery);
    List<FileInfo> fileInfoList = FileInfoBuilder.initFromDocument(resources,
        FileInfo.VIOLATIONS_CONTENT);
    project.setMostViolatedFiles(fileInfoList);

  }

  private void initMostComplexElements(Project project) throws IOException {
    Logger.info("    Retrieving most complex elements");
    Logger.debug("Accessing Sonar: getting most complex elements");

    ResourceQuery resourceQuery = ResourceQuery.createForMetrics(
        project.getKey(), MetricKeys.COMPLEXITY);
    resourceQuery.setScopes("FIL");
    resourceQuery.setDepth(-1);
    resourceQuery.setLimit(5);
    List<Resource> resources = sonar.findAll(resourceQuery);
    project.setMostComplexFiles(FileInfoBuilder.initFromDocument(resources,
        FileInfo.CCN_CONTENT));
  }

  private void initMostDuplicatedFiles(Project project) throws IOException {
    Logger.info("    Retrieving most duplicated files");
    Logger.debug("Accessing Sonar: getting most duplicated files");

    ResourceQuery resourceQuery = ResourceQuery.createForMetrics(
        project.getKey(), MetricKeys.DUPLICATED_LINES);
    resourceQuery.setScopes("FIL");
    resourceQuery.setDepth(-1);
    resourceQuery.setLimit(5);
    List<Resource> resources = sonar.findAll(resourceQuery);
    project.setMostDuplicatedFiles(FileInfoBuilder.initFromDocument(resources,
        FileInfo.DUPLICATIONS_CONTENT));
  }

  private int initMostViolatedRulesFromNode(Project project,
      final Resource mostViolatedNode) throws ReportException, IOException {

    RuleBuilder ruleBuilder = RuleBuilder.getInstance(credentials, sonar);

    List<org.sonar.wsclient.services.Measure> measuresNode = mostViolatedNode
        .getMeasures();
    Iterator<org.sonar.wsclient.services.Measure> it = measuresNode.iterator();
    if (!it.hasNext()) {
      Logger.warn("There is not result on select //resources/resource/msr");
    }
    int count = 0;
    while (it.hasNext()) {
      org.sonar.wsclient.services.Measure measureNode = it.next();
      String formattedValueNode = measureNode.getFormattedValue();
      if (!formattedValueNode.equals("0")) {
        Rule rule = ruleBuilder.initFromNode(measureNode);
        if ("workbook".equals(pdfRefporter.getReportType())) {
          ruleBuilder.loadViolatedResources(rule, rule.getKey(),
              project.getKey());
        }
        project.getMostViolatedRules().add(rule);
        count++;
      }
    }
    return count;
  }

}
