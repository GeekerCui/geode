/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.geode.management.internal.configuration;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;
import static org.apache.geode.distributed.ConfigurationProperties.CLUSTER_CONFIGURATION_DIR;
import static org.apache.geode.distributed.ConfigurationProperties.ENABLE_CLUSTER_CONFIGURATION;
import static org.apache.geode.distributed.ConfigurationProperties.GROUPS;
import static org.apache.geode.distributed.ConfigurationProperties.LOAD_CLUSTER_CONFIGURATION_FROM_DIR;
import static org.apache.geode.distributed.ConfigurationProperties.LOCATORS;
import static org.apache.geode.distributed.ConfigurationProperties.LOG_FILE_SIZE_LIMIT;
import static org.apache.geode.distributed.ConfigurationProperties.USE_CLUSTER_CONFIGURATION;
import static org.assertj.core.api.Assertions.assertThat;

import org.apache.geode.cache.Cache;
import org.apache.geode.distributed.internal.InternalLocator;
import org.apache.geode.distributed.internal.SharedConfiguration;
import org.apache.geode.internal.ClassPathLoader;
import org.apache.geode.internal.JarClassLoader;
import org.apache.geode.internal.JarDeployer;
import org.apache.geode.internal.lang.StringUtils;
import org.apache.geode.management.cli.Result;
import org.apache.geode.management.internal.cli.result.CommandResult;
import org.apache.geode.management.internal.configuration.domain.Configuration;
import org.apache.geode.management.internal.configuration.utils.ZipUtils;
import org.apache.geode.test.dunit.internal.JUnit4DistributedTestCase;
import org.apache.geode.test.dunit.rules.GfshShellConnectionRule;
import org.apache.geode.test.dunit.rules.LocatorServerStartupRule;
import org.apache.geode.test.dunit.rules.Member;
import org.apache.geode.test.junit.categories.DistributedTest;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import java.io.File;
import java.io.FileFilter;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

@Category(DistributedTest.class)
public class ClusterConfigDUnitTest extends JUnit4DistributedTestCase {

  private Properties locatorProps;
  private Properties serverProps;
  private GfshShellConnectionRule gfshConnector;
  @Rule
  public LocatorServerStartupRule lsRule = new LocatorServerStartupRule();

  @Before
  public void before() throws Exception {
    locatorProps = new Properties();
    locatorProps.setProperty(ENABLE_CLUSTER_CONFIGURATION, "true");

    serverProps = new Properties();
    serverProps.setProperty(USE_CLUSTER_CONFIGURATION, "true");
  }

  @After
  public void after() throws Exception {
    if (gfshConnector != null) {
      gfshConnector.close();
    }
  }

  @Test
  public void testStartLocator() throws Exception {
    Member firstLocator = startLocatorWithLoadCCFromDir();

    locatorProps.setProperty(LOCATORS, "localhost[" + firstLocator.getPort() + "]");
    Member secondLocator = lsRule.startLocatorVM(1, locatorProps);

    verifyLocatorConfig(secondLocator);
  }

  @Test
  public void testStartServerWithSingleGroup() throws Exception {
    Member locator = startLocatorWithLoadCCFromDir();

    Member serverWithNoGroup = lsRule.startServerVM(1, serverProps, locator.getPort());
    verifyServerConfig(NO_GROUP, serverWithNoGroup);

    serverProps.setProperty(GROUPS, "group1");
    Member serverForGroup1 = lsRule.startServerVM(2, serverProps, locator.getPort());
    verifyServerConfig(GROUP1, serverForGroup1);

    serverProps.setProperty(GROUPS, "group2");
    Member serverForGroup2 = lsRule.startServerVM(3, serverProps, locator.getPort());
    verifyServerConfig(GROUP2, serverForGroup2);
  }

  @Test
  public void testStartServerWithMultipleGroup() throws Exception {
    Member locator = startLocatorWithLoadCCFromDir();

    serverProps.setProperty(GROUPS, "group1,group2");
    Member server = lsRule.startServerVM(1, serverProps, locator.getPort());

    verifyServerConfig(GROUP1_AND_2, server);
  }

