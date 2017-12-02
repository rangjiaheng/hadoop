/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.mapred.uploader;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.io.FileUtils;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Unit test class for FrameworkUploader.
 */
public class TestFrameworkUploader {
  private static String testDir;

  @Before
  public void setUp() {
    String testRootDir =
        new File(System.getProperty("test.build.data", "/tmp"))
            .getAbsolutePath()
            .replace(' ', '+');
    Random random = new Random(System.currentTimeMillis());
    testDir = testRootDir + File.separatorChar +
        Long.toString(random.nextLong());
  }

  /**
   * Test requesting command line help.
   * @throws IOException test failure
   */
  @Test
  public void testHelp() throws IOException {
    String[] args = new String[]{"-help"};
    FrameworkUploader uploader = new FrameworkUploader();
    boolean success = uploader.parseArguments(args);
    Assert.assertFalse("Expected to print help", success);
    Assert.assertEquals("Expected ignore run", null,
        uploader.input);
    Assert.assertEquals("Expected ignore run", null,
        uploader.whitelist);
    Assert.assertEquals("Expected ignore run", null,
        uploader.target);
  }

  /**
   * Test invalid argument parsing.
   * @throws IOException test failure
   */
  @Test
  public void testWrongArgument() throws IOException {
    String[] args = new String[]{"-unexpected"};
    FrameworkUploader uploader = new FrameworkUploader();
    boolean success = uploader.parseArguments(args);
    Assert.assertFalse("Expected to print help", success);
  }

  /**
   * Test normal argument passing.
   * @throws IOException test failure
   */
  @Test
  public void testArguments() throws IOException {
    String[] args =
        new String[]{
            "-input", "A",
            "-whitelist", "B",
            "-blacklist", "C",
            "-fs", "hdfs://C:8020",
            "-target", "D",
            "-replication", "100"};
    FrameworkUploader uploader = new FrameworkUploader();
    boolean success = uploader.parseArguments(args);
    Assert.assertTrue("Expected to print help", success);
    Assert.assertEquals("Input mismatch", "A",
        uploader.input);
    Assert.assertEquals("Whitelist mismatch", "B",
        uploader.whitelist);
    Assert.assertEquals("Blacklist mismatch", "C",
        uploader.blacklist);
    Assert.assertEquals("Target mismatch", "hdfs://C:8020/D",
        uploader.target);
    Assert.assertEquals("Replication mismatch", 100,
        uploader.replication);
  }

  /**
   * Test whether we can filter a class path properly.
   * @throws IOException test failure
   */
  @Test
  public void testCollectPackages() throws IOException, UploaderException {
    File parent = new File(testDir);
    try {
      parent.deleteOnExit();
      Assert.assertTrue("Directory creation failed", parent.mkdirs());
      File dirA = new File(parent, "A");
      Assert.assertTrue(dirA.mkdirs());
      File dirB = new File(parent, "B");
      Assert.assertTrue(dirB.mkdirs());
      File jarA = new File(dirA, "a.jar");
      Assert.assertTrue(jarA.createNewFile());
      File jarB = new File(dirA, "b.jar");
      Assert.assertTrue(jarB.createNewFile());
      File jarC = new File(dirA, "c.jar");
      Assert.assertTrue(jarC.createNewFile());
      File txtD = new File(dirA, "d.txt");
      Assert.assertTrue(txtD.createNewFile());
      File jarD = new File(dirB, "d.jar");
      Assert.assertTrue(jarD.createNewFile());
      File txtE = new File(dirB, "e.txt");
      Assert.assertTrue(txtE.createNewFile());

      FrameworkUploader uploader = new FrameworkUploader();
      uploader.whitelist = ".*a\\.jar,.*b\\.jar,.*d\\.jar";
      uploader.blacklist = ".*b\\.jar";
      uploader.input = dirA.getAbsolutePath() + File.separatorChar + "*" +
          File.pathSeparatorChar +
          dirB.getAbsolutePath() + File.separatorChar + "*";
      uploader.collectPackages();
      Assert.assertEquals("Whitelist count error", 3,
          uploader.whitelistedFiles.size());
      Assert.assertEquals("Blacklist count error", 1,
          uploader.blacklistedFiles.size());

      Assert.assertTrue("File not collected",
          uploader.filteredInputFiles.contains(jarA.getAbsolutePath()));
      Assert.assertFalse("File collected",
          uploader.filteredInputFiles.contains(jarB.getAbsolutePath()));
      Assert.assertTrue("File not collected",
          uploader.filteredInputFiles.contains(jarD.getAbsolutePath()));
      Assert.assertEquals("Too many whitelists", 2,
          uploader.filteredInputFiles.size());
    } finally {
      FileUtils.deleteDirectory(parent);
    }
  }