  @Test
  public void testImportWithRunningServer() throws Exception {
    String zipFilePath = getClass().getResource(EXPORTED_CLUSTER_CONFIG_ZIP_FILENAME).getPath();
    // set up the locator/servers
    Member locator = lsRule.startLocatorVM(0, locatorProps);
    Member server1 = lsRule.startServerVM(1, serverProps, locator.getPort());
    gfshConnector =
        new GfshShellConnectionRule(locator.getPort(), GfshShellConnectionRule.PortType.locator);
    gfshConnector.connect();
    CommandResult result =
        gfshConnector.executeCommand("import cluster-configuration --zip-file-name=" + zipFilePath);

    assertThat(result.getStatus()).isEqualTo(Result.Status.ERROR);
  }

  @Test
  public void testImportClusterConfig() throws Exception {
    String zipFilePath = getClass().getResource(EXPORTED_CLUSTER_CONFIG_ZIP_FILENAME).getPath();
    // set up the locator/servers
    Member locator = lsRule.startLocatorVM(0, locatorProps);
    verifyInitialLocatorConfigInFileSystem(locator);

    gfshConnector =
        new GfshShellConnectionRule(locator.getPort(), GfshShellConnectionRule.PortType.locator);
    gfshConnector.connect();
    assertThat(gfshConnector.isConnected()).isTrue();

    CommandResult result =
        gfshConnector.executeCommand("import cluster-configuration --zip-file-name=" + zipFilePath);
    assertThat(result.getStatus()).isEqualTo(Result.Status.OK);

    // verify that the previous folder is copied to "cluster_configxxxxxx".
    String workingDirFiles = Arrays.stream(locator.getWorkingDir().listFiles()).map(File::getName)
        .collect(joining(", "));
    System.out.println("Locator working dir contains: " + workingDirFiles);
    assertThat(locator.getWorkingDir().listFiles())
        .filteredOn((File file) -> file.getName() != "cluster_config")
        .filteredOn((File file) -> file.getName().startsWith("cluster_config")).isNotEmpty();
    verifyLocatorConfig(locator);

    Member server1 = lsRule.startServerVM(1, serverProps, locator.getPort());
    verifyServerConfig(NO_GROUP, server1);

    serverProps.setProperty(GROUPS, "group1");
    Member server2 = lsRule.startServerVM(2, serverProps, locator.getPort());
    verifyServerConfig(GROUP1, server2);

    serverProps.setProperty(GROUPS, "group1,group2");
    Member server3 = lsRule.startServerVM(3, serverProps, locator.getPort());
    verifyServerConfig(GROUP1_AND_2, server3);
  }

  @Test
  public void testDeployToNoServer() throws Exception {
    String clusterJarPath = getClass().getResource("cluster.jar").getPath();
    // set up the locator/servers
    Member locator = lsRule.startLocatorVM(0, locatorProps);

    gfshConnector =
        new GfshShellConnectionRule(locator.getPort(), GfshShellConnectionRule.PortType.locator);
    gfshConnector.connect();
    assertThat(gfshConnector.isConnected()).isTrue();

    CommandResult result = gfshConnector.executeCommand("deploy --jar=" + clusterJarPath);
    assertThat(result.getStatus()).isEqualTo(Result.Status.ERROR);
  }

  @Test
  public void testDeploy() throws Exception {
    String clusterJarPath = getClass().getResource("cluster.jar").getPath();
    String group1Jar = getClass().getResource("group1.jar").getPath();
    String group2Jar = getClass().getResource("group2.jar").getPath();

    // set up the locator/servers
    Member locator = lsRule.startLocatorVM(0, locatorProps);
    Member server1 = lsRule.startServerVM(1, serverProps, locator.getPort());
    serverProps.setProperty(GROUPS, "group1");
    Member server2 = lsRule.startServerVM(2, serverProps, locator.getPort());
    serverProps.setProperty(GROUPS, "group1,group2");
    Member server3 = lsRule.startServerVM(3, serverProps, locator.getPort());

    gfshConnector =
        new GfshShellConnectionRule(locator.getPort(), GfshShellConnectionRule.PortType.locator);
    gfshConnector.connect();
    assertThat(gfshConnector.isConnected()).isTrue();

    CommandResult result = gfshConnector.executeCommand("deploy --jar=" + clusterJarPath);
    assertThat(result.getStatus()).isEqualTo(Result.Status.OK);

    verifyFileExists(locator, "cluster_config/cluster/cluster.jar");
    ExpectedConfig cluster = new ExpectedConfig().jars("cluster.jar");
    verifyServerConfig(cluster, server1);
    verifyServerConfig(cluster, server2);
    verifyServerConfig(cluster, server3);

    result = gfshConnector.executeCommand("deploy --jar=" + group1Jar + " --group=group1");
    assertThat(result.getStatus()).isEqualTo(Result.Status.OK);
    verifyFileExists(locator, "cluster_config/group1/group1.jar");
    ExpectedConfig groupOne = new ExpectedConfig().jars("group1.jar", "cluster.jar");
    verifyServerConfig(cluster, server1);
    verifyServerConfig(groupOne, server2);
    verifyServerConfig(groupOne, server3);

    result = gfshConnector.executeCommand("deploy --jar=" + group2Jar + " --group=group2");
    assertThat(result.getStatus()).isEqualTo(Result.Status.OK);
    verifyFileExists(locator, "cluster_config/group2/group2.jar");

    ExpectedConfig groupOneAndTwo =
        new ExpectedConfig().jars("group1.jar", "group2.jar", "cluster.jar");
    verifyServerConfig(cluster, server1);
    verifyServerConfig(groupOne, server2);
    verifyServerConfig(groupOneAndTwo, server3);
  }

  private Member startLocatorWithLoadCCFromDir() throws Exception {
    File locatorDir = lsRule.getRootFolder().newFolder("locator-0");
    File configDir = new File(locatorDir, "cluster_config");

    // The unzip should yield a cluster config directory structure like:
    // tempFolder/locator-0/cluster_config/cluster/cluster.xml
    // tempFolder/locator-0/cluster_config/cluster/cluster.properties
    // tempFolder/locator-0/cluster_config/cluster/cluster.jar
    // tempFolder/locator-0/cluster_config/group1/ {group1.xml, group1.properties, group1.jar}
    // tempFolder/locator-0/cluster_config/group2/ ...
    ZipUtils.unzip(getClass().getResource(EXPORTED_CLUSTER_CONFIG_ZIP_FILENAME).getPath(),
        configDir.getCanonicalPath());

    Properties properties = new Properties();
    properties.setProperty(ENABLE_CLUSTER_CONFIGURATION, "true");
    properties.setProperty(LOAD_CLUSTER_CONFIGURATION_FROM_DIR, "true");
    properties.setProperty(CLUSTER_CONFIGURATION_DIR, locatorDir.getCanonicalPath());

    Member locator = lsRule.startLocatorVM(0, properties);
    verifyLocatorConfig(locator);

    return locator;
  }

  private static String getServerJarName(String jarName) {
    return JarDeployer.JAR_PREFIX + jarName + "#1";
  }


  public static final String EXPORTED_CLUSTER_CONFIG_ZIP_FILENAME = "cluster_config.zip";
  public static final String[] CONFIG_NAMES = new String[] {"cluster", "group1", "group2"};

  public static final ExpectedConfig NO_GROUP =
      new ExpectedConfig().maxLogFileSize("5000").regions("regionForCluster").jars("cluster.jar");

  public static final ExpectedConfig GROUP1 = new ExpectedConfig().maxLogFileSize("6000")
      .regions("regionForCluster", "regionForGroup1").jars("cluster.jar", "group1.jar");

  public static final ExpectedConfig GROUP2 = new ExpectedConfig().maxLogFileSize("7000")
      .regions("regionForCluster", "regionForGroup2").jars("cluster.jar", "group2.jar");

  public static final ExpectedConfig GROUP1_AND_2 = new ExpectedConfig().maxLogFileSize("7000")
      .regions("regionForCluster", "regionForGroup1", "regionForGroup2")
      .jars("cluster.jar", "group1.jar", "group2.jar");


  public static void verifyLocatorConfig(Member locator) {
    verifyLocatorConfigExistsInFileSystem(locator.getWorkingDir());
    locator.invoke(() -> verifyLocatorConfigExistsInInternalRegion());
  }

  public static void verifyServerConfig(ExpectedConfig expectedConfig, Member server)
      throws ClassNotFoundException {
    verifyServerJarFilesExistInFileSystem(server.getWorkingDir(), expectedConfig.jars);
    server.invoke(() -> verifyServerConfigInMemory(expectedConfig));
  }