  /**
   * Test building a tarball from source jars.
   */
  @Test
  public void testBuildTarBall() throws IOException, UploaderException {
    File parent = new File(testDir);
    try {
      parent.deleteOnExit();
      FrameworkUploader uploader = prepareTree(parent);

      File gzipFile = new File("upload.tar.gz");
      gzipFile.deleteOnExit();
      Assert.assertTrue("Creating output", gzipFile.createNewFile());
      uploader.targetStream = new FileOutputStream(gzipFile);

      uploader.buildPackage();

      TarArchiveInputStream result = null;
      try {
        result =
            new TarArchiveInputStream(
                new GZIPInputStream(new FileInputStream(gzipFile)));
        Set<String> fileNames = new HashSet<>();
        Set<Long> sizes = new HashSet<>();
        TarArchiveEntry entry1 = result.getNextTarEntry();
        fileNames.add(entry1.getName());
        sizes.add(entry1.getSize());
        TarArchiveEntry entry2 = result.getNextTarEntry();
        fileNames.add(entry2.getName());
        sizes.add(entry2.getSize());
        Assert.assertTrue(
            "File name error", fileNames.contains("a.jar"));
        Assert.assertTrue(
            "File size error", sizes.contains((long) 13));
        Assert.assertTrue(
            "File name error", fileNames.contains("b.jar"));
        Assert.assertTrue(
            "File size error", sizes.contains((long) 14));
      } finally {
        if (result != null) {
          result.close();
        }
      }
    } finally {
      FileUtils.deleteDirectory(parent);
    }
  }

  /**
   * Test upload to HDFS.
   */
  @Test
  public void testUpload() throws IOException, UploaderException {
    final String fileName = "/upload.tar.gz";
    File parent = new File(testDir);
    try {
      parent.deleteOnExit();

      FrameworkUploader uploader = prepareTree(parent);

      uploader.target = "file://" + parent.getAbsolutePath() + fileName;

      uploader.buildPackage();
      try (TarArchiveInputStream archiveInputStream = new TarArchiveInputStream(
          new GZIPInputStream(
              new FileInputStream(
                  parent.getAbsolutePath() + fileName)))) {
        Set<String> fileNames = new HashSet<>();
        Set<Long> sizes = new HashSet<>();
        TarArchiveEntry entry1 = archiveInputStream.getNextTarEntry();
        fileNames.add(entry1.getName());
        sizes.add(entry1.getSize());
        TarArchiveEntry entry2 = archiveInputStream.getNextTarEntry();
        fileNames.add(entry2.getName());
        sizes.add(entry2.getSize());
        Assert.assertTrue(
            "File name error", fileNames.contains("a.jar"));
        Assert.assertTrue(
            "File size error", sizes.contains((long) 13));
        Assert.assertTrue(
            "File name error", fileNames.contains("b.jar"));
        Assert.assertTrue(
            "File size error", sizes.contains((long) 14));
      }
    } finally {
      FileUtils.deleteDirectory(parent);
    }
  }

  /**
   * Prepare a mock directory tree to compress and upload.
   */
  private FrameworkUploader prepareTree(File parent)
      throws FileNotFoundException {
    Assert.assertTrue(parent.mkdirs());
    File dirA = new File(parent, "A");
    Assert.assertTrue(dirA.mkdirs());
    File jarA = new File(parent, "a.jar");
    PrintStream printStream = new PrintStream(new FileOutputStream(jarA));
    printStream.println("Hello World!");
    printStream.close();
    File jarB = new File(dirA, "b.jar");
    printStream = new PrintStream(new FileOutputStream(jarB));
    printStream.println("Hello Galaxy!");
    printStream.close();

    FrameworkUploader uploader = new FrameworkUploader();
    uploader.filteredInputFiles.add(jarA.getAbsolutePath());
    uploader.filteredInputFiles.add(jarB.getAbsolutePath());

    return uploader;
  }

  /**
   * Test regex pattern matching and environment variable replacement.
   */
  @Test
  public void testEnvironmentReplacement() throws UploaderException {
    String input = "C/$A/B,$B,D";
    Map<String, String> map = new HashMap<>();
    map.put("A", "X");
    map.put("B", "Y");
    map.put("C", "Z");
    FrameworkUploader uploader = new FrameworkUploader();
    String output = uploader.expandEnvironmentVariables(input, map);
    Assert.assertEquals("Environment not expanded", "C/X/B,Y,D", output);

  }

  /**
   * Test regex pattern matching and environment variable replacement.
   */
  @Test
  public void testRecursiveEnvironmentReplacement()
      throws UploaderException {
    String input = "C/$A/B,$B,D";
    Map<String, String> map = new HashMap<>();
    map.put("A", "X");
    map.put("B", "$C");
    map.put("C", "Y");
    FrameworkUploader uploader = new FrameworkUploader();
    String output = uploader.expandEnvironmentVariables(input, map);
    Assert.assertEquals("Environment not expanded", "C/X/B,Y,D", output);

  }

}