  public static void verifyLocatorConfigExistsInFileSystem(File workingDir) {
    File clusterConfigDir = new File(workingDir, "cluster_config");
    assertThat(clusterConfigDir).exists();

    for (String configName : CONFIG_NAMES) {
      File configDir = new File(clusterConfigDir, configName);
      assertThat(configDir).exists();

      File jar = new File(configDir, configName + ".jar");
      File properties = new File(configDir, configName + ".properties");
      File xml = new File(configDir, configName + ".xml");
      assertThat(configDir.listFiles()).contains(jar, properties, xml);
    }
  }

  public static void verifyInitialLocatorConfigInFileSystem(Member member) {
    File clusterConfigDir = new File(member.getWorkingDir(), "cluster_config");
    assertThat(clusterConfigDir).exists();
    File configDir = new File(clusterConfigDir, "cluster");
    assertThat(configDir).exists();
    File properties = new File(configDir, "cluster.properties");
    assertThat(properties).exists();
    File xml = new File(configDir, "cluster.xml");
    assertThat(xml).exists();
  }

  public static void verifyLocatorConfigExistsInInternalRegion() throws Exception {
    InternalLocator internalLocator = LocatorServerStartupRule.locatorStarter.locator;
    SharedConfiguration sc = internalLocator.getSharedConfiguration();

    for (String configName : CONFIG_NAMES) {
      Configuration config = sc.getConfiguration(configName);
      assertThat(config).isNotNull();
    }
  }

  public static void verifyServerConfigInMemory(ExpectedConfig expectedConfig)
      throws ClassNotFoundException {
    Cache cache = LocatorServerStartupRule.serverStarter.cache;

    for (String region : expectedConfig.regions) {
      assertThat(cache.getRegion(region)).isNotNull();
    }

    if (!StringUtils.isBlank(expectedConfig.maxLogFileSize)) {
      Properties props = cache.getDistributedSystem().getProperties();
      assertThat(props.getProperty(LOG_FILE_SIZE_LIMIT)).isEqualTo(expectedConfig.maxLogFileSize);
    }

    for (String jar : expectedConfig.jars) {
      JarClassLoader jarClassLoader = findJarClassLoader(jar);
      assertThat(jarClassLoader).isNotNull();
      assertThat(jarClassLoader.loadClass(nameOfClassContainedInJar(jar))).isNotNull();
    }
  }

  public static void verifyServerJarFilesExistInFileSystem(File workingDir, String[] jarNames) {
    assertThat(workingDir.listFiles()).isNotEmpty();

    List<String> expectedJarNames = new ArrayList();
    for (String jarName : jarNames) {
      expectedJarNames.add(getServerJarName(jarName));
    }

    // make sure each expected jar is in the working dir
    for (String jarName : expectedJarNames) {
      assertThat(workingDir.listFiles()).contains(new File(workingDir, jarName));
    }

    File[] acutalJars = workingDir.listFiles(pathname -> pathname.getName().contains(".jar"));

    // make sure the workingdir contains only jars in the list
    for (File jar : acutalJars) {
      assertThat(expectedJarNames).contains(jar.getName());
    }
  }

  public static String nameOfClassContainedInJar(String jarName) {
    switch (jarName) {
      case "cluster.jar":
        return "Cluster";
      case "group1.jar":
        return "Group1";
      case "group2.jar":
        return "Group2";
      default:
        throw new IllegalArgumentException(
            EXPORTED_CLUSTER_CONFIG_ZIP_FILENAME + " does not contain a jar named " + jarName);
    }
  }

  public static JarClassLoader findJarClassLoader(final String jarName) {
    Collection<ClassLoader> classLoaders = ClassPathLoader.getLatest().getClassLoaders();
    for (ClassLoader classLoader : classLoaders) {
      if (classLoader instanceof JarClassLoader
          && ((JarClassLoader) classLoader).getJarName().equals(jarName)) {
        return (JarClassLoader) classLoader;
      }
    }
    return null;
  }

  public static void verifyFileExists(Member member, String relativePath) {
    File workingDir = member.getWorkingDir();
    assertThat(new File(workingDir, relativePath)).exists();
  }

  private static class ExpectedConfig implements Serializable {
    public String maxLogFileSize;
    public String[] regions = new String[] {};
    public String[] jars = new String[] {};

    public ExpectedConfig maxLogFileSize(String maxLogFileSize) {
      this.maxLogFileSize = maxLogFileSize;
      return this;
    }

    public ExpectedConfig regions(String... regions) {
      this.regions = regions;
      return this;
    }

    public ExpectedConfig jars(String... jars) {
      this.jars = jars;
      return this;
    }
  }


}